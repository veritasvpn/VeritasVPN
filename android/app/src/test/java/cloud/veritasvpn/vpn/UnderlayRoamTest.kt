package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class UnderlayRoamTest {

    @Test
    fun newForegroundUnderlayIsUsedEvenIfLeftoverCellExists() {
        val action = UnderlayRoam.action(
            callbackIdentity = "wifi",
            callbackValidated = true,
            callbackHasIpv4 = true,
            callbackForeground = true,
            lastIdentity = "cell",
            foregroundIdentity = "wifi",
            handshakeOk = true,
        )
        assertEquals(UnderlayRoam.Action.USE_CALLBACK, action)
    }

    @Test
    fun leftoverBackgroundWifiDoesNotStealCellularSession() {
        val action = UnderlayRoam.action(
            callbackIdentity = "wifi",
            callbackValidated = true,
            callbackHasIpv4 = true,
            callbackForeground = false,
            lastIdentity = "cell",
            foregroundIdentity = "cell",
            handshakeOk = true,
        )
        assertEquals(UnderlayRoam.Action.SKIP, action)
    }

    @Test
    fun leftoverBackgroundCellDoesNotStealWifiSession() {
        val action = UnderlayRoam.action(
            callbackIdentity = "cell",
            callbackValidated = true,
            callbackHasIpv4 = true,
            callbackForeground = false,
            lastIdentity = "wifi",
            foregroundIdentity = "wifi",
            handshakeOk = true,
        )
        assertEquals(UnderlayRoam.Action.SKIP, action)
    }

    @Test
    fun newUnderlayWaitsUntilValidated() {
        val action = UnderlayRoam.action(
            callbackIdentity = "wifi",
            callbackValidated = false,
            callbackHasIpv4 = false,
            callbackForeground = true,
            lastIdentity = "cell",
            foregroundIdentity = "cell",
            handshakeOk = true,
        )
        assertEquals(UnderlayRoam.Action.WAIT, action)
    }

    @Test
    fun leftoverChatterOnOldUnderlayFollowsForegroundMove() {
        val action = UnderlayRoam.action(
            callbackIdentity = "cell",
            callbackValidated = true,
            callbackHasIpv4 = true,
            callbackForeground = false,
            lastIdentity = "cell",
            foregroundIdentity = "wifi",
            handshakeOk = true,
        )
        assertEquals(UnderlayRoam.Action.USE_FOREGROUND, action)
    }

    @Test
    fun sameForegroundUnderlayWithHandshakeSkips() {
        val action = UnderlayRoam.action(
            callbackIdentity = "wifi",
            callbackValidated = true,
            callbackHasIpv4 = true,
            callbackForeground = true,
            lastIdentity = "wifi",
            foregroundIdentity = "wifi",
            handshakeOk = true,
        )
        assertEquals(UnderlayRoam.Action.SKIP, action)
    }

    @Test
    fun onLostMovesToNewForeground() {
        val action = UnderlayRoam.action(
            callbackIdentity = null,
            callbackValidated = false,
            callbackHasIpv4 = false,
            callbackForeground = false,
            lastIdentity = "cell",
            foregroundIdentity = "wifi",
            handshakeOk = false,
        )
        assertEquals(UnderlayRoam.Action.USE_FOREGROUND, action)
    }
}
