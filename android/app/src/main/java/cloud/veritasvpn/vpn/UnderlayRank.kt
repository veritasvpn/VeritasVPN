package cloud.veritasvpn.vpn

/**
 * Scores a candidate underlay so leftover cellular cannot beat the Wi‑Fi
 * the user just switched to (that skip + pin path froze download at 0B).
 */
object UnderlayRank {

    fun score(
        preferred: Boolean,
        validated: Boolean,
        foreground: Boolean,
        wifiOrEthernet: Boolean,
        cellular: Boolean,
    ): Int {
        var score = 0
        if (preferred) score += 1_000
        if (validated) score += 100
        if (foreground) score += 50
        if (wifiOrEthernet) score += 40
        if (cellular) score -= 15
        return score
    }
}
