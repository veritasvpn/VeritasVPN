package cloud.veritasvpn.support

import android.content.Context
import android.os.Build
import cloud.veritasvpn.secure.SecurePrefs
import cloud.veritasvpn.vpn.EndpointSelector
import cloud.veritasvpn.vpn.VeritasVpnService

/**
 * Reads connection facts for the support report.
 * The saved tunnel config is only scanned for its Endpoint line.
 * Keys, the stealth path, and the rest of the config stay out of the result.
 */
object DiagnosticSnapshot {
    data class Endpoints(val active: String, val wan: String, val stealth: String)

    fun endpoints(context: Context): Endpoints {
        val prefs = SecurePrefs.open(context.applicationContext, VeritasVpnService.PREFS_NAME)
        val config = prefs.getString(VeritasVpnService.KEY_CONFIG, null).orEmpty()
        return Endpoints(
            active = EndpointSelector.endpointFromConfig(config),
            wan = prefs.getString(VeritasVpnService.KEY_ENDPOINT_WAN, null).orEmpty(),
            stealth = prefs.getString(VeritasVpnService.KEY_STEALTH_ENDPOINT, null).orEmpty(),
        )
    }

    data class Identity(
        val versionName: String,
        val versionCode: String,
        val osVersion: String,
        val device: String,
    )

    @Suppress("DEPRECATION")
    fun identity(context: Context): Identity {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return Identity(
            versionName = info.versionName.orEmpty(),
            versionCode = code.toString(),
            osVersion = osVersion(),
            device = deviceLabel(),
        )
    }

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        if (model.isEmpty() || model.equals("unknown", ignoreCase = true)) return ""
        if (manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true)) return model
        return "$manufacturer $model"
    }

    fun assemble(
        context: Context,
        connected: Boolean,
        connecting: Boolean,
        handshakeEpochMs: Long,
        nowMs: Long,
        transport: String,
        lastError: String?,
    ): DiagnosticReport {
        val endpoints = endpoints(context)
        val identity = identity(context)
        return buildDiagnosticReport(
            versionName = identity.versionName,
            versionCode = identity.versionCode,
            osVersion = identity.osVersion,
            device = identity.device,
            connected = connected,
            connecting = connecting,
            handshakeEpochMs = handshakeEpochMs,
            nowMs = nowMs,
            transport = transport,
            lastError = lastError,
            activeEndpoint = endpoints.active,
            wanEndpoint = endpoints.wan,
            stealthEndpoint = endpoints.stealth,
        )
    }
}
