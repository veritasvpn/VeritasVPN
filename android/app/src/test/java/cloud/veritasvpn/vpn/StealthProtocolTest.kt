package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

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
        assumeTrue("wstunnel binary not available", bin.canExecute())
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

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
