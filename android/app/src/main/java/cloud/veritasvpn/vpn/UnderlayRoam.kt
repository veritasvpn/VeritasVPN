package cloud.veritasvpn.vpn

/**
 * Decides whether path-adapt should move WireGuard onto [liveIdentity].
 *
 * [liveIdentity] is the system best-matching non-VPN network, not whichever
 * leftover Wi‑Fi or cell just fired a callback. Same-/24 is irrelevant.
 */
object UnderlayRoam {

    enum class Action {
        /** Best-matching underlay is missing or not yet VALIDATED. */
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
