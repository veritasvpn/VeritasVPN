package cloud.veritasvpn.vpn

import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlayRankTest {

    @Test
    fun foregroundWifiBeatsLeftoverValidatedCellular() {
        val wifi = UnderlayRank.score(
            preferred = false,
            validated = true,
            foreground = true,
            wifiOrEthernet = true,
            cellular = false,
        )
        val cell = UnderlayRank.score(
            preferred = false,
            validated = true,
            foreground = false,
            wifiOrEthernet = false,
            cellular = true,
        )
        assertTrue(wifi > cell)
    }

    @Test
    fun foregroundCellularBeatsLeftoverValidatedWifi() {
        val cell = UnderlayRank.score(
            preferred = false,
            validated = true,
            foreground = true,
            wifiOrEthernet = false,
            cellular = true,
        )
        val wifi = UnderlayRank.score(
            preferred = false,
            validated = true,
            foreground = false,
            wifiOrEthernet = true,
            cellular = false,
        )
        assertTrue(cell > wifi)
    }

    @Test
    fun preferredWifiBeatsValidatedCellularEvenIfCellIsForeground() {
        val wifi = UnderlayRank.score(
            preferred = true,
            validated = true,
            foreground = false,
            wifiOrEthernet = true,
            cellular = false,
        )
        val cell = UnderlayRank.score(
            preferred = false,
            validated = true,
            foreground = true,
            wifiOrEthernet = false,
            cellular = true,
        )
        assertTrue(wifi > cell)
    }
}
