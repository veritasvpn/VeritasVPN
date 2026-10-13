package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRestoreTest {

    @Test
    fun signedOutThenSignInClearsTheFlagAndRunsAPeerCheck() {
        var signedOut = true
        var showSignInAgain = true
        var peerChecks = 0
        var recoveryResumed = false
        var outcome: PeerCheckOutcome.Outcome? = PeerCheckOutcome.Outcome.SIGNED_OUT
        val tunnelHealthy = false

        assertEquals(
            TunnelRebuild.RecoveryStep.STOP,
            recoveryStep(outcome),
        )

        val decision = AuthRestore.afterSignIn(
            wasSignedOut = signedOut,
            tunnelHealthy = tunnelHealthy,
        )
        signedOut = decision.signedOut
        showSignInAgain = decision.showSignInAgain
        outcome = AuthRestore.peerOutcomeAfterSignIn(outcome)
        if (decision.forcePeerCheck && !signedOut) peerChecks += 1
        if (decision.resumeRecovery) recoveryResumed = true

        assertFalse(signedOut)
        assertFalse(showSignInAgain)
        assertEquals(1, peerChecks)
        assertNull(outcome)
        assertTrue(recoveryResumed)
        assertEquals(TunnelRebuild.RecoveryStep.REBUILD, recoveryStep(outcome))
        assertEquals(7L, TunnelRebuild.pinHandle(7L))
    }

    @Test
    fun healthyTunnelAfterSignInChecksThePeerWithoutRestartingRecovery() {
        val decision = AuthRestore.afterSignIn(wasSignedOut = true, tunnelHealthy = true)
        assertFalse(decision.signedOut)
        assertFalse(decision.showSignInAgain)
        assertTrue(decision.forcePeerCheck)
        assertFalse(decision.resumeRecovery)
    }

    @Test
    fun signInKeepsADeletedPeerResult() {
        assertEquals(
            PeerCheckOutcome.Outcome.PEER_GONE,
            AuthRestore.peerOutcomeAfterSignIn(PeerCheckOutcome.Outcome.PEER_GONE),
        )
    }

    private fun recoveryStep(peer: PeerCheckOutcome.Outcome?): TunnelRebuild.RecoveryStep =
        TunnelRebuild.decide(
            sessionActive = true,
            peer = peer,
            handshakeCompleted = false,
            probeRoundCompleted = true,
            rebuilding = true,
            waiting = false,
            defaultHandle = 7L,
            trackedHandle = 7L,
        )
}
