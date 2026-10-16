package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home Wi-Fi and cellular, switched back and forth. Each switch binds the
 * callback network before it is the validated default, re-binds when it
 * becomes the default, and does not show the give-up line at two seconds.
 * Twelve switches do not stack a userspace restart per capability update.
 */
class AlternatingRoamTest {
    private val wifi = 1_648_378_171_405L
    private val cellular = 8_900_000_000_002L

    @Test
    fun twelveSwitchesBindBeforeTheDefaultAndStayQuiet() {
        var state = EarlyUnderlay.State()
        var turnOffs = 0
        repeat(12) { i ->
            val handle = if (i % 2 == 0) wifi else cellular
            val other = if (handle == wifi) cellular else wifi
            val current = EarlyUnderlay.bindTarget(state)
            if (current != null && current != handle) {
                val lost = EarlyUnderlay.onLost(state, current)
                assertFalse(EarlyUnderlay.restartsUserspace(state, lost))
                state = lost
            }
            val appeared = EarlyUnderlay.onAvailable(
                state,
                handle,
                hasAddress = true,
                isDefault = false,
                adoptEarly = true,
            )
            assertEquals(handle, EarlyUnderlay.bindTarget(appeared))
            assertEquals(handle, appeared.early)
            assertTrue(EarlyUnderlay.shouldRebind(state.bound, handle, becameDefault = false))
            if (EarlyUnderlay.restartsUserspace(state, appeared)) turnOffs++
            // The other transport's callback must not steal the bind.
            val otherCaps = EarlyUnderlay.onCapabilities(
                appeared,
                other,
                hasAddress = true,
                isDefault = true,
                adoptEarly = false,
            )
            assertEquals(handle, EarlyUnderlay.bindTarget(otherCaps))
            assertFalse(EarlyUnderlay.restartsUserspace(appeared, otherCaps))
            assertFalse(EarlyUnderlay.staleBlocks(bound = handle, target = handle, systemDefault = other))
            assertTrue(EarlyUnderlay.attemptStillCurrent(handle, bindTarget = handle, systemDefault = other))
            // Capabilities for this network, still not the default: no second turn-off.
            val caps = EarlyUnderlay.onCapabilities(
                otherCaps,
                handle,
                hasAddress = true,
                isDefault = false,
                adoptEarly = true,
            )
            assertFalse(EarlyUnderlay.restartsUserspace(otherCaps, caps))
            assertEquals(handle, EarlyUnderlay.bindTarget(caps))
            val def = EarlyUnderlay.onCapabilities(
                caps,
                handle,
                hasAddress = true,
                isDefault = true,
                adoptEarly = false,
            )
            assertEquals(handle, def.defaultHandle)
            assertNull(def.early)
            assertEquals(handle, EarlyUnderlay.bindTarget(def))
            assertFalse(EarlyUnderlay.restartsUserspace(caps, def))
            assertTrue(EarlyUnderlay.shouldRebind(handle, handle, becameDefault = true))
            assertNull(
                EndpointRefresh.noticeAfter(
                    1,
                    rebuildTried = true,
                    recoveryPending = false,
                    elapsedMs = 2_000L,
                ),
            )
            state = def.copy(bound = handle)
        }
        assertEquals(12, turnOffs)
        assertEquals(
            EndpointRefresh.GIVE_UP_MESSAGE,
            EndpointRefresh.noticeAfter(
                1,
                rebuildTried = true,
                recoveryPending = false,
                elapsedMs = RoamRecovery.NOTICE_AFTER_MS,
            ),
        )
        var streak = TunnelRebuild.RebuildStreak(null, 0)
        streak = TunnelRebuild.noteFailedRebuild(streak, wifi)
        assertFalse(TunnelRebuild.shouldReprovisionFreshTunnel(streak.count))
        val capsAgain = EarlyUnderlay.onCapabilities(
            EarlyUnderlay.State(defaultHandle = wifi, bound = wifi),
            wifi,
            hasAddress = true,
            isDefault = false,
            adoptEarly = true,
        )
        assertFalse(
            EarlyUnderlay.restartsUserspace(
                EarlyUnderlay.State(defaultHandle = wifi, bound = wifi),
                capsAgain,
            ),
        )
        streak = TunnelRebuild.noteFailedRebuild(streak, wifi)
        assertTrue(TunnelRebuild.shouldReprovisionFreshTunnel(streak.count))
        assertEquals(3, TunnelRebuild.noteFailedRebuild(streak, wifi).count)
    }

    @Test
    fun serviceKeepsOneRoamPerSwitchAndWaitsToShowTheError() {
        val source = kotlinSource("VeritasVpnService.kt")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val schedule = slice(source, "fun scheduleUnderlayAdapt", "adaptJob = scope.launch")
        assertTrue(schedule.contains("activeRoamHandle"))
        assertTrue(schedule.indexOf("activeRoamHandle") < schedule.indexOf("adaptJob?.cancel()"))
        val protect = slice(source, "fun protectBackendSockets", "fun protectAndBind")
        assertTrue(protect.contains("dataPlaneNetwork()"))
        val recover = slice(source, "fun recoverWithFreshEndpoint", "data class RoamRound")
        assertTrue(recover.contains("roamFailRebuilds"))
        assertTrue(recover.contains("shouldReprovisionFreshTunnel"))
        assertTrue(recover.contains("NOTICE_AFTER_MS") || recover.contains("noteResolveFailure"))
        assertFalse(recover.contains("var failedRebuilds"))
        val connect = slice(source, "ACTION_CONNECT -> {", "ACTION_DISCONNECT")
        assertTrue(connect.contains("nextTunnelIsFresh = false"))
        assertTrue(connect.contains("clearEarlyRoam()"))
        assertEquals(2, Regex("""\.bindSocket\(""").findAll(source).count())
        assertFalse(source.contains("setUnderlyingNetworks(arrayOf"))
        assertTrue(source.contains("early-bind"))
        assertTrue(source.contains("rebind-default"))
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
        } ?: error("$name not found")
        return file.readText()
    }
}
