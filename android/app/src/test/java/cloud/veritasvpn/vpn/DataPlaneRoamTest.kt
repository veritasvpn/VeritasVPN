package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wi-Fi, then no default, then cellular. The data-plane sockets are never
 * bindSocket'd. Each change clears the cached peer result and wakes recovery
 * on the network the system is using now.
 */
class DataPlaneRoamTest {
    private val wifi = 10L
    private val cellular = 20L

    @Test
    fun wifiLostThenCellularDoesNotBindDataPlaneAndTriggersRecovery() {
        var watch = TunnelRebuild.DefaultWatch()
        var controlOpen = true
        var controlHandle: Long? = wifi
        var tracked: Long? = wifi
        var clears = 0
        var wakes = 0

        val wifiReaction = observe(watch, wifi, controlOpen, controlHandle)
        assertFalse(wifiReaction.clearCachedPeerOutcome)
        assertTrue(wifiReaction.wakeRecovery)
        wakes += 1
        watch = wifiReaction.next
        controlHandle = wifi

        val lost = observe(watch, null, controlOpen, controlHandle)
        assertTrue(lost.clearCachedPeerOutcome)
        assertTrue(lost.dropControlClient)
        assertTrue(lost.wakeRecovery)
        assertEquals(
            TunnelRebuild.RecoveryStep.RESET,
            recoveryStep(defaultHandle = null, trackedHandle = tracked, peerCleared = true),
        )
        clears += 1
        wakes += 1
        controlOpen = false
        controlHandle = null
        watch = lost.next
        tracked = null

        val cell = observe(watch, cellular, controlOpen, controlHandle)
        assertTrue(cell.clearCachedPeerOutcome)
        assertTrue(cell.wakeRecovery)
        assertFalse(cell.dataPlaneBindSocket)
        assertEquals(cellular, TunnelRebuild.pinHandle(cellular))
        assertNull(TunnelRebuild.pinHandle(null))
        clears += 1
        wakes += 1
        // The loss already reset the tracked handle. Cellular ends the backoff
        // and rebuilds on that default. It does not pick another network.
        assertEquals(
            TunnelRebuild.RecoveryStep.REBUILD,
            TunnelRebuild.decide(
                sessionActive = true,
                peer = null,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = false,
                defaultHandle = cellular,
                trackedHandle = tracked,
            ),
        )
        watch = cell.next
        controlOpen = true
        controlHandle = cellular

        assertEquals(TunnelRebuild.DefaultWatch(seen = true, handle = cellular), watch)
        assertEquals(2, clears)
        assertEquals(3, wakes)
        assertTrue(controlOpen)
        assertEquals(cellular, controlHandle)

        val again = observe(watch, cellular, controlOpen, controlHandle)
        assertTrue(again.followDataPlane)
        assertFalse(again.dataPlaneBindSocket)
        assertFalse(again.clearCachedPeerOutcome)
        assertFalse(again.dropControlClient)
        assertTrue(again.wakeRecovery)
    }

    @Test
    fun partialBindIsNotSuccessAndDoesNotSuppressTheNextFollow() {
        assertFalse(TunnelRebuild.dataPlaneTreatsPartialBindAsSuccess(boundV4 = true, boundV6 = false))
        assertFalse(TunnelRebuild.dataPlaneTreatsPartialBindAsSuccess(boundV4 = false, boundV6 = true))
        assertFalse(TunnelRebuild.dataPlaneTreatsPartialBindAsSuccess(boundV4 = true, boundV6 = true))
        assertFalse(TunnelRebuild.dataPlaneTreatsPartialBindAsSuccess(boundV4 = false, boundV6 = false))

        val reaction = TunnelRebuild.onSystemDefault(
            watch = TunnelRebuild.DefaultWatch(seen = true, handle = cellular),
            nextHandle = cellular,
            controlClientOpen = true,
            controlClientHandle = cellular,
        )
        assertTrue(reaction.followDataPlane)
        assertFalse(reaction.dataPlaneBindSocket)
        assertEquals(TunnelRebuild.DataPlaneAction.PROTECT_UNBOUND, reaction.dataPlane)
    }

    @Test
    fun serviceSourceBindsOnlyTheControlClient() {
        val source = serviceSource()
        val code = source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertEquals(1, Regex("""\.bindSocket\(""").findAll(code).count())
        assertTrue(code.contains("protectThenBind"))
        assertFalse(code.contains("setUnderlyingNetworks(arrayOf"))
        assertFalse(code.contains("boundV4 || boundV6"))
        assertFalse(code.contains("socketsBoundHandle"))
        assertFalse(code.contains("pinnedHandle"))
    }

    @Test
    fun directWifiToCellularStillClearsAndRecoversOnCellular() {
        val reaction = TunnelRebuild.onSystemDefault(
            watch = TunnelRebuild.DefaultWatch(seen = true, handle = wifi),
            nextHandle = cellular,
            controlClientOpen = true,
            controlClientHandle = wifi,
        )
        assertTrue(reaction.clearCachedPeerOutcome)
        assertTrue(reaction.dropControlClient)
        assertTrue(reaction.wakeRecovery)
        assertTrue(reaction.followDataPlane)
        assertFalse(reaction.dataPlaneBindSocket)
        assertEquals(
            TunnelRebuild.RecoveryStep.RESET,
            recoveryStep(defaultHandle = cellular, trackedHandle = wifi, peerCleared = false),
        )
        assertEquals(cellular, reaction.next.handle)
    }

    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt"),
            File("app/src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt"),
            File("android/app/src/main/java/cloud/veritasvpn/vpn/VeritasVpnService.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("VeritasVpnService.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun observe(
        watch: TunnelRebuild.DefaultWatch,
        next: Long?,
        controlOpen: Boolean,
        controlHandle: Long?,
    ): TunnelRebuild.DefaultReaction {
        val reaction = TunnelRebuild.onSystemDefault(
            watch = watch,
            nextHandle = next,
            controlClientOpen = controlOpen,
            controlClientHandle = controlHandle,
        )
        assertTrue(reaction.followDataPlane)
        assertFalse(reaction.dataPlaneBindSocket)
        assertEquals(TunnelRebuild.DataPlaneAction.PROTECT_UNBOUND, reaction.dataPlane)
        return reaction
    }

    private fun recoveryStep(
        defaultHandle: Long?,
        trackedHandle: Long?,
        peerCleared: Boolean,
    ): TunnelRebuild.RecoveryStep = TunnelRebuild.decide(
        sessionActive = true,
        peer = if (peerCleared) null else PeerCheckOutcome.Outcome.PEER_ALIVE,
        handshakeCompleted = false,
        probeRoundCompleted = true,
        rebuilding = true,
        waiting = true,
        defaultHandle = defaultHandle,
        trackedHandle = trackedHandle,
    )
}
