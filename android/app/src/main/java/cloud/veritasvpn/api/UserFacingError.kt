package cloud.veritasvpn.api

import android.content.Context
import cloud.veritasvpn.R
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object UserFacingError {
    fun toUserMessage(error: Throwable, context: Context): String {
        return when (error) {
            is SocketTimeoutException,
            is java.io.InterruptedIOException ->
                context.getString(R.string.error_server_timeout)
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException ->
                context.getString(R.string.error_cannot_reach_server)
            is SSLException ->
                context.getString(R.string.error_secure_connection_failed)
            is ApiException ->
                error.serverMessage ?: context.getString(R.string.error_generic)
            else -> {
                android.util.Log.w("VeritasVPN", "Unhandled error: ${error.javaClass.simpleName}: ${error.message}")
                context.getString(R.string.error_generic)
            }
        }
    }
}

class ApiException(
    val serverMessage: String?,
    val httpCode: Int,
) : Exception(serverMessage ?: "HTTP $httpCode")
