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
        assertEquals(EndpointRefresh.GIVE_UP_MESSAGE, EndpointRefresh.noticeAfter(3))
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
        assertEquals(1_000L, EndpointRefresh.DEBOUNCE_MS)
        assertTrue(EndpointRefresh.HANDSHAKE_WAIT_MS <= 3_000L)
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
