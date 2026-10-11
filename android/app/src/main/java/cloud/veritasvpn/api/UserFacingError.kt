package cloud.veritasvpn.api

import android.content.Context
import androidx.annotation.StringRes
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

    @StringRes
    fun messageRes(error: Throwable): Int? {
        return when (error) {
            is UserVisibleError -> null
            is SocketTimeoutException,
            is InterruptedIOException -> R.string.error_server_timeout
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException -> R.string.error_cannot_reach_server
            is SSLException -> R.string.error_secure_connection_failed
            else -> R.string.error_generic
        }
    }

    fun toUserMessage(error: Throwable, context: Context): String {
        if (error is UserVisibleError) {
            return error.userMessage
        }
        val resId = messageRes(error) ?: return error.message ?: context.getString(R.string.error_generic)
        return context.getString(resId)
    }
}
