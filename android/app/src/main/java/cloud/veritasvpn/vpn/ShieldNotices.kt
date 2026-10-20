package cloud.veritasvpn.vpn

import cloud.veritasvpn.api.ShieldBlocksPayload
import cloud.veritasvpn.api.ShieldCategoryPayload
import cloud.veritasvpn.api.ShieldNoticePayload

/**
 * Pure Veritas Shield notice and counter rules. No Android APIs, so the
 * service and the UI share one decision and tests can run on the JVM.
 *
 * Free accounts cannot create a peer. Shield runs only on a Premium tunnel.
 * The default policy blocks malicious sites and trackers. Ads and adult sites
 * stay off until a Premium user turns them on. Counts for a category that is
 * off are not shown as protection.
 */
data class ShieldCategoryCounts(
    val malware: Long = 0,
    val phishing: Long = 0,
    val scam: Long = 0,
    val crypto: Long = 0,
    val trackers: Long = 0,
    val ads: Long = 0,
    val adult: Long = 0,
) {
    fun maliciousTotal(): Long = malware + phishing + scam + crypto
}

data class ShieldBlocks(
    val today: ShieldCategoryCounts = ShieldCategoryCounts(),
    val week: ShieldCategoryCounts = ShieldCategoryCounts(),
)

data class ShieldServerNotice(
    val id: String = "",
    val domain: String = "",
    val category: String = "",
    val at: Long = 0,
)

data class ShieldNoticePrefs(
    val malicious: Boolean = true,
    val trackers: Boolean = false,
    val ads: Boolean = false,
    val adult: Boolean = false,
)

enum class ShieldGroup(val label: String) {
    MALICIOUS("Malicious"),
    TRACKERS("Trackers"),
    ADS("Ads"),
    ADULT("Adult"),
}

data class ShieldCategoryRow(
    val group: ShieldGroup,
    val enabled: Boolean,
    val today: Long,
    val week: Long,
)

data class PreparedShieldNotice(
    val title: String,
    val body: String,
    val redactOnLockScreen: Boolean,
)

data class ShieldNoticePlan(
    val notification: PreparedShieldNotice?,
    val banner: String?,
    val seen: Set<String>,
    val lastSentAtMs: Long,
)

object ShieldPollPolicy {
    const val INTERVAL_MS = 5_000L
    const val RECONNECT_SLICE_MS = 500L
    const val NOTICE_GAP_MS = 20_000L

    /** Failures back off from 15s and cap at 60s. A 401 does not use this. */
    fun failureDelayMs(failures: Int): Long {
        if (failures <= 1) return 15_000L
        val steps = (failures - 1).coerceAtMost(2)
        return 15_000L shl steps
    }
}

fun shieldGroup(category: String): ShieldGroup? = when (category) {
    "malware", "phishing", "scam", "crypto" -> ShieldGroup.MALICIOUS
    "trackers" -> ShieldGroup.TRACKERS
    "ads" -> ShieldGroup.ADS
    "adult" -> ShieldGroup.ADULT
    else -> null
}

fun groupEnabled(policy: ShieldPolicy, group: ShieldGroup): Boolean = when (group) {
    ShieldGroup.MALICIOUS -> policy.blockMalicious
    ShieldGroup.TRACKERS -> policy.blockTrackers
    ShieldGroup.ADS -> policy.blockAds
    ShieldGroup.ADULT -> policy.blockAdult
}

fun noticePrefEnabled(prefs: ShieldNoticePrefs, group: ShieldGroup): Boolean = when (group) {
    ShieldGroup.MALICIOUS -> prefs.malicious
    ShieldGroup.TRACKERS -> prefs.trackers
    ShieldGroup.ADS -> prefs.ads
    ShieldGroup.ADULT -> prefs.adult
}

fun categoryToday(blocks: ShieldBlocks?, group: ShieldGroup): Long {
    val today = blocks?.today ?: return 0
    return when (group) {
        ShieldGroup.MALICIOUS -> today.maliciousTotal()
        ShieldGroup.TRACKERS -> today.trackers
        ShieldGroup.ADS -> today.ads
        ShieldGroup.ADULT -> today.adult
    }
}

fun categoryWeek(blocks: ShieldBlocks?, group: ShieldGroup): Long {
    val week = blocks?.week ?: return 0
    return when (group) {
        ShieldGroup.MALICIOUS -> week.maliciousTotal()
        ShieldGroup.TRACKERS -> week.trackers
        ShieldGroup.ADS -> week.ads
        ShieldGroup.ADULT -> week.adult
    }
}

/** Rows for the Shield screen. Disabled categories stay visible as Off, with no count. */
fun shieldCategoryRows(policy: ShieldPolicy, blocks: ShieldBlocks?): List<ShieldCategoryRow> =
    ShieldGroup.entries.map { group ->
        val enabled = groupEnabled(policy, group)
        ShieldCategoryRow(
            group = group,
            enabled = enabled,
            today = if (enabled) categoryToday(blocks, group) else 0,
            week = if (enabled) categoryWeek(blocks, group) else 0,
        )
    }

fun categoryLabel(category: String): String = when (category) {
    "malware" -> "malware"
    "phishing" -> "phishing"
    "scam" -> "scam"
    "crypto" -> "cryptomining"
    "trackers" -> "trackers"
    "ads" -> "ads"
    "adult" -> "adult"
    else -> ""
}

fun planShieldNotices(
    incoming: List<ShieldServerNotice>,
    seen: Set<String>,
    prefs: ShieldNoticePrefs,
    policy: ShieldPolicy,
    nowMs: Long,
    lastSentAtMs: Long,
): ShieldNoticePlan {
    val fresh = incoming.filter { notice ->
        val group = shieldGroup(notice.category) ?: return@filter false
        notice.id.isNotBlank() &&
            notice.id !in seen &&
            groupEnabled(policy, group) &&
            noticePrefEnabled(prefs, group)
    }.distinctBy { it.id }
    val nextSeen = LinkedHashSet(seen)
    fresh.forEach { nextSeen.add(it.id) }
    while (nextSeen.size > 64) {
        val first = nextSeen.first()
        nextSeen.remove(first)
    }
    if (fresh.isEmpty()) {
        return ShieldNoticePlan(null, null, nextSeen, lastSentAtMs)
    }
    val banner = noticeSentence(fresh)
    val gapOpen = nowMs - lastSentAtMs >= ShieldPollPolicy.NOTICE_GAP_MS
    val notification = if (gapOpen) {
        PreparedShieldNotice(
            title = "Veritas Shield",
            body = banner,
            redactOnLockScreen = fresh.any { it.category == "adult" },
        )
    } else {
        null
    }
    return ShieldNoticePlan(
        notification = notification,
        banner = banner,
        seen = nextSeen,
        lastSentAtMs = if (notification != null) nowMs else lastSentAtMs,
    )
}

fun ShieldCategoryPayload.toCounts() = ShieldCategoryCounts(
    malware = malware,
    phishing = phishing,
    scam = scam,
    crypto = crypto,
    trackers = trackers,
    ads = ads,
    adult = adult,
)

fun ShieldBlocksPayload.toBlocks() = ShieldBlocks(today = today.toCounts(), week = week.toCounts())

fun ShieldNoticePayload.toNotice() = ShieldServerNotice(id = id, domain = domain, category = category, at = at)

fun noticeSentence(notices: List<ShieldServerNotice>): String {
    if (notices.isEmpty()) return ""
    if (notices.size > 1 || notices.any { it.category == "adult" && notices.size > 1 }) {
        if (notices.size > 1) return "Veritas Shield blocked ${notices.size} sites"
    }
    val one = notices.first()
    if (one.category == "adult") return "Veritas Shield blocked an adult site"
    val label = categoryLabel(one.category)
    val domain = one.domain.trim().trimEnd('.').take(253)
    if (domain.isEmpty() || label.isEmpty()) return "Veritas Shield blocked a site"
    return "Veritas Shield blocked $domain ($label)"
}
