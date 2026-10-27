package cloud.veritasvpn.billing

import cloud.veritasvpn.api.BillingStatus

/**
 * Purchase history on the account screen goes stale when a Play renewal is
 * recorded server-side (RTDN). The device is not told. The screen refetches
 * when it opens and about once a minute while it is visible in the foreground.
 * A failed read keeps the rows already shown.
 */
object AccountBillingRefresh {
    const val POLL_INTERVAL_MS = 60_000L

    fun shouldPoll(accountVisible: Boolean, appInForeground: Boolean, signedIn: Boolean): Boolean =
        accountVisible && appInForeground && signedIn

    /**
     * An account that already has a status stays on screen during the read.
     * The first read, with nothing to show, may use the progress indicator.
     */
    fun silentOnOpen(hasStatus: Boolean): Boolean = hasStatus

    data class Shown(
        val status: BillingStatus?,
        val error: String?,
        val historyFailed: Boolean,
        val refreshing: Boolean,
    )

    /**
     * [loaded] null means the read failed. [failureMessage] is shown only for
     * a first load that has nothing on screen, and only after [visibleFailure]
     * drops raw exception and timeout text. A silent read, or any read that
     * already has a status, leaves that status in place.
     */
    fun apply(
        current: Shown,
        loaded: BillingStatus?,
        failureMessage: String?,
        silent: Boolean,
        showIndicator: Boolean,
    ): Shown {
        if (loaded != null) {
            return current.copy(
                status = loaded,
                error = null,
                historyFailed = false,
                refreshing = if (showIndicator) false else current.refreshing,
            )
        }
        val hadStatus = current.status != null
        val hadPayments = current.status?.payments != null
        val reportError = !silent && !hadStatus
        val reportHistoryFailure = !silent && !hadPayments
        val safeError = visibleFailure(failureMessage)
        return current.copy(
            error = if (reportError) safeError ?: current.error else current.error,
            historyFailed = if (reportHistoryFailure) true else current.historyFailed,
            refreshing = if (showIndicator) false else current.refreshing,
        )
    }

    /** Mapped copy may be shown. Exception text and timeouts may not. */
    fun visibleFailure(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = text.lowercase()
        val raw = lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("exception") ||
            lower.contains("java.") ||
            lower.contains("kotlin.")
        return if (raw) null else text
    }
}
