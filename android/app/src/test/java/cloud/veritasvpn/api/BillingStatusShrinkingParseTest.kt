package cloud.veritasvpn.api

import com.google.gson.FieldNamingStrategy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release minify is on. R8 renames Kotlin fields, and Gson 2.11 only keeps a
 * JSON name for fields annotated with [com.google.gson.annotations.SerializedName].
 * Unannotated fields are matched by the obfuscated Java name, so a real
 * "payments" array stays null. The account screen shows "This billing service
 * has not sent purchase history yet." only in that case.
 *
 * This parser refuses every source field name. That is the same mismatch a
 * minified build creates. [com.google.gson.annotations.SerializedName] still
 * wins, which is what the release keep rules preserve.
 */
class BillingStatusShrinkingParseTest {

    @Test
    fun shrinkingParseKeepsPurchaseHistoryRows() {
        val status = parseAsRelease(
            """
            {
              "is_premium": true,
              "tier": "premium",
              "status": "active",
              "payments": [
                {
                  "created_at": "2026-03-02T18:04:05Z",
                  "amount_cents": 3000,
                  "currency": "usd",
                  "plan": "annual",
                  "status": "completed",
                  "provider": "google_play"
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(status.isPremium)
        val payments = status.payments
        assertNotNull("release shrinking dropped payments", payments)
        assertEquals(1, payments!!.size)
        val payment = payments[0]
        assertEquals("2026-03-02T18:04:05Z", payment.createdAt)
        assertEquals(3000L, payment.amountCents)
        assertEquals("usd", payment.currency)
        assertEquals("annual", payment.plan)
        assertEquals("completed", payment.status)
        assertEquals("google_play", payment.provider)
    }

    @Test
    fun shrinkingParseKeepsEmptyPurchaseHistory() {
        val status = parseAsRelease(
            """
            {"is_premium": true, "payments": []}
            """.trimIndent()
        )

        assertNotNull("release shrinking dropped an empty payments array", status.payments)
        assertTrue(status.payments!!.isEmpty())
    }

    @Test
    fun shrinkingParseLeavesMissingPurchaseHistoryNull() {
        val status = parseAsRelease("""{"is_premium": true}""")
        assertNull(status.payments)
    }

    private fun parseAsRelease(json: String): BillingStatus {
        val gson = GsonBuilder()
            .setFieldNamingStrategy(releaseFieldRename())
            .create()
        return gson.fromJson(json, BillingStatus::class.java)
    }

    private fun releaseFieldRename(): FieldNamingStrategy =
        FieldNamingStrategy { field -> "r8_" + field.name }
}
