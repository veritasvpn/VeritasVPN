package cloud.veritasvpn.vpn

/**
 * Scores a candidate underlay. Foreground (the network the user is on) must
 * beat leftover transports: leftover Wi‑Fi must not beat cellular on A→B,
 * and leftover cell must not beat Wi‑Fi on B→A.
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
        if (foreground) score += 200
        if (validated) score += 100
        if (wifiOrEthernet) score += 20
        if (cellular) score -= 5
        return score
    }
}
