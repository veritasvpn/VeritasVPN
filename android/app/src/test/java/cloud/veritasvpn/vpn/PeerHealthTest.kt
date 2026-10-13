package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerHealthTest {

    @Test
    fun okWhenHandshakeExistsOnCurrentLink() {
        assertEquals(
            PeerHealth.Decision.OK,
            PeerHealth.decide(
                handshakeMs = 1_500L,
                linkAttachedAtMs = 1_000L,
                nowMs = 2_000L,
                networkUsable = true,
                lastVerifyAtMs = 0L,
            ),
        )
    }

    @Test
    fun redialWhenNoHandshakeButUnderTimeout() {
        assertEquals(
            PeerHealth.Decision.REDIAL,
            PeerHealth.decide(
                handshakeMs = 0L,
                linkAttachedAtMs = 1_000L,
                nowMs = 20_000L,
                networkUsable = true,
                lastVerifyAtMs = 0L,
            ),
        )
    }

    @Test
    fun verifyPeerWhenNoHandshakePastTimeout() {
        assertEquals(
            PeerHealth.Decision.VERIFY_PEER,
            PeerHealth.decide(
                handshakeMs = 0L,
                linkAttachedAtMs = 1_000L,
                nowMs = 50_000L,
                networkUsable = true,
                lastVerifyAtMs = 0L,
            ),
        )
    }

    @Test
    fun verifyPeerCooldownRespected() {
        assertEquals(
            PeerHealth.Decision.REDIAL,
            PeerHealth.decide(
                handshakeMs = 0L,
                linkAttachedAtMs = 1_000L,
                nowMs = 50_000L,
                networkUsable = true,
                lastVerifyAtMs = 40_000L,
            ),
        )
    }

    @Test
    fun okWhenNetworkNotUsable() {
        assertEquals(
            PeerHealth.Decision.OK,
            PeerHealth.decide(
                handshakeMs = 0L,
                linkAttachedAtMs = 1_000L,
                nowMs = 100_000L,
                networkUsable = false,
                lastVerifyAtMs = 0L,
            ),
        )
    }

    @Test
    fun redialWhenHandshakePredatesLink() {
        assertEquals(
            PeerHealth.Decision.REDIAL,
            PeerHealth.decide(
                handshakeMs = 500L,
                linkAttachedAtMs = 1_000L,
                nowMs = 20_000L,
                networkUsable = true,
                lastVerifyAtMs = 0L,
            ),
        )
    }

    @Test
    fun redialWhenRxDeltaPositiveEvenWithoutHandshake() {
        assertEquals(
            PeerHealth.Decision.REDIAL,
            PeerHealth.decide(
                handshakeMs = 0L,
                linkAttachedAtMs = 1_000L,
                nowMs = 50_000L,
                networkUsable = true,
                lastVerifyAtMs = 0L,
                rxDeltaSinceAttach = 100L,
            ),
        )
    }

    @Test
    fun verifyPeerWhenNoHandshakeAndNoRx() {
        assertEquals(
            PeerHealth.Decision.VERIFY_PEER,
            PeerHealth.decide(
                handshakeMs = 0L,
                linkAttachedAtMs = 1_000L,
                nowMs = 50_000L,
                networkUsable = true,
                lastVerifyAtMs = 0L,
                rxDeltaSinceAttach = 0L,
            ),
        )
    }
}
