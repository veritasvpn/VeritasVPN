package cloud.veritasvpn.vpn

import android.content.Context
import cloud.veritasvpn.secure.SecurePrefs

/**
 * Client-side tunnel preferences (split tunnel / per-app bypass).
 *
 * When [excludeLan] is enabled, [0.0.0.0/0] in AllowedIPs is replaced with
 * internet-covering prefixes that omit RFC1918 (10/8, 172.16/12, 192.168/16)
 * so local LAN traffic stays off the VPN.
 */
object VpnSettings {
    private const val PREFS = "veritas_vpn_settings"
    private const val KEY_EXCLUDE_LAN = "exclude_lan"
    private const val KEY_BYPASS_APPS = "bypass_apps"
    private const val KEY_CURRENT_PEER_ID = "current_peer_id"
    private const val KEY_DEVICE_ID = "device_id"

    /** Practical AllowedIPs covering the public internet while excluding RFC1918. */
    val EXCLUDE_LAN_ALLOWED_IPS: List<String> = listOf(
        "0.0.0.0/5",
        "8.0.0.0/7",
        "11.0.0.0/8",
        "12.0.0.0/6",
        "16.0.0.0/4",
        "32.0.0.0/3",
        "64.0.0.0/2",
        "128.0.0.0/3",
        "160.0.0.0/5",
        "168.0.0.0/6",
        "172.0.0.0/12",
        "172.32.0.0/11",
        "172.64.0.0/10",
        "172.128.0.0/9",
        "173.0.0.0/8",
        "174.0.0.0/7",
        "176.0.0.0/4",
        "192.0.0.0/9",
        "192.128.0.0/11",
        "192.160.0.0/13",
        "192.169.0.0/16",
        "192.170.0.0/15",
        "192.172.0.0/14",
        "192.176.0.0/12",
        "192.192.0.0/10",
        "193.0.0.0/8",
        "194.0.0.0/7",
        "196.0.0.0/6",
        "200.0.0.0/5",
        "208.0.0.0/4"
    )

    private fun prefs(context: Context) = SecurePrefs.open(context, PREFS)

    fun excludeLan(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXCLUDE_LAN, false)

    fun setExcludeLan(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXCLUDE_LAN, enabled).apply()
    }

    fun bypassApps(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_BYPASS_APPS, "") ?: ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun setBypassApps(context: Context, packages: Collection<String>) {
        val text = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        prefs(context).edit().putString(KEY_BYPASS_APPS, text).apply()
    }

    fun currentPeerId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT_PEER_ID, null)

    fun setCurrentPeerId(context: Context, peerId: String?) {
        prefs(context).edit().apply {
            if (peerId.isNullOrBlank()) remove(KEY_CURRENT_PEER_ID)
            else putString(KEY_CURRENT_PEER_ID, peerId)
        }.apply()
    }

    /** Stable install UUID used as WireGuard peer device_id (multi-device Premium). */
    fun deviceId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    /**
     * If server AllowedIPs contain a default route and exclude-LAN is on,
     * replace only `0.0.0.0/0` (keep other routes / `::/0` as-is).
     */
    fun resolveAllowedIps(context: Context, serverIps: List<String>): List<String> {
        if (!excludeLan(context)) return serverIps
        if (serverIps.none { it == "0.0.0.0/0" }) return serverIps
        return serverIps.filter { it != "0.0.0.0/0" } + EXCLUDE_LAN_ALLOWED_IPS
    }
}
