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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream

class VeritasVpnService : GoBackend.VpnService(), Tunnel {

    private val backend by lazy { GoBackend(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transitionJob: Job? = null
    private var disconnectJob: Job? = null
    private val stateMutex = Mutex()
    @Volatile private var suppressStateBroadcast = false

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
                val pendingDisconnect = disconnectJob?.takeIf { it.isActive }
                if (transitionJob?.isActive == true && transitionJob !== pendingDisconnect) {
                    transitionJob?.cancel()
                }
                transitionJob = scope.launch {
                    try {
                        // A reconnect must never race a prior teardown. The previous
                        // disconnect is allowed to finish before this transition enters
                        // the serialized backend state section.
                        pendingDisconnect?.join()
                        stateMutex.withLock {
                            val parsed = Config.parse(
                                ByteArrayInputStream(config.toByteArray(Charsets.UTF_8))
                            )
                            val egressIp = establishTunnel(parsed)
                            broadcastState(true, null, egressIp)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Connect failed", e)
                        runCatching {
                            stateMutex.withLock {
                                backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                            }
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
                disconnectJob?.cancel()
                disconnectJob = scope.launch {
                    stateMutex.withLock {
                        runCatching {
                            backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                        }
                        broadcastState(false, null)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Keep the service instance alive after teardown so an
                        // immediate reconnect cannot race service destruction.
                    }
                }
                transitionJob = disconnectJob
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
                            stateMutex.withLock {
                                val parsed = Config.parse(
                                    ByteArrayInputStream(savedConfig.toByteArray(Charsets.UTF_8))
                                )
                                val egressIp = establishTunnel(parsed)
                                broadcastState(true, null, egressIp)
                            }
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
                if (!suppressStateBroadcast) {
                    broadcastState(false, null)
                }
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

    private suspend fun establishTunnel(parsed: Config): String {
        var lastError: Exception? = null
        repeat(2) { cycle ->
            suppressStateBroadcast = true
            try {
                // The first cycle starts the backend directly. A fresh service
                // does not need a blocking DOWN call, and skipping it avoids
                // hanging the very first connection on some Android versions.
                // A failed cycle is explicitly torn down before the retry to
                // recover stale route/socket state after a rapid reconnect.
                if (cycle > 0) {
                    runCatching {
                        backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                    }
                    delay(500)
                }
                val state = backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                if (state != Tunnel.State.UP) {
                    throw IllegalStateException(
                        "WireGuard backend did not enter the UP state"
                    )
                }
                delay(250)
                return verifyTunnelEgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Tunnel validation cycle " + (cycle + 1) + " failed; retrying", e)
            } finally {
                suppressStateBroadcast = false
            }
        }
        throw lastError ?: IllegalStateException(
            "VPN egress validation timed out; no encrypted traffic was confirmed"
        )
    }

    private suspend fun verifyTunnelEgress(): String {
        // Race the independent endpoints, then retry the race briefly. Android
        // may need a few milliseconds to install routes/DNS after a rapid
        // WireGuard teardown; treating that window as a hard failure makes the
        // second connection flaky even though the peer is already active.
        repeat(3) { attempt ->
            val egressIp = raceEgressEndpoints()
            if (egressIp != null) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Connected · $egressIp"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return egressIp
            }
            if (attempt < 2) delay(250)
        }
        throw IllegalStateException(
            "VPN egress validation timed out; no encrypted traffic was confirmed"
        )
    }

    private suspend fun raceEgressEndpoints(): String? = coroutineScope {
        val results = Channel<String>(EGRESS_ENDPOINTS.size)
        val jobs = EGRESS_ENDPOINTS.map { endpoint ->
            launch(Dispatchers.IO) {
                runCatching {
                    ApiClient.getText(endpoint, timeoutSeconds = 2)
                }.onSuccess { egressIp ->
                    results.trySend(egressIp)
                }
            }
        }
        try {
            withTimeoutOrNull(2_500) { results.receive() }
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
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
