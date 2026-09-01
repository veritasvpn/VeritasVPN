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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchingNetworks = false
    private var lastNetworkId: Int? = null
    private var lastTransportFingerprint: String? = null
    private var pathAdaptJob: Job? = null
    private var lastPathAdaptAtMs = 0L
    /** Ignore underlay callbacks until this uptime; VPN bring-up looks like a path change. */
    @Volatile private var tunnelStableAfterMs = 0L
    /** True while the user intends to stay connected (saved config present). */
    private fun sessionIntended(): Boolean =
        vpnStatePrefs().getString(KEY_CONFIG, null) != null

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
                vpnStatePrefs().edit()
                    .putString(KEY_CONFIG, config)
                    .apply()
                hadGoodHandshake = false
                pathAdaptJob?.cancel()
                tunnelStableAfterMs = Long.MAX_VALUE
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
                        // VPN bring-up changes the default network; wait before
                        // treating underlay callbacks as real path changes.
                        tunnelStableAfterMs = System.currentTimeMillis() + PATH_ADAPT_GRACE_MS
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
                            tunnelStableAfterMs = System.currentTimeMillis() + PATH_ADAPT_GRACE_MS
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
                stopStatsPolling()
                // GoBackend's DOWN path calls stopSelf(), which briefly drops the
                // system VPN icon. If the user still wants a session, keep the
                // saved config and let START_STICKY / Always-on restore it —
                // never ask the UI to delete the peer or show a full disconnect.
                if (sessionIntended()) {
                    Log.w(
                        TAG,
                        "Tunnel went DOWN while session intended; preserving config for restore"
                    )
                    return
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                broadcastState(false, null)
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
        }
        sendBroadcast(
            Intent(ACTION_STATS).setPackage(packageName)
                .putExtra(EXTRA_RX_BYTES, stats.totalRx())
                .putExtra(EXTRA_TX_BYTES, stats.totalTx())
                .putExtra(EXTRA_HANDSHAKE_MS, handshakeMs)
        )
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

    /**
     * Watch the *underlay* network only (NOT_VPN). registerDefaultNetworkCallback
     * is unsafe here: when the tunnel comes up Android reports the VPN as the
     * new default network, which used to soft-bounce the tunnel in a loop.
     *
     * Path changes must NOT call backend.setState(DOWN): GoBackend's DOWN path
     * stopSelf()s the VpnService and drops the status-bar VPN icon. Bind the
     * still-UP tunnel to the new underlay instead.
     */
    private fun startNetworkWatch() {
        if (watchingNetworks) return
        val cm = connectivityManager() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onUnderlayNetworkChanged(network, "available")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                onUnderlayNetworkChanged(network, "capabilities", networkCapabilities)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Underlay network lost netId=${network.networkHandle}")
                if (!sessionIntended()) return
                if (System.currentTimeMillis() < tunnelStableAfterMs) return
                // Old path gone — bind to whatever underlay remains; never tear down.
                val underlay = bestUnderlayNetwork() ?: return
                onUnderlayNetworkChanged(underlay.first, "failover", underlay.second)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            watchingNetworks = true
            captureCurrentNetworkFingerprint()
        }.onFailure { e ->
            Log.w(TAG, "Could not register underlay network callback", e)
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
        // Never include TRANSPORT_VPN — VPN bring-up must not look like a path change.
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bt")
        }
        return transports.joinToString("|").ifEmpty { "other" }
    }

    private fun bestUnderlayNetwork(): Pair<Network, NetworkCapabilities>? {
        val cm = connectivityManager() ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            return network to caps
        }
        return null
    }

    private fun captureCurrentNetworkFingerprint() {
        val underlay = bestUnderlayNetwork()
        if (underlay == null) {
            lastNetworkId = null
            lastTransportFingerprint = null
            return
        }
        val (network, caps) = underlay
        lastNetworkId = network.networkHandle.toInt()
        lastTransportFingerprint = transportFingerprint(caps)
    }

    private fun onUnderlayNetworkChanged(
        network: Network,
        reason: String,
        caps: NetworkCapabilities? = null
    ) {
        if (!sessionIntended()) {
            captureCurrentNetworkFingerprint()
            return
        }
        // Still bringing the tunnel up / just came up — ignore VPN-induced flaps.
        if (System.currentTimeMillis() < tunnelStableAfterMs) {
            Log.i(TAG, "Ignoring underlay $reason during post-connect grace")
            return
        }
        if (transitionJob?.isActive == true || disconnectJob?.isActive == true) {
            return
        }
        val cm = connectivityManager()
        val resolvedCaps = caps ?: cm?.getNetworkCapabilities(network) ?: return
        if (!resolvedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
        if (resolvedCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        val fingerprint = transportFingerprint(resolvedCaps)
        val netId = network.networkHandle.toInt()
        val networkChanged = lastNetworkId != null && lastNetworkId != netId
        val transportChanged =
            lastTransportFingerprint != null && lastTransportFingerprint != fingerprint
        if (!networkChanged && !transportChanged) {
            // First observation after grace: seed baselines without adapting.
            if (lastNetworkId == null) {
                lastNetworkId = netId
                lastTransportFingerprint = fingerprint
            }
            return
        }
        lastNetworkId = netId
        lastTransportFingerprint = fingerprint
        Log.i(
            TAG,
            "Underlay path change ($reason): netId=$netId transport=$fingerprint — rebinding"
        )
        scheduleSoftPathAdapt(network)
    }

    private fun scheduleSoftPathAdapt(network: Network) {
        val now = System.currentTimeMillis()
        if (now - lastPathAdaptAtMs < PATH_ADAPT_DEBOUNCE_MS) {
            Log.i(TAG, "Skipping soft path adapt (debounce)")
            return
        }
        pathAdaptJob?.cancel()
        pathAdaptJob = scope.launch {
            delay(PATH_ADAPT_SETTLE_MS)
            softAdaptTunnelForNewPath(network)
        }
    }

    /**
     * Keep the WireGuard tunnel and VpnService UP. Only tell Android which
     * underlay to use so UDP can roam. Never DOWN — that drops the VPN icon.
     */
    private suspend fun softAdaptTunnelForNewPath(network: Network) {
        if (!sessionIntended()) return
        if (disconnectJob?.isActive == true) return
        if (System.currentTimeMillis() < tunnelStableAfterMs) return
        lastPathAdaptAtMs = System.currentTimeMillis()
        tunnelStableAfterMs = lastPathAdaptAtMs + PATH_ADAPT_GRACE_MS
        Log.i(TAG, "Soft path adapt: binding VpnService to new underlay (no tunnel bounce)")
        val bound = runCatching {
            setUnderlyingNetworks(arrayOf(network))
            true
        }.getOrElse { e ->
            Log.w(TAG, "setUnderlyingNetworks(specific) failed; falling back to default", e)
            runCatching {
                setUnderlyingNetworks(null)
                true
            }.getOrElse { e2 ->
                Log.e(TAG, "Could not rebind VPN underlay; leaving tunnel UP", e2)
                false
            }
        }
        ServiceCompat.startForeground(
            this@VeritasVpnService,
            NOTIFICATION_ID,
            buildNotification(
                if (bound) "Connected · adapted to new network"
                else "Connected · waiting for network"
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        // Stay "connected" in the UI — path adapt is not a disconnect.
        broadcastState(true, null)
        startBackgroundEgressValidation()
        captureCurrentNetworkFingerprint()
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
        private const val PATH_ADAPT_GRACE_MS = 10_000L
        private val EGRESS_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
