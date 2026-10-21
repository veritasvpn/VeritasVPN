package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StealthDialTest {

    @Test
    fun repinProtectsAConnectedSocketAndDoesNotBindIt() {
        val underlay = kotlinSource("StealthUnderlay.kt")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val attach = slice(underlay, "fun attach", "fun repin")
        assertTrue(attach.contains("protectThenBind"))
        assertTrue(attach.contains("network.bindSocket"))
        assertTrue(attach.indexOf("protectThenBind") < attach.indexOf("network.bindSocket"))
        val repin = underlay.substring(underlay.indexOf("fun repin"))
        assertFalse(repin.contains("bindSocket"))
        assertTrue(repin.contains("return protect(socket)"))
    }

    @Test
    fun stealthWriteLoopFlushesEveryFrame() {
        val transport = kotlinSource("StealthTransport.kt")
        val loop = slice(transport, "fun writeLoop", "fun readLoop")
        val writes = Regex("""output\.write\(""").findAll(loop).count()
        val flushes = Regex("""output\.flush\(\)""").findAll(loop).count()
        assertEquals(writes, flushes)
        assertTrue(writes >= 4)
    }

    @Test
    fun repinOfAMissingNetworkDoesNotCloseTheSocket() {
        val socket = Socket()
        try {
            assertFalse(StealthUnderlay.repin(socket, null) { true })
            assertFalse(socket.isClosed)
            socket.close()
            assertFalse(StealthUnderlay.repin(socket, null) { true })
        } finally {
            socket.close()
        }
    }

    @Test
    fun protectRunsBeforeBindAndBindFailureDoesNotConnect() {
        val events = mutableListOf<String>()
        val socket = Socket()
        try {
            StealthDial.protectThenBind(
                socket,
                protect = {
                    events += "protect"
                    true
                },
                bind = {
                    events += "bind"
                    throw java.io.IOException("bind")
                },
            )
            throw AssertionError("bind failure must not return")
        } catch (e: StealthDial.Failure) {
            assertEquals(StealthDial.Stage.TCP, e.stage)
            assertEquals(StealthDial.BIND_FAILED, e.userMessage)
        }
        assertEquals(listOf("protect", "bind"), events)
        assertTrue(socket.isClosed)
        assertFalse(StealthDial.BIND_FAILED.contains("timeout", ignoreCase = true))
    }

    @Test
    fun dialFailsWhenNoNetworkAndDoesNotProtectOrBind() {
        val events = mutableListOf<String>()
        val socket = Socket()
        try {
            StealthDial.dial(
                socket,
                networkAvailable = false,
                protect = {
                    events += "protect"
                    true
                },
                bind = { events += "bind" },
            )
            throw AssertionError("missing network must not return a socket")
        } catch (e: StealthDial.Failure) {
            assertEquals(StealthDial.NO_NETWORK, e.userMessage)
        }
        assertTrue(events.isEmpty())
        assertTrue(socket.isClosed)
    }

    @Test
    fun underlayAttachWithNoNetworkDoesNotProtect() {
        val socket = Socket()
        var protected = false
        val handle = StealthUnderlay.attach(socket, null) {
            protected = true
            true
        }
        assertNull(handle)
        assertFalse(protected)
        socket.close()
    }

    @Test
    fun failedDialReleasesTheUpgradeHold() {
        val now = 100_000L
        assertTrue(
            StealthDial.mayRedial(
                stage = StealthDial.Stage.TCP,
                dialing = true,
                attemptStartedAtMs = now - 1_000L,
                openedAtMs = 0L,
                boundHandle = 10L,
                currentHandle = 10L,
                nowMs = now,
                attemptFailed = true,
            ),
        )
    }

    @Test
    fun sameNetworkUpgradeIsNotRedialedUntilTheBudget() {
        val now = 100_000L
        assertFalse(
            StealthDial.mayRedial(
                stage = StealthDial.Stage.TLS,
                dialing = true,
                attemptStartedAtMs = now - 1_000L,
                openedAtMs = 0L,
                boundHandle = 10L,
                currentHandle = 10L,
                nowMs = now,
            ),
        )
        assertTrue(
            StealthDial.mayRedial(
                stage = StealthDial.Stage.TLS,
                dialing = true,
                attemptStartedAtMs = now - StealthDial.UPGRADE_BUDGET_MS,
                openedAtMs = 0L,
                boundHandle = 10L,
                currentHandle = 10L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun networkChangeRedialsImmediatelyAndOpenStaysQuiet() {
        assertTrue(
            StealthDial.mayRedial(
                stage = StealthDial.Stage.TCP,
                dialing = true,
                attemptStartedAtMs = 1_000L,
                openedAtMs = 0L,
                boundHandle = 10L,
                currentHandle = 20L,
                nowMs = 1_500L,
            ),
        )
        assertFalse(
            StealthDial.mayRedial(
                stage = StealthDial.Stage.OPEN,
                dialing = false,
                attemptStartedAtMs = 0L,
                openedAtMs = 5_000L,
                boundHandle = 10L,
                currentHandle = 10L,
                nowMs = 5_000L + StealthDial.OPEN_QUIET_MS - 1L,
            ),
        )
        assertTrue(
            StealthDial.mayRedial(
                stage = StealthDial.Stage.OPEN,
                dialing = false,
                attemptStartedAtMs = 0L,
                openedAtMs = 5_000L,
                boundHandle = 10L,
                currentHandle = 20L,
                nowMs = 5_100L,
            ),
        )
    }

    @Test
    fun userFacingErrorIsPlainAndDiagnosticsKeepTheStage() {
        val message = StealthDial.userFacing(
            StealthDial.Stage.TCP,
            SocketTimeoutException("connect timed out to 203.0.113.10:443"),
        )
        assertEquals(StealthDial.USER_UNREACHABLE, message)
        assertFalse(message.contains("tcp"))
        assertFalse(message.contains("tls"))
        assertFalse(message.contains("203.0.113.10"))
        assertFalse(message.contains("timeout", ignoreCase = true))
        assertFalse(message.contains("timed out", ignoreCase = true))
        assertFalse(message.contains("Exception"))
        val connect = StealthDial.userFacing(
            StealthDial.Stage.TLS,
            ConnectException("failed 198.51.100.8"),
        )
        assertEquals(StealthDial.USER_UNREACHABLE, connect)
        assertFalse(connect.contains("198.51.100.8"))
        val diag = StealthDial.detail(StealthDial.Stage.TCP, message)
        assertEquals("tcp: ${StealthDial.USER_UNREACHABLE}", diag)
        assertFalse(diag!!.contains("203.0.113.10"))
        assertNull(StealthDial.detail(StealthDial.Stage.OPEN, message))
        assertEquals("tls", StealthDial.detail(StealthDial.Stage.TLS, null))
    }

    @Test
    fun statusLineIsFriendlyAndFirstConnectSaysConnecting() {
        assertEquals(
            "Reconnecting…",
            StealthDial.statusLine(
                reconnecting = true,
                handshakeOk = false,
                notice = null,
                stealthDetail = "tcp",
            ),
        )
        assertEquals(
            "Connecting…",
            StealthDial.statusLine(
                reconnecting = false,
                handshakeOk = false,
                notice = null,
                stealthDetail = "tls",
            ),
        )
        assertEquals(
            "Connecting… · ${StealthDial.USER_UNREACHABLE}",
            StealthDial.statusLine(
                reconnecting = false,
                handshakeOk = false,
                notice = null,
                stealthDetail = "tls: ${StealthDial.USER_UNREACHABLE}",
            ),
        )
        assertEquals(
            "Reconnecting… · ${StealthDial.USER_UNREACHABLE}",
            StealthDial.statusLine(
                reconnecting = true,
                handshakeOk = false,
                notice = null,
                stealthDetail = "Stealth tls: the secure connection failed",
            ),
        )
        val shown = StealthDial.statusLine(
            true,
            false,
            null,
            "Stealth tcp: the server did not respond",
        )!!
        assertFalse(shown.contains("tcp"))
        assertFalse(shown.contains("tls"))
        assertFalse(shown.contains("upgrade"))
        assertFalse(shown.contains("timeout", ignoreCase = true))
        assertEquals(
            "Reconnecting…",
            StealthDial.statusLine(
                reconnecting = true,
                handshakeOk = false,
                notice = null,
                stealthDetail = null,
            ),
        )
        assertNull(
            StealthDial.statusLine(
                reconnecting = false,
                handshakeOk = true,
                notice = null,
                stealthDetail = "tcp",
            ),
        )
        assertNull(StealthDial.publish("connect timed out"))
        assertNull(StealthDial.publish("203.0.113.10 refused"))
        assertNull(StealthDial.publish("peer 2001:db8::1"))
        assertNull(StealthDial.publish("from ::1"))
        assertNull(StealthDial.publish("fe80::abcd"))
        assertEquals(
            StealthDial.USER_UNREACHABLE,
            StealthDial.publish(StealthDial.USER_UNREACHABLE),
        )
    }

    @Test
    fun transportConnectsOnlyAfterProtectAndBind() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1)
        val acceptThread = Thread {
            runCatching {
                val client = server.accept()
                client.getOutputStream().write(byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x2F))
                client.close()
                accepted.countDown()
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val transport = StealthTransport(
            remoteHost = "127.0.0.1",
            remotePort = server.localPort,
            pathPrefix = "abc123",
            attachUnderlay = { socket ->
                StealthDial.protectThenBind(
                    socket,
                    protect = {
                        events += "protect"
                        true
                    },
                    bind = { events += "bind" },
                )
                7L
            },
        )
        try {
            transport.start()
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            val snapshot = synchronized(events) { events.toList() }
            assertEquals(listOf("protect", "bind"), snapshot.take(2))
            val error = waitForError(transport)
            assertEquals(StealthDial.USER_UNREACHABLE, error)
            assertFalse(error.contains("tls"))
            assertTrue(StealthDial.detail(transport.stage, error)!!.contains("tls"))
            assertFalse(error.contains("127.0.0.1"))
            assertFalse(error.contains("timeout", ignoreCase = true))
            assertFalse(error.contains("timed out", ignoreCase = true))
        } finally {
            transport.stop()
            server.close()
        }
    }

    @Test
    fun transportDoesNotConnectWhenAttachHasNoNetwork() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicBoolean(false)
        val acceptThread = Thread {
            runCatching {
                server.accept()
                accepted.set(true)
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
        val transport = StealthTransport(
            remoteHost = "127.0.0.1",
            remotePort = server.localPort,
            pathPrefix = "abc123",
            attachUnderlay = { null },
        )
        try {
            transport.start()
            val error = waitForError(transport)
            Thread.sleep(500)
            assertFalse(accepted.get())
            assertEquals(StealthDial.USER_UNREACHABLE, error)
            assertFalse(error.contains("tcp"))
            assertTrue(error.contains("network"))
            assertTrue(StealthDial.detail(transport.stage, error)!!.startsWith("tcp"))
            assertFalse(error.contains("timeout", ignoreCase = true))
            assertTrue(transport.mayRedial(4L, System.currentTimeMillis()))
        } finally {
            transport.stop()
            server.close()
        }
    }

    @Test
    fun serviceBindsStealthOnEachDialAndDoesNotRedialDuringUpgrade() {
        val service = kotlinSource("VeritasVpnService.kt")
        val code = service
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertEquals(2, Regex("""\.bindSocket\(""").findAll(code).count())
        val start = slice(code, "fun startStealth", "fun stopStealthTransport")
        assertTrue(start.contains("StealthUnderlay.attach"))
        assertTrue(start.contains("dataPlaneNetwork()"))
        assertTrue(start.indexOf("attachUnderlay") < start.indexOf("dataPlaneNetwork()"))
        assertFalse(start.contains("protectSocket"))
        val apply = slice(code, "fun applyStealthResolved", "fun rememberFreshAdvertisement")
        assertTrue(apply.indexOf("mayRedial") < apply.indexOf("transport.reconnect()"))
        val watch = slice(code, "fun installAndWatch", "fun endpointAcceptable")
        assertTrue(watch.indexOf("stealthUpgradeInProgress") < watch.indexOf("applyResolved"))
        val reprovision = slice(code, "fun requestReprovision", "fun scheduleReprovisionRetry")
        assertTrue(reprovision.contains("stealthUpgradeInProgress"))
        assertTrue(reprovision.contains("scheduleAfterUpgrade"))
        val fresh = slice(code, "fun escalateFreshTunnel", "fun handlePeerGone")
        assertTrue(fresh.indexOf("stealthUpgradeInProgress") < fresh.indexOf("handlePeerGone"))
        assertTrue(fresh.contains("scheduleAfterUpgrade"))
        val protect = slice(code, "fun protectBackendSockets", "fun protectAndBind")
        assertTrue(protect.contains("loopbackRelay"))
        assertTrue(protect.indexOf("loopbackRelay") < protect.indexOf("protectAndBind"))
        assertTrue(protect.contains("prepareLoopbackSocket"))
        assertTrue(protect.indexOf("prepareLoopbackSocket") < protect.indexOf("protectAndBind"))
        assertTrue(protect.contains("stealth dataplane"))
        assertTrue(protect.contains("tune=0"))
        assertFalse(protect.contains("UDP_GRO"))
        val open = slice(code, "fun openInterface", "fun openLockdownHold")
        assertTrue(open.contains("loopbackRelay"))
        assertFalse(open.contains("addDisallowedApplication(packageName)"))
        assertTrue(open.contains("repinStealthCarrier()"))
        assertTrue(open.indexOf("establish()") < open.indexOf("repinStealthCarrier()"))
        val kick = slice(code, "fun kickHandshakePacket", "fun armNatKeepalive")
        assertTrue(kick.contains("writeTunTrigger"))
        assertTrue(kick.contains("stealth kick tun="))
        assertFalse(code.contains("appendStealthStage"))
        assertFalse(code.contains("shortStage"))
        val restore = slice(code, "fun restoreUdpTunnel", "fun startStealth")
        assertTrue(restore.contains("endpointToSaveAfterUdpRestore"))
        assertTrue(restore.indexOf("endpointToSaveAfterUdpRestore") < restore.indexOf("lastChosenEndpoint ="))
        assertTrue(restore.indexOf("lastChosenEndpoint =") < restore.indexOf("publishTransport"))
        val underlay = kotlinSource("StealthUnderlay.kt")
        assertTrue(underlay.contains("protectThenBind"))
        assertTrue(underlay.contains("network.bindSocket"))
        assertTrue(underlay.contains("fun repin"))
        assertTrue(code.contains("stealth repin ok="))
        assertTrue(code.contains("repinStealthCarrier()"))
        assertTrue(underlay.indexOf("network == null") < underlay.indexOf("protectThenBind"))
    }

    @Test
    fun stealthNetworkChangeDialsOnceAndClosesTheOldTunnel() {
        val service = kotlinSource("VeritasVpnService.kt")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val adapt = slice(service, "fun adaptWhileStealth", "fun scheduleStealthRecheck")
        assertTrue(adapt.contains("StealthRoam.next"))
        assertFalse(adapt.contains("recoverWithFreshEndpoint"))
        assertTrue(adapt.contains("stealth roam dial="))
        assertTrue(adapt.contains("stealth roam up="))
        val schedule = slice(service, "fun scheduleUnderlayAdapt", "adaptJob = scope.launch")
        assertTrue(schedule.contains("usesStealth()"))
        assertTrue(schedule.indexOf("usesStealth()") < schedule.indexOf("adaptJob?.cancel()"))
        val dial = slice(service, "fun dialStealthForRoam", "fun rebindWithRetry")
        assertTrue(dial.indexOf("transport.reconnect()") < dial.indexOf("startStealth"))
        val transport = kotlinSource("StealthTransport.kt")
        val close = slice(transport, "fun closeSession", "fun beginAttempt")
        assertTrue(close.contains("encodeClose"))
        assertTrue(close.indexOf("encodeClose") < close.indexOf("sessionGen.incrementAndGet"))
        val stop = slice(service, "fun stopStealthTransport", "fun cancelStealthWork")
        assertTrue(stop.contains("transport.detach()"))
        assertTrue(stop.contains("Looper.myLooper()"))
        assertEquals(2, Regex("""\.bindSocket\(""").findAll(service).count())
    }

    private fun waitForError(transport: StealthTransport): String {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            val error = transport.lastError
            if (!error.isNullOrBlank()) return error
            Thread.sleep(40)
        }
        throw AssertionError("stealth dial did not report an error")
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("$start … $end", from >= 0 && to > from)
        return source.substring(from, to)
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
