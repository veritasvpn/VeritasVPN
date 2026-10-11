package cloud.veritasvpn.billing

import android.app.Activity
import android.content.Context
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.CheckoutResponse
import cloud.veritasvpn.auth.AuthenticatedApi
import cloud.veritasvpn.auth.SessionExpiredException

fun createStoreBilling(context: Context, billing: BillingRepository): StoreBilling =
    DirectStoreBilling(context, billing)

/**
 * Website / sideload build. Premium checkout stays on BTCPay, same as before
 * the Play Billing split. This source set is not compiled into the Play AAB.
 */
class DirectStoreBilling(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val billing: BillingRepository,
) : StoreBilling {
    override val usesPlayBilling: Boolean = false

    override fun connect() {}

    override fun close() {}

    override fun purchase(activity: Activity, planId: String, accountId: String, callbacks: StoreBillingCallbacks, trialAllowed: Boolean) {
        try {
            callbacks.onCheckoutUrl(billing.createDirectCheckout(planId))
        } catch (e: SessionExpiredException) {
            callbacks.onSessionExpired()
        } catch (e: Exception) {
            callbacks.onError(e.message ?: "Could not open checkout.")
        }
    }

    override fun restore(accountId: String, callbacks: StoreBillingCallbacks) {}

    override fun manageSubscription(activity: Activity, planId: String?) {}
}

fun BillingRepository.createDirectCheckout(planId: String): String = AuthenticatedApi.execute(
    auth,
    { token ->
        ApiClient.post(
            "/api/v1/billing/subscribe",
            mapOf(
                "tier" to "premium",
                "payment_method" to "btcpay",
                "plan_id" to planId,
                "return_target" to "android",
            ),
            token,
        )
    },
) { response ->
    val data = ApiClient.parse<CheckoutResponse>(response)
    if (!response.isSuccessful) {
        throw BillingRepository.Error(
            data?.error?.takeIf { it.isNotBlank() } ?: "Could not start checkout.",
        )
    }
    data?.checkoutUrl?.takeIf { url ->
        url.startsWith("https://btcpay-mainnet.veritasvpn.cloud/") ||
            url.startsWith("https://btcpay.veritasvpn.cloud/")
    } ?: throw BillingRepository.Error("The server returned an invalid checkout URL.")
}
