package cloud.veritasvpn.vpn

/**
 * Decides whether path-adapt should move WireGuard onto [liveIdentity].
 *
 * [liveIdentity] is the system-default network handle, not link properties
 * and not whichever leftover Wi-Fi or cell just fired a callback. A
 * capability or link update for that same handle skips. Same-/24 is irrelevant.
 */
object UnderlayRoam {

    enum class Action {
        /**
         * The system default has no address yet. NET_CAPABILITY_VALIDATED is
         * not required: validation probes can sit behind the tunnel and never
         * complete while the roam waits for them.
         */
        WAIT,
        /** Already on that underlay. */
        SKIP,
        /** Device moved to a different underlay. */
        ROAM,
    }

    fun action(
        liveIdentity: String?,
        liveReady: Boolean,
        lastIdentity: String?,
    ): Action {
        if (liveIdentity == null || !liveReady) return Action.WAIT
        if (liveIdentity == lastIdentity) return Action.SKIP
        return Action.ROAM
    }
}
