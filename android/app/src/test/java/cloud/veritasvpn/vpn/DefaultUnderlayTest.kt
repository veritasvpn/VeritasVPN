package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultUnderlayTest {
    private val cellular = DefaultUnderlay.Candidate("cell", listOf("10.64.1.8"))
    private val homeWifi = DefaultUnderlay.Candidate("wifi", listOf("192.168.0.10"))
    private val lan = "192.168.0.6:51820"

    @Test
    fun cellularToHomeWifiWithoutBestMatchUsesTheLan() {
        assertEquals(
            "wifi",
            DefaultUnderlay.select(
                bestMatchId = null,
                candidates = listOf(cellular, homeWifi),
                lanEndpoint = lan,
            ),
        )
        // The platform best-match is not overridden, including cellular.
        assertEquals(
            "cell",
            DefaultUnderlay.select(
                bestMatchId = "cell",
                candidates = listOf(cellular, homeWifi),
                lanEndpoint = lan,
            ),
        )
    }

    @Test
    fun twoNetworksWithoutALanMatchAreNotRanked() {
        assertNull(
            DefaultUnderlay.select(
                bestMatchId = null,
                candidates = listOf(cellular, homeWifi),
                lanEndpoint = null,
            ),
        )
        assertNull(
            DefaultUnderlay.select(
                bestMatchId = null,
                candidates = listOf(
                    cellular,
                    DefaultUnderlay.Candidate("cafe", listOf("192.168.1.20")),
                ),
                lanEndpoint = lan,
            ),
        )
        assertEquals(
            "cell",
            DefaultUnderlay.select(
                bestMatchId = null,
                candidates = listOf(cellular),
                lanEndpoint = lan,
            ),
        )
    }
}
