package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShieldPolicyTest {
    @Test
    fun defaultsMatchStandardThreatCoverage() {
        val policy = ShieldPolicy()
        assertEquals(true, policy.blockMalicious)
        assertEquals(false, policy.blockAds)
        assertEquals(false, policy.blockAdult)
    }

    @Test
    fun wireBodySendsTogglesNotCategories() {
        val body = ShieldPolicy(blockMalicious = false, blockAds = true, blockAdult = true).wireBody()
        @Suppress("UNCHECKED_CAST")
        val shield = body["shield"] as Map<String, Any>
        assertEquals(false, shield["block_malicious"])
        assertEquals(true, shield["block_ads"])
        assertEquals(true, shield["block_adult"])
        assertFalse(shield.containsKey("categories"))
        assertEquals(setOf("shield"), body.keys)
    }
}