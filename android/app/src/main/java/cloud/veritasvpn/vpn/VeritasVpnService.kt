package cloud.veritasvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import cloud.veritasvpn.MainActivity
import cloud.veritasvpn.R
import cloud.veritasvpn.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class VeritasVpnService : GoBackend.VpnService(), Tunnel {

    private val backend by lazy { GoBackend(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transitionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Completes the shared VpnService future used by GoBackend so the backend
        // reuses THIS instance instead of starting the library's base service.
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: return START_STICKY
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_CONFIG, config)
                    .apply()
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                transitionJob?.cancel()
                transitionJob = scope.launch {
                    try {
                        val parsed = Config.parse(ByteArrayInputStream(config.toByteArray(Charsets.UTF_8)))
                        val state = backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                        if (state != Tunnel.State.UP) {
                            throw IllegalStateException("WireGuard backend did not enter the UP state")
                        }
                        val egressIp = verifyTunnelEgress()
                        broadcastState(true, null, egressIp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Connect failed", e)
                        try {
                            backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                        } catch (_: Exception) {
                        }
                        broadcastState(false, friendlyError(e))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_DISCONNECT -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(KEY_CONFIG)
                    .apply()
                transitionJob?.cancel()
                transitionJob = scope.launch {
                    runCatching {
                        backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                    }
                    broadcastState(false, null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> {
                // Android may restart an Always-on VPN service without the original Intent.
                // Keep the last approved configuration so the tunnel can be restored.
                val savedConfig = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_CONFIG, null)
                if (savedConfig != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Restoring secure tunnel…"))
                    scope.launch {
                        try {
                            val parsed = Config.parse(
                                ByteArrayInputStream(savedConfig.toByteArray(Charsets.UTF_8))
                            )
                            backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                            val egressIp = verifyTunnelEgress()
                            broadcastState(true, null, egressIp)
                        } catch (e: Exception) {
                            Log.e(TAG, "Automatic VPN restore failed", e)
                            runCatching {
                                backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                            }
                            broadcastState(
                                false,
                                "VPN restore failed. Enable Android Always-on VPN and Block connections without VPN."
                            )
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        transitionJob?.cancel()
        runCatching { backend.setState(this, Tunnel.State.DOWN, null) }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_CONFIG).apply()
        broadcastState(
            false,
            "VPN permission was revoked. Enable Always-on VPN and Block connections without VPN."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { backend.setState(this, Tunnel.State.DOWN, null) }
        scope.cancel()
        super.onDestroy()
    }

    override fun getName(): String = TUNNEL_NAME

    override fun onStateChange(newState: Tunnel.State) {
        when (newState) {
            Tunnel.State.UP -> {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Verifying encrypted tunnel…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                broadcastState(false, null)
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        if (e is BackendException) {
            return when (e.reason) {
                BackendException.Reason.VPN_NOT_AUTHORIZED ->
                    "VPN permission not granted. Grant VPN access and try again."
                BackendException.Reason.TUN_CREATION_ERROR ->
                    "Could not create the VPN tunnel."
                BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                    "Could not resolve the server address."
                BackendException.Reason.UNABLE_TO_START_VPN ->
                    "Could not start the VPN service."
                BackendException.Reason.GO_ACTIVATION_ERROR_CODE ->
                    "The WireGuard backend failed to start (${e.format.joinToString()})."
                else -> e.message ?: "Connection failed."
            }
        }
        return e.message?.takeIf { it.isNotBlank() } ?: "Connection failed. Check your network and try again."
    }

    private suspend fun verifyTunnelEgress(): String {
        var lastError: Throwable? = null
        // Try each independent endpoint once. Repeating all three endpoints five
        // times made a transient DNS failure look like a 40-45 second connection.
        // The WireGuard handshake is triggered by the first request; bounded
        // fallbacks keep connection feedback fast without skipping validation.
        for (endpoint in EGRESS_ENDPOINTS) {
            try {
                val egressIp = ApiClient.getText(endpoint, timeoutSeconds = 2)
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Connected · $egressIp"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return egressIp
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "VPN egress validation timed out; no encrypted traffic was confirmed",
            lastError
        )
    }

    private fun broadcastState(connected: Boolean, error: String?, egressIp: String? = null) {
        val intent = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_CONNECTED, connected)
        if (error != null) intent.putExtra(EXTRA_ERROR, error)
        if (egressIp != null) intent.putExtra(EXTRA_EGRESS_IP, egressIp)
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeritasVPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_veritas)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VeritasVPN", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN connection status"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val TUNNEL_NAME = "veritas"
        const val CHANNEL_ID = "veritas_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "cloud.veritasvpn.CONNECT"
        const val ACTION_DISCONNECT = "cloud.veritasvpn.DISCONNECT"
        const val ACTION_STATE = "cloud.veritasvpn.STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        const val EXTRA_EGRESS_IP = "egress_ip"
        const val PREFS_NAME = "veritas_vpn_state"
        const val KEY_CONFIG = "last_approved_config"
        private const val TAG = "VeritasVpnService"
        private val EGRESS_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
