package cloud.veritasvpn.billing

import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.api.CheckoutResponse
import cloud.veritasvpn.auth.AuthRepository
import cloud.veritasvpn.auth.AuthenticatedApi

class BillingRepository(private val auth: AuthRepository) {
    fun status(): BillingStatus = AuthenticatedApi.execute(
        auth,
        { token -> ApiClient.getFast("/api/v1/billing/status", token) }
    ) { response ->
        val data = ApiClient.parse<BillingStatus>(response)
        if (!response.isSuccessful) {
            throw billingError(response.code, data?.error, "Could not load your plan.")
        }
        data ?: throw Error("The server returned an invalid plan response.")
    }

    fun createCheckout(paymentMethod: String, planId: String): String = AuthenticatedApi.execute(
        auth,
        { token ->
            ApiClient.post(
                "/api/v1/billing/subscribe",
                mapOf("tier" to "premium", "payment_method" to paymentMethod, "plan_id" to planId),
                token
            )
        }
    ) { response ->
        val data = ApiClient.parse<CheckoutResponse>(response)
        if (!response.isSuccessful) {
            throw billingError(response.code, data?.error, "Could not start checkout.")
        }
        data?.checkoutUrl?.takeIf { it.startsWith("https://") }
            ?: throw Error("The server returned an invalid checkout URL.")
    }

    fun cancel() {
        AuthenticatedApi.execute(
            auth,
            { token -> ApiClient.post("/api/v1/billing/cancel", emptyMap<String, Any>(), token) }
        ) { response ->
            if (!response.isSuccessful) {
                val data = ApiClient.parse<CheckoutResponse>(response)
                throw billingError(response.code, data?.error, "Could not cancel your subscription.")
            }
        }
    }

    private fun billingError(code: Int, serverMessage: String?, fallback: String): Error {
        // Do not force a full sign-out on billing 401. Session expiry is handled
        // by AuthenticatedApi only when refresh fails. Billing auth mismatches
        // should surface as plan-load errors, not kick the user to login.
        return Error(serverMessage?.takeIf { it.isNotBlank() } ?: fallback)
    }

    class Error(message: String) : Exception(message)
}
