package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Connect, disconnect, reconnect on the kept tun, then change networks.
 * The roam after that reconnect used to sit on Reconnecting with no message:
 * keepalive was stripped, one kick was easy to miss, and a failed rebuild
 * never became the give-up line.
 */
class KeptTunRoamTest {
    @Test
    fun reconnectOnTheKeptTunThenRoamRetriesWithAVisibleMessage() {
        assertNull(
            EndpointRefresh.noticeAfter(
                1,
                rebuildTried = true,
                recoveryPending = false,
                elapsedMs = 2_000L,
            ),
        )
        assertEquals(
            EndpointRefresh.GIVE_UP_MESSAGE,
            EndpointRefresh.noticeAfter(
                1,
                rebuildTried = true,
                recoveryPending = false,
                elapsedMs = RoamRecovery.NOTICE_AFTER_MS,
            ),
        )
        assertEquals(2_000L, RoamRecovery.Retry.next(RoamRecovery.Retry.INITIAL_MS))
        assertEquals(RoamRecovery.Retry.MAX_MS, RoamRecovery.Retry.next(RoamRecovery.Retry.MAX_MS))
        val kicks = HandshakeKick.retransmitDelaysMs()
        assertTrue(kicks.first() in 1 until EndpointRefresh.HANDSHAKE_WAIT_MS)

        val service = kotlinSource("VeritasVpnService.kt")
        val disconnect = slice(service, "ACTION_DISCONNECT -> {", "else -> {")
        assertTrue(disconnect.contains("keepInterface = DisconnectUi.keepVpnInterface"))
        assertTrue(disconnect.contains("releaseTunnelOnce(disconnectGen, keepInterface = keepInterface)"))
        assertFalse(disconnect.contains("VpnService.prepare"))

        val bring = slice(service, "fun bringUp(", "fun switchToStealth(")
        val kept = slice(bring, "keptTun ->", "else ->")
        assertTrue(kept.contains("rebindLiveTunnel(parsed, gen)"))
        assertTrue(kept.indexOf("rebindLiveTunnel(parsed, gen)") < kept.indexOf("followSystemDefault()"))

        val rebind = slice(service, "fun rebindLiveTunnel", "fun userspaceConfigOrThrow")
        assertTrue(rebind.contains("userspaceConfigOrThrow"))
        assertTrue(rebind.contains("kickHandshakeAfterProtect()"))
        val userspace = slice(service, "fun userspaceConfigOrThrow", "fun noteTunnelReady")
        assertTrue(userspace.contains("HandshakeKick.withoutKeepalive"))
        val kick = slice(service, "fun kickHandshakeAfterProtect()", "fun kickHandshakePacket()")
        assertTrue(kick.contains("HandshakeKick.retransmitDelaysMs()"))
        assertTrue(kick.indexOf("kickHandshakePacket()") < kick.indexOf("retransmitDelaysMs()"))

        val recover = slice(service, "fun recoverWithFreshEndpoint", "data class RoamRound")
        val failedAt = recover.indexOf("InstallOutcome.RETRY, InstallOutcome.FAILED")
        assertTrue(failedAt > 0)
        val failed = recover.substring(failedAt, recover.indexOf("delay(wait)", failedAt) + "delay(wait)".length)
        assertTrue(failed.contains("noteResolveFailure(rebuildTried = round.rebuildTried)"))
        assertTrue(failed.contains("RoamRecovery.Retry.next"))
        assertTrue(failed.contains("escalateFreshTunnel"))
        assertTrue(failed.indexOf("noteResolveFailure") < failed.indexOf("delay(wait)"))
        val publish = slice(service, "fun publishReconnecting", "fun ")
        assertTrue(publish.contains("if (!reconnecting) reconnectNotice = null"))

        val shared = slice(service, "fun retireSharedClient()", "fun tunnelJobs()")
        assertTrue(shared.contains("evictAll"))
        assertFalse(shared.contains("cancelAll"))

        val apply = slice(service, "fun applyBackendState", "fun openInterface")
        assertTrue(apply.contains("rebindLiveTunnel"))
        assertFalse(apply.contains("setState"))
        assertFalse(apply.contains("prepare"))
        assertFalse(service.contains("VpnService.prepare"))
        assertFalse(service.contains("backend.setState"))
    }

    @Test
    fun consentIsNotRequestedBeforeTheHomeFrame() {
        val activity = kotlinSource("MainActivity.kt")
        val beforeContent = slice(activity, "override fun onCreate", "setContent {")
        assertFalse(beforeContent.contains("VpnService.prepare"))
        assertFalse(beforeContent.contains("vpnPermissionLauncher"))

        val request = slice(activity, "fun requestConnect()", "LaunchedEffect(homeDrawn, needsVpnConsent")
        assertTrue(request.indexOf("withFrameNanos") < request.indexOf("VpnService.prepare"))

        val restoredStep = slice(
            activity,
            "OnboardingGate.Step.SHOW_SYSTEM_DIALOG ->",
            "OnboardingGate.Step.DONE",
        )
        assertFalse(restoredStep.contains("VpnService.prepare"))
        assertFalse(restoredStep.contains("vpnPermissionLauncher"))

        val accept = slice(activity, "onAccept = {", "onDecline = {")
        assertTrue(accept.indexOf("withFrameNanos") < accept.indexOf("VpnService.prepare"))

        val ask = slice(activity, "LaunchedEffect(homeDrawn, needsVpnConsent", "val lifecycleOwner")
        assertTrue(ask.contains("if (!homeDrawn || showOnboardingDisclosure || !needsVpnConsent) return"))
        assertTrue(ask.contains("VpnService.prepare"))
        assertFalse(ask.contains("restoringSavedVpnSession"))

        val preflight = kotlinSource("NetworkPreflight.kt")
        assertFalse(preflight.contains("allowPrepare"))

        val service = kotlinSource("VeritasVpnService.kt")
        val restore = slice(service, "fun restoreSavedSessionIfNeeded", "fun onRevoke")
        assertTrue(restore.contains("broadcastConsentNeeded()"))
        assertFalse(restore.contains("VpnService.prepare"))
        assertFalse(restore.contains("setState"))
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("$start … $end", from >= 0 && to > from)
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
