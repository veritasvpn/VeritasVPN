package cloud.veritasvpn.vpn

import android.content.Context
import android.os.Build
import cloud.veritasvpn.api.PeerResponse
import com.wireguard.crypto.KeyPair

/**
 * WireGuard config and device metadata shared by the activity (first connect)
 * and the VPN service (background re-provision). MTU, DNS and bypass apps must
 * not drift between those two paths.
 */
object WireGuardSession {

    // Display-only metadata for the account dashboard. Do not add hardware
    // identifiers (IMEI, Android ID, serial, MAC), account data or location.
    // A user-selected dashboard name stays server-side and is never overwritten.
    fun deviceMetadata(context: Context): Map<String, String> {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val deviceModel = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .take(100)
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return mapOf(
            "device_platform" to "Android",
            "device_model" to deviceModel,
            "device_os_version" to "Android ${Build.VERSION.RELEASE.orEmpty()}".trim(),
            "client_version" to appVersion,
        )
    }

    fun buildConfig(context: Context, peer: PeerResponse, keyPair: KeyPair): String {
        val dns = peer.dnsServer?.trim().orEmpty()
        require(dns.isNotEmpty()) {
            "Server did not provide a DNS gateway; connect aborted to avoid unfiltered public DNS."
        }
        val serverAllowed = peer.clientAllowedIps ?: peer.allowedIps ?: listOf("0.0.0.0/0", "::/0")
        val allowed = VpnSettings.resolveAllowedIps(context, serverAllowed).joinToString(",")
        val bypassApps = VpnSettings.bypassApps(context)
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${keyPair.privateKey.toBase64()}")
            appendLine("Address = ${peer.assignedIp}")
            appendLine("DNS = $dns")
            // Product default MTU 1280 (reliability on mobile/hostile paths); see docs/MTU_STRATEGY.md
            appendLine("MTU = 1280")
            if (bypassApps.isNotEmpty()) {
                // GoBackend maps ExcludedApplications → VpnService.Builder.addDisallowedApplication
                appendLine("ExcludedApplications = ${bypassApps.joinToString(", ")}")
            }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${peer.serverPublicKey}")
            if (!peer.presharedKey.isNullOrEmpty()) {
                appendLine("PresharedKey = ${peer.presharedKey}")
            }
            appendLine("Endpoint = ${peer.serverEndpoint}")
            appendLine("AllowedIPs = $allowed")
            appendLine("PersistentKeepalive = 25")
        }
    }
}
