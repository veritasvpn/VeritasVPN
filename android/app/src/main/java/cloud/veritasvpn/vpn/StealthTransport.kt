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
 * server over TLS. WireGuard's endpoint is [localEndpoint]. Only this socket
 * leaves the device.
 *
 * Each dial creates a plain socket, then [attachUnderlay] protect()s it and
 * bind()s it to the underlay resolved at that moment. The socket connects
 * with a timeout and is wrapped in a trust-all SSLSocket. protect() alone
 * can stamp a stale VPN netId, so a missing network or a failed bind fails
 * the dial and the supervisor retries. [reconnect] closes the in-flight
 * socket so the next dial binds to the network that is current then.
 *
 * The TLS certificate is not verified. wstunnel's server certificate is
 * generated in-process, and the Linux client leaves certificate verification
 * off (`--tls-verify-certificate` defaults to false).
 */
class StealthTransport(
    private val remoteHost: String,
    private val remotePort: Int,
    private val pathPrefix: String,
    private val attachUnderlay: (Socket) -> Long?,
) {
    private val running = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)
    private val outbound = ArrayBlockingQueue<ByteArray>(64)
    private val udpSocket = DatagramSocket(null)
    private var supervisor: Thread? = null
    private var activeSocket: Socket? = null
    private var dialingSocket: Socket? = null
    @Volatile private var lastPeer: InetSocketAddress? = null
    @Volatile var lastError: String? = null
        private set
    @Volatile var stage: StealthDial.Stage? = null
        private set
    @Volatile private var dialing = false
    @Volatile private var attemptStartedAtMs = 0L
    @Volatile private var openedAtMs = 0L
    @Volatile private var boundHandle: Long? = null

    val localEndpoint: String
        get() = "127.0.0.1:${udpSocket.localPort}"

    fun targets(host: String, port: Int, pathPrefix: String): Boolean =
        remoteHost == host && remotePort == port && this.pathPrefix == pathPrefix

    fun mayRedial(currentHandle: Long?, nowMs: Long): Boolean = StealthDial.mayRedial(
        stage = stage,
        dialing = dialing,
        attemptStartedAtMs = attemptStartedAtMs,
        openedAtMs = openedAtMs,
        boundHandle = boundHandle,
        currentHandle = currentHandle,
        nowMs = nowMs,
    )

    fun start() {
        if (running.getAndSet(true)) return
        beginAttempt(keepBoundHandle = false)
        udpSocket.reuseAddress = true
        udpSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        udpSocket.soTimeout = 0
        Thread({ udpLoop() }, "veritas-stealth-udp").also { it.isDaemon = true; it.start() }
        supervisor = Thread({
            while (running.get()) {
                if (!dialing) beginAttempt(keepBoundHandle = true)
                try {
                    runSession()
                } catch (_: SessionSuperseded) {
                    // reconnect() closed this dial. The next loop binds again.
                } catch (e: Exception) {
                    val failed = stage ?: StealthDial.Stage.TCP
                    lastError = StealthDial.userFacing(failed, e)
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
        beginAttempt(keepBoundHandle = false)
        closeDialing()
        closeActiveSocket()
    }

    fun stop() {
        running.set(false)
        dialing = false
        sessionGen.incrementAndGet()
        closeDialing()
        closeActiveSocket()
        runCatching { udpSocket.close() }
        supervisor?.interrupt()
        supervisor?.join(1_500)
        supervisor = null
        outbound.clear()
    }

    private fun beginAttempt(keepBoundHandle: Boolean) {
        stage = StealthDial.Stage.TCP
        dialing = true
        openedAtMs = 0L
        attemptStartedAtMs = System.currentTimeMillis()
        if (!keepBoundHandle) boundHandle = null
    }

    private fun runSession() {
        val endpoint = WstunnelProtocol.Endpoint(remoteHost, remotePort)
        val path = WstunnelProtocol.upgradePath(pathPrefix)
        val gen = sessionGen.incrementAndGet()
        stage = StealthDial.Stage.TCP
        val tcp = openTls(endpoint, gen)
        synchronized(this) { activeSocket = tcp }
        if (!running.get() || gen != sessionGen.get()) {
            tcp.close()
            return
        }
        val input = tcp.getInputStream()
        val output = tcp.getOutputStream()
        stage = StealthDial.Stage.UPGRADE
        handshake(endpoint, path, input, output)
        tcp.soTimeout = 0
        stage = StealthDial.Stage.OPEN
        dialing = false
        openedAtMs = System.currentTimeMillis()
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

    private fun openTls(endpoint: WstunnelProtocol.Endpoint, gen: Int): SSLSocket {
        val plain = Socket()
        plain.tcpNoDelay = true
        var ssl: SSLSocket? = null
        synchronized(this) { dialingSocket = plain }
        try {
            if (!running.get() || gen != sessionGen.get()) throw SessionSuperseded()
            val handle = attachUnderlay(plain)
            if (handle == null) {
                throw StealthDial.Failure(StealthDial.Stage.TCP, StealthDial.NO_NETWORK)
            }
            boundHandle = handle
            if (!running.get() || gen != sessionGen.get()) throw SessionSuperseded()
            plain.connect(
                InetSocketAddress(endpoint.host, endpoint.port),
                StealthDial.CONNECT_TIMEOUT_MS,
            )
            stage = StealthDial.Stage.TLS
            ssl = wrapTls(plain, endpoint)
            ssl.soTimeout = StealthDial.CONNECT_TIMEOUT_MS
            ssl.startHandshake()
            synchronized(this) {
                if (dialingSocket === plain) dialingSocket = null
            }
            return ssl
        } catch (e: Exception) {
            synchronized(this) {
                if (dialingSocket === plain) dialingSocket = null
            }
            runCatching { ssl?.close() }
            runCatching { plain.close() }
            if (e is SessionSuperseded || gen != sessionGen.get() || !running.get()) {
                throw SessionSuperseded()
            }
            throw e
        }
    }

    private fun wrapTls(plain: Socket, endpoint: WstunnelProtocol.Endpoint): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        val socket = context.socketFactory.createSocket(
            plain,
            endpoint.host,
            endpoint.port,
            true,
        ) as SSLSocket
        socket.useClientMode = true
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
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
            throw VeritasVpnService.VpnError("Stealth server rejected the connection.")
        }
        val accept = headerValue(response, "sec-websocket-accept")
        if (accept != null && accept != WstunnelProtocol.acceptKey(key)) {
            throw VeritasVpnService.VpnError("Stealth server identity didn't match. Try again or switch server.")
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

    /** Binds the relay without dialing. Unit tests deliver server frames into it. */
    internal fun bindRelayForTest() {
        check(!udpSocket.isBound)
        udpSocket.reuseAddress = true
        udpSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }

    internal fun notePeerForTest(address: InetAddress, port: Int) {
        lastPeer = InetSocketAddress(address, port)
    }

    /**
     * The same parser the read loop uses. Split TCP reads and continuation
     * frames are reassembled, then a binary payload is written to the peer.
     */
    private val serverFramesForTest = WsFrameBuffer()

    internal fun appendServerBytesForTest(data: ByteArray) {
        serverFramesForTest.append(data)
        while (true) {
            val frame = serverFramesForTest.next() ?: return
            if (frame.opcode == 0x2) deliverToWireGuard(frame.payload)
        }
    }

    private fun closeDialing() {
        val socket = synchronized(this) {
            val current = dialingSocket
            dialingSocket = null
            current
        }
        runCatching { socket?.close() }
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

    private class SessionSuperseded : Exception()

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
