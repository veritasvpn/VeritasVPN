package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRecoveryTest {

    @Test
    fun peerGoneTriggersReprovision() {
        val plan = PeerRecovery.plan(PeerCheckOutcome.Outcome.PEER_GONE)
        assertTrue(plan.reprovision)
        assertFalse(plan.stopRetries)
        assertNull(plan.userMessage)
        assertEquals(PeerRecovery.Next.REPROVISION, plan.next)
        assertEquals(
            TunnelRebuild.RecoveryStep.REPROVISION,
            TunnelRebuild.decide(
                sessionActive = true,
                peer = PeerCheckOutcome.Outcome.PEER_GONE,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = true,
                defaultHandle = 7L,
                trackedHandle = 7L,
            ),
        )
    }

    @Test
    fun signedOutStopsRetries() {
        val plan = PeerRecovery.planForAccess(signedOut = true, unavailable = false)
        assertEquals(PeerCheckOutcome.Outcome.SIGNED_OUT, plan?.outcome)
        assertTrue(plan!!.stopRetries)
        assertFalse(plan.reprovision)
        assertEquals(ControlPlane.SIGN_IN_AGAIN, plan.userMessage)
        assertFalse(plan.userMessage!!.contains("Exception"))
        assertFalse(
            PeerRecovery.shouldScheduleRetry(
                succeeded = false,
                generationMatches = true,
                sessionIntended = true,
                disconnectInProgress = false,
                signedOut = true,
            ),
        )
        assertEquals(
            TunnelRebuild.RecoveryStep.STOP,
            TunnelRebuild.decide(
                sessionActive = true,
                peer = PeerCheckOutcome.Outcome.SIGNED_OUT,
                handshakeCompleted = false,
                probeRoundCompleted = true,
                rebuilding = true,
                waiting = false,
                defaultHandle = 7L,
                trackedHandle = 7L,
            ),
        )
    }

    @Test
    fun transportFailureStillRetries() {
        val plan = PeerRecovery.planForAccess(signedOut = false, unavailable = true)
        assertEquals(PeerCheckOutcome.Outcome.NETWORK_ERROR, plan?.outcome)
        assertFalse(plan!!.stopRetries)
        assertTrue(
            PeerRecovery.shouldScheduleRetry(
                succeeded = false,
                generationMatches = true,
                sessionIntended = true,
                disconnectInProgress = false,
                signedOut = false,
            ),
        )
    }
}
