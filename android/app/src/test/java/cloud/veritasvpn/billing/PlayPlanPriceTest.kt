package cloud.veritasvpn.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPlanPriceTest {
    private val trialPhase = StorePricePhase(
        basePlanId = "monthly",
        offerId = "free-trial-7d",
        formattedPrice = "Free",
        priceAmountMicros = 0,
        billingPeriod = "P7D",
        infiniteRecurring = false,
    )
    private val trialPaidPhase = StorePricePhase(
        basePlanId = "monthly",
        offerId = "free-trial-7d",
        formattedPrice = "PYG 18,000",
        priceAmountMicros = 18_000_000_000,
        billingPeriod = "P1M",
        infiniteRecurring = true,
    )
    private val basePhase = StorePricePhase(
        basePlanId = "monthly",
        offerId = null,
        formattedPrice = "PYG 20,000",
        priceAmountMicros = 20_000_000_000,
        billingPeriod = "P1M",
        infiniteRecurring = true,
    )

    @Test
    fun regularPriceIsTheBasePlanInfinitePhase() {
        val price = regularBasePlanPrice(
            "premium_monthly",
            "monthly",
            listOf(trialPhase, trialPaidPhase, basePhase),
        )
        assertEquals("PYG 20,000", price?.formattedPrice)
        assertEquals("P1M", price?.billingPeriod)
        assertEquals("PYG 20,000 /month", playPlanPriceLabel(price!!))
    }

    @Test
    fun fallsBackToPaidPhaseWhenTheBaseOfferIsMissing() {
        val price = regularBasePlanPrice(
            "premium_annual",
            "yearly",
            listOf(
                StorePricePhase("yearly", "intro", "PYG 1", 1, "P1Y", infiniteRecurring = false),
                StorePricePhase("yearly", "intro", "PYG 180,000", 180_000_000_000, "P1Y", infiniteRecurring = true),
            ),
        )
        assertEquals("PYG 180,000", price?.formattedPrice)
        assertEquals("PYG 180,000 /year", playPlanPriceLabel(price!!))
    }

    @Test
    fun missingCatalogReturnsNull() {
        assertNull(regularBasePlanPrice("premium_monthly", "monthly", emptyList()))
        assertNull(
            regularBasePlanPrice(
                "premium_monthly",
                "monthly",
                listOf(trialPhase),
            ),
        )
    }

    @Test
    fun playBuildDoesNotInventUsdWhileDirectKeepsIt() {
        assertEquals(
            "$3 / 30 days",
            planChoiceDetail(playBilling = false, pricesLoaded = true, price = null, directDetail = "$3 / 30 days"),
        )
        assertEquals(
            "$30 / 365 days",
            planChoiceDetail(playBilling = false, pricesLoaded = false, price = null, directDetail = "$30 / 365 days"),
        )
        assertEquals(
            "Loading price…",
            planChoiceDetail(playBilling = true, pricesLoaded = false, price = null, directDetail = "$3 / 30 days"),
        )
        assertEquals(
            "",
            planChoiceDetail(playBilling = true, pricesLoaded = true, price = null, directDetail = "$3 / 30 days"),
        )
        assertEquals(
            "PYG 20,000 /month",
            planChoiceDetail(playBilling = true, pricesLoaded = true, price = PlayPlanPrice("premium_monthly", "PYG 20,000", "P1M"), directDetail = "$3 / 30 days"),
        )

        val loading = shownPlanPrice(true, pricesLoaded = false, price = null, directPrice = "$3", directSuffix = "/month")
        assertTrue(loading.loading)
        assertNull(loading.price)
        val hidden = shownPlanPrice(true, pricesLoaded = true, price = null, directPrice = "$3", directSuffix = "/month")
        assertFalse(hidden.loading)
        assertNull(hidden.price)
        val direct = shownPlanPrice(false, pricesLoaded = false, price = null, directPrice = "$30", directSuffix = "/year")
        assertEquals("$30", direct.price)
        assertEquals("/year", direct.suffix)
        assertFalse(direct.loading)
    }

    @Test
    fun catalogQueryWaitsHidesOrLoadsWithoutAnError() {
        assertEquals(PlayCatalogRequest.WAIT, playCatalogRequest(hasPlans = true, clientReady = false, setupGaveUp = false))
        assertEquals(PlayCatalogRequest.LOAD, playCatalogRequest(hasPlans = true, clientReady = true, setupGaveUp = false))
        assertEquals(PlayCatalogRequest.HIDE, playCatalogRequest(hasPlans = true, clientReady = false, setupGaveUp = true))
        assertEquals(PlayCatalogRequest.LOAD, playCatalogRequest(hasPlans = true, clientReady = true, setupGaveUp = true))
        assertEquals(PlayCatalogRequest.HIDE, playCatalogRequest(hasPlans = false, clientReady = true, setupGaveUp = false))
        val hidden = shownPlanPrice(true, pricesLoaded = true, price = null, directPrice = "$3", directSuffix = "/month")
        assertFalse(hidden.loading)
        assertNull(hidden.price)

        assertFalse(shouldStartPlayBillingConnection(closed = false, ready = false, inProgress = true))
        assertFalse(shouldStartPlayBillingConnection(closed = false, ready = true, inProgress = false))
        assertFalse(shouldStartPlayBillingConnection(closed = true, ready = false, inProgress = false))
        assertTrue(shouldStartPlayBillingConnection(closed = false, ready = false, inProgress = false))

        assertTrue(shouldArmPlayCatalogTimeout(hasPlans = true, clientReady = false))
        assertFalse(shouldArmPlayCatalogTimeout(hasPlans = true, clientReady = true))
        assertFalse(shouldArmPlayCatalogTimeout(hasPlans = false, clientReady = false))
        assertEquals(PLAY_BILLING_RECONNECT_BACKOFF_MS, playReconnectBackoffMs(alreadyScheduled = false))
        assertNull(playReconnectBackoffMs(alreadyScheduled = true))
    }

    @Test
    fun purchaseVerifyErrorsDoNotShowRawServerText() {
        val raw = "purchase_token is required and the product id is invalid"
        val message = playPurchaseErrorMessage(raw)
        assertEquals("Couldn't verify this Google Play purchase.", message)
        assertFalse(message.contains("purchase_token"))
        assertFalse(message.contains("invalid", ignoreCase = true))
        assertFalse(message.contains("required", ignoreCase = true))
        assertEquals("Couldn't verify this Google Play purchase.", playPurchaseErrorMessage("  "))
        assertEquals("Couldn't verify this Google Play purchase.", playPurchaseErrorMessage(null))
        assertEquals(
            "Google Play is still processing this subscription.",
            playPurchaseErrorMessage("Google Play is still processing this subscription."),
        )
    }
}
