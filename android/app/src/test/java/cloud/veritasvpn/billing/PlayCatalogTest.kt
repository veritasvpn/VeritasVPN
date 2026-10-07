package cloud.veritasvpn.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayCatalogTest {
    @Test
    fun catalogMatchesExistingPremiumPlans() {
        assertEquals(2, PlayCatalog.all.size)
        assertEquals("premium_monthly", PlayCatalog.monthly.productId)
        assertEquals("monthly", PlayCatalog.monthly.basePlanId)
        assertEquals(300L, PlayCatalog.monthly.priceCents)
        assertEquals(30, PlayCatalog.monthly.periodDays)
        assertEquals("premium_annual", PlayCatalog.annual.productId)
        assertEquals("yearly", PlayCatalog.annual.basePlanId)
        assertEquals(3000L, PlayCatalog.annual.priceCents)
        assertEquals(365, PlayCatalog.annual.periodDays)
        assertEquals(PlayCatalog.monthly, PlayCatalog.byPlanId("premium_monthly"))
        assertEquals(PlayCatalog.annual, PlayCatalog.byProductId("premium_annual"))
    }
}
