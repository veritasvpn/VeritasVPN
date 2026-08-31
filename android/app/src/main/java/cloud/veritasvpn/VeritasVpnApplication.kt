package cloud.veritasvpn

import android.app.Application
import cloud.veritasvpn.secure.SecurePrefs
import cloud.veritasvpn.vpn.VeritasVpnService

/**
 * Warms encrypted prefs (and migrates any leftover plaintext) at process start so
 * [cloud.veritasvpn.vpn.VeritasVpnService] Always-on restores do not race migration.
 */
class VeritasVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SecurePrefs.open(this, "veritas_auth")
        SecurePrefs.open(this, VeritasVpnService.PREFS_NAME)
        SecurePrefs.open(this, "veritas_vpn_settings")
        SecurePrefs.open(this, "veritas_billing_cache")
        SecurePrefs.open(this, "veritasvpn_permissions")
    }
}
