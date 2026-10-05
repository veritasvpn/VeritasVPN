package cloud.veritasvpn.vpn

/**
 * Premium Veritas Shield toggles. The client sends these three booleans.
 * The server maps them to DNS categories; this object never carries a category list.
 *
 * Defaults match the connected Standard policy: malicious on, ads off, adult off.
 */
data class ShieldPolicy(
    val blockMalicious: Boolean = true,
    val blockAds: Boolean = false,
    val blockAdult: Boolean = false,
) {
    fun wireBody(): Map<String, Any> = mapOf(
        "shield" to mapOf(
            "block_malicious" to blockMalicious,
            "block_ads" to blockAds,
            "block_adult" to blockAdult,
        )
    )
}
