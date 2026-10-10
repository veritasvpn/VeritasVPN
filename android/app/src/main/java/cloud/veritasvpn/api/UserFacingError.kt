package cloud.veritasvpn.api

import android.content.Context
import android.util.Log
import cloud.veritasvpn.R
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object UserFacingError {
    private const val TAG = "VeritasVPN.ErrorMapper"

    fun toUserMessage(error: Throwable, context: Context): String {
        return when (error) {
            is SocketTimeoutException,
            is InterruptedIOException ->
                context.getString(R.string.error_server_timeout)
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException ->
                context.getString(R.string.error_cannot_reach_server)
            is SSLException ->
                context.getString(R.string.error_secure_connection_failed)
            is ApiException ->
                error.serverMessage ?: context.getString(R.string.error_generic)
            is cloud.veritasvpn.auth.AuthRepository.Error ->
                error.message ?: context.getString(R.string.error_generic)
            else -> {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Unhandled error: ${error.javaClass.name}")
                }
                context.getString(R.string.error_generic)
            }
        }
    }
}

class ApiException(
    val serverMessage: String?,
    val httpCode: Int,
) : Exception(serverMessage ?: "HTTP $httpCode")
