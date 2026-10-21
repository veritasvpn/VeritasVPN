package cloud.veritasvpn.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
 * The TLS socket has one reader and one writer. The reader never writes, so
 * a short read timeout is not used: Android's TLS stack can discard a
 * record when a read times out, which drops the handshake response while
 * the websocket stays open. The UDP side is a channel bound to 127.0.0.1.
 * Send and receive do not share a lock. A reply is sent from that bound
 * port to the same family and port WireGuard sent from, which is the source
 * userspace WireGuard is listening on.
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
    private val control = ArrayBlockingQueue<ByteArray>(32)
    private val udpChannel: DatagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
    private var v6Channel: DatagramChannel? = null
    private var boundPort: Int = 0
    private var v6BoundPort: Int = 0
    private val ipv4Loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private var supervisor: Thread? = null
    private var activeSocket: Socket? = null
    private var dialingSocket: Socket? = null
    /** Plain TCP socket that was protect()'d and bound. TLS writes use its fd. */
    private var carrier: Socket? = null
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
        boundPort = (udpChannel.localAddress as? InetSocketAddress)?.port ?: 0
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
        control.clear()
    }

    fun stop() {
        running.set(false)
        dialing = false
        sessionGen.incrementAndGet()
        closeDialing()
        closeActiveSocket()
        runCatching { v6Channel?.close() }
        v6Channel = null
        runCatching { udpChannel.close() }
        control.clear()
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
        // Blocking reads. A short timeout on Android's TLS stack can drop the
        // handshake response and leave this websocket open.
        tcp.soTimeout = 0
        tcp.keepAlive = true
        stage = StealthDial.Stage.OPEN
        dialing = false
        openedAtMs = System.currentTimeMillis()
        lastError = null
        val writer = Thread({
            writeLoop(output, gen)
        }, "veritas-stealth-wr").also { it.isDaemon = true; it.start() }
        val reader = Thread({
            readLoop(input, gen)
        }, "veritas-stealth-rd").also { it.isDaemon = true; it.start() }
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
                carrier = plain
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
     * Writes the websocket only. The reader never touches [output], so Android's
     * TLS stack is not asked to read and write on one thread or to abort a read.
     */
    private fun writeLoop(output: OutputStream, gen: Int) {
        var lastWriteAt = System.currentTimeMillis()
        try {
            while (running.get() && gen == sessionGen.get()) {
                var wrote = false
                while (running.get() && gen == sessionGen.get()) {
                    val frame = control.poll() ?: break
                    output.write(frame)
                    output.flush()
                    noteFlush()
                    wrote = true
                    lastWriteAt = System.currentTimeMillis()
                }
                while (running.get() && gen == sessionGen.get()) {
                    val payload = outbound.poll() ?: break
                    output.write(WstunnelProtocol.encodeBinary(payload))
                    output.flush()
                    noteOut(payload.size)
                    wrote = true
                    lastWriteAt = System.currentTimeMillis()
                }
                val idleMs = System.currentTimeMillis() - lastWriteAt
                if (!wrote && idleMs >= PING_EVERY_MS && running.get() && gen == sessionGen.get()) {
                    output.write(WstunnelProtocol.encodePing())
                    output.flush()
                    notePing()
                    wrote = true
                    lastWriteAt = System.currentTimeMillis()
                }
                if (wrote) continue
                val payload = outbound.poll(WRITE_WAIT_MS, TimeUnit.MILLISECONDS) ?: continue
                output.write(WstunnelProtocol.encodeBinary(payload))
                output.flush()
                noteOut(payload.size)
                lastWriteAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            noteEnd(e.javaClass.simpleName)
        } finally {
            sessionGen.compareAndSet(gen, gen + 1)
            closeActiveSocket()
        }
    }

    /** Blocking read. A binary frame is written back to WireGuard before the next read. */
    private fun readLoop(input: InputStream, gen: Int) {
        val parser = WsFrameBuffer()
        val scratch = ByteArray(4096)
        try {
            while (running.get() && gen == sessionGen.get()) {
                val n = input.read(scratch)
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
                            val pong = WstunnelProtocol.encodePong(frame.payload)
                            if (!control.offer(pong)) {
                                control.poll()
                                control.offer(pong)
                            }
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
        val fam = familyCode(peer.address)
        val loopback = peer.address.isLoopbackAddress || fam == 4 && isMappedLoopback(peer.address)
        val channel = if (fam == 6) v6Relay() else udpChannel
        if (channel == null) {
            noteFail("v6", loopback, fam)
            return
        }
        val dest = if (fam == 6) {
            InetSocketAddress(InetAddress.getByName("::1"), peer.port)
        } else {
            InetSocketAddress(ipv4Loopback, peer.port)
        }
        try {
            val sent = channel.send(ByteBuffer.wrap(payload), dest)
            val sport = if (fam == 6) v6BoundPort == boundPort && boundPort != 0 else boundPort != 0
            if (sent == payload.size) noteOk(loopback, fam, sport) else noteFail("short", loopback, fam)
        } catch (e: Exception) {
            noteFail(e.javaClass.simpleName, loopback, fam)
        }
    }

    /** IPv6 replies have to leave ::1 or the v6 WireGuard socket never sees them. */
    private fun v6Relay(): DatagramChannel? {
        v6Channel?.let { return it }
        val channel = try {
            DatagramChannel.open(StandardProtocolFamily.INET6)
        } catch (_: Exception) {
            return null
        }
        return try {
            channel.socket().reuseAddress = true
            val loopback6 = InetAddress.getByName("::1")
            try {
                val port = if (boundPort in 1..65535) boundPort else 0
                channel.bind(InetSocketAddress(loopback6, port))
            } catch (_: Exception) {
                channel.bind(InetSocketAddress(loopback6, 0))
            }
            channel.configureBlocking(true)
            v6BoundPort = (channel.localAddress as? InetSocketAddress)?.port ?: 0
            v6Channel = channel
            channel
        } catch (_: Exception) {
            runCatching { channel.close() }
            null
        }
    }

    /** The datagram socket the service can pin to lo. Same fd the channel sends on. */
    internal fun relaySocket(): java.net.DatagramSocket = udpChannel.socket()

    /** Binds the relay without dialing. Unit tests deliver server frames into it. */
    internal fun bindRelayForTest() {
        check(!udpChannel.socket().isBound)
        udpChannel.socket().reuseAddress = true
        udpChannel.bind(InetSocketAddress(ipv4Loopback, 0))
        udpChannel.configureBlocking(true)
        boundPort = (udpChannel.localAddress as? InetSocketAddress)?.port ?: 0
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
        counts.fam = familyCode(lastPeer?.address)
    }

    private fun noteOut(bytes: Int) = bump {
        counts.out += 1
        counts.outBytes += bytes
        counts.flush += 1
    }

    private fun noteFlush() = bump {
        counts.flush += 1
    }

    private fun noteIn(bytes: Int) = bump {
        counts.wsIn += 1
        counts.wsInBytes += bytes
    }

    private fun noteOk(loopback: Boolean, fam: Int, fromBoundPort: Boolean) = bump {
        counts.ok += 1
        counts.loopback = if (loopback) 1 else 0
        counts.fam = fam
        counts.sport = if (fromBoundPort) 1 else 0
    }

    private fun noteFail(kind: String, loopback: Boolean, fam: Int = 0) = bump(force = true) {
        counts.fail += 1
        counts.err = kind.take(16)
        counts.loopback = if (loopback) 1 else 0
        if (fam != 0) counts.fam = fam
    }

    private fun noteMiss() = bump(force = true) {
        counts.miss += 1
    }

    private fun notePing() = bump {
        counts.ping += 1
        counts.flush += 1
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

    private fun closeDialing() {
        val socket = synchronized(this) {
            val current = dialingSocket
            dialingSocket = null
            current
        }
        runCatching { socket?.close() }
    }

    /**
     * The connected underlay socket, so the service can protect and bind it
     * again after establish(). Null before the TLS handshake finishes.
     */
    internal fun carrierSocket(): Socket? = carrier

    private fun closeActiveSocket() {
        val socket = synchronized(this) {
            val current = activeSocket
            activeSocket = null
            carrier = null
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
        var flush = 0L
        var loopback = 1
        var fam = 4
        var sport = 1
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
            flush = flush,
            loopback = loopback,
            fam = fam,
            sport = sport,
            read = read,
            err = err,
        )
    }

    private fun familyCode(address: InetAddress?): Int {
        if (address == null) return 0
        if (address is java.net.Inet4Address) return 4
        if (isMappedLoopback(address) || isMappedV4(address)) return 4
        if (address is java.net.Inet6Address) return 6
        return 0
    }

    private fun isMappedV4(address: InetAddress): Boolean {
        if (address !is java.net.Inet6Address) return false
        val raw = address.address
        if (raw.size != 16) return false
        for (i in 0 until 10) if (raw[i] != 0.toByte()) return false
        return raw[10] == 0xff.toByte() && raw[11] == 0xff.toByte()
    }

    private fun isMappedLoopback(address: InetAddress): Boolean {
        if (!isMappedV4(address)) return false
        val raw = address.address
        return raw[12] == 127.toByte()
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
        /** How long the writer waits for the next WireGuard datagram. */
        private const val WRITE_WAIT_MS = 50L
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
            flush: Long,
            loopback: Int,
            fam: Int,
            sport: Int,
            read: String,
            err: String,
        ): List<String> {
            val readPart = read.take(16).ifEmpty { "up" }
            val errPart = if (err.isEmpty()) "" else " err=${err.take(16)}"
            return listOf(
                "stealth c2s udp=$udp ub=$udpBytes out=$out ob=$outBytes",
                "stealth s2c in=$wsIn ib=$wsInBytes ok=$ok fail=$fail miss=$miss fam=$fam sport=$sport",
                "stealth io ping=$ping other=$other loop=$loopback fl=$flush read=$readPart$errPart",
            )
        }
    }
}
