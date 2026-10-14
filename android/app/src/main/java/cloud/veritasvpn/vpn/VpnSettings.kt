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
    private const val KEY_STEALTH_MODE = "stealth_mode"
    private const val KEY_SHIELD_MALICIOUS = "shield_block_malicious"
    private const val KEY_SHIELD_ADS = "shield_block_ads"
    private const val KEY_SHIELD_ADULT = "shield_block_adult"
    private const val KEY_SHIELD_TRACKERS = "shield_block_trackers"
    private const val KEY_SHARE_DIAGNOSTICS = "share_diagnostics"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_VPN_DISCLOSURE_ACCEPTED = "vpn_disclosure_accepted"
    private const val KEY_ONBOARDING_VPN_DONE = "onboarding_vpn_done"
    private const val KEY_SESSION_ESTABLISHED_AT = "session_established_at"
    private const val KEY_PEER_GONE_AT = "peer_gone_at"
    private const val KEY_REPROVISION_IN_PROGRESS_AT = "reprovision_in_progress_at"
    private const val KEY_PEER_CHECK_OUTCOME = "peer_check_outcome"
    private const val KEY_PEER_CHECK_CODE = "peer_check_code"
    private const val KEY_PEER_CHECK_VIA = "peer_check_via"
    private const val KEY_REPROVISION_ATTEMPTS = "reprovision_attempts"
    private const val KEY_REPROVISION_OUTCOME = "reprovision_outcome"
    private const val KEY_REPROVISION_CODE = "reprovision_code"

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

    /** Auto is the default: UDP first, then Stealth if the handshake does not complete. */
    fun stealthMode(context: Context): StealthMode =
        StealthMode.fromStored(prefs(context).getString(KEY_STEALTH_MODE, null))

    fun setStealthMode(context: Context, mode: StealthMode) {
        prefs(context).edit().putString(KEY_STEALTH_MODE, mode.stored()).apply()
    }

    /** Premium DNS filters. Malicious and trackers default on; ads and adult default off. */
    fun shieldPolicy(context: Context): ShieldPolicy {
        val stored = prefs(context)
        return ShieldPolicy(
            blockMalicious = stored.getBoolean(KEY_SHIELD_MALICIOUS, true),
            blockAds = stored.getBoolean(KEY_SHIELD_ADS, false),
            blockAdult = stored.getBoolean(KEY_SHIELD_ADULT, false),
            blockTrackers = stored.getBoolean(KEY_SHIELD_TRACKERS, true),
        )
    }

    /** Off unless the user opts in. Contact us includes a redacted report only when this is on. */
    fun shareDiagnostics(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHARE_DIAGNOSTICS, false)

    fun setShareDiagnostics(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHARE_DIAGNOSTICS, enabled).apply()
    }

    /** True only after the in-app VPN disclosure has been accepted. */
    fun vpnDisclosureAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VPN_DISCLOSURE_ACCEPTED, false)

    fun setVpnDisclosureAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_VPN_DISCLOSURE_ACCEPTED, accepted).apply()
    }

    /** True after the first-run onboarding VPN consent step completes (accept or decline). */
    fun onboardingVpnDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_VPN_DONE, false)

    fun setOnboardingVpnDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_VPN_DONE, done).apply()
    }

    fun lastError(context: Context): String =
        prefs(context).getString(KEY_LAST_ERROR, null).orEmpty()

    fun setLastError(context: Context, message: String) {
        val safe = message.replace('\n', ' ').replace('\r', ' ').trim().take(300)
        if (
            safe.contains("PrivateKey", ignoreCase = true) ||
            safe.contains("PresharedKey", ignoreCase = true) ||
            safe.contains("[Interface]", ignoreCase = true)
        ) {
            return
        }
        prefs(context).edit().putString(KEY_LAST_ERROR, safe).apply()
    }

    fun setShieldPolicy(context: Context, policy: ShieldPolicy) {
        prefs(context).edit()
            .putBoolean(KEY_SHIELD_MALICIOUS, policy.blockMalicious)
            .putBoolean(KEY_SHIELD_ADS, policy.blockAds)
            .putBoolean(KEY_SHIELD_ADULT, policy.blockAdult)
            .putBoolean(KEY_SHIELD_TRACKERS, policy.blockTrackers)
            .apply()
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

    /**
     * Timestamp when the tunnel interface was established (not when the first handshake completed).
     * Used as a stable marker to prevent the first-connect timeout from deleting an established peer
     * after Activity recreation. This is a "don't delete" marker, not a measure of connection health.
     */
    fun sessionEstablishedAt(context: Context): Long =
        prefs(context).getLong(KEY_SESSION_ESTABLISHED_AT, 0L)

    fun setSessionEstablishedAt(context: Context, timestampMs: Long) {
        prefs(context).edit().putLong(KEY_SESSION_ESTABLISHED_AT, timestampMs).apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit().apply {
            remove(KEY_SESSION_ESTABLISHED_AT)
        }.apply()
    }

    fun peerGoneAt(context: Context): Long =
        prefs(context).getLong(KEY_PEER_GONE_AT, 0L)

    fun setPeerGoneAt(context: Context, timestampMs: Long) {
        prefs(context).edit().putLong(KEY_PEER_GONE_AT, timestampMs).apply()
    }

    fun clearPeerGoneAt(context: Context) {
        prefs(context).edit().remove(KEY_PEER_GONE_AT).apply()
    }

    fun reprovisionInProgressAt(context: Context): Long =
        prefs(context).getLong(KEY_REPROVISION_IN_PROGRESS_AT, 0L)

    fun setReprovisionInProgressAt(context: Context, timestampMs: Long) {
        // commit so a second provisioner in this process sees the claim
        // before it starts its own peer create.
        prefs(context).edit().putLong(KEY_REPROVISION_IN_PROGRESS_AT, timestampMs).commit()
    }

    fun clearReprovisionInProgressAt(context: Context) {
        prefs(context).edit().remove(KEY_REPROVISION_IN_PROGRESS_AT).commit()
    }

    data class RecoveryDiagnostics(
        val peerCheckOutcome: String,
        val peerCheckCode: Int,
        val peerCheckVia: String,
        val peerGoneAtMs: Long,
        val reprovisionAttempts: Int,
        val reprovisionOutcome: String,
        val reprovisionCode: Int,
    )

    fun recoveryDiagnostics(context: Context): RecoveryDiagnostics {
        val store = prefs(context)
        return RecoveryDiagnostics(
            peerCheckOutcome = store.getString(KEY_PEER_CHECK_OUTCOME, null).orEmpty(),
            peerCheckCode = store.getInt(KEY_PEER_CHECK_CODE, -1),
            peerCheckVia = store.getString(KEY_PEER_CHECK_VIA, null).orEmpty(),
            peerGoneAtMs = store.getLong(KEY_PEER_GONE_AT, 0L),
            reprovisionAttempts = store.getInt(KEY_REPROVISION_ATTEMPTS, 0),
            reprovisionOutcome = store.getString(KEY_REPROVISION_OUTCOME, null).orEmpty(),
            reprovisionCode = store.getInt(KEY_REPROVISION_CODE, -1),
        )
    }

    fun notePeerCheck(context: Context, outcome: String, code: Int, via: String) {
        val safeOutcome = PeerCheckLog.sanitizeOutcome(outcome)
        val safeVia = PeerCheckLog.sanitizeVia(via)
        if (safeOutcome.isEmpty() || safeVia.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_PEER_CHECK_OUTCOME, safeOutcome)
            .putInt(KEY_PEER_CHECK_CODE, code.coerceIn(0, 599))
            .putString(KEY_PEER_CHECK_VIA, safeVia)
            .apply()
    }

    /** Counts a re-provision attempt. A skip is recorded but not counted. */
    fun noteReprovision(context: Context, outcome: String, code: Int) {
        val safeOutcome = PeerCheckLog.sanitizeAttempt(outcome)
        if (safeOutcome.isEmpty()) return
        val store = prefs(context)
        val editor = store.edit()
            .putString(KEY_REPROVISION_OUTCOME, safeOutcome)
            .putInt(KEY_REPROVISION_CODE, code.coerceIn(0, 599))
        if (PeerCheckLog.countsAttempt(safeOutcome)) {
            val attempts = store.getInt(KEY_REPROVISION_ATTEMPTS, 0).coerceAtLeast(0)
            editor.putInt(KEY_REPROVISION_ATTEMPTS, attempts + 1)
        }
        editor.apply()
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
