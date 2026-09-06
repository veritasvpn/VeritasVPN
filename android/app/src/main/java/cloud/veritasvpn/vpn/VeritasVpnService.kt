package cloud.veritasvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.Inet4Address
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
    private var adaptJob: Job? = null
    private var validationGeneration = 0L
    @Volatile private var adapting = false
    @Volatile private var underlayWatchRegistered = false
    private var lastUnderlayFingerprint: String? = null
    private var lastChosenEndpoint: String? = null
    private val underlayCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleUnderlayAdapt(network)
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            scheduleUnderlayAdapt(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            scheduleUnderlayAdapt(network)
        override fun onLost(network: Network) = scheduleUnderlayAdapt(null)
    }
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
                val endpointLan = intent.getStringExtra(EXTRA_ENDPOINT_LAN).orEmpty()
                val endpointWan = intent.getStringExtra(EXTRA_ENDPOINT_WAN).orEmpty()
                lastUnderlayFingerprint = null
                lastChosenEndpoint = null
                vpnStatePrefs().edit()
                    .putString(KEY_CONFIG, config)
                    .putString(KEY_ENDPOINT_LAN, endpointLan)
                    .putString(KEY_ENDPOINT_WAN, endpointWan)
                    .apply()
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
                        // Bind the current non-VPN underlay after UP. Path-adapt
                        // updates this when Wi-Fi/cellular changes.
                        runCatching { setUnderlyingNetworks(null) }
                        registerUnderlayWatch()
                        broadcastState(true, null)
                        startStatsPolling()
                        startBackgroundEgressValidation()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Connect failed", e)
                        stopStatsPolling()
                        // Keep KEY_CONFIG so sticky/Always-on can retry. Only the
                        // user's Disconnect clears the intended session.
                        runCatching {
                            backend.setState(this@VeritasVpnService, Tunnel.State.DOWN, null)
                        }
                        if (sessionIntended()) {
                            ServiceCompat.startForeground(
                                this@VeritasVpnService,
                                NOTIFICATION_ID,
                                buildNotification("Connected · recovering…"),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                            // Stay connected in the UI; retry bring-up shortly.
                            broadcastState(true, null)
                            delay(2_000)
                            if (sessionIntended() && disconnectJob?.isActive != true) {
                                runCatching {
                                    val retry = Config.parse(
                                        ByteArrayInputStream(config.toByteArray(Charsets.UTF_8))
                                    )
                                    backend.setState(this@VeritasVpnService, Tunnel.State.UP, retry)
                                    runCatching { setUnderlyingNetworks(null) }
                                    registerUnderlayWatch()
                                    startStatsPolling()
                                    startBackgroundEgressValidation()
                                    ServiceCompat.startForeground(
                                        this@VeritasVpnService,
                                        NOTIFICATION_ID,
                                        buildNotification("Connected · checking route…"),
                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                    )
                                    broadcastState(true, null)
                                }.onFailure { retryError ->
                                    Log.e(TAG, "Connect retry failed; keeping session intended", retryError)
                                }
                            }
                        } else {
                            broadcastState(false, friendlyError(e))
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
            ACTION_DISCONNECT -> {
                vpnStatePrefs().edit()
                    .remove(KEY_CONFIG)
                    .remove(KEY_ENDPOINT_LAN)
                    .remove(KEY_ENDPOINT_WAN)
                    .apply()
                unregisterUnderlayWatch()
                lastUnderlayFingerprint = null
                lastChosenEndpoint = null
                adaptJob?.cancel()
                restoreJob?.cancel()
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
                restoreSavedSessionIfNeeded()
            }
        }
        return START_STICKY
    }

    private var restoreJob: Job? = null

    private fun restoreSavedSessionIfNeeded() {
        if (vpnStatePrefs().getString(KEY_CONFIG, null) == null) return
        if (restoreJob?.isActive == true || disconnectJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, buildNotification("Restoring secure tunnel…"))
        restoreJob = scope.launch {
            while (sessionIntended() && disconnectJob?.isActive != true) {
                try {
                    val savedConfig = vpnStatePrefs().getString(KEY_CONFIG, null)
                        ?: return@launch
                    val parsed = Config.parse(
                        ByteArrayInputStream(savedConfig.toByteArray(Charsets.UTF_8))
                    )
                    backend.setState(this@VeritasVpnService, Tunnel.State.UP, parsed)
                    runCatching { setUnderlyingNetworks(null) }
                    registerUnderlayWatch()
                    ServiceCompat.startForeground(
                        this@VeritasVpnService,
                        NOTIFICATION_ID,
                        buildNotification("Connected · checking route…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    broadcastState(true, null)
                    startStatsPolling()
                    startBackgroundEgressValidation()
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Automatic VPN restore failed; retrying", e)
                    broadcastState(true, null)
                    delay(3_000)
                }
            }
        }
    }

    override fun onRevoke() {
        adaptJob?.cancel()
        unregisterUnderlayWatch()
        lastUnderlayFingerprint = null
        lastChosenEndpoint = null
        transitionJob?.cancel()
        validationGeneration++
        validationJob?.cancel()
        stopStatsPolling()
        runCatching { backend.setState(this, Tunnel.State.DOWN, null) }
        vpnStatePrefs().edit()
            .remove(KEY_CONFIG)
            .remove(KEY_ENDPOINT_LAN)
            .remove(KEY_ENDPOINT_WAN)
            .apply()
        broadcastState(
            false,
            "VPN permission was revoked. Tap Connect now to reconnect."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        adaptJob?.cancel()
        unregisterUnderlayWatch()
        stopStatsPolling()
        // Do not turn the tunnel DOWN here when a session is still intended —
        // START_STICKY / Always-on will restart and restore from KEY_CONFIG.
        if (!sessionIntended()) {
            runCatching { backend.setState(this, Tunnel.State.DOWN, null) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun getName(): String = TUNNEL_NAME

    override fun onStateChange(newState: Tunnel.State) {
        when (newState) {
            Tunnel.State.UP -> {
                startStatsPolling()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Verifying encrypted tunnel…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            else -> {
                if (!adapting) {
                    stopStatsPolling()
                }
                // Product rule: never treat an unintended DOWN as Disconnect.
                // Keep KEY_CONFIG and tell the UI we are still connected while
                // sticky restore / path-adapt brings the tunnel back.
                if (sessionIntended()) {
                    Log.w(TAG, "Tunnel DOWN while session intended; UI stays connected")
                    broadcastState(true, null)
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
        // Handshake age climbing toward ~2m is WireGuard's normal rekey window.
        // Never reconnect / tear down based on handshake age — that caused the
        // classic connect loop (HANDSHAKE_STALE_MS=120s in older builds).
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

    private fun vpnStatePrefs() = SecurePrefs.open(this, PREFS_NAME)

    private fun connectivityManager(): ConnectivityManager =
        getSystemService(ConnectivityManager::class.java)

    private fun registerUnderlayWatch() {
        if (underlayWatchRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager().registerNetworkCallback(request, underlayCallback)
            underlayWatchRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Could not watch underlay networks", error)
        }
    }

    private fun unregisterUnderlayWatch() {
        if (!underlayWatchRegistered) return
        runCatching { connectivityManager().unregisterNetworkCallback(underlayCallback) }
        underlayWatchRegistered = false
    }

    private fun scheduleUnderlayAdapt(preferred: Network?) {
        if (!sessionIntended()) return
        adaptJob?.cancel()
        adaptJob = scope.launch {
            delay(UNDERLAY_ADAPT_DEBOUNCE_MS)
            if (!sessionIntended() || disconnectJob?.isActive == true) return@launch
            transitionJob?.takeIf { it.isActive && it !== adaptJob }?.join()
            if (!sessionIntended() || disconnectJob?.isActive == true) return@launch
            adaptUnderlay(preferred)
        }
    }

    private fun adaptUnderlay(preferred: Network?) {
        val config = vpnStatePrefs().getString(KEY_CONFIG, null) ?: return
        val picked = bestUnderlay(preferred) ?: return
        val (network, link) = picked
        val ipv4s = ipv4Addresses(link)
        if (ipv4s.isEmpty()) return
        val current = EndpointSelector.endpointFromConfig(config)
        val chosen = EndpointSelector.choose(
            current = current,
            lan = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null),
            wan = vpnStatePrefs().getString(KEY_ENDPOINT_WAN, null),
            underlayIpv4s = ipv4s,
        )
        if (chosen.isEmpty()) return
        val fingerprint = underlayFingerprint(network, link, ipv4s)
        val firstBind = lastUnderlayFingerprint == null
        if (fingerprint == lastUnderlayFingerprint && chosen == lastChosenEndpoint) return
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
        if (firstBind && chosen == current) {
            lastUnderlayFingerprint = fingerprint
            lastChosenEndpoint = chosen
            Log.i(TAG, "path-adapt bind underlay=$fingerprint endpoint=$chosen")
            return
        }
        val updated = EndpointSelector.replaceEndpoint(config, chosen)
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(updated.toByteArray(Charsets.UTF_8)))
        }.getOrElse { error ->
            Log.e(TAG, "path-adapt config parse failed", error)
            return
        }
        adapting = true
        try {
            val state = backend.setState(this, Tunnel.State.UP, parsed)
            if (state != Tunnel.State.UP) {
                Log.w(TAG, "path-adapt backend state=$state")
                return
            }
            vpnStatePrefs().edit().putString(KEY_CONFIG, updated).apply()
            lastUnderlayFingerprint = fingerprint
            lastChosenEndpoint = chosen
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
            startStatsPolling()
            startBackgroundEgressValidation()
            Log.i(TAG, "path-adapt endpoint=$chosen underlay=$fingerprint")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "path-adapt failed; keeping session intended", e)
        } finally {
            adapting = false
        }
    }

    @Suppress("DEPRECATION")
    private fun bestUnderlay(preferred: Network?): Pair<Network, LinkProperties>? {
        val cm = connectivityManager()
        val seen = linkedSetOf<Network>()
        if (preferred != null) seen.add(preferred)
        cm.allNetworks.forEach { seen.add(it) }
        var bestWifi: Pair<Network, LinkProperties>? = null
        var bestAny: Pair<Network, LinkProperties>? = null
        for (network in seen) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val link = cm.getLinkProperties(network) ?: continue
            val pair = network to link
            val wifiOrEther = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (wifiOrEther) {
                if (bestWifi == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    bestWifi = pair
                }
            }
            if (bestAny == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                bestAny = pair
            }
        }
        return bestWifi ?: bestAny
    }

    private fun ipv4Addresses(link: LinkProperties): List<String> =
        link.linkAddresses.mapNotNull { addr ->
            val inet = addr.address as? Inet4Address ?: return@mapNotNull null
            if (inet.isLoopbackAddress) null else inet.hostAddress?.takeIf { it.isNotBlank() }
        }

    private fun underlayFingerprint(
        network: Network,
        link: LinkProperties,
        ipv4s: List<String>,
    ): String = listOf(
        network.networkHandle.toString(),
        link.interfaceName.orEmpty(),
        ipv4s.sorted().joinToString(","),
    ).joinToString("|")

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
        const val EXTRA_ENDPOINT_LAN = "endpoint_lan"
        const val EXTRA_ENDPOINT_WAN = "endpoint_wan"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        const val EXTRA_EGRESS_IP = "egress_ip"
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_HANDSHAKE_MS = "handshake_ms"
        const val PREFS_NAME = "veritas_vpn_state"
        const val KEY_CONFIG = "last_approved_config"
        const val KEY_ENDPOINT_LAN = "endpoint_lan"
        const val KEY_ENDPOINT_WAN = "endpoint_wan"
        private const val UNDERLAY_ADAPT_DEBOUNCE_MS = 1_200L
        private const val TAG = "VeritasVpnService"
        private val EGRESS_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
