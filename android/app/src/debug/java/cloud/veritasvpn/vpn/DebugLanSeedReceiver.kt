package cloud.veritasvpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.veritasvpn.BuildConfig
import cloud.veritasvpn.secure.SecurePrefs

/**
 * Debug-only seed for the stored LAN endpoint. Release builds do not compile
 * this receiver. It exists so an emulator can reproduce the home-Wi-Fi
 * same-/24 case without a server that advertises a private address.
 *
 * The value is not logged.
 */
class DebugLanSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != ACTION) return
        val endpoint = parse(intent.getStringExtra(EXTRA_LAN)) ?: return
        SecurePrefs.open(context, VeritasVpnService.PREFS_NAME)
            .edit()
            .putString(VeritasVpnService.KEY_ENDPOINT_LAN, endpoint)
            .commit()
    }

    companion object {
        const val ACTION = "cloud.veritasvpn.DEBUG_SEED_LAN"
        const val EXTRA_LAN = "lan"

        /** host:port, port 1–65535. Anything else is ignored. */
        fun parse(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.length - 1) return null
            val host = value.substring(0, colon).trim()
            val portText = value.substring(colon + 1).trim()
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            if (host.contains("://") || host.startsWith("[")) return null
            val port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return "$host:$port"
        }
    }
}
