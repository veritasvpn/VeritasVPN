package cloud.veritasvpn.vpn

/**
 * What the VPN service does after the user signs in again.
 *
 * The tunnel stays up while the session is signed out, so a later sign-in
 * has to clear that stop, run a peer check, and resume recovery when the
 * tunnel is not healthy. Recovery still uses only the current system-default
 * network. This does not choose another network and it does not turn Stealth on.
 */
object AuthRestore {
    data class Decision(
        val signedOut: Boolean,
        val showSignInAgain: Boolean,
        val forcePeerCheck: Boolean,
        val resumeRecovery: Boolean,
    )

    fun afterSignIn(wasSignedOut: Boolean, tunnelHealthy: Boolean): Decision = Decision(
        signedOut = false,
        showSignInAgain = false,
        forcePeerCheck = true,
        resumeRecovery = wasSignedOut && !tunnelHealthy,
    )

    /**
     * A signed-out peer result makes the recovery loop stop. Drop only that
     * result. A deleted-peer result stays so re-provision still runs.
     */
    fun peerOutcomeAfterSignIn(outcome: PeerCheckOutcome.Outcome?): PeerCheckOutcome.Outcome? =
        if (outcome == PeerCheckOutcome.Outcome.SIGNED_OUT) null else outcome
}
