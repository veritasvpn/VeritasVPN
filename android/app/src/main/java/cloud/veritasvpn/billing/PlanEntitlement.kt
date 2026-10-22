package cloud.veritasvpn.billing

import cloud.veritasvpn.api.BillingStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Premium comes from GET /api/v1/billing/status, the same payload the website
 * account page uses. Bitcoin, Play, and trial are all `is_premium`. The Direct
 * build does not ask Google Play.
 */
object PlanEntitlement {
    /**
     * An active Google Play subscription on the Play build. Deleting the
     * account does not cancel it. The direct build has no Play management UI.
     */
    fun activeGooglePlaySubscription(playBilling: Boolean, status: BillingStatus?): Boolean {
        if (!playBilling || status == null) return false
        if (!status.paymentMethod.equals("google_play", ignoreCase = true)) return false
        return grantsPremium(status)
    }

    fun grantsPremium(status: BillingStatus?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (status == null) return false
        if (status.isPremium) return true
        if (!status.tier.equals("premium", ignoreCase = true)) return false
        if (!status.status.equals("active", ignoreCase = true)) return false
        val end = periodEndEpochMs(status.currentPeriodEnd) ?: return false
        return nowMs < end
    }

    /**
     * The home may say "Premium required" only after a settled status for this
     * sign-in says the account is not Premium. No status, a refresh still in
     * flight, and a failed fetch stay unknown.
     */
    fun showPremiumRequired(
        status: BillingStatus?,
        settled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (grantsPremium(status, nowMs)) return false
        return settled && status != null
    }

    /** Checking while the account endpoint has not settled, unless Premium is already known. */
    fun homeBillingReady(
        status: BillingStatus?,
        settled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = grantsPremium(status, nowMs) || (settled && status != null)

    /**
     * RFC3339 from billing-svc, a date, or unix time. Values below 1e12 are
     * seconds. Treating those as milliseconds would put a live expiry in 1970.
     */
    fun periodEndEpochMs(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val numeric = raw.toLongOrNull()
        if (numeric != null) {
            return if (numeric < 1_000_000_000_000L) numeric * 1000 else numeric
        }
        runCatching { return Instant.parse(raw).toEpochMilli() }
        if (raw.length >= 10) {
            runCatching {
                val day = LocalDate.parse(raw.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
                return day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }
        return null
    }
}
