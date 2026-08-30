package cloud.veritasvpn.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.AuthResponse
import org.json.JSONObject

data class User(
    val email: String? = null,
    val accountId: String,
    val isAnonymous: Boolean = false
)

class AuthRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("veritas_auth", Context.MODE_PRIVATE)

    fun getStoredUser(): User? {
        val id = prefs.getString("account_id", null) ?: return null
        return User(
            email = prefs.getString("email", null),
            accountId = id,
            isAnonymous = prefs.getBoolean("is_anonymous", false)
        )
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)
    fun getRefreshToken(): String? = prefs.getString("refresh_token", null)

    fun isAccessTokenExpired(skewSeconds: Long = 30): Boolean {
        val token = getAccessToken() ?: return true
        val parts = token.split('.')
        if (parts.size < 2) return true
        return try {
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
            val exp = JSONObject(payload).optLong("exp", 0L)
            exp == 0L || exp <= (System.currentTimeMillis() / 1000) + skewSeconds
        } catch (_: Exception) {
            true
        }
    }

    /** Returns a non-expired access token, refreshing the session when needed. */
    fun getValidAccessToken(): String? {
        val existing = getAccessToken()?.takeIf { it.isNotBlank() }
        if (existing != null && !isAccessTokenExpired()) return existing
        if (!refreshSession()) return null
        return getAccessToken()?.takeIf { it.isNotBlank() }
    }

    fun requireAccessToken(): String {
        return getValidAccessToken() ?: run {
            signOut()
            throw SessionExpiredException()
        }
    }

    /** Returns false when a stored user was signed out because the session is dead. */
    fun validateSessionOnResume(): Boolean {
        if (getStoredUser() == null) return true
        if (getValidAccessToken() != null) return true
        signOut()
        return false
    }

    private fun persist(user: User, data: AuthResponse) {
        prefs.edit()
            .putString("account_id", data.accountId)
            .putString("email", data.email)
            .putBoolean("is_anonymous", user.isAnonymous)
            .putString("access_token", data.accessToken)
            .putString("refresh_token", data.refreshToken)
            .apply()
    }

    fun signIn(email: String, password: String): User {
        val normalized = email.trim().lowercase()
        val data = ApiClient.post("/api/v1/auth/signin",
            mapOf("email" to normalized, "password" to password)).use { res ->
            if (!res.isSuccessful) {
                val message = extractError(res)
                if (message.contains("Verify your email", ignoreCase = true)) {
                    throw VerificationRequired(normalized)
                }
                throw Error(message)
            }
            ApiClient.parse<AuthResponse>(res)
                ?: throw Error("The server returned an invalid sign-in response.")
        }
        val user = User(email = data.email ?: normalized, accountId = data.accountId)
        persist(user, data)
        return user
    }

    fun signUp(email: String, password: String, turnstileToken: String): User {
        val normalized = email.trim().lowercase()
        val data = ApiClient.post(
            "/api/v1/auth/register",
            mapOf(
                "email" to normalized,
                "password" to password,
                "turnstile_token" to turnstileToken
            )
        ).use { res ->
            if (!res.isSuccessful) {
                val message = extractError(res)
                if (message == "An account with this email already exists.") {
                    throw AccountAlreadyExists(normalized)
                }
                throw Error(message)
            }
            ApiClient.parse<AuthResponse>(res)
                ?: throw Error("The server returned an invalid registration response.")
        }
        if (data.verificationRequired) {
            throw VerificationRequired(normalized)
        }
        val user = User(email = normalized, accountId = data.accountId)
        persist(user, data)
        return user
    }

    fun resendVerification(email: String) {
        ApiClient.post(
            "/api/v1/auth/resend-verification",
            mapOf("email" to email.trim().lowercase())
        ).use { res ->
            if (!res.isSuccessful) throw Error(extractError(res))
        }
    }

    fun signInWithAccountId(accountId: String): User {
        val data = ApiClient.post("/api/v1/auth/signin-account",
            mapOf("account_id" to accountId.trim())).use { res ->
            if (!res.isSuccessful) throw Error(extractError(res))
            ApiClient.parse<AuthResponse>(res)
                ?: throw Error("The server returned an invalid sign-in response.")
        }
        val user = User(accountId = data.accountId, isAnonymous = true)
        persist(user, data)
        return user
    }

    fun registerAnonymous(turnstileToken: String): User {
        val data = ApiClient.post(
            "/api/v1/auth/register-anonymous",
            mapOf("turnstile_token" to turnstileToken)
        ).use { res ->
            if (!res.isSuccessful) throw Error(extractError(res))
            ApiClient.parse<AuthResponse>(res)
                ?: throw Error("The server returned an invalid registration response.")
        }
        val user = User(accountId = data.accountId, isAnonymous = true)
        persist(user, data)
        return user
    }

    fun resetPassword(email: String) {
        val normalized = email.trim().lowercase()
        ApiClient.post("/api/v1/auth/reset-password", mapOf("email" to normalized)).use { res ->
            if (!res.isSuccessful) throw Error(extractError(res))
        }
    }

    fun refreshSession(): Boolean {
        val rt = getRefreshToken() ?: return false
        val res = try {
            ApiClient.post("/api/v1/auth/refresh", mapOf("refresh_token" to rt))
        } catch (_: Exception) { return false }
        val data = res.use {
            if (!it.isSuccessful) return false
            ApiClient.parse<AuthResponse>(it) ?: return false
        }
        prefs.edit().putString("access_token", data.accessToken)
            .putString("refresh_token", data.refreshToken).apply()
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    fun logoutAllSessions() {
        val token = getAccessToken() ?: throw Error("Not signed in.")
        ApiClient.post("/api/v1/auth/logout-all", emptyMap(), token).use { res ->
            if (!res.isSuccessful) throw Error(extractError(res))
        }
        signOut()
    }

    private fun extractError(res: okhttp3.Response): String {
        val err = ApiClient.parse<cloud.veritasvpn.api.AuthError>(res)
        val msg = err?.error?.takeIf { it.isNotBlank() } ?: defaultAuthError(res.code)
        return when {
            msg.contains("email_not_verified", true) || msg.contains("verify your email", true) ->
                "Verify your email before signing in."
            msg.contains("incorrect email or password", true) || msg.contains("invalid email or password", true) ->
                "Incorrect email or password."
            msg.contains("password must be at least", true) -> "Password must be at least 10 characters."
            msg.contains("already exists", true) -> "An account with this email already exists."
            msg.contains("too many", true) -> if (msg.endsWith(".")) msg else "$msg."
            msg.contains("account", true) -> "Account ID not found."
            msg.startsWith("Request failed (") -> "Could not reach the sign-in service. Check your connection and try again."
            else -> msg
        }
    }

    private fun defaultAuthError(code: Int): String = when (code) {
        401 -> "Incorrect email or password."
        403 -> "Verify your email before signing in."
        429 -> "Too many sign-in attempts; try again later."
        else -> "Request failed ($code)"
    }

    class VerificationRequired(val email: String) : Exception(
        "Check $email for a verification link. Verify it before signing in."
    )

    class AccountAlreadyExists(val email: String) : Exception(
        "An account with this email already exists."
    )

    class Error(msg: String) : Exception(msg)
}
