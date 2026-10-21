package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class StealthProtocolTest {

    @Test
    fun upgradePathStripsOneLeadingSlash() {
        assertEquals("/abc123/events", WstunnelProtocol.upgradePath("abc123"))
        assertEquals("/abc123/events", WstunnelProtocol.upgradePath("/abc123"))
    }

    @Test
    fun upgradePathRejectsNestedOrEmptyPrefixes() {
        assertFails { WstunnelProtocol.upgradePath("") }
        assertFails { WstunnelProtocol.upgradePath("/") }
        assertFails { WstunnelProtocol.upgradePath("/abc/def") }
        assertFails { WstunnelProtocol.upgradePath("abc/def") }
        assertFails { WstunnelProtocol.upgradePath("..") }
    }

    @Test
    fun parsesHostPortAndOmitsDefaultTlsPort() {
        assertEquals(
            WstunnelProtocol.Endpoint("vpn.example", 443),
            WstunnelProtocol.parseEndpoint("vpn.example:443"),
        )
        assertEquals("vpn.example", WstunnelProtocol.hostHeader(WstunnelProtocol.Endpoint("vpn.example", 443)))
        assertEquals(
            "vpn.example:8443",
            WstunnelProtocol.hostHeader(WstunnelProtocol.Endpoint("vpn.example", 8443)),
        )
        assertEquals(
            "[2001:db8::1]",
            WstunnelProtocol.hostHeader(WstunnelProtocol.Endpoint("2001:db8::1", 443)),
        )
        assertNull(WstunnelProtocol.parseEndpoint("not a host"))
        assertNull(WstunnelProtocol.parseEndpoint("vpn.example"))
    }

    @Test
    fun jwtTargetsNodeWireGuardAndIsUnverifiedBearerMaterial() {
        val jwt = WstunnelProtocol.tunnelJwt("6f9619ff-8b86-d011-b42d-00cf4fc964ff")
        val payload = String(
            Base64.getUrlDecoder().decode(jwt.split(".")[1]),
            Charsets.UTF_8,
        )
        assertTrue(payload.contains(""""p":{"Udp":{"timeout":null}}"""))
        assertTrue(payload.contains(""""r":"127.0.0.1""""))
        assertTrue(payload.contains(""""rp":51820"""))
        assertTrue(WstunnelProtocol.protocolHeader(jwt).startsWith("v1, authorization.bearer."))
    }

    @Test
    fun binaryFrameIsUnmaskedHelloWg() {
        val frame = WstunnelProtocol.encodeBinary("hello-wg".toByteArray(Charsets.US_ASCII))
        assertEquals(0x82, frame[0].toInt() and 0xFF)
        assertEquals(0x08, frame[1].toInt() and 0xFF)
        assertEquals("hello-wg", frame.copyOfRange(2, frame.size).toString(Charsets.US_ASCII))
        val parser = WsFrameBuffer()
        parser.append(frame)
        val parsed = parser.next()
        assertEquals(0x2, parsed?.opcode)
        assertEquals("hello-wg", parsed?.payload?.toString(Charsets.US_ASCII))
        assertNull(parser.next())
    }

    @Test
    fun serverBinaryFrameRoundTripsIncludingFragments() {
        val payload = ByteArray(92) { (it + 7).toByte() }
        val frame = WstunnelProtocol.encodeBinary(payload)
        val split = WsFrameBuffer()
        split.append(frame, 5)
        assertNull(split.next())
        split.append(frame.copyOfRange(5, frame.size))
        val whole = split.next()
        assertEquals(0x2, whole?.opcode)
        assertTrue(payload.contentEquals(whole?.payload))

        val mid = 40
        val head = byteArrayOf(0x02, mid.toByte()) + payload.copyOfRange(0, mid)
        val tailLen = payload.size - mid
        val tail = byteArrayOf(0x80.toByte(), tailLen.toByte()) + payload.copyOfRange(mid, payload.size)
        val fragmented = WsFrameBuffer()
        fragmented.append(head)
        assertNull(fragmented.next())
        fragmented.append(tail)
        val joined = fragmented.next()
        assertEquals(0x2, joined?.opcode)
        assertTrue(payload.contentEquals(joined?.payload))

        val masked = ByteArray(6 + payload.size)
        masked[0] = 0x82.toByte()
        masked[1] = (0x80 or payload.size).toByte()
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        System.arraycopy(mask, 0, masked, 2, 4)
        for (i in payload.indices) {
            masked[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        val maskedParser = WsFrameBuffer()
        maskedParser.append(masked)
        val unmasked = maskedParser.next()
        assertEquals(0x2, unmasked?.opcode)
        assertTrue(payload.contentEquals(unmasked?.payload))
    }

    @Test
    fun serverFrameIsDeliveredWhenTheSourceIsNotLoopback() {
        val transport = StealthTransport(
            remoteHost = "127.0.0.1",
            remotePort = 1,
            pathPrefix = "abc123",
            attachUnderlay = { 1L },
        )
        val wg = DatagramSocket(null)
        try {
            wg.reuseAddress = true
            wg.bind(InetSocketAddress(0))
            wg.soTimeout = 2_000
            transport.bindRelayForTest()
            transport.notePeerForTest(InetAddress.getByName("203.0.113.10"), wg.localPort)
            val payload = ByteArray(92) { (it + 5).toByte() }
            transport.appendServerBytesForTest(WstunnelProtocol.encodeBinary(payload))
            val reply = DatagramPacket(ByteArray(128), 128)
            wg.receive(reply)
            assertEquals(payload.size, reply.length)
            assertTrue(payload.contentEquals(reply.data.copyOf(reply.length)))
            val stats = transport.relayStats()
            assertEquals(1, stats.ok)
            assertEquals(0, stats.fail)
            assertFalse(stats.loopback)
        } finally {
            wg.close()
            transport.stop()
        }
    }

    @Test(timeout = 20_000)
    fun relaysHandshakeSizedPacketsBothWaysWithoutASecondClientDatagram() {
        val peer = FakeStealthPeer()
        val lines = ArrayList<String>()
        val transport = StealthTransport(
            remoteHost = "127.0.0.1",
            remotePort = peer.port,
            pathPrefix = "abc123",
            attachUnderlay = { 1L },
            trace = { line -> synchronized(lines) { lines.add(line) } },
        )
        val wg = DatagramSocket(null)
        try {
            transport.start()
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline && transport.stage != StealthDial.Stage.OPEN) {
                if (!transport.lastError.isNullOrBlank()) break
                Thread.sleep(20)
            }
            assertEquals(transport.lastError, StealthDial.Stage.OPEN, transport.stage)
            wg.reuseAddress = true
            wg.bind(InetSocketAddress(0))
            wg.soTimeout = 4_000
            val local = WstunnelProtocol.parseEndpoint(transport.localEndpoint)
            assertNotNull(local)
            val initiation = ByteArray(148) { (it + 1).toByte() }
            wg.send(
                DatagramPacket(
                    initiation,
                    initiation.size,
                    InetAddress.getByName("127.0.0.1"),
                    local!!.port,
                ),
            )
            val forwarded = peer.fromClient.poll(4, TimeUnit.SECONDS)
            assertNotNull(forwarded)
            assertTrue(initiation.contentEquals(forwarded!!))
            val response = ByteArray(92) { (it + 9).toByte() }
            peer.sendToClient(response)
            val reply = DatagramPacket(ByteArray(256), 256)
            wg.receive(reply)
            assertEquals(response.size, reply.length)
            assertTrue(response.contentEquals(reply.data.copyOf(reply.length)))
            val data = ByteArray(64) { (it + 3).toByte() }
            wg.send(
                DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName("127.0.0.1"),
                    local.port,
                ),
            )
            val forwardedData = peer.fromClient.poll(4, TimeUnit.SECONDS)
            assertNotNull(forwardedData)
            assertTrue(data.contentEquals(forwardedData!!))
            val back = ByteArray(80) { (it + 5).toByte() }
            peer.sendToClient(back)
            val backPacket = DatagramPacket(ByteArray(256), 256)
            wg.receive(backPacket)
            assertEquals(back.size, backPacket.length)
            assertTrue(back.contentEquals(backPacket.data.copyOf(backPacket.length)))
            val stats = transport.relayStats()
            assertTrue(stats.udp >= 2)
            assertTrue(stats.out >= 2)
            assertTrue(stats.wsIn >= 2)
            assertTrue(stats.ok >= 2)
            assertEquals(0L, stats.fail)
            assertEquals(0L, stats.miss)
            val traced = synchronized(lines) { lines.toList() }
            assertTrue(traced.any { it.startsWith("stealth c2s ") })
            assertTrue(traced.any { it.startsWith("stealth s2c ") && it.contains("ok=") })
            traced.forEach { assertRelayLineIsSafe(it) }
        } finally {
            wg.close()
            transport.stop()
            peer.close()
        }
    }

    @Test
    fun relayCountersStayInTheDiagnosticRingAtMaxValues() {
        val lines = StealthTransport.stealthRelayLines(
            udp = Long.MAX_VALUE,
            udpBytes = Long.MAX_VALUE,
            out = Long.MAX_VALUE,
            outBytes = Long.MAX_VALUE,
            wsIn = Long.MAX_VALUE,
            wsInBytes = Long.MAX_VALUE,
            ok = Long.MAX_VALUE,
            fail = Long.MAX_VALUE,
            miss = Long.MAX_VALUE,
            ping = Long.MAX_VALUE,
            other = Long.MAX_VALUE,
            loopback = 0,
            read = "SocketTimeoutException",
            err = "ClosedChannelException",
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("stealth c2s "))
        assertTrue(lines[1].startsWith("stealth s2c "))
        assertTrue(lines[2].startsWith("stealth io "))
        assertTrue(lines[2].contains("read=SocketTimeoutEx"))
        assertTrue(lines[2].contains("err=ClosedChannelExc"))
        lines.forEach { assertRelayLineIsSafe(it) }
    }

    @Test
    fun serverFrameIsDeliveredToTheWireGuardSocket() {
        val transport = StealthTransport(
            remoteHost = "127.0.0.1",
            remotePort = 1,
            pathPrefix = "abc123",
            attachUnderlay = { 1L },
        )
        val wg = DatagramSocket(null)
        try {
            wg.reuseAddress = true
            wg.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            wg.soTimeout = 2_000
            transport.bindRelayForTest()
            transport.notePeerForTest(InetAddress.getByName("127.0.0.1"), wg.localPort)
            val payload = ByteArray(92) { (it + 3).toByte() }
            val frame = WstunnelProtocol.encodeBinary(payload)
            transport.appendServerBytesForTest(frame.copyOfRange(0, 4))
            transport.appendServerBytesForTest(frame.copyOfRange(4, frame.size))
            val reply = DatagramPacket(ByteArray(128), 128)
            wg.receive(reply)
            assertEquals(payload.size, reply.length)
            assertTrue(payload.contentEquals(reply.data.copyOf(reply.length)))
        } finally {
            wg.close()
            transport.stop()
        }
    }

    @Test
    fun parserAcceptsMaskedPongAndSplitsAcrossAppends() {
        val payload = ByteArray(200) { it.toByte() }
        val frame = WstunnelProtocol.encodeBinary(payload)
        assertEquals(126, frame[1].toInt() and 0x7F)
        val parser = WsFrameBuffer()
        parser.append(frame, 3)
        assertNull(parser.next())
        parser.append(frame.copyOfRange(3, frame.size))
        val parsed = parser.next()
        assertEquals(0x2, parsed?.opcode)
        assertTrue(payload.contentEquals(parsed?.payload))

        val masked = byteArrayOf(
            0x8A.toByte(),
            0x81.toByte(),
            0x01, 0x02, 0x03, 0x04,
            (0x01 xor 'Z'.code).toByte(),
        )
        val maskedParser = WsFrameBuffer()
        maskedParser.append(masked)
        val pong = maskedParser.next()
        assertEquals(0xA, pong?.opcode)
        assertEquals("Z", pong?.payload?.toString(Charsets.US_ASCII))
    }

    @Test
    fun autoStartsOnUdpThenFallsBackOnlyWithoutAFreshHandshake() {
        val offer = StealthOffer("203.0.113.10:443", "abc123", available = true)
        assertEquals(InitialTransport.UDP, StealthPlanner.initial(StealthMode.AUTO, offer))
        assertEquals(InitialTransport.UDP, StealthPlanner.initial(StealthMode.UDP, offer))
        assertEquals(InitialTransport.STEALTH, StealthPlanner.initial(StealthMode.STEALTH, offer))
        assertEquals(
            InitialTransport.STEALTH,
            StealthPlanner.initial(StealthMode.AUTO, offer, resumeStealth = true),
        )
        assertEquals(
            InitialTransport.UDP,
            StealthPlanner.initial(StealthMode.UDP, offer, resumeStealth = true),
        )
        assertEquals(
            InitialTransport.STEALTH_UNAVAILABLE,
            StealthPlanner.initial(StealthMode.STEALTH, offer.copy(available = false)),
        )
        assertFalse(StealthPlanner.shouldFallback(StealthMode.AUTO, offer, handshakeCompleted = false))
        assertFalse(StealthPlanner.shouldFallback(StealthMode.AUTO, offer, handshakeCompleted = true))
        assertFalse(StealthPlanner.shouldFallback(StealthMode.UDP, offer, handshakeCompleted = false))
        assertFalse(StealthPlanner.shouldFallback(StealthMode.STEALTH, offer, handshakeCompleted = false))
        assertFalse(StealthPlanner.switchesToStealthAfterFailedUdp(StealthMode.AUTO, failedUdpRounds = 1))
        assertFalse(StealthPlanner.switchesToStealthAfterFailedUdp(StealthMode.AUTO, failedUdpRounds = 4))
        assertFalse(StealthPlanner.switchesToStealthAfterFailedUdp(StealthMode.UDP, failedUdpRounds = 2))
        assertFalse(StealthPlanner.switchesToStealthAfterFailedUdp(StealthMode.STEALTH, failedUdpRounds = 2))
        assertFalse(StealthPlanner.udpAttemptSucceeded(1_000, baselineMs = 1_000, startedMs = 2_000))
        assertFalse(StealthPlanner.udpAttemptSucceeded(1_500, baselineMs = 1_000, startedMs = 2_000))
        assertTrue(StealthPlanner.udpAttemptSucceeded(2_100, baselineMs = 1_000, startedMs = 2_000))
    }

    @Test
    fun replaceEndpointKeepsSplitTunnelAndPointsWireGuardAtLocalhost() {
        val original = """
            [Interface]
            PrivateKey = abc
            Address = 10.8.0.2/32
            DNS = 10.8.0.1
            MTU = 1280
            ExcludedApplications = com.example.mail

            [Peer]
            PublicKey = def
            Endpoint = 203.0.113.10:443
            AllowedIPs = 0.0.0.0/5, 8.0.0.0/7
            PersistentKeepalive = 25
        """.trimIndent()
        val updated = EndpointSelector.replaceEndpoint(original, "127.0.0.1:41820")
        assertEquals("127.0.0.1:41820", EndpointSelector.endpointFromConfig(updated))
        assertTrue(updated.contains("ExcludedApplications = com.example.mail"))
        assertTrue(updated.contains("AllowedIPs = 0.0.0.0/5, 8.0.0.0/7"))
        assertTrue(updated.contains("MTU = 1280"))
        assertFalse(updated.contains("203.0.113.10:443"))
    }

    @Test
    fun echoesUdpThroughBundledWstunnelWhenPresent() {
        val bin = File(System.getenv("VERITAS_WSTUNNEL_BIN") ?: "/tmp/wstunnel")
        assumeTrue("wstunnel binary not available", bin.isFile && bin.canExecute())
        val echo = DatagramSocket(null)
        echo.reuseAddress = true
        try {
            echo.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), WstunnelProtocol.WG_REMOTE_PORT))
        } catch (_: Exception) {
            echo.close()
            assumeTrue("WireGuard port ${WstunnelProtocol.WG_REMOTE_PORT} is busy", false)
            return
        }
        val running = AtomicBoolean(true)
        val echoThread = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    echo.receive(packet)
                } catch (_: Exception) {
                    return@Thread
                }
                val reply = DatagramPacket(packet.data, packet.length, packet.address, packet.port)
                runCatching { echo.send(reply) }
            }
        }
        echoThread.isDaemon = true
        echoThread.start()

        val wss = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val wssPort = wss.localPort
        wss.close()

        val server = ProcessBuilder(
            bin.absolutePath,
            "server",
            "--restrict-http-upgrade-path-prefix",
            "abc123",
            "--restrict-to",
            "127.0.0.1:${WstunnelProtocol.WG_REMOTE_PORT}",
            "wss://127.0.0.1:$wssPort",
        ).apply {
            redirectErrorStream(true)
            environment().remove("NO_COLOR")
        }.start()
        var transport: StealthTransport? = null
        var client: DatagramSocket? = null
        try {
            Thread.sleep(400)
            assumeTrue("wstunnel server exited", server.isAlive)
            transport = StealthTransport(
                remoteHost = "127.0.0.1",
                remotePort = wssPort,
                pathPrefix = "abc123",
                attachUnderlay = { 1L },
            )
            transport.start()
            client = DatagramSocket(null)
            client.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            client.soTimeout = 8_000
            val payload = "hello-wg".toByteArray(Charsets.US_ASCII)
            val local = WstunnelProtocol.parseEndpoint(transport.localEndpoint)
            assertTrue(local != null)
            client.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName("127.0.0.1"),
                    local!!.port,
                ),
            )
            val reply = DatagramPacket(ByteArray(64), 64)
            client.receive(reply)
            assertEquals("hello-wg", String(reply.data, 0, reply.length, Charsets.US_ASCII))
        } finally {
            running.set(false)
            client?.close()
            transport?.stop()
            echo.close()
            server.destroy()
            server.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (server.isAlive) server.destroyForcibly()
        }
    }

    /**
     * Same gates as [RoamLog] and the support diagnostic ring: 160 characters,
     * no addresses, and only the characters those rings keep.
     */
    private fun assertRelayLineIsSafe(line: String) {
        assertTrue(line.length <= 160)
        assertEquals(line, RoamLog.sanitize(line))
        assertFalse(Regex("""(?:\d{1,3}\.){3}\d{1,3}""").containsMatchIn(line))
        assertTrue(line.all { it.isLetterOrDigit() || it == ' ' || it in "=._\$:-" })
    }

    /**
     * In-process wstunnel stand-in: TLS, the HTTP upgrade, then one binary
     * frame per datagram in both directions.
     */
    private class FakeStealthPeer : AutoCloseable {
        val fromClient = ArrayBlockingQueue<ByteArray>(8)
        private val toClient = ArrayBlockingQueue<ByteArray>(8)
        private val server: SSLServerSocket = newServer()
        val port: Int = server.localPort
        private val thread = Thread(this::run, "fake-stealth").also {
            it.isDaemon = true
            it.start()
        }

        fun sendToClient(payload: ByteArray) {
            toClient.put(payload)
        }

        override fun close() {
            thread.interrupt()
            runCatching { server.close() }
            thread.join(1_000)
        }

        private fun run() {
            val socket = try {
                server.accept() as SSLSocket
            } catch (_: Exception) {
                return
            }
            socket.use { tls ->
                tls.soTimeout = 5_000
                val input = tls.inputStream
                val output = tls.outputStream
                val headers = readHeaders(input)
                val key = headerValue(headers, "sec-websocket-key") ?: return
                val accept = WstunnelProtocol.acceptKey(key)
                output.write(
                    buildString {
                        append("HTTP/1.1 101 Switching Protocols\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Accept: $accept\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.US_ASCII),
                )
                output.flush()
                tls.soTimeout = 40
                val parser = WsFrameBuffer()
                val scratch = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    while (true) {
                        val payload = toClient.poll() ?: break
                        output.write(WstunnelProtocol.encodeBinary(payload))
                        output.flush()
                    }
                    val n = try {
                        input.read(scratch)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        return
                    }
                    if (n < 0) return
                    if (n == 0) continue
                    parser.append(scratch, n)
                    while (true) {
                        val frame = parser.next() ?: break
                        if (frame.opcode == 0x2) fromClient.offer(frame.payload.copyOf())
                    }
                }
            }
        }

        private fun readHeaders(input: InputStream): String {
            val out = StringBuilder()
            while (out.length < 16_384) {
                val b = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    continue
                }
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

        private fun newServer(): SSLServerSocket {
            val dir = java.nio.file.Files.createTempDirectory("stealth-relay")
            val store = dir.resolve("relay.p12").toFile()
            val bundled = File(System.getProperty("java.home"), "bin/keytool")
            val tool = if (bundled.canExecute()) bundled.absolutePath else "keytool"
            val proc = ProcessBuilder(
                tool,
                "-genkeypair",
                "-alias", "relay",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", store.absolutePath,
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", "CN=localhost",
                "-validity", "1",
                "-noprompt",
            ).redirectErrorStream(true).start()
            val log = proc.inputStream.readBytes()
            check(proc.waitFor() == 0) { "keytool failed: ${String(log)}" }
            val keys = KeyStore.getInstance("PKCS12")
            store.inputStream().use { keys.load(it, "changeit".toCharArray()) }
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(keys, "changeit".toCharArray())
            val context = SSLContext.getInstance("TLS")
            context.init(factory.keyManagers, null, SecureRandom())
            return context.serverSocketFactory.createServerSocket(
                0,
                50,
                InetAddress.getByName("127.0.0.1"),
            ) as SSLServerSocket
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
