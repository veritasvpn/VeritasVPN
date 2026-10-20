package cloud.veritasvpn.api

import cloud.veritasvpn.support.ConnectTiming
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** The process-wide client. Tunnel control calls may pass a different one. */
    fun sharedClient(): OkHttpClient = client
    @PublishedApi
    internal val gson = Gson()

    suspend fun post(
        path: String,
        body: Map<String, Any>,
        token: String? = null,
        retryOnTimeout: Boolean = false,
        onRetry: ((attempt: Int) -> Unit)? = null,
        httpClient: OkHttpClient? = null,
    ): Response {
        val b = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").post(b)
            .header("Content-Type", "application/json")
            .header("X-Veritas-Client", "android")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val base = httpClient ?: client
        val timed = if (ConnectTiming.isArmed()) notingDns(base) else base
        val callTimeout = if (retryOnTimeout) 15L else 20L
        val retryClient = if (retryOnTimeout) {
            timed.newBuilder().callTimeout(callTimeout, TimeUnit.SECONDS).build()
        } else {
            timed
        }
        return executeWithRetry(requestFactory = { builder.build() }, httpClient = retryClient, retryOnTimeout = retryOnTimeout, onRetry = onRetry)
    }

    /**
     * Authentication is interactive. A long retry chain makes a failed sign-up
     * look like a frozen button, so use one bounded attempt and let the person
     * decide when to retry.
     */
    suspend fun postFast(
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
        return fastClient.newCall(builder.build()).await()
    }

    suspend fun patch(path: String, body: Map<String, Any>, token: String): Response {
        val b = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").patch(b)
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = false)
    }

    suspend fun delete(path: String, token: String, httpClient: OkHttpClient? = null): Response {
        val builder = Request.Builder().url("$BASE_URL$path").delete()
            .header("Authorization", "Bearer $token")
        return executeWithRetry(
            requestFactory = { builder.build() },
            httpClient = httpClient ?: client,
            retryOnTimeout = false,
        )
    }

    /** DNS completion for an armed connect. The host is not recorded. */
    private fun notingDns(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .eventListener(object : EventListener() {
                override fun dnsEnd(
                    call: Call,
                    domainName: String,
                    inetAddressList: List<java.net.InetAddress>,
                ) {
                    ConnectTiming.mark("dns")
                }
            })
            .build()

    suspend fun delete(path: String, body: Map<String, Any>, token: String): Response {
        val requestBody = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").delete(requestBody)
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() }, retryOnTimeout = false)
    }

    suspend fun get(path: String, token: String, httpClient: OkHttpClient = client): Response {
        val builder = Request.Builder().url("$BASE_URL$path").get()
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("X-Veritas-Client", "android")
        return executeWithRetry(
            requestFactory = { builder.build() },
            httpClient = httpClient,
            retryOnTimeout = true,
        )
    }

    /**
     * One short read on [httpClient]. Reconnect uses this so a network change
     * does not sit through the general retry budget before it can install the
     * endpoint for the network the phone is on now. [onCall] runs before the
     * call is awaited so a newer network can cancel it.
     */
    suspend fun getOnce(
        path: String,
        token: String,
        httpClient: OkHttpClient,
        timeoutSeconds: Long = 4,
        onCall: ((Call) -> Unit)? = null,
    ): Response {
        val request = Request.Builder().url("$BASE_URL$path").get()
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("X-Veritas-Client", "android")
            .build()
        val fast = httpClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val call = fast.newCall(request)
        onCall?.invoke(call)
        return call.await()
    }

    /**
     * Read-only UI updates must never keep the screen in a loading state behind
     * the general API retry budget.  Billing status is safe to retry manually,
     * so it gets a small, single-attempt deadline instead.
     */
    suspend fun getFast(path: String, token: String, timeoutSeconds: Long = 6): Response {
        val request = Request.Builder().url("$BASE_URL$path").get()
            .header("Authorization", "Bearer $token")
            .build()
        val fastClient = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return fastClient.newCall(request).await()
    }

    suspend fun getText(url: String, timeoutSeconds: Long = 5): String {
        val request = Request.Builder().url(url).get().build()
        val validationClient = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return executeWithRetry({ request }, validationClient, retryOnTimeout = false).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code + " during VPN egress validation")
            }
            response.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Empty VPN egress validation response")
        }
    }

    private suspend fun executeWithRetry(
        requestFactory: () -> Request,
        httpClient: OkHttpClient = client,
        retryOnTimeout: Boolean = false,
        onRetry: ((attempt: Int) -> Unit)? = null
    ): Response = executeWithRetryInternal(requestFactory, httpClient, retryOnTimeout, onRetry)

    internal suspend fun executeWithRetryInternal(
        requestFactory: () -> Request,
        httpClient: OkHttpClient,
        retryOnTimeout: Boolean,
        onRetry: ((attempt: Int) -> Unit)?
    ): Response {
        var lastError: IOException? = null
        val maxRetries = 2
        repeat(maxRetries + 1) { attempt ->
            try {
                return httpClient.newCall(requestFactory()).await()
            } catch (error: IOException) {
                lastError = error
                val shouldRetry = when {
                    error is ConnectException || error is UnknownHostException || error is NoRouteToHostException -> true
                    retryOnTimeout && (error is SocketTimeoutException || error is InterruptedIOException) -> true
                    else -> false
                }
                if (!shouldRetry || attempt == maxRetries) throw error
                onRetry?.invoke(attempt + 1)
                val backoffMs = when (attempt) {
                    0 -> 1000L
                    1 -> 2000L
                    else -> 2000L
                }
                delay(backoffMs)
            }
        }
        throw lastError ?: IOException("Network request failed")
    }

    inline fun <reified T> parse(response: Response): T? {
        val body = response.body?.string() ?: return null
        return try { gson.fromJson(body, T::class.java) } catch (_: Exception) { null }
    }
}

/**
 * Suspend extension for OkHttp Call that supports cancellation.
 * When the coroutine is cancelled, the HTTP call is also cancelled.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) {
                response.close()
                return
            }
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
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
    @SerializedName("tier") val tier: String = "free",
    @SerializedName("status") val status: String = "active",
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
    @SerializedName("trial_eligible") val trialEligible: Boolean = false,
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

/** GET /api/v1/wg/peers/{id}: the endpoint fields for the caller’s current address. */
data class PeerServerView(
    @SerializedName("public_key") val publicKey: String? = null,
)

data class PeerEndpointView(
    @SerializedName("server_endpoint") val serverEndpoint: String? = null,
    @SerializedName("server_endpoint_lan") val serverEndpointLan: String? = null,
    @SerializedName("server_endpoint_wan") val serverEndpointWan: String? = null,
    @SerializedName("stealth_endpoint") val stealthEndpoint: String? = null,
    @SerializedName("stealth_available") val stealthAvailable: Boolean? = null,
    @SerializedName("stealth_path_prefix") val stealthPathPrefix: String? = null,
    @SerializedName("server") val server: PeerServerView? = null,
)

data class PeerListResponse(
    // Release minify renames these fields. Gson 2.11 then ignores the JSON
    // keys, the poller never selects this device, and no block notice is posted.
    @SerializedName("peers") val peers: List<PeerInfo> = emptyList(),
    @SerializedName("error") val error: String? = null
)

data class PeerInfo(
    @SerializedName("id") val id: String = "",
    @SerializedName("account_id") val accountId: String? = null,
    @SerializedName("server_id") val serverId: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("pubkey") val pubkey: String? = null,
    @SerializedName("assigned_ip") val assignedIp: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("shield_preset") val shieldPreset: String = "standard",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("expires_at") val expiresAt: Long? = null,
    @SerializedName("dns_blocked_count") val dnsBlockedCount: Long = 0,
    @SerializedName("shield_blocks") val shieldBlocks: ShieldBlocksPayload? = null,
    @SerializedName("shield_notices") val shieldNotices: List<ShieldNoticePayload> = emptyList(),
)

data class ShieldBlocksPayload(
    @SerializedName("today") val today: ShieldCategoryPayload = ShieldCategoryPayload(),
    @SerializedName("week") val week: ShieldCategoryPayload = ShieldCategoryPayload(),
)

data class ShieldCategoryPayload(
    @SerializedName("malware") val malware: Long = 0,
    @SerializedName("phishing") val phishing: Long = 0,
    @SerializedName("scam") val scam: Long = 0,
    @SerializedName("crypto") val crypto: Long = 0,
    @SerializedName("trackers") val trackers: Long = 0,
    @SerializedName("ads") val ads: Long = 0,
    @SerializedName("adult") val adult: Long = 0,
)

data class ShieldNoticePayload(
    @SerializedName("id") val id: String = "",
    @SerializedName("domain") val domain: String = "",
    @SerializedName("category") val category: String = "",
    @SerializedName("at") val at: Long = 0,
)
