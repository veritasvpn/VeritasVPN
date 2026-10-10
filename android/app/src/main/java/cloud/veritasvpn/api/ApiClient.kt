package cloud.veritasvpn.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// No certificate pinning here yet. Pinning would stop a user-installed or
// enterprise MITM CA from reading bearer tokens, but a pin that outlives its
// certificate bricks the API for every installed copy of the app until users
// update, and there is no cert rotation runbook with backup pins to make that
// safe. Deliberately deferred 2026-09 until that runbook exists; until then the
// app relies on the system trust store. See CertificatePinner when revisiting.
object ApiClient {
    private const val BASE_URL = "https://api.veritasvpn.cloud"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Content-Type", "application/json")
                .header("X-Veritas-Client", "android")
                .build()
            chain.proceed(req)
        }
        .build()
    @PublishedApi
    internal val gson = Gson()

    fun post(
        path: String,
        body: Map<String, Any>,
        token: String? = null,
        retryOnTimeout: Boolean = false
    ): Response {
        val b = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").post(b)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = retryOnTimeout)
    }

    /**
     * Authentication is interactive. A long retry chain makes a failed sign-up
     * look like a frozen button, so use one bounded attempt and let the person
     * decide when to retry.
     */
    fun postFast(
        path: String,
        body: Map<String, Any>,
        token: String? = null,
        timeoutSeconds: Long = 10,
    ): Response {
        val requestBody = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").post(requestBody)
        token?.let { builder.header("Authorization", "Bearer $it") }
        val fastClient = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return fastClient.newCall(builder.build()).execute()
    }

    fun patch(path: String, body: Map<String, Any>, token: String): Response {
        val b = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").patch(b)
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = false)
    }

    fun delete(path: String, token: String): Response {
        val builder = Request.Builder().url("$BASE_URL$path").delete()
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = false)
    }

    fun delete(path: String, body: Map<String, Any>, token: String): Response {
        val requestBody = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").delete(requestBody)
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = false)
    }

    fun get(path: String, token: String): Response {
        val builder = Request.Builder().url("$BASE_URL$path").get()
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = true)
    }

    /**
     * Read-only UI updates must never keep the screen in a loading state behind
     * the general API retry budget.  Billing status is safe to retry manually,
     * so it gets a small, single-attempt deadline instead.
     */
    fun getFast(path: String, token: String, timeoutSeconds: Long = 6): Response {
        val request = Request.Builder().url("$BASE_URL$path").get()
            .header("Authorization", "Bearer $token")
            .build()
        val fastClient = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return fastClient.newCall(request).execute()
    }

    fun getText(url: String, timeoutSeconds: Long = 5): String {
        val request = Request.Builder().url(url).get().build()
        val validationClient = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return executeWithRetry({ request }, validationClient, retryOnTimeout = true).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code + " during VPN egress validation")
            }
            response.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Empty VPN egress validation response")
        }
    }

    private fun executeWithRetry(
        requestFactory: () -> Request,
        httpClient: OkHttpClient = client,
        retryOnTimeout: Boolean = false
    ): Response {
        var lastError: IOException? = null
        val maxRetries = 2
        repeat(maxRetries + 1) { attempt ->
            try {
                return httpClient.newCall(requestFactory()).execute()
            } catch (error: IOException) {
                lastError = error
                val shouldRetry = when {
                    error is ConnectException || error is UnknownHostException || error is NoRouteToHostException -> true
                    retryOnTimeout && (error is SocketTimeoutException || error is InterruptedIOException) -> true
                    else -> false
                }
                if (!shouldRetry || attempt == maxRetries) throw error
                val backoffMs = when (attempt) {
                    0 -> 1000L
                    1 -> 2000L
                    else -> 2000L
                }
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
        throw lastError ?: IOException("Network request failed")
    }

    inline fun <reified T> parse(response: Response): T? {
        val body = response.body?.string() ?: return null
        return try { gson.fromJson(body, T::class.java) } catch (_: Exception) { null }
    }
}

data class AuthResponse(
    @SerializedName("access_token") val accessToken: String = "",
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("account_id") val accountId: String = "",
    @SerializedName("expires_at") val expiresAt: Long = 0,
    val email: String? = null,
    @SerializedName("verification_required") val verificationRequired: Boolean = false,
    val message: String? = null
)

data class AuthError(val error: String)

data class PurchaseHistoryItem(
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("amount_cents") val amountCents: Long = 0,
    // Same JSON names as the properties. Release R8 still renames the fields,
    // and Gson only keeps a name that is written on @SerializedName.
    @SerializedName("currency") val currency: String? = null,
    @SerializedName("plan") val plan: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("provider") val provider: String? = null
)

data class BillingStatus(
    val tier: String = "free",
    val status: String = "active",
    @SerializedName("payment_method") val paymentMethod: String = "none",
    @SerializedName("plan_id") val planId: String? = null,
    @SerializedName("current_period_end") val currentPeriodEnd: String? = null,
    @SerializedName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    @SerializedName("is_premium") val isPremium: Boolean = false,
    @SerializedName("payment_state") val paymentState: String = "none",
    @SerializedName("payment_message") val paymentMessage: String? = null,
    @SerializedName("poll_after_seconds") val pollAfterSeconds: Int = 0,
    // The status payload's key is "payments". Without @SerializedName, R8
    // renames the field and a real array stays null, which the account screen
    // shows as purchase history that was never sent.
    @SerializedName("payments") val payments: List<PurchaseHistoryItem>? = null,
    val error: String? = null
)

data class CheckoutResponse(
    @SerializedName("checkout_url") val checkoutUrl: String? = null,
    val error: String? = null
)

data class PeerResponse(
    @SerializedName("peer_id") val peerId: String,
    @SerializedName("server_public_key") val serverPublicKey: String,
    @SerializedName("server_endpoint") val serverEndpoint: String,
    @SerializedName("server_endpoint_lan") val serverEndpointLan: String? = null,
    @SerializedName("server_endpoint_wan") val serverEndpointWan: String? = null,
    @SerializedName("stealth_endpoint") val stealthEndpoint: String? = null,
    @SerializedName("stealth_available") val stealthAvailable: Boolean = false,
    @SerializedName("stealth_path_prefix") val stealthPathPrefix: String? = null,
    @SerializedName("assigned_ip") val assignedIp: String,
    @SerializedName("dns_server") val dnsServer: String?,
    @SerializedName("preshared_key") val presharedKey: String?,
    @SerializedName("client_allowed_ips") val clientAllowedIps: List<String>?,
    @SerializedName("allowed_ips") val allowedIps: List<String>?,
    val error: String? = null
)

data class PeerListResponse(
    val peers: List<PeerInfo> = emptyList(),
    val error: String? = null
)

data class PeerInfo(
    val id: String = "",
    @SerializedName("account_id") val accountId: String? = null,
    @SerializedName("server_id") val serverId: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    val pubkey: String? = null,
    @SerializedName("assigned_ip") val assignedIp: String = "",
    val status: String = "",
    @SerializedName("shield_preset") val shieldPreset: String = "standard",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("expires_at") val expiresAt: Long? = null,
    @SerializedName("dns_blocked_count") val dnsBlockedCount: Long = 0
)
