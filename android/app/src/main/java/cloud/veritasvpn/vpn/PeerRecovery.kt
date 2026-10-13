package cloud.veritasvpn.vpn

/**
 * What the service does with a peer-check result. A deleted peer is
 * re-provisioned. A signed-out session stops; it is not a network error and
 * it does not keep rebuilding.
 */
object PeerRecovery {
    enum class Next { CONTINUE, REPROVISION, STOP }

    data class Plan(
        val outcome: PeerCheckOutcome.Outcome,
        val next: Next,
        val reprovision: Boolean,
        val stopRetries: Boolean,
        val userMessage: String?,
    )

    fun plan(outcome: PeerCheckOutcome.Outcome): Plan {
        val next = when (outcome) {
            PeerCheckOutcome.Outcome.PEER_GONE -> Next.REPROVISION
            PeerCheckOutcome.Outcome.SIGNED_OUT -> Next.STOP
            PeerCheckOutcome.Outcome.PEER_ALIVE,
            PeerCheckOutcome.Outcome.REFRESH_AND_RETRY,
            PeerCheckOutcome.Outcome.NETWORK_ERROR -> Next.CONTINUE
        }
        return Plan(
            outcome = outcome,
            next = next,
            reprovision = next == Next.REPROVISION,
            stopRetries = next == Next.STOP,
            userMessage = if (next == Next.STOP) ControlPlane.SIGN_IN_AGAIN else null,
        )
    }

    /**
     * No token and no refresh token is signed out. A transport failure while
     * a refresh token still exists stays a network error so recovery can
     * continue. A usable token returns null and the caller performs the check.
     */
    fun planForAccess(signedOut: Boolean, unavailable: Boolean): Plan? = when {
        signedOut -> plan(PeerCheckOutcome.Outcome.SIGNED_OUT)
        unavailable -> plan(PeerCheckOutcome.Outcome.NETWORK_ERROR)
        else -> null
    }

    fun shouldScheduleRetry(
        succeeded: Boolean,
        generationMatches: Boolean,
        sessionIntended: Boolean,
        disconnectInProgress: Boolean,
        signedOut: Boolean,
    ): Boolean = !signedOut && ReprovisionBackoff.shouldScheduleRetry(
        succeeded = succeeded,
        generationMatches = generationMatches,
        sessionIntended = sessionIntended,
        disconnectInProgress = disconnectInProgress,
    )
}
