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

interface UserVisibleError {
    val userMessage: String
}

object UserFacingError {
    private const val TAG = "VeritasVPN.ErrorMapper"

    fun toUserMessage(error: Throwable, context: Context): String {
        return when (error) {
            is UserVisibleError -> error.userMessage
            is SocketTimeoutException,
            is InterruptedIOException ->
                context.getString(R.string.error_server_timeout)
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException ->
                context.getString(R.string.error_cannot_reach_server)
            is SSLException ->
                context.getString(R.string.error_secure_connection_failed)
            else -> {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Unhandled error: ${error.javaClass.name}")
                }
                context.getString(R.string.error_generic)
            }
        }
    }
}
