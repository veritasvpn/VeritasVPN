package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PeerGoneRecoveryTest {

    @Test
    fun peerGoneCreatesOnceWhileStealthIsHeldOrTheWindowIsOpen() {
        val now = 50_000L
        val windowOpen = now - 1_000L
        assertEquals(
            PeerGoneRecovery.Action.CREATE,
            PeerGoneRecovery.action(
                peerGone = true,
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.IDLE,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.CREATE,
            PeerGoneRecovery.action(
                peerGone = true,
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.WAITING,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.IN_FLIGHT,
            PeerGoneRecovery.action(
                peerGone = true,
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.RUNNING,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            ReprovisionFlight.Decision.SKIP,
            ReprovisionFlight.decide(
                ReprovisionFlight.Phase.IDLE,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
    }

    @Test
    fun ordinaryReprovisionDefersForTheHoldAndTheFlightWindow() {
        val now = 50_000L
        val windowOpen = now - 1_000L
        assertEquals(
            PeerGoneRecovery.Action.DEFER,
            PeerGoneRecovery.action(
                peerGone = false,
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.IDLE,
                persistedAtMs = 0L,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.DEFER,
            PeerGoneRecovery.action(
                peerGone = false,
                upgradeHeld = false,
                phase = ReprovisionFlight.Phase.IDLE,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.IN_FLIGHT,
            PeerGoneRecovery.action(
                peerGone = false,
                upgradeHeld = false,
                phase = ReprovisionFlight.Phase.RUNNING,
                persistedAtMs = 0L,
                nowMs = now,
            ),
        )
        // The service's own backoff is replaced. That is the same flight,
        // not a second create, and it still waits out a Stealth hold.
        assertEquals(
            PeerGoneRecovery.Action.CREATE,
            PeerGoneRecovery.action(
                peerGone = false,
                upgradeHeld = false,
                phase = ReprovisionFlight.Phase.WAITING,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.DEFER,
            PeerGoneRecovery.action(
                peerGone = false,
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.WAITING,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.CREATE,
            PeerGoneRecovery.action(
                peerGone = false,
                upgradeHeld = false,
                phase = ReprovisionFlight.Phase.IDLE,
                persistedAtMs = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun onlyARealPeerReadBypassesTheHold() {
        val service = kotlinSource("VeritasVpnService.kt")
        val fetch = slice(service, "fun fetchFreshEndpoint", "fun getPeerOnce")
        assertTrue(fetch.contains("val gen = sessionGeneration"))
        assertTrue(fetch.indexOf("val gen = sessionGeneration") < fetch.indexOf("requestReprovision(gen, peerGone = true)"))
        assertFalse(fetch.contains("requestReprovision(sessionGeneration"))
        val rebuild = slice(service, "fun rebuildUntilHandshake", "fun sessionStillActive")
        assertFalse(rebuild.contains("peerGone"))
        assertTrue(rebuild.contains("requestReprovision(gen)"))
        val after = slice(service, "fun scheduleAfterUpgrade", "fun escalateFreshTunnel")
        assertFalse(after.contains("peerGone"))
        assertTrue(after.contains("requestReprovision(gen)"))
        val plan = slice(service, "fun applyPeerPlan", "fun stopForSignedOut")
        assertTrue(plan.contains("requestReprovision(gen, peerGone = true)"))
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
    }
}
