package cloud.veritasvpn.vpn

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.util.Log

/**
 * Android system Always-on VPN + lockdown ("Block connections without VPN").
 *
 * Third-party apps cannot flip these switches. VeritasVPN asks for VPN consent
 * first so this package is registered in the system VPN list, then treats both
 * switches as mandatory before the tunnel starts. There is no in-app off toggle.
 * Stopping the VPN service does not clear them. After an intentional disconnect,
 * if they are still on, Android keeps blocking traffic with no tunnel. The app
 * can only open system VPN settings so the user can turn both switches off.
 *
 * The HyperOS VPN list header (the master VPN switch) is not part of this check.
 * Per-app Always-on and Block connections without VPN are.
 *
 * Secure setting names are not always in the public SDK stubs, so we use the
 * platform string keys (same approach as WireGuard Android).
 */
object VpnKillSwitch {
    private const val TAG = "VeritasVPN"
    private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    private const val ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"

    private enum class SettingsTable(val uri: Uri) {
        Secure(Settings.Secure.CONTENT_URI),
        Global(Settings.Global.CONTENT_URI),
        System(Settings.System.CONTENT_URI),
    }

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
     * True when lockdown is on for [packageName].
     *
     * A readable Always-on package must be this app. Android 12+ and HyperOS
     * hide `always_on_vpn_app` from release apps (SecurityException, or a null
     * that does not identify any package). Lockdown stays readable and is 1
     * only while some VPN is always-on with Block connections without VPN.
     * Another app's always-on cannot leave this package prepared, so a hidden
     * package plus lockdown plus [vpnPrepared] is this app. A named other
     * package, lockdown 0, or a hidden package when this app is not prepared
     * all fail closed.
     */
    fun lockdownSatisfied(
        alwaysOnApp: String?,
        packageName: String,
        lockdownValue: Int,
        vpnPrepared: Boolean = false,
    ): Boolean {
        if (lockdownValue == 0) return false
        val normalized = normalizeAlwaysOnPackage(alwaysOnApp)
        if (normalized != null) return normalized == packageName
        return vpnPrepared
    }

    /**
     * Package token from an Always-on setting. Accepts a bare package, a
     * `package/class` component, and `package:user` or `user:package` forms
     * some OEM builds store. Blank input is unknown, not a package name.
     */
    fun normalizeAlwaysOnPackage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val head = raw.trim().substringBefore('/').trim()
        if (head.isEmpty()) return null
        val tokens = head.split(':', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return tokens.firstOrNull { token -> token.contains('.') }
    }

    /**
     * Lockdown flag from a settings string. AOSP stores `1` / `0`. Some OEM
     * builds store `true` / `on`, which [Settings.Secure.getInt] treats as 0.
     */
    fun parseLockdown(raw: String?): Int {
        val value = raw?.trim()?.lowercase() ?: return 0
        if (value.isEmpty()) return 0
        if (value == "1" || value == "true" || value == "yes" || value == "on" || value == "enabled") {
            return 1
        }
        if (value == "0" || value == "false" || value == "no" || value == "off" || value == "disabled") {
            return 0
        }
        val parsed = value.toIntOrNull() ?: return 0
        return if (parsed != 0) 1 else 0
    }

    /** First non-blank Always-on package across Secure, then Global, then System. */
    fun firstAlwaysOnPackage(vararg raws: String?): String? {
        for (raw in raws) {
            val normalized = normalizeAlwaysOnPackage(raw)
            if (normalized != null) return normalized
        }
        return null
    }

    /**
     * Secure is authoritative when present, including an explicit off.
     * Global/System are used only when Secure has no value (hidden or unset).
     */
    fun resolveLockdownValue(secureRaw: String?, globalRaw: String?, systemRaw: String?): Int {
        if (secureRaw != null) return parseLockdown(secureRaw)
        val global = parseLockdown(globalRaw)
        if (global != 0) return global
        return parseLockdown(systemRaw)
    }

    fun isLockdownEnabled(context: Context, vpnPrepared: Boolean? = null): Boolean {
        val resolver = context.contentResolver
        val alwaysOnApp = firstAlwaysOnPackage(
            readSetting(resolver, SettingsTable.Secure, ALWAYS_ON_VPN_APP),
            readSetting(resolver, SettingsTable.Global, ALWAYS_ON_VPN_APP),
            readSetting(resolver, SettingsTable.System, ALWAYS_ON_VPN_APP),
        )
        val secureLockdown = readSetting(resolver, SettingsTable.Secure, ALWAYS_ON_VPN_LOCKDOWN)
        val lockdown = if (secureLockdown != null) {
            parseLockdown(secureLockdown)
        } else {
            resolveLockdownValue(
                null,
                readSetting(resolver, SettingsTable.Global, ALWAYS_ON_VPN_LOCKDOWN),
                readSetting(resolver, SettingsTable.System, ALWAYS_ON_VPN_LOCKDOWN),
            )
        }
        val prepared = resolvePrepared(context, alwaysOnApp, lockdown, vpnPrepared)
        val satisfied = lockdownSatisfied(alwaysOnApp, context.packageName, lockdown, prepared)
        if (!satisfied) {
            Log.i(
                TAG,
                "Always-on lockdown not detected: lockdown=$lockdown " +
                    "packageKnown=${alwaysOnApp != null} " +
                    "packageMatches=${alwaysOnApp == context.packageName} prepared=$prepared",
            )
        }
        return satisfied
    }

    fun systemVpnSettingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS)

    /**
     * [VpnService.prepare] is not a pure read: it can take the VPN slot when
     * this app is pre-consented and no always-on VPN is set. Call it only when
     * lockdown is already on and the package is hidden. In that state another
     * always-on app cannot be replaced, and this app is already prepared if it
     * is the always-on VPN.
     */
    private fun resolvePrepared(
        context: Context,
        alwaysOnApp: String?,
        lockdown: Int,
        vpnPrepared: Boolean?,
    ): Boolean {
        if (lockdown == 0 || alwaysOnApp != null) return vpnPrepared == true
        if (vpnPrepared != null) return vpnPrepared
        return try {
            VpnService.prepare(context) == null
        } catch (e: RuntimeException) {
            Log.i(TAG, "VpnService.prepare failed during lockdown check: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun readSetting(resolver: ContentResolver, table: SettingsTable, name: String): String? {
        val viaApi = try {
            when (table) {
                SettingsTable.Secure -> Settings.Secure.getString(resolver, name)
                SettingsTable.Global -> Settings.Global.getString(resolver, name)
                SettingsTable.System -> Settings.System.getString(resolver, name)
            }
        } catch (_: RuntimeException) {
            null
        }
        if (!viaApi.isNullOrBlank()) return viaApi
        return try {
            querySetting(resolver, table.uri, name)?.takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Direct provider read. [Settings.Secure.getString] rejects hidden keys in
     * the app process on Android 12+. The provider on some OEM builds still
     * returns them. AOSP's provider rejects `always_on_vpn_app` too; that
     * failure is reported as null and the caller uses the prepared-VPN signal.
     */
    private fun querySetting(resolver: ContentResolver, uri: Uri, name: String): String? {
        val columns = arrayOf(Settings.NameValueTable.VALUE)
        val direct = runCatching {
            resolver.query(Uri.withAppendedPath(uri, name), columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(Settings.NameValueTable.VALUE)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull()
        if (!direct.isNullOrBlank()) return direct
        return resolver.query(uri, columns, "name=?", arrayOf(name), null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(Settings.NameValueTable.VALUE)
            if (index < 0) null else cursor.getString(index)
        }
    }
}
