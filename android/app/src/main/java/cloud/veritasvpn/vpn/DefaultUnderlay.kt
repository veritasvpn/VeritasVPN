package cloud.veritasvpn.vpn

/**
 * Which non-VPN network the tunnel should follow.
 *
 * The platform best-match wins when it has one. With no best-match, two live
 * transports are not ranked against each other — except the single network
 * that shares a /24 with the known LAN endpoint. That is the home LAN, and
 * the public endpoint cannot hairpin onto it.
 */
object DefaultUnderlay {
    data class Candidate(val id: String, val ipv4s: List<String>)

    fun select(bestMatchId: String?, candidates: List<Candidate>, lanEndpoint: String?): String? {
        val best = bestMatchId?.takeIf { it.isNotBlank() }
        if (best != null) return best
        if (candidates.size == 1) return candidates[0].id
        val host = lanEndpoint
            ?.let { EndpointSelector.host(it) }
            ?.takeIf { EndpointSelector.isRfc1918(it) }
            ?: return null
        val onLan = candidates.filter { candidate ->
            candidate.ipv4s.any { EndpointSelector.sameIpv4Slash24(it, host) }
        }
        return onLan.singleOrNull()?.id
    }
}
