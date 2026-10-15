package cloud.veritasvpn.billing

import cloud.veritasvpn.api.BillingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class PlanEntitlementTest {
    private val oct15 = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
    private val oct29 = "2026-10-29T00:00:00Z"

    @Test
    fun webPaidPremiumIsPremiumInTheDirectClient() {
        val web = BillingStatus(
            tier = "premium",
            status = "active",
            paymentMethod = "btcpay",
            currentPeriodEnd = oct29,
            isPremium = true,
        )
        assertTrue(PlanEntitlement.grantsPremium(web, oct15))
        assertFalse(PlanEntitlement.showPremiumRequired(web, settled = true, nowMs = oct15))
        assertTrue(PlanEntitlement.homeBillingReady(web, settled = false, nowMs = oct15))

        val play = web.copy(paymentMethod = "google_play")
        assertTrue(PlanEntitlement.grantsPremium(play, oct15))
        val trial = web.copy(paymentMethod = "none", trialEligible = true)
        assertTrue(PlanEntitlement.grantsPremium(trial, oct15))
    }

    @Test
    fun tierAndFutureExpiryStillGrantPremiumWhenTheBooleanIsMissing() {
        val status = BillingStatus(
            tier = "premium",
            status = "active",
            paymentMethod = "btcpay",
            currentPeriodEnd = oct29,
            isPremium = false,
        )
        assertTrue(PlanEntitlement.grantsPremium(status, oct15))
        val seconds = Instant.parse(oct29).epochSecond.toString()
        assertTrue(
            PlanEntitlement.grantsPremium(
                status.copy(currentPeriodEnd = seconds),
                oct15,
            ),
        )
        val millis = Instant.parse(oct29).toEpochMilli().toString()
        assertTrue(
            PlanEntitlement.grantsPremium(
                status.copy(currentPeriodEnd = millis),
                oct15,
            ),
        )
        assertTrue(
            PlanEntitlement.grantsPremium(
                status.copy(currentPeriodEnd = "2026-10-29"),
                oct15,
            ),
        )
    }

    @Test
    fun expiredOrFreeIsNotPremium() {
        val expired = BillingStatus(
            tier = "premium",
            status = "active",
            paymentMethod = "btcpay",
            currentPeriodEnd = "2026-10-01T00:00:00Z",
            isPremium = false,
        )
        assertFalse(PlanEntitlement.grantsPremium(expired, oct15))
        val free = BillingStatus(tier = "free", status = "active", isPremium = false)
        assertFalse(PlanEntitlement.grantsPremium(free, oct15))
        assertFalse(PlanEntitlement.grantsPremium(null, oct15))
    }

    @Test
    fun signInDoesNotShowPremiumRequiredBeforeTheServerAnswers() {
        val staleFree = BillingStatus(tier = "free", isPremium = false, paymentMethod = "none")
        assertFalse(PlanEntitlement.showPremiumRequired(staleFree, settled = false, nowMs = oct15))
        assertFalse(PlanEntitlement.homeBillingReady(staleFree, settled = false, nowMs = oct15))
        assertFalse(PlanEntitlement.showPremiumRequired(null, settled = true, nowMs = oct15))
        assertFalse(PlanEntitlement.homeBillingReady(null, settled = true, nowMs = oct15))
        assertTrue(PlanEntitlement.showPremiumRequired(staleFree, settled = true, nowMs = oct15))
        assertTrue(PlanEntitlement.homeBillingReady(staleFree, settled = true, nowMs = oct15))
    }

    @Test
    fun signInRefreshesBillingAndResumeRefreshesAgain() {
        val source = kotlinSource("MainActivity.kt")
        val refresh = slice(source, "fun refreshBilling", "fun storeCallbacks")
        assertFalse(refresh.contains("BillingStatus()"))
        assertTrue(refresh.contains("billingSettled = true"))

        val signIn = slice(source, "fun finishAuthentication()", "if (user == null || reauthenticate)")
        assertTrue(signIn.contains("billingSettled = false"))
        assertTrue(signIn.contains("billingStatus = null"))

        val load = slice(source, "LaunchedEffect(user?.accountId)", "LaunchedEffect(observedBillingReturnVersion")
        assertTrue(load.contains("refreshBilling()"))
        assertTrue(load.contains("grantsPremium"))

        val resume = slice(source, "Lifecycle.Event.ON_RESUME", "val splitTunnelDirty")
        assertTrue(resume.contains("refreshBilling()"))

        val connect = slice(source, "fun requestConnect()", "userWantsConnected = true")
        assertTrue(connect.contains("grantsPremium"))
        assertFalse(connect.contains("PLAY_BILLING"))
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("$start not found", from >= 0 && to > from)
        return source.substring(from, to)
    }

    private fun kotlinSource(name: String): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("android/app/src/main/java"),
        )
        val file = roots.firstNotNullOfOrNull { root ->
            root.walk().firstOrNull { it.isFile && it.name == name }
        } ?: error("$name not found from ${File(".").absolutePath}")
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
    }
}
