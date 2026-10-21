package cloud.veritasvpn.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
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
 * server over TLS. WireGuard's endpoint is [localEndpoint]. Only the TLS
 * socket leaves the device.
 *
 * Each dial creates a plain socket, then [attachUnderlay] protect()s it and
 * bind()s it to the underlay resolved at that moment. The socket connects
 * with a timeout and is wrapped in a trust-all SSLSocket. protect() alone
 * can stamp a stale VPN netId, so a missing network or a failed bind fails
 * the dial and the supervisor retries. [reconnect] closes the in-flight
 * socket so the next dial binds to the network that is current then.
 *
 * One thread reads and writes the TLS socket. Android's TLS stack is not
 * safe when those overlap, and a stuck write would leave the websocket open
 * while WireGuard's later packets never leave. The UDP side is a channel
 * with separate send and receive locks: a [java.net.DatagramSocket] holds
 * one lock for the whole blocking receive, so the handshake response could
 * not be written back until WireGuard sent another datagram.
 *
 * Replies always go to 127.0.0.1 and the port WireGuard sent from. The relay
 * is bound to loopback, and that is the address the userspace socket can
 * receive on.
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
    private val trace: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)
    private val outbound = ArrayBlockingQueue<ByteArray>(64)
    private val udpChannel: DatagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
    private val ipv4Loopback: InetAddress = InetAddress.getByName("127.0.0.1")
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
    private val counts = RelayCounts()

    val localEndpoint: String
        get() {
            val bound = udpChannel.localAddress as? InetSocketAddress
            return "127.0.0.1:${bound?.port ?: 0}"
        }

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
        udpChannel.socket().reuseAddress = true
        udpChannel.bind(InetSocketAddress(ipv4Loopback, 0))
        udpChannel.configureBlocking(true)
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
        runCatching { udpChannel.close() }
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
        // Short waits so queued WireGuard datagrams are written while the
        // peer is quiet. One thread owns the TLS socket for both directions.
        tcp.soTimeout = IO_WAIT_MS
        stage = StealthDial.Stage.OPEN
        dialing = false
        openedAtMs = System.currentTimeMillis()
        lastError = null
        val io = Thread({
            ioLoop(input, output, gen)
        }, "veritas-stealth-io").also { it.isDaemon = true; it.start() }
        while (running.get() && gen == sessionGen.get() && !tcp.isClosed) {
            if (!io.isAlive) break
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                break
            }
        }
        sessionGen.compareAndSet(gen, gen + 1)
        runCatching { tcp.close() }
        io.join(1_000)
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
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
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
        val buf = ByteBuffer.allocate(2048)
        while (running.get()) {
            buf.clear()
            val src = try {
                udpChannel.receive(buf) as? InetSocketAddress
            } catch (_: Exception) {
                if (!running.get() || !udpChannel.isOpen) return
                continue
            } ?: continue
            buf.flip()
            val length = buf.remaining()
            if (length <= 0) continue
            val copy = ByteArray(length)
            buf.get(copy)
            lastPeer = src
            noteUdp(length, src.address.isLoopbackAddress)
            if (!outbound.offer(copy)) {
                outbound.poll()
                outbound.offer(copy)
            }
        }
    }

    /**
     * Reads and writes the websocket on one thread. A server frame is turned
     * back into a UDP datagram before the next read, so a handshake response
     * is not stuck behind a blocking receive.
     */
    private fun ioLoop(input: InputStream, output: OutputStream, gen: Int) {
        val parser = WsFrameBuffer()
        val scratch = ByteArray(4096)
        var lastWriteAt = System.currentTimeMillis()
        try {
            while (running.get() && gen == sessionGen.get()) {
                var wrote = false
                while (running.get() && gen == sessionGen.get()) {
                    val payload = outbound.poll() ?: break
                    output.write(WstunnelProtocol.encodeBinary(payload))
                    noteOut(payload.size)
                    wrote = true
                    lastWriteAt = System.currentTimeMillis()
                }
                val idleMs = System.currentTimeMillis() - lastWriteAt
                if (!wrote && idleMs >= PING_EVERY_MS && running.get() && gen == sessionGen.get()) {
                    output.write(WstunnelProtocol.encodePing())
                    notePing()
                    wrote = true
                    lastWriteAt = System.currentTimeMillis()
                }
                if (wrote) output.flush()
                val n = try {
                    input.read(scratch)
                } catch (_: SocketTimeoutException) {
                    flushCounts()
                    continue
                }
                if (n < 0) {
                    noteEnd("eof")
                    break
                }
                if (n == 0) continue
                parser.append(scratch, n)
                while (true) {
                    val frame = parser.next() ?: break
                    when (frame.opcode) {
                        0x2 -> {
                            noteIn(frame.payload.size)
                            deliverToWireGuard(frame.payload)
                        }
                        0x9 -> {
                            output.write(WstunnelProtocol.encodePong(frame.payload))
                            output.flush()
                            lastWriteAt = System.currentTimeMillis()
                        }
                        0x8 -> {
                            noteEnd("close")
                            return
                        }
                        else -> noteOther()
                    }
                }
            }
        } catch (e: Exception) {
            noteEnd(e.javaClass.simpleName)
        } finally {
            sessionGen.compareAndSet(gen, gen + 1)
            closeActiveSocket()
        }
    }

    private fun deliverToWireGuard(payload: ByteArray) {
        val peer = lastPeer
        if (peer == null || payload.isEmpty() || peer.port !in 1..65535) {
            noteMiss()
            return
        }
        val loopback = peer.address.isLoopbackAddress
        val packet = ByteBuffer.wrap(payload)
        try {
            val sent = udpChannel.send(packet, InetSocketAddress(ipv4Loopback, peer.port))
            if (sent == payload.size) noteOk(loopback) else noteFail("short", loopback)
        } catch (e: Exception) {
            noteFail(e.javaClass.simpleName, loopback)
        }
    }

    /** Binds the relay without dialing. Unit tests deliver server frames into it. */
    internal fun bindRelayForTest() {
        check(!udpChannel.socket().isBound)
        udpChannel.socket().reuseAddress = true
        udpChannel.bind(InetSocketAddress(ipv4Loopback, 0))
        udpChannel.configureBlocking(true)
    }

    internal fun notePeerForTest(address: InetAddress, port: Int) {
        lastPeer = InetSocketAddress(address, port)
    }

    internal fun relayStats(): RelayStats = synchronized(counts) {
        RelayStats(
            udp = counts.udp,
            udpBytes = counts.udpBytes,
            out = counts.out,
            outBytes = counts.outBytes,
            wsIn = counts.wsIn,
            wsInBytes = counts.wsInBytes,
            ok = counts.ok,
            fail = counts.fail,
            miss = counts.miss,
            loopback = counts.loopback == 1,
        )
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

    private fun noteUdp(bytes: Int, loopback: Boolean) = bump {
        counts.udp += 1
        counts.udpBytes += bytes
        counts.loopback = if (loopback) 1 else 0
    }

    private fun noteOut(bytes: Int) = bump {
        counts.out += 1
        counts.outBytes += bytes
    }

    private fun noteIn(bytes: Int) = bump {
        counts.wsIn += 1
        counts.wsInBytes += bytes
    }

    private fun noteOk(loopback: Boolean) = bump {
        counts.ok += 1
        counts.loopback = if (loopback) 1 else 0
    }

    private fun noteFail(kind: String, loopback: Boolean) = bump(force = true) {
        counts.fail += 1
        counts.err = kind.take(16)
        counts.loopback = if (loopback) 1 else 0
    }

    private fun noteMiss() = bump(force = true) {
        counts.miss += 1
    }

    private fun notePing() = bump {
        counts.ping += 1
    }

    private fun noteOther() = bump {
        counts.other += 1
    }

    private fun noteEnd(reason: String) = bump(force = true) {
        counts.read = reason.take(16)
    }

    private fun bump(force: Boolean = false, change: () -> Unit) {
        val line = synchronized(counts) {
            change()
            counts.dirty = true
            val now = System.currentTimeMillis()
            if (!force && now - counts.loggedAt < LOG_EVERY_MS) return@synchronized null
            counts.dirty = false
            counts.loggedAt = now
            counts.lines()
        } ?: return
        line.forEach { runCatching { trace(it) } }
    }

    private fun flushCounts() {
        val line = synchronized(counts) {
            if (!counts.dirty) return@synchronized null
            val now = System.currentTimeMillis()
            if (now - counts.loggedAt < LOG_EVERY_MS) return@synchronized null
            counts.dirty = false
            counts.loggedAt = now
            counts.lines()
        } ?: return
        line.forEach { runCatching { trace(it) } }
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

    private class RelayCounts {
        var udp = 0L
        var udpBytes = 0L
        var out = 0L
        var outBytes = 0L
        var wsIn = 0L
        var wsInBytes = 0L
        var ok = 0L
        var fail = 0L
        var miss = 0L
        var ping = 0L
        var other = 0L
        var loopback = 1
        var read = "up"
        var err = ""
        var dirty = false
        var loggedAt = 0L

        fun lines(): List<String> = stealthRelayLines(
            udp = udp,
            udpBytes = udpBytes,
            out = out,
            outBytes = outBytes,
            wsIn = wsIn,
            wsInBytes = wsInBytes,
            ok = ok,
            fail = fail,
            miss = miss,
            ping = ping,
            other = other,
            loopback = loopback,
            read = read,
            err = err,
        )
    }

    internal data class RelayStats(
        val udp: Long,
        val udpBytes: Long,
        val out: Long,
        val outBytes: Long,
        val wsIn: Long,
        val wsInBytes: Long,
        val ok: Long,
        val fail: Long,
        val miss: Long,
        val loopback: Boolean,
    )

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** How long a quiet websocket read waits before queued datagrams are written. */
        private const val IO_WAIT_MS = 40
        private const val PING_EVERY_MS = 30_000L
        private const val LOG_EVERY_MS = 1_000L

        /**
         * Three short lines, each small enough for the diagnostic ring.
         * c2s is phone to server, s2c is server to phone, io is the socket.
         * No addresses, keys, or hostnames.
         */
        internal fun stealthRelayLines(
            udp: Long,
            udpBytes: Long,
            out: Long,
            outBytes: Long,
            wsIn: Long,
            wsInBytes: Long,
            ok: Long,
            fail: Long,
            miss: Long,
            ping: Long,
            other: Long,
            loopback: Int,
            read: String,
            err: String,
        ): List<String> {
            val readPart = read.take(16).ifEmpty { "up" }
            val errPart = if (err.isEmpty()) "" else " err=${err.take(16)}"
            return listOf(
                "stealth c2s udp=$udp ub=$udpBytes out=$out ob=$outBytes",
                "stealth s2c in=$wsIn ib=$wsInBytes ok=$ok fail=$fail miss=$miss",
                "stealth io ping=$ping other=$other loop=$loopback read=$readPart$errPart",
            )
        }
    }
}
