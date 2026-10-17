package cloud.veritasvpn.vpn

/**
 * What a failed WireGuard handshake means, without copying the endpoint,
 * a key, or a token into the diagnostic ring.
 *
 * The control client can reach the API while these UDP sockets do not,
 * because protect() on its own can leave them on a stale VPN netId.
 */
object DataPlaneHealth {
    enum class ConfigCheck { UNREAD, MATCH, MISMATCH }

    fun endpointOf(userspaceConfig: String?): String? {
        if (userspaceConfig.isNullOrBlank()) return null
        for (line in userspaceConfig.lineSequence()) {
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            if (!trimmed.substring(0, eq).trim().equals("endpoint", ignoreCase = true)) continue
            return trimmed.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun configCheck(userspaceConfig: String?, intendedEndpoint: String?): ConfigCheck {
        if (intendedEndpoint.isNullOrBlank()) return ConfigCheck.UNREAD
        if (userspaceConfig.isNullOrBlank()) return ConfigCheck.UNREAD
        val live = endpointOf(userspaceConfig) ?: return ConfigCheck.MISMATCH
        return if (live.equals(intendedEndpoint.trim(), ignoreCase = true)) {
            ConfigCheck.MATCH
        } else {
            ConfigCheck.MISMATCH
        }
    }

    /**
     * One short token for the roam log. [boundHandle] is what the socket is
     * actually bound to when the platform can say so, otherwise the handle
     * bindSocket was given. Zero means unbound.
     */
    fun failure(
        v4Present: Boolean,
        protectV4: Boolean,
        bindAttempted: Boolean,
        bindV4: Boolean,
        boundHandle: Long?,
        expectedHandle: Long?,
        tx: Long,
        rx: Long,
        config: ConfigCheck,
    ): String {
        if (config == ConfigCheck.MISMATCH) return "config-mismatch"
        if (!v4Present) return "no-socket"
        if (!protectV4) return "protect-failed"
        if (bindAttempted && !bindV4) return "bind-failed"
        if (expectedHandle != null && (boundHandle == null || boundHandle == 0L)) return "unbound"
        if (boundHandle != null && expectedHandle != null &&
            boundHandle != 0L && boundHandle != expectedHandle
        ) {
            return "stale-net"
        }
        if (tx <= 0L) return "no-send"
        if (rx <= 0L) return "no-reply"
        return "no-handshake"
    }
}
