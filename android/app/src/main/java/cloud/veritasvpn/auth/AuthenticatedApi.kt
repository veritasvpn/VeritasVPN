package cloud.veritasvpn.auth

import okhttp3.Response

class SessionExpiredException : Exception("Your session expired. Sign in again.")

object AuthenticatedApi {
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
            if (response.code == 401) {
                response.close()
                auth.signOut()
                throw SessionExpiredException()
            }
        }
        return response
    }

    inline fun <T> execute(auth: AuthRepository, noinline block: (String) -> Response, parse: (Response) -> T): T {
        return withAuth(auth, block).use(parse)
    }
}
