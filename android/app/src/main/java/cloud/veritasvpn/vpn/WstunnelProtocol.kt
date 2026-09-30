package cloud.veritasvpn.vpn

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format for erebe/wstunnel 10.6.2, the binary the Linux client and the
 * node DaemonSet run.
 *
 * Confirmed against that binary: the client GETs `/{segment}/events`, puts a
 * JWT in `Sec-WebSocket-Protocol`, and sends each UDP datagram as one
 * unmasked binary frame (`websocket_mask_frame` defaults to false). The server
 * does not verify the JWT signature (`insecure_decode`).
 *
 * The remote inside the tunnel is always the node's WireGuard listener
 * (`127.0.0.1:51820`), matching
 * `wstunnel client -L udp://127.0.0.1:41820:127.0.0.1:51820`.
 */
object WstunnelProtocol {
    const val WG_REMOTE_HOST = "127.0.0.1"
    const val WG_REMOTE_PORT = 51820
    const val JWT_PREFIX = "authorization.bearer."
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val random = SecureRandom()

    data class Endpoint(val host: String, val port: Int)

    fun parseEndpoint(raw: String): Endpoint? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 255 || value.any { it.isWhitespace() }) return null
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1 || end + 1 >= value.length || value[end + 1] != ':') return null
            host = value.substring(1, end)
            portText = value.substring(end + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.lastIndex) return null
            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        if (host.isEmpty() || host.length > 253) return null
        val hostOk = host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }
        if (!hostOk || host.startsWith('-') || host.endsWith('-')) return null
        return Endpoint(host, port)
    }

    /**
     * First URL segment the server compares to `--restrict-http-upgrade-path-prefix`.
     * A single leading slash is stripped so `/abc123` and `abc123` both become
     * `/abc123/events`. wstunnel extracts that segment and matches it exactly;
     * a leading slash in the secret does not match the official client either.
     */
    fun upgradePath(prefix: String): String {
        val segment = prefix.trim().trimStart('/')
        if (segment.isEmpty() || segment.length > 128 || segment.contains('/')) {
            throw IllegalArgumentException("invalid stealth path prefix")
        }
        val ok = segment.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' }
        if (!ok || segment == "." || segment == "..") {
            throw IllegalArgumentException("invalid stealth path prefix")
        }
        return "/$segment/events"
    }

    fun tunnelJwt(id: String, remoteHost: String = WG_REMOTE_HOST, remotePort: Int = WG_REMOTE_PORT): String {
        require(id.isNotEmpty() && id.length <= 80 && id.all { it.isLetterOrDigit() || it == '-' })
        require(remoteHost.all { it.isLetterOrDigit() || it == '.' || it == ':' })
        require(remotePort in 1..65535)
        val header = base64Url("""{"typ":"JWT","alg":"HS256"}""".toByteArray(Charsets.UTF_8))
        val payloadJson =
            """{"id":"$id","p":{"Udp":{"timeout":null}},"r":"$remoteHost","rp":$remotePort}"""
        val payload = base64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$header.$payload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("veritas-stealth".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = base64Url(mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII)))
        return "$signingInput.$sig"
    }

    fun protocolHeader(jwt: String): String = "v1, $JWT_PREFIX$jwt"

    fun websocketKey(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey + WS_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun hostHeader(endpoint: Endpoint): String {
        val host = if (endpoint.host.contains(':')) "[${endpoint.host}]" else endpoint.host
        return when (endpoint.port) {
            80, 443 -> host
            else -> "$host:${endpoint.port}"
        }
    }

    /** Unmasked binary frame. wstunnel 10.6.2 clients do not set the mask bit. */
    fun encodeBinary(payload: ByteArray): ByteArray = encodeFrame(0x2, payload)

    fun encodePing(): ByteArray = encodeFrame(0x9, ByteArray(0))

    fun encodePong(payload: ByteArray): ByteArray = encodeFrame(0xA, payload)

    fun encodeClose(): ByteArray = encodeFrame(0x8, ByteArray(0))

    private fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 65535) { "stealth frame too large" }
        val head = (0x80 or (opcode and 0x0F)).toByte()
        val header = when {
            payload.size < 126 -> byteArrayOf(head, payload.size.toByte())
            else -> byteArrayOf(
                head,
                126.toByte(),
                (payload.size shr 8).toByte(),
                (payload.size and 0xFF).toByte(),
            )
        }
        return header + payload
    }

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}

/** Incremental websocket frame parser. Returns null until a full frame is buffered. */
class WsFrameBuffer {
    private val buf = ArrayDeque<Byte>()

    data class Frame(val opcode: Int, val payload: ByteArray)

    fun append(data: ByteArray, length: Int = data.size) {
        for (i in 0 until length) buf.addLast(data[i])
    }

    fun next(): Frame? {
        if (buf.size < 2) return null
        val b0 = buf.elementAt(0).toInt() and 0xFF
        val b1 = buf.elementAt(1).toInt() and 0xFF
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = b1 and 0x7F
        var header = 2
        if (len == 126) {
            if (buf.size < 4) return null
            len = ((buf.elementAt(2).toInt() and 0xFF) shl 8) or (buf.elementAt(3).toInt() and 0xFF)
            header = 4
        } else if (len == 127) {
            return null
        }
        val maskLen = if (masked) 4 else 0
        val total = header + maskLen + len
        if (buf.size < total) return null
        repeat(header) { buf.removeFirst() }
        val mask = if (masked) ByteArray(4) { buf.removeFirst() } else null
        val payload = ByteArray(len)
        for (i in 0 until len) {
            val byte = buf.removeFirst()
            payload[i] = if (mask != null) (byte.toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte() else byte
        }
        return Frame(opcode, payload)
    }
}
