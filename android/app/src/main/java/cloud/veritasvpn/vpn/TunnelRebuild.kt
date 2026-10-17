package cloud.veritasvpn.vpn

/**
 * Recovery when hard-rebind probes never complete a handshake.
 *
 * The rebuild always uses the phone's current system-default network. Another
 * transport is never selected, and Stealth is not a substitute for a new socket.
 * A peer-check network error does not block a rebuild. A deleted peer is
 * re-provision's job.
 *
 * WireGuard data-plane sockets are protect()'d and then bindSocket'd to the
 * current system-default network only. protect() alone can stamp a stale VPN
 * netId, which is why a peer check on the bound control client can succeed
 * while the UDP handshake never leaves. Another network is never selected.
 * After repeated rebuilds with no handshake, the peer is re-provisioned and
 * the tun is replaced. That is not a give-up: the next attempt still runs.
 */
object TunnelRebuild {
    const val MIN_BACKOFF_MS = 5_000L
    const val MAX_BACKOFF_MS = 60_000L
    const val PEER_CHECK_INTERVAL_MS = 60_000L

    /** Full rebuilds on one default network with no handshake before a forced check. */
    const val REBUILDS_BEFORE_UNDERLYING_CHECK = 3

    /**
     * Rebuilds on one system-default handle with no handshake before a new
     * peer and a new tun. The roam does not stop.
     */
    const val REBUILDS_BEFORE_FRESH_TUNNEL = 2

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
     * The only handle a socket may pass to bindSocket: the current system
     * default. Null means that network is gone and nothing is bound.
     */
    fun pinHandle(systemDefaultHandle: Long?): Long? = systemDefaultHandle

    /**
     * Data-plane bindSocket is allowed only when [targetHandle] is the current
     * system default. Any other handle, including a network the phone already
     * left, is rejected.
     */
    fun dataPlaneMayBind(systemDefaultHandle: Long?, targetHandle: Long?): Boolean =
        systemDefaultHandle != null && targetHandle == systemDefaultHandle

    /**
     * Enough failed rebuilds on this handle to replace the peer and the tun.
     * A smaller count stays on socket swap and rebuild.
     */
    fun shouldReprovisionFreshTunnel(failedRebuildsOnHandle: Int): Boolean =
        failedRebuildsOnHandle >= REBUILDS_BEFORE_FRESH_TUNNEL

    /** protect(), then bindSocket to the current system default when it exists. */
    enum class DataPlaneAction {
        PROTECT_THEN_BIND_DEFAULT,
    }

    /**
     * Last system-default handle this session has reacted to.
     * [seen] is false until the first observation, so "never seen" is not
     * the same as "seen, and the default is gone".
     */
    data class DefaultWatch(
        val seen: Boolean = false,
        val handle: Long? = null,
    )

    /**
     * What to do when the system default is observed again.
     *
     * [followDataPlane] is always true. A failed or skipped protect must not
     * record a pin that suppresses the next callback, and a v4-only or v6-only
     * result is not a success. [dataPlaneBindSocket] is true only while a
     * system-default handle exists, and that handle is the only bind target.
     * [clearCachedPeerOutcome] and [wakeRecovery] still run on a real handle
     * change, including Wi-Fi to lost and lost to cellular.
     */
    data class DefaultReaction(
        val clearCachedPeerOutcome: Boolean,
        val dropControlClient: Boolean,
        val wakeRecovery: Boolean,
        val followDataPlane: Boolean,
        val dataPlaneBindSocket: Boolean,
        val dataPlane: DataPlaneAction,
        val next: DefaultWatch,
    )

    fun onSystemDefault(
        watch: DefaultWatch,
        nextHandle: Long?,
        controlClientOpen: Boolean,
        controlClientHandle: Long?,
    ): DefaultReaction {
        val changed = watch.seen && watch.handle != nextHandle
        val dropControl = controlClientOpen &&
            (nextHandle == null || nextHandle != controlClientHandle)
        return DefaultReaction(
            clearCachedPeerOutcome = changed,
            dropControlClient = dropControl,
            wakeRecovery = true,
            followDataPlane = true,
            dataPlaneBindSocket = nextHandle != null,
            dataPlane = DataPlaneAction.PROTECT_THEN_BIND_DEFAULT,
            next = DefaultWatch(seen = true, handle = nextHandle),
        )
    }

    /**
     * A partial bind used to count as success (`boundV4 || boundV6`) and then
     * the v4 socket, which carries an IPv4 endpoint, stayed on the old network.
     * The data plane does not bind, so neither flag can arm that gate.
     */
    fun dataPlaneTreatsPartialBindAsSuccess(boundV4: Boolean, boundV6: Boolean): Boolean = when {
        boundV4 && boundV6 -> false
        boundV4 || boundV6 -> false
        else -> false
    }

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

    /**
     * Cached outcomes from the previous default network. A signed-out result
     * stays, because it is not a property of the network. Every other result,
     * including a network error, is dropped so the next check is a real answer.
     */
    fun shouldClearCachedPeerOutcome(outcome: PeerCheckOutcome.Outcome?): Boolean = when (outcome) {
        null, PeerCheckOutcome.Outcome.SIGNED_OUT -> false
        else -> true
    }

    /** A finished rebuild that did not handshake may check again immediately. */
    fun bypassPeerCheckRateLimit(failedRebuildRound: Boolean): Boolean = failedRebuildRound

    data class RebuildStreak(val handle: Long?, val count: Int)

    /** Count consecutive no-handshake rebuilds on one system-default handle. */
    fun noteFailedRebuild(previous: RebuildStreak, handle: Long?): RebuildStreak {
        if (handle == null || previous.handle != handle) return RebuildStreak(handle, 1)
        return RebuildStreak(handle, previous.count + 1)
    }

    /**
     * After [REBUILDS_BEFORE_UNDERLYING_CHECK] rebuilds on the same default
     * network, the next peer check uses the protected client bound to that
     * network. The result is not guessed: only a 404 or 410 re-provisions.
     */
    fun shouldForceUnderlyingPeerCheck(rebuildsOnSameDefault: Int): Boolean =
        rebuildsOnSameDefault >= REBUILDS_BEFORE_UNDERLYING_CHECK
}
