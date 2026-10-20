package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldNoticesTest {
    private val policy = ShieldPolicy()
    private val prefs = ShieldNoticePrefs()

    @Test
    fun disabledCategoriesAreNotShownAsCounts() {
        val blocks = ShieldBlocks(
            today = ShieldCategoryCounts(malware = 2, ads = 40, adult = 7, trackers = 3),
            week = ShieldCategoryCounts(malware = 5, phishing = 1, ads = 90, trackers = 8),
        )
        val rows = shieldCategoryRows(policy, blocks)
        val malicious = rows.first { it.group == ShieldGroup.MALICIOUS }
        val ads = rows.first { it.group == ShieldGroup.ADS }
        val adult = rows.first { it.group == ShieldGroup.ADULT }
        assertTrue(malicious.enabled)
        assertEquals(2L, malicious.today)
        assertEquals(6L, malicious.week)
        assertTrue(rows.first { it.group == ShieldGroup.TRACKERS }.enabled)
        assertFalse(ads.enabled)
        assertEquals(0L, ads.today)
        assertEquals(0L, ads.week)
        assertFalse(adult.enabled)
        assertEquals(0L, adult.today)
    }

    @Test
    fun noticesFollowTogglesAndDedup() {
        val incoming = listOf(
            ShieldServerNotice("a", "evil.example", "scam", 1),
            ShieldServerNotice("a", "evil.example", "scam", 1),
            ShieldServerNotice("b", "ad.example", "ads", 1),
            ShieldServerNotice("c", "adult.example", "adult", 1),
        )
        val plan = planShieldNotices(incoming, emptySet(), prefs, policy, nowMs = 100_000, lastSentAtMs = 0)
        assertEquals("Veritas Shield blocked evil.example (scam)", plan.notification?.body)
        assertFalse(plan.notification!!.redactOnLockScreen)
        val again = planShieldNotices(incoming, plan.seen, prefs, policy, nowMs = 130_000, lastSentAtMs = plan.lastSentAtMs)
        assertNull(again.notification)
        assertNull(again.banner)
    }

    @Test
    fun adultCopyOmitsTheDomainAndBurstsCollapse() {
        val adult = planShieldNotices(
            listOf(ShieldServerNotice("c", "adult.example", "adult", 1)),
            emptySet(),
            ShieldNoticePrefs(adult = true),
            ShieldPolicy(blockAdult = true),
            nowMs = 50_000,
            lastSentAtMs = 0,
        )
        assertEquals("Veritas Shield blocked an adult site", adult.notification?.body)
        assertTrue(adult.notification!!.redactOnLockScreen)
        assertFalse(adult.notification.body.contains("adult.example"))

        val burst = planShieldNotices(
            listOf(
                ShieldServerNotice("a", "one.example", "malware", 1),
                ShieldServerNotice("b", "two.example", "phishing", 1),
            ),
            emptySet(),
            prefs,
            policy,
            nowMs = 50_000,
            lastSentAtMs = 0,
        )
        assertEquals("Veritas Shield blocked 2 sites", burst.notification?.body)
        assertFalse(burst.notification!!.body.contains("one.example"))
    }

    @Test
    fun gapSuppressesASecondNotificationButKeepsTheBanner() {
        val first = planShieldNotices(
            listOf(ShieldServerNotice("a", "one.example", "malware", 1)),
            emptySet(),
            prefs,
            policy,
            nowMs = 20_000,
            lastSentAtMs = 0,
        )
        val second = planShieldNotices(
            listOf(ShieldServerNotice("b", "two.example", "phishing", 1)),
            first.seen,
            prefs,
            policy,
            nowMs = 25_000,
            lastSentAtMs = first.lastSentAtMs,
        )
        assertNull(second.notification)
        assertEquals("Veritas Shield blocked two.example (phishing)", second.banner)
        assertEquals(first.lastSentAtMs, second.lastSentAtMs)
    }

    @Test
    fun turnedOffFilterDoesNotNotify() {
        val plan = planShieldNotices(
            listOf(ShieldServerNotice("a", "evil.example", "scam", 1)),
            emptySet(),
            prefs,
            ShieldPolicy(blockMalicious = false, blockTrackers = true),
            nowMs = 10_000,
            lastSentAtMs = 0,
        )
        assertNull(plan.notification)
    }

    @Test
    fun failureBackoffGrowsAndCaps() {
        assertEquals(15_000L, ShieldPollPolicy.failureDelayMs(1))
        assertEquals(30_000L, ShieldPollPolicy.failureDelayMs(2))
        assertEquals(60_000L, ShieldPollPolicy.failureDelayMs(3))
        assertEquals(60_000L, ShieldPollPolicy.failureDelayMs(9))
    }

    @Test
    fun wireBodyStaysTheFourToggles() {
        val body = ShieldPolicy(blockMalicious = false, blockAds = true, blockAdult = true, blockTrackers = false).wireBody()
        val shield = body["shield"] as Map<*, *>
        assertEquals(setOf("block_malicious", "block_ads", "block_adult", "block_trackers"), shield.keys)
    }
}
