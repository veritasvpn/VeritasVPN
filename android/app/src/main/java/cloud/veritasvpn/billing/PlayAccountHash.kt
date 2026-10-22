package cloud.veritasvpn.billing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One-way Play Billing account binding. Google stores this instead of the
 * Account ID. The same domain separator is in billing-svc. It is not a
 * credential and must stay stable: changing it makes new purchases fail to
 * match. Purchases that already stored the raw Account ID still match.
 */
object PlayAccountHash {
    private const val DOMAIN = "veritasvpn-play-account-v1"

    fun obfuscatedAccountId(accountId: String): String {
        val id = accountId.trim()
        require(id.isNotEmpty())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(DOMAIN.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(id.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
        }
        check(hex.length == 64)
        return hex
    }
}
