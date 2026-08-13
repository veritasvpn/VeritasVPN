package cloud.veritasvpn.billing

import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.api.CheckoutResponse

class BillingRepository {
    fun status(token: String): BillingStatus = ApiClient.get("/api/v1/billing/status", token).use { response ->
        val data = ApiClient.parse<BillingStatus>(response)
        if (!response.isSuccessful) throw Error(data?.error ?: "Could not load your plan.")
        data ?: throw Error("The server returned an invalid plan response.")
    }

    fun createCheckout(token: String, paymentMethod: String): String = ApiClient.post(
        "/api/v1/billing/subscribe",
        mapOf("tier" to "premium", "payment_method" to paymentMethod),
        token
    ).use { response ->
        val data = ApiClient.parse<CheckoutResponse>(response)
        if (!response.isSuccessful) throw Error(data?.error ?: "Could not start checkout.")
        data?.checkoutUrl?.takeIf { it.startsWith("https://") }
            ?: throw Error("The server returned an invalid checkout URL.")
    }

    fun cancel(token: String) {
        ApiClient.post("/api/v1/billing/cancel", emptyMap<String, Any>(), token).use { response ->
            if (!response.isSuccessful) {
                val data = ApiClient.parse<CheckoutResponse>(response)
                throw Error(data?.error ?: "Could not cancel your subscription.")
            }
        }
    }

    class Error(message: String) : Exception(message)
}
