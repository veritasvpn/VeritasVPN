package cloud.veritasvpn.auth

import okhttp3.Response

class SessionExpiredException : Exception("Your session expired. Sign in again.")

object AuthenticatedApi {
    /**
     * Runs [request] with a fresh access token. On 401, refreshes once and retries.
     * Only treats the session as dead when refresh itself fails — a second 401
     * after a successful refresh is returned to the caller (service misconfig,
     * wrong audience, etc.) instead of wiping local credentials.
     */
    suspend fun withAuth(auth: AuthRepository, request: suspend (String) -> Response): Response {
        var token = auth.requireAccessToken()
        var response = request(token)
        if (response.code == 401) {
            response.close()
            // Force the rotation. The rejected token may still be unexpired locally,
            // and a second caller waits on the same lock and reuses the new token.
            if (!auth.refreshSession(force = true)) {
                auth.signOut()
                throw SessionExpiredException()
            }
            token = auth.getAccessToken()?.takeIf { it.isNotBlank() } ?: run {
                auth.signOut()
                throw SessionExpiredException()
            }
            response = request(token)
        }
        return response
    }

    suspend inline fun <T> execute(auth: AuthRepository, noinline block: suspend (String) -> Response, parse: (Response) -> T): T {
        return withAuth(auth, block).use(parse)
    }
}
