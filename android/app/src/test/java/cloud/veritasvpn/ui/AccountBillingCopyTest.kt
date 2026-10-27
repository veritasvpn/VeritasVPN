package cloud.veritasvpn.ui

import cloud.veritasvpn.api.PurchaseHistoryItem
import cloud.veritasvpn.auth.emailToPersist
import cloud.veritasvpn.billing.PlanEntitlement
import cloud.veritasvpn.api.BillingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBillingCopyTest {
    @Test
    fun typedEmailIsStoredWhenTheResponseDropsIt() {
        assertEquals("owner@example.com", emailToPersist(null, "owner@example.com"))
        assertEquals("owner@example.com", emailToPersist("  ", "owner@example.com"))
        assertEquals("from-server@example.com", emailToPersist("from-server@example.com", "typed@example.com"))
        assertNull(emailToPersist("  ", " "))
    }

    @Test
    fun purchaseHistoryLabelsTrialRenewalAndRefund() {
        assertEquals(
            "Monthly · Free trial · Google Play",
            purchaseHistoryDetail(PurchaseHistoryItem(amountCents = 0, currency = "usd", plan = "monthly", provider = "google_play", isTrial = true, status = "completed")),
        )
        assertEquals(
            "Monthly · 20000 PYG · Google Play",
            purchaseHistoryDetail(PurchaseHistoryItem(amountCents = 2_000_000, currency = "pyg", plan = "monthly", provider = "google_play", status = "completed")),
        )
        assertEquals("Refunded", purchaseStatusLabel("refunded"))
        assertEquals("Confirmed", purchaseStatusLabel("completed"))
    }

    @Test
    fun pausedCopyNamesTheResumeDateAndDoesNotLookLikeAnError() {
        assertEquals("Paused, resumes on Nov 15, 2026.", pausedSubscriptionCopy("2026-11-15T00:00:00Z"))
        assertEquals("Paused. Resume it in Google Play.", pausedSubscriptionCopy(null))
        assertEquals("Paused. Resume it in Google Play.", pausedSubscriptionCopy("  "))
        val copy = pausedSubscriptionCopy("2026-11-15T00:00:00Z")
        assertFalse(copy.contains("error", ignoreCase = true))
        assertFalse(copy.contains("timeout", ignoreCase = true))
    }

    @Test
    fun pausedPlaySubscriptionIsNotPremiumAndCanStillBeManaged() {
        val paused = BillingStatus(
            tier = "premium",
            status = "past_due",
            paymentMethod = "google_play",
            isPremium = false,
            accessState = "paused",
            resumesAt = "2026-11-15T00:00:00Z",
        )
        assertFalse(PlanEntitlement.grantsPremium(paused))
        assertTrue(PlanEntitlement.googlePlaySubscriptionToManage(playBilling = true, paused))
        assertFalse(PlanEntitlement.googlePlaySubscriptionToManage(playBilling = false, paused))
    }
}
