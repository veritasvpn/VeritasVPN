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

    @Test
    fun anOnReadContinuesEvenAfterTheDialogWasShown() {
        assertEquals(
            VpnKillSwitch.Prompt.CONTINUE,
            VpnKillSwitch.promptAfterRecheck(anyReadOn = true, shownThisAttempt = false),
        )
        assertEquals(
            VpnKillSwitch.Prompt.CONTINUE,
            VpnKillSwitch.promptAfterRecheck(anyReadOn = true, shownThisAttempt = true),
        )
    }

    @Test
    fun anOffReadShowsOncePerConnectAttempt() {
        assertEquals(
            VpnKillSwitch.Prompt.SHOW,
            VpnKillSwitch.promptAfterRecheck(anyReadOn = false, shownThisAttempt = false),
        )
        assertEquals(
            VpnKillSwitch.Prompt.ALREADY_SHOWN,
            VpnKillSwitch.promptAfterRecheck(anyReadOn = false, shownThisAttempt = true),
        )
    }

    @Test
    fun hiddenPackageWhileDisconnectedIsNotProofTheSwitchIsOff() {
        assertEquals(
            VpnKillSwitch.Sight.On,
            VpnKillSwitch.lockdownSight("cloud.veritasvpn", "cloud.veritasvpn", 1, vpnPrepared = false),
        )
        assertEquals(
            VpnKillSwitch.Sight.Off,
            VpnKillSwitch.lockdownSight("cloud.veritasvpn", "cloud.veritasvpn", 0, vpnPrepared = true),
        )
        assertEquals(
            VpnKillSwitch.Sight.Off,
            VpnKillSwitch.lockdownSight("com.other.vpn", "cloud.veritasvpn", 1, vpnPrepared = true),
        )
        assertEquals(
            VpnKillSwitch.Sight.Unreadable,
            VpnKillSwitch.lockdownSight(null, "cloud.veritasvpn", 1, vpnPrepared = false),
        )
        assertEquals(
            VpnKillSwitch.Sight.On,
            VpnKillSwitch.lockdownSight(null, "cloud.veritasvpn", 1, vpnPrepared = true),
        )
    }

    @Test
    fun serviceOnOverridesStaleSettingsAndSettingsOnSurvivesAMissingService() {
        assertTrue(
            VpnKillSwitch.lockdownFromSources(
                settingsOn = false,
                serviceAlwaysOn = true,
                serviceLockdown = true,
            )
        )
        assertTrue(
            VpnKillSwitch.lockdownFromSources(
                settingsOn = true,
                serviceAlwaysOn = false,
                serviceLockdown = false,
            )
        )
        assertTrue(
            VpnKillSwitch.lockdownFromSources(
                settingsOn = true,
                serviceAlwaysOn = null,
                serviceLockdown = null,
            )
        )
        assertFalse(
            VpnKillSwitch.lockdownFromSources(
                settingsOn = false,
                serviceAlwaysOn = true,
                serviceLockdown = false,
            )
        )
        assertFalse(
            VpnKillSwitch.lockdownFromSources(
                settingsOn = false,
                serviceAlwaysOn = null,
                serviceLockdown = true,
            )
        )
        assertFalse(
            VpnKillSwitch.lockdownFromSources(
                settingsOn = false,
                serviceAlwaysOn = null,
                serviceLockdown = null,
            )
        )
    }

    @Test
    fun resumeContinuesWithoutRequestConnectAndTheDialogIsGatedOnce() {
        val activity = kotlinSource("MainActivity.kt")
        val resume = slice(activity, "Lifecycle.Event.ON_RESUME", "refreshBilling()")
        assertFalse(resume.contains("requestConnect()"))
        assertTrue(resume.contains("awaitLockdown"))
        assertTrue(resume.contains("applyLockdownPrompt"))
        assertFalse(resume.contains("VpnService.prepare"))
        assertFalse(resume.contains("showKillSwitchRequired = true"))

        val connect = slice(activity, "fun requestConnect()", "LaunchedEffect(homeDrawn, needsVpnConsent")
        assertTrue(connect.indexOf("withFrameNanos") < connect.indexOf("VpnService.prepare"))
        assertTrue(connect.contains("if (showKillSwitchRequired) return"))
        assertTrue(connect.contains("awaitLockdown"))
        assertTrue(connect.contains("applyLockdownPrompt"))
        assertFalse(connect.contains("showKillSwitchBlock()"))

        val prompt = slice(activity, "fun applyLockdownPrompt", "val vpnPermissionLauncher")
        assertTrue(prompt.contains("VpnKillSwitch.Prompt.SHOW -> showKillSwitchBlock()"))
        assertTrue(prompt.contains("VpnKillSwitch.Prompt.ALREADY_SHOWN -> Unit"))
        assertTrue(prompt.contains("lockdownVerified = true"))

        val reader = slice(activity, "fun readLockdownCombined", "fun awaitLockdown")
        assertTrue(reader.contains("allowPrepare = false"))
        assertTrue(reader.contains("readServiceLockdown()"))
        assertFalse(reader.contains("VpnService.prepare"))

        val disconnectRead = slice(activity, "fun readDisconnectLockdownState", "fun applyDisconnectNote")
        assertTrue(disconnectRead.contains("readSight("))
        assertTrue(disconnectRead.contains("allowPrepare = false"))
        assertTrue(disconnectRead.contains("readServiceLockdown()"))
        assertFalse(disconnectRead.contains("VpnService.prepare"))
        assertTrue(activity.contains("Lifecycle.Event.ON_START"))
        assertTrue(activity.contains("refreshDisconnectLockdownNote(afterDisconnect = false)"))
        assertTrue(activity.contains("refreshDisconnectLockdownNote(afterDisconnect = true)"))

        val service = kotlinSource("VeritasVpnService.kt")
        val live = slice(service, "fun readServiceLockdown()", "fun retireProvisioningClient()")
        assertTrue(live.contains("active ?: return null"))
        assertTrue(live.contains("isAlwaysOn()"))
        assertTrue(live.contains("isLockdownEnabled()"))
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("$start not found", from >= 0 && to > from)
        return source.substring(from, to)
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            java.io.File("src/main/java"),
            java.io.File("app/src/main/java"),
            java.io.File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${java.io.File(".").absolutePath}")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }
}
