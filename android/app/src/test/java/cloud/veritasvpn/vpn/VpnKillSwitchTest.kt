package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnKillSwitchTest {

    @Test
    fun lockdownRequiresThisAppAndNonZeroLockdown() {
        assertTrue(VpnKillSwitch.lockdownSatisfied("cloud.veritasvpn", "cloud.veritasvpn", 1))
    }

    @Test
    fun missingAlwaysOnAppFailsClosed() {
        assertFalse(VpnKillSwitch.lockdownSatisfied(null, "cloud.veritasvpn", 1))
        assertFalse(VpnKillSwitch.lockdownSatisfied("", "cloud.veritasvpn", 1))
        assertFalse(VpnKillSwitch.lockdownSatisfied("   ", "cloud.veritasvpn", 1))
    }

    @Test
    fun otherVpnAppFailsClosed() {
        assertFalse(VpnKillSwitch.lockdownSatisfied("com.other.vpn", "cloud.veritasvpn", 1))
    }

    @Test
    fun lockdownOffFailsClosedEvenWhenThisAppIsAlwaysOn() {
        assertFalse(VpnKillSwitch.lockdownSatisfied("cloud.veritasvpn", "cloud.veritasvpn", 0))
    }

    @Test
    fun freshInstallRequestsVpnConsentBeforeLockdown() {
        assertEquals(
            VpnKillSwitch.ConnectGate.VpnConsent,
            VpnKillSwitch.nextConnectGate(vpnPrepared = false, lockdownEnabled = false)
        )
    }

    @Test
    fun missingConsentIsNotSkippedWhenLockdownSettingsAlreadyLookOn() {
        assertEquals(
            VpnKillSwitch.ConnectGate.VpnConsent,
            VpnKillSwitch.nextConnectGate(vpnPrepared = false, lockdownEnabled = true)
        )
    }

    @Test
    fun preparedAppStillRequiresLockdownBeforeTheTunnel() {
        assertEquals(
            VpnKillSwitch.ConnectGate.Lockdown,
            VpnKillSwitch.nextConnectGate(vpnPrepared = true, lockdownEnabled = false)
        )
    }

    @Test
    fun preparedAppWithLockdownMayStartTheTunnel() {
        assertEquals(
            VpnKillSwitch.ConnectGate.Tunnel,
            VpnKillSwitch.nextConnectGate(vpnPrepared = true, lockdownEnabled = true)
        )
    }
}
