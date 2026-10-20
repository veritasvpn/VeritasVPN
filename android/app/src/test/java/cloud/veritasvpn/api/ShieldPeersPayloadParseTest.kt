package cloud.veritasvpn.api

import cloud.veritasvpn.vpn.ShieldNoticePrefs
import cloud.veritasvpn.vpn.ShieldPolicy
import cloud.veritasvpn.vpn.planShieldNotices
import cloud.veritasvpn.vpn.shieldPollAccepted
import cloud.veritasvpn.vpn.toBlocks
import cloud.veritasvpn.vpn.toNotice
import com.google.gson.FieldNamingStrategy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /api/v1/wg/peers as wg-manager writes it after reading the Redis STRING
 * `wg:dns_notice:<tunnel-ip>`. Release minify renames every Kotlin field.
 * Gson 2.11 keeps only [@SerializedName], which is what a release APK has.
 */
class ShieldPeersPayloadParseTest {

    private val peerId = "11111111-1111-1111-1111-111111111111"

    /**
     * Same shape as handler.putShieldListFields plus the peer identity fields.
     * Counts match a device that had already blocked trackers, ads, and malware.
     */
    private val peersJson = """
        {
          "peers": [
            {
              "id": "other-device",
              "assigned_ip": "10.0.0.2/32",
              "status": "active",
              "shield_notices": []
            },
            {
              "id": "$peerId",
              "assigned_ip": "10.0.0.8/32",
              "status": "active",
              "shield_preset": "aggressive",
              "dns_blocked_count": 110,
              "shield_blocks": {
                "today": {
                  "ads": 30,
                  "adult": 0,
                  "crypto": 0,
                  "malware": 36,
                  "phishing": 0,
                  "scam": 0,
                  "trackers": 44
                },
                "week": {
                  "ads": 30,
                  "adult": 0,
                  "crypto": 0,
                  "malware": 36,
                  "phishing": 0,
                  "scam": 0,
                  "trackers": 44
                }
              },
              "shield_notices": [
                {
                  "id": "0123456789abcdef",
                  "domain": "cheetoclient.com",
                  "category": "malware",
                  "at": 1760961600
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun releaseParseSelectsThisPeerAndPostsTheMalwareNotice() {
        val parsed = releaseGson().fromJson(peersJson, PeerListResponse::class.java)
        val peer = parsed.peers.firstOrNull { it.id == peerId }
        assertNotNull("release shrinking dropped peers or id", peer)
        peer!!
        assertEquals("10.0.0.8/32", peer.assignedIp)
        assertEquals("active", peer.status)
        assertEquals("aggressive", peer.shieldPreset)
        assertEquals(110L, peer.dnsBlockedCount)
        assertEquals(1, peer.shieldNotices.size)
        val notice = peer.shieldNotices[0]
        assertEquals("0123456789abcdef", notice.id)
        assertEquals("cheetoclient.com", notice.domain)
        assertEquals("malware", notice.category)
        assertEquals(1760961600L, notice.at)

        val blocks = peer.shieldBlocks?.toBlocks()
        assertEquals(36L, blocks?.today?.malware)
        assertEquals(44L, blocks?.today?.trackers)
        assertEquals(30L, blocks?.today?.ads)
        assertEquals(36L, blocks?.week?.malware)

        val plan = planShieldNotices(
            incoming = peer.shieldNotices.map { it.toNotice() },
            seen = emptySet(),
            prefs = ShieldNoticePrefs(),
            policy = ShieldPolicy(),
            nowMs = 1_760_961_600_000L,
            lastSentAtMs = 0L,
        )
        assertEquals("Veritas Shield", plan.notification?.title)
        assertEquals("Veritas Shield blocked cheetoclient.com (malware)", plan.notification?.body)
        assertTrue(shieldPollAccepted(plan, posted = true))
    }

    private fun releaseGson() = GsonBuilder()
        .setFieldNamingStrategy(FieldNamingStrategy { field -> "r8_" + field.name })
        .create()
}
