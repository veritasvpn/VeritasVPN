package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cellular to home Wi-Fi must install the endpoint the API returns for the
 * network the phone is on now. The previous network's endpoint is not reused,
 * and an in-flight attempt for an older network is dropped.
 */
class EndpointRefreshTest {
    private val wan = "203.0.113.10:443"
    private val lan = "198.51.100.6:51820"

    @Test
    fun networkChangeInstallsTheFreshEndpointAndDropsThePreviousOne() {
        val fromCell = EndpointRefresh.offer(
            serverEndpoint = lan,
            lan = lan,
            wan = wan,
            stealthEndpoint = null,
            stealthAvailable = false,
            stealthPrefix = null,
            ignoredPrevious = wan,
        )
        assertNotNull(fromCell)
        assertEquals(lan, fromCell?.serverEndpoint)

        val fromHome = EndpointRefresh.offer(
            serverEndpoint = wan,
            lan = lan,
            wan = wan,
            stealthEndpoint = null,
            stealthAvailable = false,
            stealthPrefix = null,
            ignoredPrevious = lan,
        )
        assertEquals(wan, fromHome?.serverEndpoint)

        assertNull(
            EndpointRefresh.offer(
                serverEndpoint = "  ",
                lan = lan,
                wan = wan,
                stealthEndpoint = null,
                stealthAvailable = null,
                stealthPrefix = null,
                ignoredPrevious = wan,
            ),
        )
        assertNull(EndpointRefresh.freshEndpoint(null))
        assertNull(EndpointRefresh.freshEndpoint(" "))
    }

    @Test
    fun rapidFlapCancelsInFlightAttemptsForOlderNetworks() {
        val wifi = 11L
        val cell = 22L
        val other = 33L
        var latest: EndpointRefresh.Flight? = null
        latest = EndpointRefresh.supersede(latest, wifi)
        val wifiFlight = latest!!
        latest = EndpointRefresh.supersede(latest, cell)
        val cellFlight = latest!!
        latest = EndpointRefresh.supersede(latest, other)
        val otherFlight = latest!!

        assertFalse(EndpointRefresh.stillCurrent(wifiFlight, latest))
        assertFalse(EndpointRefresh.stillCurrent(cellFlight, latest))
        assertTrue(EndpointRefresh.stillCurrent(otherFlight, latest))
        assertTrue(EndpointRefresh.abandon(disconnectRequested = false, wifiFlight, latest))
        assertFalse(EndpointRefresh.abandon(disconnectRequested = false, otherFlight, latest))

        val retry = EndpointRefresh.sameNetwork(latest, other)
        assertEquals(otherFlight.generation, retry.generation)
        assertTrue(EndpointRefresh.stillCurrent(retry, latest))

        val backToWifi = EndpointRefresh.sameNetwork(latest, wifi)
        assertFalse(EndpointRefresh.stillCurrent(otherFlight, backToWifi))
        assertTrue(EndpointRefresh.stillCurrent(backToWifi, backToWifi))
    }

    @Test
    fun disconnectWhileReconnectingAbandonsTheCurrentAttempt() {
        val flight = EndpointRefresh.supersede(null, 7L)
        assertTrue(EndpointRefresh.abandon(disconnectRequested = true, flight, flight))
        assertFalse(EndpointRefresh.stillCurrent(flight, EndpointRefresh.supersede(flight, null)))
    }

    @Test
    fun failedRetriesShowAClearMessageInsteadOfARawTimeout() {
        assertNull(EndpointRefresh.noticeAfter(0))
        assertNull(EndpointRefresh.noticeAfter(2))
        assertNull(EndpointRefresh.noticeAfter(3))
        assertNull(EndpointRefresh.noticeAfter(3, rebuildTried = false, recoveryPending = false))
        assertNull(EndpointRefresh.noticeAfter(3, rebuildTried = true, recoveryPending = true))
        assertNull(EndpointRefresh.noticeAfter(2, rebuildTried = true, recoveryPending = false))
        assertEquals(
            EndpointRefresh.GIVE_UP_MESSAGE,
            EndpointRefresh.noticeAfter(3, rebuildTried = true, recoveryPending = false),
        )
        assertFalse(
            RoamRecovery.failureEligible(
                socketSwapTried = true,
                rebuildTried = false,
                recoveryPending = false,
            ),
        )
        assertFalse(
            RoamRecovery.failureEligible(
                socketSwapTried = false,
                rebuildTried = true,
                recoveryPending = false,
            ),
        )
        assertFalse(
            RoamRecovery.failureEligible(
                socketSwapTried = true,
                rebuildTried = true,
                recoveryPending = true,
            ),
        )
        assertTrue(
            RoamRecovery.failureEligible(
                socketSwapTried = true,
                rebuildTried = true,
                recoveryPending = false,
            ),
        )
        assertEquals(EndpointRefresh.GIVE_UP_MESSAGE, EndpointRefresh.displayStatus("timeout"))
        assertEquals(EndpointRefresh.GIVE_UP_MESSAGE, EndpointRefresh.displayStatus("Connection timed out"))
        assertEquals(
            EndpointRefresh.GIVE_UP_MESSAGE,
            EndpointRefresh.displayStatus("java.net.SocketTimeoutException"),
        )
        assertEquals(
            "Connection timed out. Check your network and try again.",
            EndpointRefresh.displayStatus("Connection timed out. Check your network and try again."),
        )
        assertEquals("Reconnecting…", EndpointRefresh.statusWhileReconnecting(null))
        assertEquals("Reconnecting…", EndpointRefresh.statusWhileReconnecting("  "))
        assertEquals(
            EndpointRefresh.GIVE_UP_MESSAGE,
            EndpointRefresh.statusWhileReconnecting("timed out"),
        )
    }

    @Test
    fun modeStaysOnTheUserChoiceAcrossNetworks() {
        assertEquals(
            EndpointRefresh.Transport.UDP,
            EndpointRefresh.selectedTransport(modeStored = "udp", resumeStealth = false, transportRunning = false),
        )
        assertEquals(
            EndpointRefresh.Transport.UDP,
            EndpointRefresh.selectedTransport(modeStored = "auto", resumeStealth = false, transportRunning = false),
        )
        assertEquals(
            EndpointRefresh.Transport.STEALTH,
            EndpointRefresh.selectedTransport(modeStored = "stealth", resumeStealth = false, transportRunning = false),
        )
        assertEquals(
            EndpointRefresh.Transport.STEALTH,
            EndpointRefresh.selectedTransport(modeStored = "auto", resumeStealth = true, transportRunning = false),
        )
        assertTrue(EndpointRefresh.DEBOUNCE_MS <= 250L)
        assertTrue(EndpointRefresh.HANDSHAKE_WAIT_MS <= 1_500L)
        assertTrue(EndpointRefresh.PEER_READ_TIMEOUT_SECONDS <= 2L)
        assertTrue(EndpointRefresh.PEER_READ_BUDGET_MS <= 2_000L)
        assertFalse(EndpointRefresh.establishesNewTunOnRoam())
    }

    @Test
    fun handshakeOnTheNewNetworkLeavesReconnecting() {
        val changedAt = 5_000L
        assertEquals(
            "Reconnecting…",
            EndpointRefresh.statusAfterHandshake(
                reconnecting = true,
                handshakeMs = 1_000L,
                changedAtMs = changedAt,
            ),
        )
        assertEquals(
            "Connected",
            EndpointRefresh.statusAfterHandshake(
                reconnecting = true,
                handshakeMs = 5_400L,
                changedAtMs = changedAt,
            ),
        )
        assertTrue(EndpointRefresh.handshakeRestoresConnected(5_400L, changedAt))
        assertFalse(EndpointRefresh.handshakeRestoresConnected(1_000L, changedAt))
        assertFalse(EndpointRefresh.endpointChanged("203.0.113.10:443", "203.0.113.10:443"))
        assertTrue(EndpointRefresh.endpointChanged("203.0.113.10:443", "198.51.100.6:51820"))
    }

    @Test
    fun homeLanWifiToCellularChoosesThePublicEndpoint() {
        val lan = "192.168.0.6:51820"
        val wan = "203.0.113.10:443"
        val cellular = listOf("10.64.1.8")

        // The server still says LAN because the read left over home Wi-Fi or the tunnel.
        val stale = EndpointRefresh.chooseForUnderlay(
            advertised = lan,
            lan = lan,
            wan = wan,
            underlayIpv4s = cellular,
        )
        assertEquals(wan, stale?.udpEndpoint)
        assertEquals("public", stale?.kind)

        val storedOnly = EndpointRefresh.chooseForUnderlay(
            advertised = null,
            lan = lan,
            wan = wan,
            underlayIpv4s = cellular,
        )
        assertEquals(wan, storedOnly?.udpEndpoint)
        assertEquals("public", storedOnly?.kind)

        // A different private /24 is not the node LAN.
        val otherPrivate = EndpointRefresh.chooseForUnderlay(
            advertised = lan,
            lan = lan,
            wan = wan,
            underlayIpv4s = listOf("192.168.1.20"),
        )
        assertEquals(wan, otherPrivate?.udpEndpoint)

        assertNull(
            EndpointRefresh.chooseForUnderlay(
                advertised = lan,
                lan = lan,
                wan = null,
                underlayIpv4s = cellular,
            ),
        )
        assertFalse(EndpointRefresh.keepsPrivateEndpoint(lan, cellular))
        assertTrue(EndpointRefresh.keepsPrivateEndpoint(lan, listOf("192.168.0.10")))
        assertTrue(EndpointRefresh.keepsPrivateEndpoint(wan, cellular))

        val stillHome = EndpointRefresh.chooseForUnderlay(
            advertised = wan,
            lan = lan,
            wan = wan,
            underlayIpv4s = listOf("192.168.0.10"),
        )
        assertEquals(lan, stillHome?.udpEndpoint)
        assertEquals("LAN", stillHome?.kind)

        val stealth = EndpointRefresh.chooseForUnderlay(
            advertised = lan,
            lan = lan,
            wan = wan,
            underlayIpv4s = cellular,
            stealth = true,
            stealthEndpoint = "192.168.0.6:8443",
        )
        assertEquals(wan, stealth?.udpEndpoint)
        assertEquals("203.0.113.10:8443", stealth?.stealthEndpoint)
        assertEquals("stealth", stealth?.kind)
    }

    @Test
    fun serviceReResolvesInsteadOfProbingThePreviousEndpoint() {
        val source = serviceSource()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
        assertTrue(source.contains("fetchFreshEndpoint"))
        assertTrue(source.contains("getOnce("))
        assertFalse(source.contains("probeOrder"))
        assertFalse(source.contains("SOFT_ATTACH_MS"))
        assertTrue(source.contains("EndpointRefresh.DEBOUNCE_MS"))
        assertTrue(source.contains("underlyingControlClient()"))
        assertTrue(source.contains("handshakeRestoresConnected"))
        assertTrue(source.contains("RoamRecovery.sameNetworkLeavesTunnelAlone"))
        assertTrue(source.contains("RoamRecovery.installAfterFailedSwaps"))
        assertTrue(source.contains("rebuildKeepingKillSwitch"))
        assertTrue(source.contains("dropUnderlyingControlClient"))
        assertTrue(source.contains("chooseForUnderlay"))
        assertTrue(source.contains("RoamLog.record"))
        assertTrue(source.contains("markSessionEnded"))
        assertTrue(source.contains("async { fetchFreshEndpoint"))
        assertTrue(source.contains("HandshakeKick.withoutKeepalive"))
        assertTrue(source.contains("kickHandshakeAfterProtect"))
        assertTrue(source.contains("recoveryPending = true"))
        assertTrue(source.contains("installSwapThenRebuild"))
        assertTrue(source.contains("turnUserspaceOff"))
        assertFalse(source.contains("recreateTun = true"))
        val rebuildStart = source.indexOf("fun rebuildKeepingKillSwitch")
        val rebuildEnd = source.indexOf("private fun rebindLiveTunnel", rebuildStart)
        assertTrue(rebuildStart >= 0 && rebuildEnd > rebuildStart)
        assertFalse(source.substring(rebuildStart, rebuildEnd).contains("DOWN"))
    }

    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt"),
            File("app/src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt"),
            File("android/app/src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("VeritasVpnService.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
