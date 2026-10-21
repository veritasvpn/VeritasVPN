package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerGoneRecoveryTest {

    @Test
    fun peerGoneCreatesOnceWhileStealthIsHeldOrTheWindowIsOpen() {
        val now = 50_000L
        val windowOpen = now - 1_000L
        assertEquals(
            PeerGoneRecovery.Action.CREATE,
            PeerGoneRecovery.action(
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.IDLE,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.CREATE,
            PeerGoneRecovery.action(
                upgradeHeld = true,
                phase = ReprovisionFlight.Phase.WAITING,
                persistedAtMs = windowOpen,
                nowMs = now,
            ),
        )
        assertEquals(
            PeerGoneRecovery.Action.IN_FLIGHT,
            PeerGoneRecovery.action(
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
}
