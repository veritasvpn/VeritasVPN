package cloud.veritasvpn.vpn

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android system Always-on VPN + lockdown ("Block connections without VPN").
 *
 * Third-party apps cannot flip these switches. VeritasVPN treats both as
 * mandatory before Connect and never exposes an in-app off toggle.
 *
 * Secure setting names are not always in the public SDK stubs, so we use the
 * platform string keys (same approach as WireGuard Android).
 */
object VpnKillSwitch {
    private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    private const val ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"

    /**
     * True only when [packageName] is the Always-on VPN app and lockdown is on.
     * A missing app, a different VPN app, or lockdown value 0 all fail closed.
     */
    fun lockdownSatisfied(alwaysOnApp: String?, packageName: String, lockdownValue: Int): Boolean {
        if (alwaysOnApp.isNullOrBlank()) return false
        if (alwaysOnApp != packageName) return false
        return lockdownValue != 0
    }

    fun isLockdownEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        val alwaysOnApp = runCatching {
            Settings.Secure.getString(cr, ALWAYS_ON_VPN_APP)
        }.getOrNull()
        val lockdown = runCatching {
            Settings.Secure.getInt(cr, ALWAYS_ON_VPN_LOCKDOWN, 0)
        }.getOrDefault(0)
        return lockdownSatisfied(alwaysOnApp, context.packageName, lockdown)
    }

    fun systemVpnSettingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS)
}
