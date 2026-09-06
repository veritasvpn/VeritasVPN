package cloud.veritasvpn.vpn

import android.content.Context
import android.provider.Settings

/**
 * Detects Android system Always-on VPN + lockdown ("Block connections without VPN").
 *
 * Third-party apps cannot enable those OS toggles. Connect does not wait on them;
 * while the tunnel is up, all device traffic uses the WireGuard VPN (no in-app off).
 */
object VpnKillSwitch {
    private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    private const val ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"

    fun isLockdownEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        val alwaysOnApp = runCatching {
            Settings.Secure.getString(cr, ALWAYS_ON_VPN_APP)
        }.getOrNull()
        if (alwaysOnApp.isNullOrBlank()) return false
        if (alwaysOnApp != context.packageName) return false

        val lockdown = runCatching {
            Settings.Secure.getInt(cr, ALWAYS_ON_VPN_LOCKDOWN, 0)
        }.getOrDefault(0)
        return lockdown != 0
    }
}
