package cloud.veritasvpn.api

import com.google.gson.FieldNamingStrategy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Play release minifies field names. Gson 2.11 keeps only @SerializedName,
 * which is why a signed-in email disappeared from the Account screen.
 */
class AuthEmailShrinkingParseTest {
    @Test
    fun shrinkingParseKeepsSignInEmail() {
        val auth = parse(AuthResponse::class.java, """{"account_id":"acc-1","email":"owner@example.com","access_token":"a","refresh_token":"r"}""")
        assertEquals("owner@example.com", auth.email)
        assertEquals("acc-1", auth.accountId)
    }

    @Test
    fun shrinkingParseKeepsProfileEmail() {
        val profile = parse(AccountProfile::class.java, """{"account_id":"acc-1","email":"owner@example.com"}""")
        assertEquals("owner@example.com", profile.email)
        val anonymous = parse(AccountProfile::class.java, """{"account_id":"acc-2","email":null}""")
        assertNull(anonymous.email)
    }

    @Test
    fun shrinkingParseKeepsPausedStatusAndTrialRow() {
        val status = parse(
            BillingStatus::class.java,
            """
            {
              "is_premium": false,
              "tier": "premium",
              "status": "past_due",
              "payment_method": "google_play",
              "access_state": "paused",
              "resumes_at": "2026-11-15T00:00:00Z",
              "payments": [{
                "created_at": "2026-10-26T12:00:00Z",
                "amount_cents": 0,
                "currency": "usd",
                "plan": "monthly",
                "status": "completed",
                "provider": "google_play",
                "is_trial": true
              }]
            }
            """.trimIndent(),
        )
        assertEquals("paused", status.accessState)
        assertEquals("2026-11-15T00:00:00Z", status.resumesAt)
        assertEquals(true, status.payments?.single()?.isTrial)
    }

    private fun <T> parse(type: Class<T>, json: String): T {
        val gson = GsonBuilder()
            .setFieldNamingStrategy(FieldNamingStrategy { field -> "r8_" + field.name })
            .create()
        return gson.fromJson(json, type)
    }
}
