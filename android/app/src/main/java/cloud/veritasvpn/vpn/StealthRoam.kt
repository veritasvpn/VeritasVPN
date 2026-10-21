package cloud.veritasvpn.vpn

/**
 * What Stealth does when the underlay changes.
 *
 * Direct mode is not this path. One new default network dials one websocket.
 * Later link-property and capability callbacks for that same network do not
 * dial again, and a tunnel that is already open does not get torn down
 * because its handshake is a few seconds old or because a counter was read
 * in the middle of a rebind.
 */
object StealthRoam {
    enum class Action { HEALTHY, WAIT, DIAL }

    data class Observation(
        val liveHandle: Long?,
        val boundHandle: Long?,
        val open: Boolean,
        val dialing: Boolean,
        val handshakeMs: Long,
        val rx: Long,
        val tx: Long,
        val nowMs: Long,
        /** Stall watcher. Ignored while this network's tunnel is alive. */
        val force: Boolean = false,
    )

    data class Memory(
        val issuedHandle: Long? = null,
        val issuedAtMs: Long = 0L,
        val baselineRx: Long = 0L,
        val baselineTx: Long = 0L,
        val dials: Int = 0,
    )

    /** A handshake this recent is a live session, including one from before the switch. */
    const val HANDSHAKE_FRESH_MS = 180_000L

    /**
     * After a dial for a handle, ignore repeat callbacks and stall wakes for
     * this long. A different network handle is not held.
     */
    const val SAME_NETWORK_GAP_MS = 30_000L

    fun next(memory: Memory, observation: Observation): Pair<Memory, Action> {
        val live = observation.liveHandle ?: return memory to Action.WAIT
        val onNetwork = observation.boundHandle != null && observation.boundHandle == live
        val bytesMoving = observation.rx > memory.baselineRx || observation.tx > memory.baselineTx
        val handshakeAge = if (observation.handshakeMs > 0L) {
            observation.nowMs - observation.handshakeMs
        } else {
            Long.MAX_VALUE
        }
        val handshakeFresh = handshakeAge in 0..HANDSHAKE_FRESH_MS
        if (onNetwork && observation.open && !observation.dialing && (bytesMoving || handshakeFresh)) {
            return memory.copy(
                issuedHandle = live,
                issuedAtMs = observation.nowMs,
                baselineRx = observation.rx,
                baselineTx = observation.tx,
            ) to Action.HEALTHY
        }

        val issuedThis = memory.issuedHandle == live
        val sinceIssue = if (issuedThis && memory.issuedAtMs > 0L) {
            observation.nowMs - memory.issuedAtMs
        } else {
            Long.MAX_VALUE
        }
        if (issuedThis && sinceIssue < SAME_NETWORK_GAP_MS) {
            return memory to Action.WAIT
        }

        val moved = observation.boundHandle != null && observation.boundHandle != live
        val unbound = observation.boundHandle == null && !observation.dialing
        val stalled = observation.force &&
            !bytesMoving &&
            !handshakeFresh &&
            !observation.dialing &&
            !observation.open
        if (moved || unbound || stalled) {
            return memory.copy(
                issuedHandle = live,
                issuedAtMs = observation.nowMs,
                baselineRx = observation.rx,
                baselineTx = observation.tx,
                dials = memory.dials + 1,
            ) to Action.DIAL
        }
        return memory to Action.WAIT
    }
}
