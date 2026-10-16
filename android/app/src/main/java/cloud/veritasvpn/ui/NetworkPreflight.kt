package cloud.veritasvpn.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cloud.veritasvpn.R
import cloud.veritasvpn.vpn.VpnKillSwitch

object NetworkPreflight {
    sealed class Result {
        object Ok : Result()
        data class Blocked(val message: String, val openVpnSettings: Boolean = false) : Result()
    }

    fun classifyFailure(context: Context, error: Throwable): String {
        val lockdownOn = VpnKillSwitch.isLockdownEnabled(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tunnelUp = isVpnTunnelUp(cm)
        if (lockdownOn && !tunnelUp) {
            return context.getString(R.string.error_lockdown_blocking)
        }

        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!hasInternet) {
            return context.getString(R.string.error_offline)
        }

        return cloud.veritasvpn.api.UserFacingError.toUserMessage(error, context)
    }

    fun checkLockdownOnly(context: Context): Result {
        val lockdownOn = VpnKillSwitch.isLockdownEnabled(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tunnelUp = isVpnTunnelUp(cm)
        if (lockdownOn && !tunnelUp) {
            return Result.Blocked(
                context.getString(R.string.error_lockdown_blocking),
                openVpnSettings = true
            )
        }
        return Result.Ok
    }

    private fun isVpnTunnelUp(cm: ConnectivityManager?): Boolean {
        val active = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
