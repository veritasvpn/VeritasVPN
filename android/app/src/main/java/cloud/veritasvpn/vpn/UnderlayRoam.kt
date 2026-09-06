package cloud.veritasvpn.vpn

/**
 * Decides whether a NetworkCallback should rebind WireGuard.
 *
 * Leftover Wi‑Fi or cellular often stays VALIDATED after the user has moved.
 * Those callbacks must not steal the session. Only the foreground underlay
 * (the network the device is actually using) is a roam target. Same-/24 is
 * irrelevant: A and B can be any networks, including ones that are not the
 * VPN node LAN.
 */
object UnderlayRoam {

    enum class Action {
        /** New underlay is visible but not ready; wait for VALIDATED + IPv4. */
        WAIT,
        /** No roam. First-connect chatter or leftover background transport. */
        SKIP,
        /** Probe on the callback network (it is the live foreground underlay). */
        USE_CALLBACK,
        /** Probe on the current foreground underlay (onLost or leftover chatter). */
        USE_FOREGROUND,
    }

    fun action(
        callbackIdentity: String?,
        callbackValidated: Boolean,
        callbackHasIpv4: Boolean,
        callbackForeground: Boolean,
        lastIdentity: String?,
        foregroundIdentity: String?,
        handshakeOk: Boolean,
    ): Action {
        if (callbackIdentity != null) {
            if (callbackIdentity != lastIdentity) {
                if (!callbackValidated || !callbackHasIpv4) return Action.WAIT
                // Background leftovers look "new" after we already moved.
                if (!callbackForeground) return Action.SKIP
                return Action.USE_CALLBACK
            }
            if (foregroundIdentity != null && foregroundIdentity != lastIdentity) {
                return Action.USE_FOREGROUND
            }
            return if (handshakeOk) Action.SKIP else Action.USE_CALLBACK
        }
        if (foregroundIdentity != null && foregroundIdentity != lastIdentity) {
            return Action.USE_FOREGROUND
        }
        if (handshakeOk) return Action.SKIP
        if (foregroundIdentity != null) return Action.USE_FOREGROUND
        return Action.WAIT
    }
}
