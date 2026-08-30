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
    fun withAuth(auth: AuthRepository, request: (String) -> Response): Response {
        var token = auth.requireAccessToken()
        var response = request(token)
        if (response.code == 401) {
            response.close()
            if (!auth.refreshSession()) {
                auth.signOut()
                throw SessionExpiredException()
            }
            token = auth.requireAccessToken()
            response = request(token)
        }
        return response
    }

    inline fun <T> execute(auth: AuthRepository, noinline block: (String) -> Response, parse: (Response) -> T): T {
        return withAuth(auth, block).use(parse)
    }
}
