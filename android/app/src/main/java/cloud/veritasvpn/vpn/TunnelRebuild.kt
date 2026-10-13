package cloud.veritasvpn.vpn

/**
 * Recovery when hard-rebind probes never complete a handshake.
 *
 * The rebuild always uses the phone's current system-default network. Another
 * transport is never selected, and Stealth is not a substitute for a new socket.
 * A peer-check network error does not block a rebuild. A deleted peer is
 * re-provision's job.
 */
object TunnelRebuild {
    const val MIN_BACKOFF_MS = 5_000L
    const val MAX_BACKOFF_MS = 60_000L
    const val PEER_CHECK_INTERVAL_MS = 60_000L

    enum class RecoveryStep {
        /** Create a new tunnel on the current system default. */
        REBUILD,

        /** Handshake did not complete; wait out the backoff, then rebuild. */
        WAIT,

        /** The system default changed. Drop the backoff and the stale peer result. */
        RESET,

        /** Disconnect, a new session, or a completed handshake. */
        STOP,

        /** Peer check says the peer was deleted. */
        REPROVISION,
    }

    /**
     * One full probe round with no handshake, and a peer check that is alive
     * or failed open with a network error, means the tunnel must be rebuilt.
     * A missing result is not enough: the check has to finish or fail.
     * A dead peer is not rebuilt.
     */
    fun shouldRebuild(
        peer: PeerCheckOutcome.Outcome?,
        handshakeCompleted: Boolean,
        probeRoundCompleted: Boolean,
    ): Boolean {
        if (handshakeCompleted || !probeRoundCompleted) return false
        return peer == PeerCheckOutcome.Outcome.PEER_ALIVE ||
            peer == PeerCheckOutcome.Outcome.NETWORK_ERROR
    }

    /**
     * Next step of the recovery loop. [rebuilding] is false while probes are
     * still running, and true once a rebuild loop has started. [waiting] is
     * the backoff after a rebuild that did not handshake.
     */
    fun decide(
        sessionActive: Boolean,
        peer: PeerCheckOutcome.Outcome?,
        handshakeCompleted: Boolean,
        probeRoundCompleted: Boolean,
        rebuilding: Boolean,
        waiting: Boolean,
        defaultHandle: Long?,
        trackedHandle: Long?,
    ): RecoveryStep {
        if (!sessionActive) return RecoveryStep.STOP
        // Signed out is terminal. Treating it as a network error rebuilds forever.
        if (peer == PeerCheckOutcome.Outcome.SIGNED_OUT) return RecoveryStep.STOP
        if (peer == PeerCheckOutcome.Outcome.PEER_GONE) return RecoveryStep.REPROVISION
        if (handshakeCompleted) return RecoveryStep.STOP
        val networkChanged = rebuilding &&
            trackedHandle != null &&
            defaultHandle != trackedHandle
        if (networkChanged) return RecoveryStep.RESET
        if (!rebuilding && !shouldRebuild(peer, handshakeCompleted, probeRoundCompleted)) {
            return RecoveryStep.WAIT
        }
        if (waiting) return RecoveryStep.WAIT
        return RecoveryStep.REBUILD
    }

    /**
     * The only handle that may be passed to setUnderlyingNetworks or
     * bindSocket. Null means the default network is gone and the pin is dropped.
     */
    fun pinHandle(systemDefaultHandle: Long?): Long? = systemDefaultHandle

    /**
     * Install [establishNew] and only then close the previous tun. A failed
     * establish leaves the previous interface in place.
     */
    fun <T : Any> replaceWhileHeld(establishNew: () -> T?, closePrevious: (T) -> Unit): T? {
        val created = establishNew() ?: return null
        closePrevious(created)
        return created
    }

    /**
     * The new tun is already in use, but duplicating it failed. The previous
     * keeper must be closed so that extra fd does not stay open. A failed
     * establish (no new tun) leaves the previous keeper alone.
     */
    fun shouldClosePreviousKeeper(newTunEstablished: Boolean, duplicateSucceeded: Boolean): Boolean =
        newTunEstablished && !duplicateSucceeded

    /** True when the UDP socket is not bound to the current system default. */
    fun socketNeedsRebind(boundHandle: Long?, systemDefaultHandle: Long?): Boolean {
        if (systemDefaultHandle == null) return false
        return boundHandle == null || boundHandle != systemDefaultHandle
    }

    fun nextBackoff(currentMs: Long): Long {
        val base = if (currentMs < MIN_BACKOFF_MS) MIN_BACKOFF_MS else currentMs
        return (base * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * A completed handshake or a new system-default network starts the wait
     * over at [MIN_BACKOFF_MS]. Otherwise the wait grows until [MAX_BACKOFF_MS].
     */
    fun backoffAfter(
        currentMs: Long,
        handshakeSucceeded: Boolean,
        defaultNetworkChanged: Boolean,
    ): Long {
        if (handshakeSucceeded || defaultNetworkChanged) return MIN_BACKOFF_MS
        return nextBackoff(currentMs)
    }
}
