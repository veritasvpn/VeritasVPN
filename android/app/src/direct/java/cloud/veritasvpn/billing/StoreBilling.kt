package cloud.veritasvpn.billing

import android.app.Activity
import android.content.Context
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.CheckoutResponse
import cloud.veritasvpn.api.UserFacingError
import cloud.veritasvpn.auth.AuthenticatedApi
import cloud.veritasvpn.auth.SessionExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun connect() {}

    override fun close() {
        scope.cancel()
    }

    override fun purchase(activity: Activity, planId: String, accountId: String, callbacks: StoreBillingCallbacks) {
        scope.launch {
            try {
                val url = billing.createDirectCheckout(planId)
                withContext(Dispatchers.Main) {
                    callbacks.onCheckoutUrl(url)
                }
            } catch (e: SessionExpiredException) {
                withContext(Dispatchers.Main) {
                    callbacks.onSessionExpired()
                }
            } catch (e: Exception) {
                val message = UserFacingError.toUserMessage(e, activity.applicationContext)
                withContext(Dispatchers.Main) {
                    callbacks.onError(message)
                }
            }
        }
    }

    override fun restore(accountId: String, callbacks: StoreBillingCallbacks) {}

    override fun manageSubscription(activity: Activity, planId: String?) {}
}

suspend fun BillingRepository.createDirectCheckout(planId: String): String = AuthenticatedApi.execute(
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
