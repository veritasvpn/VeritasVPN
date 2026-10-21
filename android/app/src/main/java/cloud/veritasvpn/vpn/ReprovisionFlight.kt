package cloud.veritasvpn.vpn

/**
 * One re-provision at a time, in this process and across a process restart.
 *
 * RUNNING rejects a second attempt. WAITING is the backoff after a failure;
 * a new peer-gone result cancels that wait and starts one attempt. IDLE with
 * a recent persisted timestamp still rejects an ordinary claim, so a restarted
 * process does not create a second peer while the first attempt's window is
 * open. A peer-gone claim is the exception: the window does not swallow the
 * one create that follows a 404 or 410.
 */
object ReprovisionFlight {
    const val WINDOW_MS = 60_000L

    enum class Phase { IDLE, WAITING, RUNNING }

    enum class Decision { START, SKIP, REPLACE_WAITING }

    private val lock = Any()
    private var phase = Phase.IDLE
    private var epoch = 0

    fun decide(
        phase: Phase,
        persistedAtMs: Long,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
    ): Decision = when (phase) {
        Phase.RUNNING -> Decision.SKIP
        Phase.WAITING -> Decision.REPLACE_WAITING
        Phase.IDLE ->
            if (persistedBusy(persistedAtMs, nowMs, windowMs)) Decision.SKIP else Decision.START
    }

    fun persistedBusy(persistedAtMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean =
        persistedAtMs > 0L && nowMs - persistedAtMs < windowMs

    /**
     * Claim the single flight. [replaceWaiting] is true for the service,
     * which may cancel its own backoff. The activity reconnect path passes
     * false so it does not steal a service retry.
     *
     * Returns the epoch that must be passed to [release], or null when this
     * caller must not create a peer.
     */
    fun tryClaim(
        persistedAtMs: Long,
        nowMs: Long,
        replaceWaiting: Boolean,
        peerGone: Boolean = false,
        upgradeHeld: Boolean = false,
        persist: (Long) -> Unit,
    ): Int? = synchronized(lock) {
        if (peerGone) {
            return when (
                PeerGoneRecovery.action(
                    peerGone = true,
                    upgradeHeld = upgradeHeld,
                    phase = phase,
                    persistedAtMs = persistedAtMs,
                    nowMs = nowMs,
                )
            ) {
                PeerGoneRecovery.Action.IN_FLIGHT,
                PeerGoneRecovery.Action.DEFER -> null
                PeerGoneRecovery.Action.CREATE -> claim(nowMs, persist)
            }
        }
        when (val decision = decide(phase, persistedAtMs, nowMs)) {
            Decision.SKIP -> null
            Decision.REPLACE_WAITING -> {
                if (!replaceWaiting) return null
                claim(nowMs, persist)
            }
            Decision.START -> claim(nowMs, persist)
        }
    }

    fun release(epoch: Int, clear: () -> Unit) = synchronized(lock) {
        if (this.epoch != epoch || phase == Phase.WAITING) return
        phase = Phase.IDLE
        clear()
    }

    /** Keep the flight through the backoff. False when a newer claim owns it. */
    fun markWaiting(epoch: Int, nowMs: Long, persist: (Long) -> Unit): Boolean = synchronized(lock) {
        if (this.epoch != epoch || phase != Phase.RUNNING) return false
        phase = Phase.WAITING
        persist(nowMs)
        true
    }

    /** Resume a backoff into the single running attempt. */
    fun claimWaiting(epoch: Int): Boolean = synchronized(lock) {
        if (this.epoch != epoch || phase != Phase.WAITING) return false
        phase = Phase.RUNNING
        true
    }

    /** Drop the flight. In-flight work with the previous epoch must not release it. */
    fun cancelCurrent() = synchronized(lock) {
        epoch += 1
        phase = Phase.IDLE
    }

    fun currentPhase(): Phase = synchronized(lock) { phase }

    fun blocksActivity(persistedAtMs: Long, nowMs: Long): Boolean = synchronized(lock) {
        phase != Phase.IDLE || persistedBusy(persistedAtMs, nowMs)
    }

    private fun claim(nowMs: Long, persist: (Long) -> Unit): Int {
        epoch += 1
        phase = Phase.RUNNING
        persist(nowMs)
        return epoch
    }
}
