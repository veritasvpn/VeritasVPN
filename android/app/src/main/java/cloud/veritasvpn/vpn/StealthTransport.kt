package cloud.veritasvpn.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Local UDP listener that carries WireGuard datagrams to the node's wstunnel
 * server over TLS. WireGuard's endpoint is [localEndpoint]; only this socket
 * leaves the device, and only after [protectSocket] (the same escape the
 * WireGuard UDP socket uses under Always-on lockdown).
 *
 * The TLS certificate is not verified. wstunnel's server certificate is
 * generated in-process, and the Linux client leaves certificate verification
 * off (`--tls-verify-certificate` defaults to false).
 */
class StealthTransport(
    private val remoteHost: String,
    private val remotePort: Int,
    private val pathPrefix: String,
    private val protectSocket: (Socket) -> Boolean,
) {
    private val running = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)
    private val outbound = ArrayBlockingQueue<ByteArray>(64)
    private val udpSocket = DatagramSocket(null)
    private var supervisor: Thread? = null
    private var activeSocket: Socket? = null
    @Volatile private var lastPeer: InetSocketAddress? = null
    @Volatile var lastError: String? = null
        private set

    val localEndpoint: String
        get() = "127.0.0.1:${udpSocket.localPort}"

    fun start() {
        if (running.getAndSet(true)) return
        udpSocket.reuseAddress = true
        udpSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        udpSocket.soTimeout = 0
        Thread({ udpLoop() }, "veritas-stealth-udp").also { it.isDaemon = true; it.start() }
        supervisor = Thread({
            while (running.get()) {
                try {
                    runSession()
                } catch (e: Exception) {
                    lastError = when (e) {
                        is java.net.SocketTimeoutException,
                        is java.io.InterruptedIOException -> "Connection timed out"
                        is java.net.UnknownHostException -> "Could not resolve server address"
                        is java.net.ConnectException -> "Could not connect to server"
                        is javax.net.ssl.SSLException -> "Secure connection failed"
                        else -> e.javaClass.simpleName
                    }
                }
                closeActiveSocket()
                if (running.get()) {
                    try {
                        Thread.sleep(400)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }, "veritas-stealth").also { it.isDaemon = true; it.start() }
    }

    /** Drop the TLS session so the next dial follows the current underlay. */
    fun reconnect() {
        sessionGen.incrementAndGet()
        closeActiveSocket()
    }

    fun stop() {
        running.set(false)
        sessionGen.incrementAndGet()
        closeActiveSocket()
        runCatching { udpSocket.close() }
        supervisor?.interrupt()
        supervisor?.join(1_500)
        supervisor = null
        outbound.clear()
    }

    private fun runSession() {
        val endpoint = WstunnelProtocol.Endpoint(remoteHost, remotePort)
        val path = WstunnelProtocol.upgradePath(pathPrefix)
        val gen = sessionGen.incrementAndGet()
        val tcp = openTls(endpoint)
        synchronized(this) { activeSocket = tcp }
        if (!running.get() || gen != sessionGen.get()) {
            tcp.close()
            return
        }
        val input = tcp.getInputStream()
        val output = tcp.getOutputStream()
        handshake(endpoint, path, input, output)
        lastError = null
        val reader = Thread({
            readLoop(input, output, gen)
        }, "veritas-stealth-rx").also { it.isDaemon = true; it.start() }
        val writer = Thread({
            writeLoop(output, gen)
        }, "veritas-stealth-tx").also { it.isDaemon = true; it.start() }
        while (running.get() && gen == sessionGen.get() && !tcp.isClosed) {
            if (!reader.isAlive || !writer.isAlive) break
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                break
            }
        }
        sessionGen.compareAndSet(gen, gen + 1)
        runCatching { tcp.close() }
        reader.join(1_000)
        writer.join(1_000)
    }

    private fun openTls(endpoint: WstunnelProtocol.Endpoint): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        if (!protectSocket(socket)) {
            socket.close()
            throw IllegalStateException("Could not protect the Stealth socket from the VPN")
        }
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
        socket.soTimeout = 0
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 10_000)
        socket.startHandshake()
        return socket
    }

    private fun handshake(
        endpoint: WstunnelProtocol.Endpoint,
        path: String,
        input: InputStream,
        output: OutputStream,
    ) {
        val key = WstunnelProtocol.websocketKey()
        val jwt = WstunnelProtocol.tunnelJwt(UUID.randomUUID().toString())
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: ${WstunnelProtocol.hostHeader(endpoint)}\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: ${WstunnelProtocol.protocolHeader(jwt)}\r\n")
            append("\r\n")
        }
        synchronized(output) {
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val response = readHttpHeaders(input)
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            throw IllegalStateException("Stealth handshake rejected")
        }
        val accept = headerValue(response, "sec-websocket-accept")
        if (accept != null && accept != WstunnelProtocol.acceptKey(key)) {
            throw IllegalStateException("Stealth handshake key mismatch")
        }
    }

    private fun udpLoop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udpSocket.receive(packet)
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }
            lastPeer = InetSocketAddress(packet.address, packet.port)
            val copy = packet.data.copyOf(packet.length)
            if (!outbound.offer(copy)) {
                outbound.poll()
                outbound.offer(copy)
            }
        }
    }

    private fun writeLoop(output: OutputStream, gen: Int) {
        try {
            while (running.get() && gen == sessionGen.get()) {
                val payload = outbound.poll(30, TimeUnit.SECONDS)
                synchronized(output) {
                    if (payload != null) {
                        output.write(WstunnelProtocol.encodeBinary(payload))
                    } else if (running.get() && gen == sessionGen.get()) {
                        output.write(WstunnelProtocol.encodePing())
                    }
                    output.flush()
                }
            }
        } catch (_: Exception) {
            sessionGen.compareAndSet(gen, gen + 1)
            closeActiveSocket()
        }
    }

    private fun readLoop(input: InputStream, output: OutputStream, gen: Int) {
        val parser = WsFrameBuffer()
        val scratch = ByteArray(4096)
        try {
            while (running.get() && gen == sessionGen.get()) {
                val n = input.read(scratch)
                if (n < 0) break
                if (n == 0) continue
                parser.append(scratch, n)
                while (true) {
                    val frame = parser.next() ?: break
                    when (frame.opcode) {
                        0x2 -> deliverToWireGuard(frame.payload)
                        0x9 -> synchronized(output) {
                            output.write(WstunnelProtocol.encodePong(frame.payload))
                            output.flush()
                        }
                        0x8 -> return
                        else -> Unit
                    }
                }
            }
        } catch (_: Exception) {
            // Session ended or the underlay moved.
        } finally {
            sessionGen.compareAndSet(gen, gen + 1)
            closeActiveSocket()
        }
    }

    private fun deliverToWireGuard(payload: ByteArray) {
        val peer = lastPeer ?: return
        if (payload.isEmpty()) return
        val packet = DatagramPacket(payload, payload.size, peer.address, peer.port)
        runCatching { udpSocket.send(packet) }
    }

    private fun closeActiveSocket() {
        val socket = synchronized(this) {
            val current = activeSocket
            activeSocket = null
            current
        }
        runCatching { socket?.close() }
    }

    private fun readHttpHeaders(input: InputStream): String {
        val out = StringBuilder()
        while (out.length < 16_384) {
            val b = input.read()
            if (b < 0) break
            out.append(b.toChar())
            if (out.endsWith("\r\n\r\n")) break
        }
        return out.toString()
    }

    private fun headerValue(headers: String, name: String): String? {
        for (line in headers.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
