package cloud.veritasvpn.billing

import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.api.PurchaseHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountBillingRefreshTest {
    private val originalPayment = PurchaseHistoryItem(
        createdAt = "2026-09-01T00:00:00Z",
        amountCents = 0,
        currency = "usd",
        plan = "monthly",
        provider = "google_play",
        isTrial = true,
        status = "completed",
    )
    private val renewalPayment = PurchaseHistoryItem(
        createdAt = "2026-10-01T00:00:00Z",
        amountCents = 300,
        currency = "usd",
        plan = "monthly",
        provider = "google_play",
        status = "completed",
    )
    private val original = BillingStatus(
        tier = "premium",
        status = "active",
        paymentMethod = "google_play",
        isPremium = true,
        payments = listOf(originalPayment),
    )
    private val renewed = original.copy(payments = listOf(renewalPayment, originalPayment))
    private val onScreen = AccountBillingRefresh.Shown(
        status = original,
        error = null,
        historyFailed = false,
        refreshing = false,
    )

    @Test
    fun pollRunsOnceAMinuteOnlyWhileAccountIsOpenInTheForeground() {
        assertEquals(60_000L, AccountBillingRefresh.POLL_INTERVAL_MS)
        assertTrue(AccountBillingRefresh.shouldPoll(accountVisible = true, appInForeground = true, signedIn = true))
        assertFalse(AccountBillingRefresh.shouldPoll(accountVisible = false, appInForeground = true, signedIn = true))
        assertFalse(AccountBillingRefresh.shouldPoll(accountVisible = true, appInForeground = false, signedIn = true))
        assertFalse(AccountBillingRefresh.shouldPoll(accountVisible = true, appInForeground = true, signedIn = false))
    }

    @Test
    fun openingKeepsAKnownPlanOnScreen() {
        assertTrue(AccountBillingRefresh.silentOnOpen(hasStatus = true))
        assertFalse(AccountBillingRefresh.silentOnOpen(hasStatus = false))
    }

    @Test
    fun successfulReadShowsTheServerHistoryNewestFirst() {
        val shown = AccountBillingRefresh.apply(
            current = onScreen,
            loaded = renewed,
            failureMessage = null,
            silent = true,
            showIndicator = false,
        )
        val payments = shown.status?.payments
        assertEquals(listOf(renewalPayment, originalPayment), payments)
        assertNull(shown.error)
        assertFalse(shown.historyFailed)
        assertFalse(shown.refreshing)
    }

    @Test
    fun failedBackgroundReadKeepsTheLastHistoryAndDropsRawErrors() {
        val raw = "java.net.SocketTimeoutException: timeout"
        val shown = AccountBillingRefresh.apply(
            current = onScreen,
            loaded = null,
            failureMessage = raw,
            silent = true,
            showIndicator = false,
        )
        assertSame(original, shown.status)
        assertEquals(listOf(originalPayment), shown.status?.payments)
        assertNull(shown.error)
        assertFalse(shown.historyFailed)
        assertFalse(shown.refreshing)
        assertFalse(shown.error.orEmpty().contains("timeout", ignoreCase = true))
        assertFalse(shown.error.orEmpty().contains("Exception"))
    }

    @Test
    fun failedReadWithAPlanAlreadyShowingDoesNotReplaceIt() {
        val shown = AccountBillingRefresh.apply(
            current = onScreen,
            loaded = null,
            failureMessage = "Timed out waiting for 8000 ms",
            silent = false,
            showIndicator = true,
        )
        assertSame(original, shown.status)
        assertNull(shown.error)
        assertFalse(shown.historyFailed)
        assertFalse(shown.refreshing)
    }

    @Test
    fun firstLoadCanShowAMappedMessageButNotARawTimeout() {
        val empty = AccountBillingRefresh.Shown(
            status = null,
            error = null,
            historyFailed = false,
            refreshing = true,
        )
        val friendly = "VeritasVPN's server didn't respond in time. Check your internet connection and try again."
        val mapped = AccountBillingRefresh.apply(
            current = empty,
            loaded = null,
            failureMessage = friendly,
            silent = false,
            showIndicator = true,
        )
        assertNull(mapped.status)
        assertEquals(friendly, mapped.error)
        assertTrue(mapped.historyFailed)
        assertFalse(mapped.refreshing)

        val raw = AccountBillingRefresh.apply(
            current = empty,
            loaded = null,
            failureMessage = "java.net.SocketTimeoutException: connect timed out",
            silent = false,
            showIndicator = true,
        )
        assertNull(raw.status)
        assertNull(raw.error)
        assertTrue(raw.historyFailed)
        assertFalse(raw.error.orEmpty().contains("timeout", ignoreCase = true))
    }

    @Test
    fun silentFailureDoesNotAnnounceATimeoutWhenNothingIsLoadedYet() {
        val empty = AccountBillingRefresh.Shown(
            status = null,
            error = null,
            historyFailed = false,
            refreshing = false,
        )
        val shown = AccountBillingRefresh.apply(
            current = empty,
            loaded = null,
            failureMessage = "timeout",
            silent = true,
            showIndicator = false,
        )
        assertNull(shown.status)
        assertNull(shown.error)
        assertFalse(shown.historyFailed)
    }

    @Test
    fun visibleFailureDropsExceptionTextAndKeepsMappedCopy() {
        assertNull(AccountBillingRefresh.visibleFailure(null))
        assertNull(AccountBillingRefresh.visibleFailure("  "))
        assertNull(AccountBillingRefresh.visibleFailure("timeout"))
        assertNull(AccountBillingRefresh.visibleFailure("TIMEOUT"))
        assertNull(AccountBillingRefresh.visibleFailure("Timed out waiting for 8000 ms"))
        assertNull(AccountBillingRefresh.visibleFailure("java.net.SocketTimeoutException: timeout"))
        assertNull(AccountBillingRefresh.visibleFailure("kotlin.coroutines.cancellation.CancellationException"))
        assertEquals(
            "Something went wrong. Please try again.",
            AccountBillingRefresh.visibleFailure("Something went wrong. Please try again."),
        )
        assertEquals(
            "Could not load your plan.",
            AccountBillingRefresh.visibleFailure("Could not load your plan."),
        )
    }

    @Test
    fun accountScreenRefetchesOnOpenAndPollsOnlyWhileItIsInFront() {
        val source = kotlinSource("MainActivity.kt")
        val plans = slice(source, "onPlans = {", "onStealthSettings")
        assertTrue(plans.contains("showPlans = true"))
        assertFalse(plans.contains("billingStatus == null"))

        val upgrade = slice(source, "onUpgrade = {", "onBack = { showShieldSettings = false }")
        assertTrue(upgrade.contains("showPlans = true"))
        assertFalse(upgrade.contains("billingStatus == null"))

        assertTrue(source.contains("refreshBilling(silent = AccountBillingRefresh.silentOnOpen(billingStatus != null))"))
        val open = slice(
            source,
            "refreshBilling(silent = AccountBillingRefresh.silentOnOpen(billingStatus != null))",
            "LaunchedEffect(showPlans, accountForeground, user?.accountId)",
        )
        assertFalse(open.contains("billingStatus = null"))

        val poll = slice(
            source,
            "LaunchedEffect(showPlans, accountForeground, user?.accountId)",
            "val splitTunnelDirty",
        )
        assertTrue(poll.contains("AccountBillingRefresh.shouldPoll"))
        assertTrue(poll.contains("accountVisible = showPlans"))
        assertTrue(poll.contains("appInForeground = accountForeground"))
        assertTrue(poll.contains("signedIn = user != null"))
        assertTrue(poll.contains("delay(AccountBillingRefresh.POLL_INTERVAL_MS)"))
        assertTrue(poll.contains("refreshBilling(silent = true)"))
        assertTrue(poll.indexOf("delay(AccountBillingRefresh.POLL_INTERVAL_MS)") < poll.indexOf("refreshBilling(silent = true)"))
        assertFalse(poll.contains("delay(500)"))

        assertTrue(source.contains("if (event == Lifecycle.Event.ON_START) accountForeground = true"))
        assertTrue(source.contains("if (event == Lifecycle.Event.ON_STOP) accountForeground = false"))

        val refresh = slice(source, "fun refreshBilling", "fun storeCallbacks")
        assertTrue(refresh.contains("AccountBillingRefresh.apply"))
        assertTrue(refresh.contains("generation != billingRefreshGeneration[0]"))
        assertTrue(refresh.contains("billingSettled = true"))
        assertTrue(refresh.contains("UserFacingError.toUserMessage"))
        assertFalse(refresh.contains("e.message"))
        assertFalse(refresh.contains("e.toString()"))
        assertFalse(refresh.contains("BillingStatus()"))
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
