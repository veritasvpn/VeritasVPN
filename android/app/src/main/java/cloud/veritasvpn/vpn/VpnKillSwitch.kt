package cloud.veritasvpn.vpn

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android system Always-on VPN + lockdown ("Block connections without VPN").
 *
 * Third-party apps cannot flip these switches. VeritasVPN asks for VPN consent
 * first so this package is registered in the system VPN list, then treats both
 * switches as mandatory before the tunnel starts. There is no in-app off toggle.
 *
 * Secure setting names are not always in the public SDK stubs, so we use the
 * platform string keys (same approach as WireGuard Android).
 */
object VpnKillSwitch {
    private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    private const val ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"

    /**
     * Connect steps in required order.
     *
     * [VpnConsent] is `VpnService.prepare`. It runs before [Lockdown] so Android
     * registers this package and can show the allow dialog. Otherwise a fresh
     * install never appears under Settings → VPN — on HyperOS that list only
     * contains apps already granted VPN consent, so another VPN such as
     * Tailscale is the only row and Always-on cannot be set for VeritasVPN.
     * [Lockdown] stays mandatory before [Tunnel]. There is no skip.
     */
    enum class ConnectGate {
        VpnConsent,
        Lockdown,
        Tunnel,
    }

    /**
     * Next Connect step. Missing VPN consent wins even if lockdown settings
     * already look enabled, so the allow dialog is not skipped.
     */
    fun nextConnectGate(vpnPrepared: Boolean, lockdownEnabled: Boolean): ConnectGate {
        if (!vpnPrepared) return ConnectGate.VpnConsent
        if (!lockdownEnabled) return ConnectGate.Lockdown
        return ConnectGate.Tunnel
    }

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
