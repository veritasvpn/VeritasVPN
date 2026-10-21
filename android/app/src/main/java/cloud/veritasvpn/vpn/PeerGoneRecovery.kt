package cloud.veritasvpn.vpn

/**
 * What to do when the peer read returns 404 or 410.
 *
 * The Stealth upgrade hold and the persisted reprovision window used to drop
 * this attempt. The tunnel then sat on Reconnecting with no further create.
 * Peer-gone creates one peer anyway. A flight that is already running is that
 * one create, so a second caller does not start another.
 */
object PeerGoneRecovery {
    enum class Action { CREATE, IN_FLIGHT }

    fun action(
        upgradeHeld: Boolean,
        phase: ReprovisionFlight.Phase,
        persistedAtMs: Long,
        nowMs: Long,
        windowMs: Long = ReprovisionFlight.WINDOW_MS,
    ): Action {
        val held = upgradeHeld
        val windowBusy = ReprovisionFlight.persistedBusy(persistedAtMs, nowMs, windowMs)
        if (phase == ReprovisionFlight.Phase.RUNNING) return Action.IN_FLIGHT
        // [held] and [windowBusy] do not change the result. They are the two
        // gates that previously skipped the create.
        if (held || windowBusy || phase == ReprovisionFlight.Phase.WAITING) return Action.CREATE
        return Action.CREATE
    }
}
