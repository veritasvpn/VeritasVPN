package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class UnderlayRoamTest {

    @Test
    fun newLiveUnderlayRoamsRegardlessOfLeftoverCallbacks() {
        val action = UnderlayRoam.action(
            liveIdentity = "wifi",
            liveReady = true,
            lastIdentity = "cell",
        )
        assertEquals(UnderlayRoam.Action.ROAM, action)
    }

    @Test
    fun leftoverIsIgnoredWhenLiveUnderlayDidNotChange() {
        val action = UnderlayRoam.action(
            liveIdentity = "cell",
            liveReady = true,
            lastIdentity = "cell",
        )
        assertEquals(UnderlayRoam.Action.SKIP, action)
    }

    @Test
    fun waitsUntilLiveUnderlayIsReady() {
        val action = UnderlayRoam.action(
            liveIdentity = "wifi",
            liveReady = false,
            lastIdentity = "cell",
        )
        assertEquals(UnderlayRoam.Action.WAIT, action)
    }

    @Test
    fun waitsWhenNoLiveUnderlay() {
        val action = UnderlayRoam.action(
            liveIdentity = null,
            liveReady = false,
            lastIdentity = "cell",
        )
        assertEquals(UnderlayRoam.Action.WAIT, action)
    }

    @Test
    fun cellToWifiRoams() {
        val action = UnderlayRoam.action(
            liveIdentity = "wifi",
            liveReady = true,
            lastIdentity = "cell",
        )
        assertEquals(UnderlayRoam.Action.ROAM, action)
    }
}
