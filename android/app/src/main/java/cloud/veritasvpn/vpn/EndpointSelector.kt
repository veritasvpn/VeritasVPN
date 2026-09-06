package cloud.veritasvpn.vpn

/**
 * Chooses the live WireGuard endpoint after an underlay change.
 *
 * Production advertises LAN UDP 51820 and WAN UDP 443 as distinct host:port
 * pairs. The connect-time endpoint must not stay frozen when the device
 * leaves or joins the node LAN.
 */
object EndpointSelector {

    fun host(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            return if (end > 1) trimmed.substring(1, end) else trimmed
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return trimmed
        val maybePort = trimmed.substring(colon + 1)
        return if (maybePort.isNotEmpty() && maybePort.all { it.isDigit() }) {
            trimmed.substring(0, colon)
        } else {
            trimmed
        }
    }

    fun sameIpv4Slash24(a: String, b: String): Boolean {
        val left = ipv4Octets(a) ?: return false
        val right = ipv4Octets(b) ?: return false
        return left[0] == right[0] && left[1] == right[1] && left[2] == right[2]
    }

    fun isRfc1918(host: String): Boolean {
        val o = ipv4Octets(host) ?: return false
        return o[0] == 10 ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 172 && o[1] in 16..31)
    }

    fun endpointFromConfig(config: String): String {
        for (line in config.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Endpoint", ignoreCase = true)) continue
            val eq = trimmed.indexOf('=')
            if (eq >= 0) return trimmed.substring(eq + 1).trim()
        }
        return ""
    }

    fun replaceEndpoint(config: String, endpoint: String): String {
        val ep = endpoint.trim()
        if (ep.isEmpty()) return config
        var replaced = false
        val lines = config.split("\n").map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Endpoint", ignoreCase = true) && trimmed.contains('=')) {
                replaced = true
                "Endpoint = $ep"
            } else {
                line
            }
        }
        return if (replaced) lines.joinToString("\n") else config
    }

    /**
     * Ordered endpoints to probe after an underlay change. Same-/24 is only a
     * hint (cafe Wi‑Fi is often `192.168.0.0/24`). Handshake decides which
     * exact API LAN/WAN pair actually works. Never invent a port.
     *
     * On a matching /24 try LAN first then WAN (home return cannot hairpin
     * WAN `:443`). Otherwise try WAN first then LAN (leaving the node LAN).
     */
    fun probeOrder(
        current: String,
        lan: String?,
        wan: String?,
        underlayIpv4s: List<String>,
    ): List<String> {
        val currentEp = current.trim()
        val wanEp = wan?.trim().orEmpty()
        var lanEp = lan?.trim().orEmpty()
        if (lanEp.isEmpty() && isRfc1918(host(currentEp))) {
            lanEp = currentEp
        }
        val lanHost = host(lanEp)
        val onLanHint = lanHost.isNotEmpty() &&
            underlayIpv4s.any { sameIpv4Slash24(it, lanHost) }
        val ordered = if (onLanHint) {
            listOf(lanEp, wanEp, currentEp)
        } else {
            listOf(wanEp, currentEp, lanEp)
        }
        return ordered.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Prefer the exact API LAN endpoint when any underlay IPv4 shares its /24.
     * Otherwise use the exact API WAN endpoint. Never invent a port.
     *
     * Live path-adapt must not call this to pick a single endpoint. Same-/24
     * is not proof of the node LAN; probe LAN and WAN and keep the one that
     * handshakes.
     */
    fun choose(
        current: String,
        lan: String?,
        wan: String?,
        underlayIpv4s: List<String>,
        allowSwitchToLan: Boolean = true,
    ): String {
        val currentEp = current.trim()
        val wanEp = wan?.trim().orEmpty()
        var lanEp = lan?.trim().orEmpty()
        if (lanEp.isEmpty() && isRfc1918(host(currentEp))) {
            lanEp = currentEp
        }
        if (underlayIpv4s.isEmpty()) {
            return currentEp
        }
        val lanHost = host(lanEp)
        val onLan = lanHost.isNotEmpty() &&
            underlayIpv4s.any { sameIpv4Slash24(it, lanHost) }
        val keepLan = lanEp.isNotEmpty() &&
            (currentEp == lanEp || currentEp.isEmpty())
        return when {
            onLan && lanEp.isNotEmpty() && (allowSwitchToLan || keepLan) -> lanEp
            wanEp.isNotEmpty() -> wanEp
            else -> currentEp
        }
    }

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }
}
