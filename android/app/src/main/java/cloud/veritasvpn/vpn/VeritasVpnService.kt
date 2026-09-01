package cloud.veritasvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
import cloud.veritasvpn.secure.SecurePrefs
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
import java.io.ByteArrayInputStream

class VeritasVpnService : GoBackend.VpnService(), Tunnel {

    private val backend by lazy { GoBackend(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transitionJob: Job? = null
    private var disconnectJob: Job? = null
    private var validationJob: Job? = null
    private var statsJob: Job? = null
    private var validationGeneration = 0L
    private var hadGoodHandshake = false
    private var reconnectSignalSent = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchingNetworks = false
    private var lastNetworkId: Int? = null
    private var lastTransportFingerprint: String? = null
    private var pathAdaptJob: Job? = null
    private var lastPathAdaptAtMs = 0L
    @Volatile private var softAdapting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Watch path changes even before the first connect so Always-on restore
        // can soft-adapt when the phone moves between Wi-Fi and cellular.
        startNetworkWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Completes the shared VpnService future used by GoBackend so the backend
        // reuses THIS instance instead of starting the library's base service.
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: return START_STICKY
                vpnStatePrefs().edit()
                    .putString(KEY_CONFIG, config)
                    .apply()
                hadGoodHandshake = false
                reconnectSignalSent = false
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                validationJob?.cancel()
                val pendingDisconnect = disconnectJob?.takeIf { it.isActive }
                if (transitionJob?.isActive == true && transitionJob !== pendingDisconnect) {
                    transitionJob?.cancel()
                }
                transitionJob = scope.launch {
                    try {
                        // Wait for a prior teardown, but keep the first startup
                        // path direct and free of an extra DOWN call.
                        pendingDisconnect?.join()
                        val parsed = Config.parse(
                            ByteArrayInputStream(config.toByteArray(Charsets.UTF_8))
                        )
                        val state = backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                        if (state != Tunnel.State.UP) {
                            throw IllegalStateException(
                                "WireGuard backend did not enter the UP state"
                            )
                        }
                        // The WireGuard interface is ready at this point. Do not
                        // block the UI on a public-IP probe: DNS/egress services
                        // can be transiently slow immediately after a reconnect.
                        // Keep the tunnel up and verify egress in the background.
                        ServiceCompat.startForeground(
                            this@VeritasVpnService,
                            NOTIFICATION_ID,
                            buildNotification("Connected · checking route…"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        )
                        broadcastState(true, null)
                        startStatsPolling()
                        startBackgroundEgressValidation()
                        startNetworkWatch()
                        captureCurrentNetworkFingerprint()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Connect failed", e)
                        stopStatsPolling()
                        runCatching {
                            backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                        }
                        broadcastState(false, friendlyError(e))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_DISCONNECT -> {
                vpnStatePrefs().edit()
                    .remove(KEY_CONFIG)
                    .apply()
                hadGoodHandshake = false
                reconnectSignalSent = false
                pathAdaptJob?.cancel()
                lastNetworkId = null
                lastTransportFingerprint = null
                transitionJob?.cancel()
                disconnectJob?.cancel()
                disconnectJob = scope.launch {
                    // Tear the interface down first. A background egress probe
                    // may be blocked in OkHttp and must not delay route removal.
                    validationGeneration++
                    validationJob?.cancel()
                    stopStatsPolling()
                    runCatching {
                        backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                    }
                    broadcastState(false, null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // Keep the service alive so a new connect can reuse it
                    // immediately without racing stopSelf/onDestroy.
                }
                transitionJob = disconnectJob
            }
            else -> {
                // Android may restart an Always-on VPN service without the original Intent.
                // Keep the last approved configuration so the tunnel can be restored.
                val savedConfig = vpnStatePrefs()
                    .getString(KEY_CONFIG, null)
                if (savedConfig != null) {
                    hadGoodHandshake = false
                    reconnectSignalSent = false
                    startForeground(NOTIFICATION_ID, buildNotification("Restoring secure tunnel…"))
                    scope.launch {
                        try {
                            val parsed = Config.parse(
                                ByteArrayInputStream(savedConfig.toByteArray(Charsets.UTF_8))
                            )
                            backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                            ServiceCompat.startForeground(
                                this@VeritasVpnService,
                                NOTIFICATION_ID,
                                buildNotification("Connected · checking route…"),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                            broadcastState(true, null)
                            startStatsPolling()
                            startBackgroundEgressValidation()
                            startNetworkWatch()
                            captureCurrentNetworkFingerprint()
                        } catch (e: Exception) {
                            Log.e(TAG, "Automatic VPN restore failed", e)
                            stopStatsPolling()
                            runCatching {
                                backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                            }
                            broadcastState(
                                false,
                                "VPN restore failed. Tap Connect now to reconnect."
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
        validationGeneration++
        validationJob?.cancel()
        stopStatsPolling()
        hadGoodHandshake = false
        reconnectSignalSent = false
        runCatching { backend.setState(this, Tunnel.State.DOWN, null) }
        vpnStatePrefs().edit().remove(KEY_CONFIG).apply()
        broadcastState(
            false,
            "VPN permission was revoked. Tap Connect now to reconnect."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopNetworkWatch()
        pathAdaptJob?.cancel()
        stopStatsPolling()
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
                // Soft path adapt intentionally cycles DOWN→UP with the same
                // peer config. Do not escalate that bounce into a full reconnect.
                if (softAdapting) return
                stopStatsPolling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                val intendedConnected = vpnStatePrefs()
                    .getString(KEY_CONFIG, null) != null
                broadcastState(false, null)
                if (intendedConnected) {
                    signalReconnectNeeded()
                }
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                broadcastStats()
                delay(1_500)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun broadcastStats() {
        val stats = runCatching { backend.getStatistics(this) }.getOrNull() ?: return
        var handshakeMs = 0L
        for (key in stats.peers()) {
            val peer = stats.peer(key) ?: continue
            if (peer.latestHandshakeEpochMillis > handshakeMs) {
                handshakeMs = peer.latestHandshakeEpochMillis
            }
        }
        val now = System.currentTimeMillis()
        val handshakeAge = if (handshakeMs > 0L) (now - handshakeMs).coerceAtLeast(0L) else Long.MAX_VALUE
        // Track that we have seen a healthy handshake, but do NOT tear down the
        // VpnService when WireGuard's natural ~2 minute rekey makes the age climb.
        // Full reconnects drop Android's status-bar VPN icon and open a brief
        // unprotected window. Recover only via explicit tunnel failure paths.
        if (handshakeMs > 0L && handshakeAge <= HANDSHAKE_HEALTHY_MS) {
            hadGoodHandshake = true
            reconnectSignalSent = false
        }
        sendBroadcast(
            Intent(ACTION_STATS).setPackage(packageName)
                .putExtra(EXTRA_RX_BYTES, stats.totalRx())
                .putExtra(EXTRA_TX_BYTES, stats.totalTx())
                .putExtra(EXTRA_HANDSHAKE_MS, handshakeMs)
        )
    }

    private fun signalReconnectNeeded() {
        if (reconnectSignalSent) return
        reconnectSignalSent = true
        sendBroadcast(Intent(ACTION_RECONNECT_NEEDED).setPackage(packageName))
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

    private fun startBackgroundEgressValidation() {
        validationJob?.cancel()
        val generation = ++validationGeneration
        validationJob = scope.launch {
            try {
                val egressIp = verifyTunnelEgress()
                if (generation != validationGeneration) return@launch
                broadcastState(true, null, egressIp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A transient egress probe failure must not tear down the
                // WireGuard interface. The tunnel remains available and the
                // next health check/connect cycle can validate it again.
                Log.w(TAG, "Background egress validation did not complete", e)
            }
        }
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


    private fun connectivityManager(): ConnectivityManager? =
        getSystemService(ConnectivityManager::class.java)

    private fun startNetworkWatch() {
        if (watchingNetworks) return
        val cm = connectivityManager() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onUnderlyingNetworkChanged(network, "available")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                onUnderlyingNetworkChanged(network, "capabilities", networkCapabilities)
            }

            override fun onLost(network: Network) {
                // A replacement network usually arrives via onAvailable shortly after.
                // Soft-adapt when that happens; do not peer-churn on transient loss.
                Log.i(TAG, "Underlying network lost netId=${network.networkHandle}")
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                cm.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
            watchingNetworks = true
            captureCurrentNetworkFingerprint()
        }.onFailure { e ->
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun stopNetworkWatch() {
        val cm = connectivityManager()
        val callback = networkCallback
        if (cm != null && callback != null) {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        watchingNetworks = false
    }

    private fun transportFingerprint(caps: NetworkCapabilities?): String {
        if (caps == null) return "unknown"
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bt")
        }
        return transports.joinToString("|").ifEmpty { "other" }
    }

    private fun captureCurrentNetworkFingerprint() {
        val cm = connectivityManager() ?: return
        val active = cm.activeNetwork
        lastNetworkId = active?.networkHandle?.toInt()
        lastTransportFingerprint = transportFingerprint(cm.getNetworkCapabilities(active))
    }

    private fun onUnderlyingNetworkChanged(
        network: Network,
        reason: String,
        caps: NetworkCapabilities? = null
    ) {
        val intended = vpnStatePrefs().getString(KEY_CONFIG, null) != null
        if (!intended) {
            captureCurrentNetworkFingerprint()
            return
        }
        val cm = connectivityManager()
        val resolvedCaps = caps ?: cm?.getNetworkCapabilities(network)
        // Ignore capability updates that only describe our own VPN transport.
        if (resolvedCaps != null &&
            resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return
        }
        val fingerprint = transportFingerprint(resolvedCaps)
        val netId = network.networkHandle.toInt()
        val networkChanged = lastNetworkId != null && lastNetworkId != netId
        val transportChanged =
            lastTransportFingerprint != null && lastTransportFingerprint != fingerprint
        lastNetworkId = netId
        lastTransportFingerprint = fingerprint
        if (!networkChanged && !transportChanged) return
        Log.i(
            TAG,
            "Path change ($reason): netId=$netId transport=$fingerprint — soft-adapting tunnel"
        )
        scheduleSoftPathAdapt()
    }

    private fun scheduleSoftPathAdapt() {
        val now = System.currentTimeMillis()
        if (now - lastPathAdaptAtMs < PATH_ADAPT_DEBOUNCE_MS) {
            Log.i(TAG, "Skipping soft path adapt (debounce)")
            return
        }
        pathAdaptJob?.cancel()
        pathAdaptJob = scope.launch {
            delay(PATH_ADAPT_SETTLE_MS)
            softAdaptTunnelForNewPath()
        }
    }

    private suspend fun softAdaptTunnelForNewPath() {
        val configText = vpnStatePrefs().getString(KEY_CONFIG, null) ?: return
        if (disconnectJob?.isActive == true) return
        lastPathAdaptAtMs = System.currentTimeMillis()
        hadGoodHandshake = false
        reconnectSignalSent = false
        softAdapting = true
        Log.i(TAG, "Soft path adapt: bouncing WireGuard with same peer config")
        try {
            runCatching {
                val parsed = Config.parse(
                    ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8))
                )
                // Same-config DOWN→UP rebinds sockets to the new underlay without
                // deleting the server peer or asking the user to reconnect.
                backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                delay(200)
                val state = backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                if (state != Tunnel.State.UP) {
                    throw IllegalStateException("Soft path adapt did not reach UP")
                }
                ServiceCompat.startForeground(
                    this@VeritasVpnService,
                    NOTIFICATION_ID,
                    buildNotification("Connected · adapting to new network…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                broadcastState(true, null)
                startStatsPolling()
                startBackgroundEgressValidation()
            }.onFailure { e ->
                Log.e(TAG, "Soft path adapt failed; requesting full reconnect", e)
                signalReconnectNeeded()
            }
        } finally {
            softAdapting = false
        }
    }

    private fun vpnStatePrefs() = SecurePrefs.open(this, PREFS_NAME)

    companion object {
        const val TUNNEL_NAME = "veritas"
        const val CHANNEL_ID = "veritas_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "cloud.veritasvpn.CONNECT"
        const val ACTION_DISCONNECT = "cloud.veritasvpn.DISCONNECT"
        const val ACTION_STATE = "cloud.veritasvpn.STATE"
        const val ACTION_STATS = "cloud.veritasvpn.STATS"
        const val ACTION_RECONNECT_NEEDED = "cloud.veritasvpn.RECONNECT_NEEDED"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        const val EXTRA_EGRESS_IP = "egress_ip"
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_HANDSHAKE_MS = "handshake_ms"
        const val PREFS_NAME = "veritas_vpn_state"
        const val KEY_CONFIG = "last_approved_config"
        private const val TAG = "VeritasVpnService"
        private const val HANDSHAKE_HEALTHY_MS = 180_000L
        private const val PATH_ADAPT_DEBOUNCE_MS = 4_000L
        private const val PATH_ADAPT_SETTLE_MS = 750L
        private val EGRESS_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
