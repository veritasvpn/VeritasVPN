package cloud.veritasvpn.vpn

/**
 * Premium Veritas Shield toggles. The client sends these four booleans.
 * The server maps them to DNS categories; this object never carries a category list.
 *
 * Defaults match the connected Standard policy: malicious on, trackers on, ads off, adult off.
 * Block trackers is the trackers category only.
 */
data class ShieldPolicy(
    val blockMalicious: Boolean = true,
    val blockAds: Boolean = false,
    val blockAdult: Boolean = false,
    val blockTrackers: Boolean = true,
) {
    fun wireBody(): Map<String, Any> = mapOf(
        "shield" to mapOf(
            "block_malicious" to blockMalicious,
            "block_ads" to blockAds,
            "block_adult" to blockAdult,
            "block_trackers" to blockTrackers,
        )
    )
}
