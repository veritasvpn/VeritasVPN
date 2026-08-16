package cloud.veritasvpn.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

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
                .build()
            chain.proceed(req)
        }
        .build()
    @PublishedApi
    internal val gson = Gson()

    fun post(path: String, body: Map<String, Any>, token: String? = null): Response {
        val b = gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder().url("$BASE_URL$path").post(b)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return executeWithRetry(requestFactory = { builder.build() })
    }

    fun delete(path: String, token: String): Response {
        val builder = Request.Builder().url("$BASE_URL$path").delete()
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() })
    }

    fun get(path: String, token: String): Response {
        val builder = Request.Builder().url("$BASE_URL$path").get()
            .header("Authorization", "Bearer $token")
        return executeWithRetry(requestFactory = { builder.build() })
    }

    fun getText(url: String, timeoutSeconds: Long = 5): String {
        val request = Request.Builder().url(url).get().build()
        val validationClient = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return executeWithRetry({ request }, validationClient).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code + " during VPN egress validation")
            }
            response.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Empty VPN egress validation response")
        }
    }

    private fun executeWithRetry(
        requestFactory: () -> Request,
        httpClient: OkHttpClient = client
    ): Response {
        var lastError: IOException? = null
        repeat(4) { attempt ->
            try {
                return httpClient.newCall(requestFactory()).execute()
            } catch (error: IOException) {
                lastError = error
                if (!isTransientNetworkError(error) || attempt == 3) throw error
                try {
                    Thread.sleep(250L shl attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
        throw lastError ?: IOException("Network request failed")
    }

    private fun isTransientNetworkError(error: IOException): Boolean =
        error is UnknownHostException ||
            error is NoRouteToHostException ||
            error is ConnectException

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

data class BillingStatus(
    val tier: String = "free",
    val status: String = "active",
    @SerializedName("payment_method") val paymentMethod: String = "none",
    @SerializedName("current_period_end") val currentPeriodEnd: String? = null,
    @SerializedName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    @SerializedName("is_premium") val isPremium: Boolean = false,
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
    @SerializedName("assigned_ip") val assignedIp: String,
    @SerializedName("dns_server") val dnsServer: String?,
    @SerializedName("preshared_key") val presharedKey: String?,
    @SerializedName("client_allowed_ips") val clientAllowedIps: List<String>?,
    @SerializedName("allowed_ips") val allowedIps: List<String>?,
    val error: String? = null
)
