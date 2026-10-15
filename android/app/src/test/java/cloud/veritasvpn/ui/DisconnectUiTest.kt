package cloud.veritasvpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DisconnectUiTest {
    @Test
    fun circleTapDisconnectsInAppAndNeverLaunchesSettings() {
        val locked = DisconnectUi.afterCircleTap(lockdownEnabled = true)
        assertFalse(locked.launchSettings)
        assertFalse(locked.callsPrepare)
        assertFalse(locked.leavesApp)
        assertNotNull(locked.note)

        val open = DisconnectUi.afterCircleTap(lockdownEnabled = false)
        assertFalse(open.launchSettings)
        assertFalse(open.callsPrepare)
        assertFalse(open.leavesApp)
        assertNull(open.note)
    }

    @Test
    fun circleTapLandsOnTheOfflineHome() {
        assertTrue(DisconnectUi.showsOfflineHome(connected = false, connecting = false, reconnecting = false))
        assertFalse(DisconnectUi.showsOfflineHome(connected = true, connecting = false, reconnecting = false))
        assertFalse(DisconnectUi.showsOfflineHome(connected = true, connecting = false, reconnecting = true))
        assertFalse(DisconnectUi.showsOfflineHome(connected = false, connecting = true, reconnecting = false))

        assertEquals(
            HeroMainAction.Disconnect,
            heroMainAction(
                connecting = false,
                billingReady = true,
                connected = true,
                signInAgain = false,
                isPremium = true,
                reconnecting = false,
            ),
        )
        assertEquals(
            HeroMainAction.Disconnect,
            heroMainAction(
                connecting = false,
                billingReady = true,
                connected = true,
                signInAgain = false,
                isPremium = true,
                reconnecting = true,
            ),
        )
        assertEquals(
            HeroMainAction.Connect,
            heroMainAction(
                connecting = false,
                billingReady = true,
                connected = false,
                signInAgain = false,
                isPremium = true,
                reconnecting = false,
            ),
        )
        assertEquals("Connect", heroActionName(HeroPhase.Ready, signInAgain = false))
        assertEquals("Disconnect", heroActionName(HeroPhase.Protected, signInAgain = false))
        assertEquals("Disconnect", heroActionName(HeroPhase.Reconnecting, signInAgain = false))
    }

    @Test
    fun circleTapRevokeDoesNotStopTheService() {
        assertFalse(DisconnectUi.stopService(userEndedSession = true, systemRevoke = true))
        assertFalse(DisconnectUi.stopService(userEndedSession = true, systemRevoke = false))
        assertFalse(DisconnectUi.stopService(userEndedSession = false, systemRevoke = true))
    }

    @Test
    fun disconnectPathDoesNotLeaveTheApp() {
        val activity = kotlinSource("MainActivity.kt")
        val disconnect = slice(activity, "fun disconnectVpnService()", "fun cancelReconnect()")
        val note = slice(activity, "fun noteIntentionalDisconnect()", "fun performLocalSignOut()")
        val circle = slice(activity, "onDisconnect = {", "onSignIn =")
        for (block in listOf(disconnect, note, circle)) {
            assertFalse(block.contains("VpnService.prepare"))
            assertFalse(block.contains("startActivity"))
            assertFalse(block.contains("moveTaskToBack"))
            assertFalse(block.contains("FLAG_ACTIVITY_NEW_TASK"))
            assertFalse(block.contains(".finish("))
            assertFalse(block.contains("stopSelf("))
        }
        assertTrue(disconnect.contains("markSessionEnded"))
        assertTrue(circle.contains("noteIntentionalDisconnect()"))
        assertTrue(note.contains("DisconnectUi.afterCircleTap"))

        val service = kotlinSource("VeritasVpnService.kt")
        val teardown = slice(service, "ACTION_DISCONNECT -> {", "else -> {")
        assertFalse(teardown.contains("VpnService.prepare"))
        assertFalse(teardown.contains("startActivity"))
        assertFalse(teardown.contains("moveTaskToBack"))
        assertFalse(teardown.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertFalse(teardown.contains(".finish("))
        assertFalse(teardown.contains("stopSelf("))
        assertTrue(teardown.contains("markSessionEnded"))
        assertTrue(teardown.contains("releaseTunnelOnce"))

        val release = slice(service, "fun releaseTunnelOnce", "fun turnUserspaceOff()")
        assertFalse(release.contains("setState"))
        assertFalse(release.contains("stopSelf("))
        assertTrue(release.contains("turnUserspaceOffLocked"))

        val off = slice(service, "fun turnUserspaceOffLocked", "fun swapTunKeeper")
        assertTrue(off.contains("wgTurnOff"))
        assertFalse(off.contains("setState"))
        assertFalse(off.contains("stopSelf("))

        val stateChange = slice(service, "fun onStateChange", "fun startStatsPolling")
        assertFalse(stateChange.contains("setState"))
        assertFalse(stateChange.contains("applyBackendState"))
        assertTrue(stateChange.contains("turnUserspaceOff"))
        assertTrue(stateChange.contains("runCatching { stopForeground"))

        assertFalse(service.contains("System.exit"))
        assertFalse(service.contains("killProcess"))
        assertFalse(service.contains("stopSelf("))
        assertFalse(service.contains("super.onRevoke"))
        assertFalse(service.contains("setStateInternal"))

        val revoke = slice(service, "fun onRevoke()", "fun onDestroy()")
        assertTrue(revoke.contains("sessionEndedByUser"))
        assertFalse(revoke.contains("stopSelf("))
        assertFalse(revoke.contains("super.onRevoke"))
        assertFalse(revoke.contains("startActivity"))
        assertFalse(revoke.contains("VpnService.prepare"))
        assertTrue(revoke.contains("runCatching { stopForeground"))

        val connectGiveUp = slice(service, "Connect retry failed", "ACTION_DISCONNECT")
        assertFalse(connectGiveUp.contains("stopSelf("))
        assertTrue(connectGiveUp.contains("runCatching { stopForeground"))

        val killSwitch = kotlinSource("VpnKillSwitch.kt")
        val guard = killSwitch.indexOf("if (!allowPrepare) return false")
        val prepare = killSwitch.indexOf("VpnService.prepare")
        assertTrue(guard >= 0 && prepare > guard)
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue(from >= 0 && to > from)
        return source.substring(from, to)
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }
}
