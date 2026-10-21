package cloud.veritasvpn.vpn

import android.net.Network
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Public-IP check after connect. The sockets are bound to the VPN network
 * and are never protected out of the tunnel. Stealth's carrier socket is
 * pinned to the underlay on its own; this package stays inside the tun so
 * this bind enters the VPN.
 *
 * If the VPN network is missing, the check is skipped. There is no fallback
 * onto the physical network. The UI then shows Protected with no address.
 */
object EgressProbe {
    val ENDPOINTS: List<String> = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com",
    )

    data class Candidate(val key: Long, val ownedByApp: Boolean?)

    /** A probe is allowed only when a VPN network is available to bind to. */
    fun mayProbe(vpnNetworkAvailable: Boolean): Boolean = vpnNetworkAvailable

    /** The check must not leave on Wi-Fi or cellular. */
    fun allowsPhysicalFallback(): Boolean = false

    /** Notification once the tunnel is up. No address when the check did not finish. */
    fun statusText(egressIp: String?): String =
        if (egressIp.isNullOrBlank()) "Protected" else "Connected · $egressIp"

    /**
     * Leaving Reconnecting must not keep that sentence. A later VPN-bound
     * check replaces Protected with an address when the check learns one.
     */
    fun settledNotification(previous: String): String {
        val head = previous.trim()
        if (head.isEmpty() || head == "Connecting…" || head.startsWith("Reconnecting")) {
            return "Protected"
        }
        return previous
    }

    fun allowsHost(host: String): Boolean {
        return when (host.trim().lowercase()) {
            "api.ipify.org", "ifconfig.me", "icanhazip.com" -> true
            else -> false
        }
    }

    fun allowsUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        return allowsHost(parsed.host)
    }

    /**
     * One owned VPN wins. Several owned networks are ambiguous, so the check
     * is skipped. With no owner (API 26–29), a single VPN network is used.
     */
    fun choose(candidates: List<Candidate>): Long? {
        val distinct = candidates.distinctBy { it.key }
        val owned = distinct.filter { it.ownedByApp == true }
        if (owned.size == 1) return owned.first().key
        if (owned.isNotEmpty()) return null
        if (distinct.size == 1) return distinct.first().key
        return null
    }

    fun client(
        bind: (Socket) -> Unit,
        lookup: (String) -> List<InetAddress>,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(socketFactory(bind))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!allowsHost(hostname)) throw UnknownHostException(hostname)
                    val addresses = lookup(hostname)
                    if (addresses.isEmpty()) throw UnknownHostException(hostname)
                    return addresses
                }
            })
            .addInterceptor { chain ->
                val host = chain.request().url.host
                if (!allowsHost(host)) throw UnknownHostException(host)
                chain.proceed(chain.request())
            }
            .build()
    }

    /** Bind only. protect() would force the socket outside the tunnel. */
    fun bind(network: Network, socket: Socket) {
        network.bindSocket(socket)
    }

    fun socketFactory(bind: (Socket) -> Unit): SocketFactory = VpnBoundSocketFactory(bind)

    /**
     * One attempt. A failure returns null. The body is not logged.
     * [client] must be [client] bound to the VPN network.
     */
    fun fetch(url: String, client: OkHttpClient): String? {
        if (!allowsUrl(url)) throw IOException("egress check host is not allowed")
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseEcho(body)
        }
    }

    /** A single public IP literal. Hostnames and private addresses are dropped. */
    fun parseEcho(body: String): String? {
        val token = body.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (token.isEmpty() || token.length > 45 || token.any { it.isWhitespace() }) return null
        if (!isLiteralIp(token)) return null
        val address = InetAddress.getByName(token)
        if (!isPublic(address)) return null
        val host = address.hostAddress ?: return null
        if (host.contains('%')) return null
        return host
    }

    private fun isLiteralIp(token: String): Boolean {
        if (token.contains(':')) {
            if (token.count { it == ':' } < 2) return false
            return token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }
        val parts = token.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() in 0..255
        }
    }

    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        if (bytes.size == 4 && bytes.all { it == 0xFF.toByte() }) return false
        return true
    }
}

/**
 * Bind, then connect. OkHttp uses the no-arg factory and connects afterwards.
 * A failed bind closes the socket and does not leave an unbound one to use.
 */
private class VpnBoundSocketFactory(
    private val bind: (Socket) -> Unit,
) : SocketFactory() {
    override fun createSocket(): Socket = open(local = null, remote = null)

    override fun createSocket(host: String, port: Int): Socket =
        open(local = null, remote = InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        open(local = InetSocketAddress(localHost, localPort), remote = InetSocketAddress(host, port))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        open(local = null, remote = InetSocketAddress(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = open(
        local = InetSocketAddress(localAddress, localPort),
        remote = InetSocketAddress(address, port),
    )

    private fun open(local: InetSocketAddress?, remote: InetSocketAddress?): Socket {
        val socket = Socket()
        try {
            bind(socket)
            if (local != null) socket.bind(local)
            if (remote != null) socket.connect(remote)
        } catch (e: Exception) {
            runCatching { socket.close() }
            if (e is IOException) throw e
            throw IOException("Could not bind the egress check to the VPN", e)
        }
        return socket
    }
}
