package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(
            VpnKillSwitch.lockdownSatisfied(
                "com.tailscale.ipn/com.tailscale.ipn.IPNService",
                "cloud.veritasvpn",
                1,
                vpnPrepared = true,
            )
        )
    }

    @Test
    fun lockdownOffFailsClosedEvenWhenThisAppIsAlwaysOn() {
        assertFalse(VpnKillSwitch.lockdownSatisfied("cloud.veritasvpn", "cloud.veritasvpn", 0))
        assertFalse(
            VpnKillSwitch.lockdownSatisfied(
                "cloud.veritasvpn",
                "cloud.veritasvpn",
                0,
                vpnPrepared = true,
            )
        )
    }

    @Test
    fun oemPackageFormsStillMatchThisApp() {
        assertTrue(VpnKillSwitch.lockdownSatisfied(" cloud.veritasvpn ", "cloud.veritasvpn", 1))
        assertTrue(VpnKillSwitch.lockdownSatisfied("cloud.veritasvpn:0", "cloud.veritasvpn", 1))
        assertTrue(VpnKillSwitch.lockdownSatisfied("0:cloud.veritasvpn", "cloud.veritasvpn", 1))
        assertTrue(
            VpnKillSwitch.lockdownSatisfied(
                "cloud.veritasvpn/cloud.veritasvpn.vpn.VeritasVpnService",
                "cloud.veritasvpn",
                1,
            )
        )
    }

    @Test
    fun hiddenPackageWithLockdownRequiresThisAppToBePrepared() {
        // Android 12+ / HyperOS release builds cannot read always_on_vpn_app.
        // Lockdown 1 plus VpnService.prepare() == null means this package is
        // the always-on VPN. Not prepared fails closed (Tailscale owns it).
        assertTrue(
            VpnKillSwitch.lockdownSatisfied(
                null,
                "cloud.veritasvpn",
                1,
                vpnPrepared = true,
            )
        )
        assertFalse(VpnKillSwitch.lockdownSatisfied(null, "cloud.veritasvpn", 1, vpnPrepared = false))
        assertFalse(VpnKillSwitch.lockdownSatisfied("", "cloud.veritasvpn", 1, vpnPrepared = false))
        assertFalse(
            VpnKillSwitch.lockdownSatisfied(
                "com.tailscale.ipn",
                "cloud.veritasvpn",
                1,
                vpnPrepared = true,
            )
        )
    }

    @Test
    fun parseLockdownAcceptsOemWordsAndRejectsOff() {
        assertEquals(1, VpnKillSwitch.parseLockdown("1"))
        assertEquals(1, VpnKillSwitch.parseLockdown(" true "))
        assertEquals(1, VpnKillSwitch.parseLockdown("ON"))
        assertEquals(1, VpnKillSwitch.parseLockdown("yes"))
        assertEquals(1, VpnKillSwitch.parseLockdown("2"))
        assertEquals(0, VpnKillSwitch.parseLockdown(null))
        assertEquals(0, VpnKillSwitch.parseLockdown(""))
        assertEquals(0, VpnKillSwitch.parseLockdown("0"))
        assertEquals(0, VpnKillSwitch.parseLockdown("false"))
        assertEquals(0, VpnKillSwitch.parseLockdown("off"))
    }

    @Test
    fun supplementalSettingsDoNotOverrideAnExplicitSecureValue() {
        assertEquals(
            "cloud.veritasvpn",
            VpnKillSwitch.firstAlwaysOnPackage(null, "cloud.veritasvpn", "com.tailscale.ipn"),
        )
        assertEquals(
            "com.tailscale.ipn",
            VpnKillSwitch.firstAlwaysOnPackage("com.tailscale.ipn", "cloud.veritasvpn", null),
        )
        assertNull(VpnKillSwitch.firstAlwaysOnPackage(null, "", "   "))
        assertEquals(0, VpnKillSwitch.resolveLockdownValue("0", "1", "true"))
        assertEquals(0, VpnKillSwitch.resolveLockdownValue("false", "1", null))
        assertEquals(1, VpnKillSwitch.resolveLockdownValue(null, "true", null))
        assertEquals(1, VpnKillSwitch.resolveLockdownValue(null, null, "1"))
        assertEquals(0, VpnKillSwitch.resolveLockdownValue(null, null, null))
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
