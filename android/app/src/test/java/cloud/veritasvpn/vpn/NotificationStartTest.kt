package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationStartTest {
    @Test
    fun connectingIsShownOnlyWhileAConnectIsUnderway() {
        assertEquals(
            "Not connected",
            VeritasVpnService.notificationForStart(action = null, remembered = "Connecting…"),
        )
        assertEquals(
            "Not connected",
            VeritasVpnService.notificationForStart(
                action = VeritasVpnService.ACTION_QUERY_STATE,
                remembered = "",
            ),
        )
        assertEquals(
            "Not connected",
            VeritasVpnService.notificationForStart(
                action = VeritasVpnService.ACTION_ENSURE_LOCKDOWN_HOLD,
                remembered = "Connecting…",
            ),
        )
        assertEquals(
            "Connecting…",
            VeritasVpnService.notificationForStart(
                action = VeritasVpnService.ACTION_CONNECT,
                remembered = "Not connected",
            ),
        )
        assertEquals(
            "Disconnected",
            VeritasVpnService.notificationForStart(
                action = VeritasVpnService.ACTION_DISCONNECT,
                remembered = "Connecting…",
            ),
        )
        assertEquals(
            "Protected",
            VeritasVpnService.notificationForStart(action = null, remembered = "Protected"),
        )
        assertEquals(
            "Reconnecting…",
            VeritasVpnService.notificationForStart(action = null, remembered = "Reconnecting…"),
        )
        assertFalse(
            VeritasVpnService.notificationForStart(null, "Not connected").contains("Connecting"),
        )
    }
}
