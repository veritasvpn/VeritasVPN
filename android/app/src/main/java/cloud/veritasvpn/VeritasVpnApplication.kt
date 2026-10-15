package cloud.veritasvpn

import android.app.Application
import android.os.StrictMode
import cloud.veritasvpn.secure.SecurePrefs
import cloud.veritasvpn.support.DiagnosticLog
import cloud.veritasvpn.vpn.VeritasVpnService

/**
 * Warms encrypted prefs (and migrates any leftover plaintext) at process start so
 * [cloud.veritasvpn.vpn.VeritasVpnService] Always-on restores do not race migration.
 */
class VeritasVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Log network I/O on the main thread. Do not kill the process:
            // a debug session should show the violation, not hide the next one.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        DiagnosticLog.install(this)
        DiagnosticLog.recordExitReasons(this)
        SecurePrefs.open(this, "veritas_auth")
        SecurePrefs.open(this, VeritasVpnService.PREFS_NAME)
        SecurePrefs.open(this, "veritas_vpn_settings")
        SecurePrefs.open(this, "veritas_billing_cache")
        SecurePrefs.open(this, "veritasvpn_permissions")
    }
}
