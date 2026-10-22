package cloud.veritasvpn.vpn

/**
 * Whether a re-provision may create a peer.
 *
 * A real 404/410 ([peerGone]) creates one peer even while a Stealth upgrade
 * is held or the cross-process window is still open. Any other caller waits
 * for that hold and that window. A flight that is already running is the one
 * create, so a second caller does not start another.
 */
object PeerGoneRecovery {
    enum class Action { CREATE, IN_FLIGHT, DEFER }

    fun action(
        peerGone: Boolean,
        upgradeHeld: Boolean,
        phase: ReprovisionFlight.Phase,
        persistedAtMs: Long,
        nowMs: Long,
        windowMs: Long = ReprovisionFlight.WINDOW_MS,
    ): Action {
        if (phase == ReprovisionFlight.Phase.RUNNING) return Action.IN_FLIGHT
        if (peerGone) return Action.CREATE
        if (upgradeHeld) return Action.DEFER
        val windowBusy = ReprovisionFlight.persistedBusy(persistedAtMs, nowMs, windowMs)
        if (windowBusy && phase == ReprovisionFlight.Phase.IDLE) return Action.DEFER
        return Action.CREATE
    }
}
