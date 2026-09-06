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
import android.os.Build
import android.os.ParcelFileDescriptor
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
    private var restoreJob: Job? = null
    private var healthJob: Job? = null
    private var validationGeneration = 0L
    @Volatile private var sessionGeneration = 0L
    @Volatile private var underlayWatchRegistered = false
    private var lastUnderlayFingerprint: String? = null
    private var lastUnderlayIdentity: String? = null
    private var lastChosenEndpoint: String? = null
    private var lastRebindAtMs = 0L
    private val tunnelOpLock = Any()
    @Volatile private var tunKeeper: ParcelFileDescriptor? = null
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
                val connectGen = ++sessionGeneration
                lastUnderlayFingerprint = null
                lastUnderlayIdentity = null
                lastChosenEndpoint = null
                lastRebindAtMs = 0L
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
                        if (connectGen != sessionGeneration || !sessionIntended()) return@launch
                        val parsed = Config.parse(
                            ByteArrayInputStream(config.toByteArray(Charsets.UTF_8))
                        )
                        val state = applyBackendState(Tunnel.State.UP, parsed)
                        if (connectGen != sessionGeneration || !sessionIntended()) {
                            runCatching {
                                applyBackendState(Tunnel.State.DOWN, null)
                            }
                            return@launch
                        }
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
                        // Dynamic underlay only — never pin a Network object.
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
                            applyBackendState(Tunnel.State.DOWN, null)
                        }
                        if (connectGen == sessionGeneration && sessionIntended()) {
                            ServiceCompat.startForeground(
                                this@VeritasVpnService,
                                NOTIFICATION_ID,
                                buildNotification("Connected · recovering…"),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                            // Stay connected in the UI; retry bring-up shortly.
                            broadcastState(true, null)
                            delay(2_000)
                            if (connectGen == sessionGeneration &&
                                sessionIntended() &&
                                disconnectJob?.isActive != true
                            ) {
                                runCatching {
                                    val retry = Config.parse(
                                        ByteArrayInputStream(config.toByteArray(Charsets.UTF_8))
                                    )
                                    applyBackendState(Tunnel.State.UP, retry)
                                    if (connectGen != sessionGeneration || !sessionIntended()) {
                                        runCatching {
                                            applyBackendState(Tunnel.State.DOWN, null)
                                        }
                                        return@runCatching
                                    }
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
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                sessionGeneration++
                synchronized(tunnelOpLock) {
                    vpnStatePrefs().edit()
                        .remove(KEY_CONFIG)
                        .remove(KEY_ENDPOINT_LAN)
                        .remove(KEY_ENDPOINT_WAN)
                        .apply()
                }
                unregisterUnderlayWatch()
                lastUnderlayFingerprint = null
                lastUnderlayIdentity = null
                lastChosenEndpoint = null
                lastRebindAtMs = 0L
                adaptJob?.cancel()
                healthJob?.cancel()
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
                        applyBackendState(Tunnel.State.DOWN, null)
                    }
                    closeTunKeeper()
                    runCatching { setUnderlyingNetworks(null) }
                    broadcastState(false, null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                transitionJob = disconnectJob
                return START_NOT_STICKY
            }
            else -> {
                // Android may restart an Always-on VPN service without the original Intent.
                // Keep the last approved configuration so the tunnel can be restored.
                if (!sessionIntended()) {
                    return START_NOT_STICKY
                }
                restoreSavedSessionIfNeeded()
                return START_STICKY
            }
        }
    }

    private fun restoreSavedSessionIfNeeded() {
        if (vpnStatePrefs().getString(KEY_CONFIG, null) == null) return
        if (restoreJob?.isActive == true || disconnectJob?.isActive == true) return
        val restoreGen = sessionGeneration
        startForeground(NOTIFICATION_ID, buildNotification("Restoring secure tunnel…"))
        restoreJob = scope.launch {
            while (restoreGen == sessionGeneration &&
                sessionIntended() &&
                disconnectJob?.isActive != true
            ) {
                try {
                    val savedConfig = vpnStatePrefs().getString(KEY_CONFIG, null)
                        ?: return@launch
                    val parsed = Config.parse(
                        ByteArrayInputStream(savedConfig.toByteArray(Charsets.UTF_8))
                    )
                    applyBackendState(Tunnel.State.UP, parsed)
                    if (restoreGen != sessionGeneration || !sessionIntended()) {
                        runCatching {
                            applyBackendState(Tunnel.State.DOWN, null)
                        }
                        return@launch
                    }
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
                    if (restoreGen != sessionGeneration || !sessionIntended()) return@launch
                    broadcastState(true, null)
                    delay(3_000)
                }
            }
        }
    }

    override fun onRevoke() {
        sessionGeneration++
        adaptJob?.cancel()
        unregisterUnderlayWatch()
        lastUnderlayFingerprint = null
        lastUnderlayIdentity = null
        lastChosenEndpoint = null
        lastRebindAtMs = 0L
        transitionJob?.cancel()
        restoreJob?.cancel()
        validationGeneration++
        validationJob?.cancel()
        stopStatsPolling()
        runCatching { applyBackendState(Tunnel.State.DOWN, null) }
        closeTunKeeper()
        runCatching { setUnderlyingNetworks(null) }
        synchronized(tunnelOpLock) {
            vpnStatePrefs().edit()
                .remove(KEY_CONFIG)
                .remove(KEY_ENDPOINT_LAN)
                .remove(KEY_ENDPOINT_WAN)
                .apply()
        }
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
            runCatching { applyBackendState(Tunnel.State.DOWN, null) }
            closeTunKeeper()
            runCatching { setUnderlyingNetworks(null) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun getName(): String = TUNNEL_NAME

    /**
     * Keep a dup of the VpnService tun so path-adapt can restart userspace
     * WireGuard without Builder.establish(). Closing the last tun fd is what
     * drops the status-bar VPN key.
     */
    override fun getBuilder(): Builder {
        return object : Builder() {
            override fun establish(): ParcelFileDescriptor? {
                val pfd = super.establish() ?: return null
                val dup = runCatching { pfd.dup() }.getOrNull()
                if (dup != null) {
                    synchronized(tunnelOpLock) {
                        val old = tunKeeper
                        tunKeeper = dup
                        runCatching { old?.close() }
                    }
                }
                return pfd
            }
        }
    }

    override fun onStateChange(newState: Tunnel.State) {
        when (newState) {
            Tunnel.State.UP -> {
                if (!sessionIntended()) {
                    runCatching { applyBackendState(Tunnel.State.DOWN, null) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return
                }
                startStatsPolling()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Verifying encrypted tunnel…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            else -> {
                // Product rule: never treat an unintended DOWN as Disconnect.
                // Keep KEY_CONFIG, the system VPN icon (do not stopForeground),
                // and live stats while the session is still intended.
                if (sessionIntended()) {
                    Log.w(TAG, "Tunnel DOWN while session intended; UI stays connected")
                    broadcastState(true, null)
                    startStatsPolling()
                    return
                }
                stopStatsPolling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                broadcastState(false, null)
            }
        }
    }

    private fun startStatsPolling() {
        if (!sessionIntended()) return
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (sessionIntended()) {
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
                if (sessionIntended()) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification("Connected · $egressIp"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
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
        val sessionGen = sessionGeneration
        validationJob = scope.launch {
            try {
                val egressIp = verifyTunnelEgress()
                if (generation != validationGeneration) return@launch
                if (sessionGen != sessionGeneration || !sessionIntended()) return@launch
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
        if (connected && !sessionIntended()) return
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
            startHealthWatch()
        }.onFailure { error ->
            Log.w(TAG, "Could not watch underlay networks", error)
        }
    }

    private fun unregisterUnderlayWatch() {
        if (!underlayWatchRegistered) return
        runCatching { connectivityManager().unregisterNetworkCallback(underlayCallback) }
        underlayWatchRegistered = false
        healthJob?.cancel()
        healthJob = null
    }

    private fun scheduleUnderlayAdapt(preferred: Network?) {
        if (!sessionIntended()) return
        val gen = sessionGeneration
        adaptJob?.cancel()
        adaptJob = scope.launch {
            delay(UNDERLAY_ADAPT_DEBOUNCE_MS)
            if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) {
                return@launch
            }
            transitionJob?.takeIf { it.isActive && it !== adaptJob }?.join()
            if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) {
                return@launch
            }
            adaptUnderlay(preferred, gen)
        }
    }

    private suspend fun adaptUnderlay(preferred: Network?, gen: Long) {
        if (gen != sessionGeneration || !sessionIntended()) return
        val config = vpnStatePrefs().getString(KEY_CONFIG, null) ?: return
        val cm = connectivityManager()
        // A new preferred underlay (Wi‑Fi after cellular) must win even while
        // leftover cell is still validated. Picking cell and skipping froze
        // download at 0B on B→A.
        if (lastUnderlayFingerprint != null && preferred != null) {
            val pCaps = cm.getNetworkCapabilities(preferred)
            val pLink = cm.getLinkProperties(preferred)
            if (pCaps != null && pLink != null && isUsableUnderlay(pCaps)) {
                val pId = underlayIdentity(preferred, pLink)
                if (pId != lastUnderlayIdentity) {
                    if (!pCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                        ipv4Addresses(pLink).isEmpty()
                    ) {
                        Log.i(TAG, "path-adapt waiting for preferred underlay $pId")
                        return
                    }
                }
            }
        }
        val picked = bestUnderlay(preferred, requireValidated = lastUnderlayFingerprint != null)
            ?: return
        val (network, link) = picked
        val ipv4s = ipv4Addresses(link)
        if (ipv4s.isEmpty()) return
        val fingerprint = underlayFingerprint(network, link, ipv4s)
        // First callback after connect: record only. Never swap the API endpoint
        // (0.2.31 swapped WAN :443 → LAN :51820 on any 192.168.0.0/24 Wi-Fi).
        if (lastUnderlayFingerprint == null) {
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
            reprotectBackendSockets()
            lastUnderlayFingerprint = fingerprint
            lastUnderlayIdentity = underlayIdentity(network, link)
            lastChosenEndpoint = EndpointSelector.endpointFromConfig(config)
            Log.i(TAG, "path-adapt record underlay=$fingerprint endpoint=$lastChosenEndpoint")
            return
        }
        val identity = underlayIdentity(network, link)
        val sameUnderlay = identity == lastUnderlayIdentity
        val handshakeOk = latestHandshakeMs() > 0L &&
            (lastRebindAtMs == 0L || latestHandshakeMs() > lastRebindAtMs)
        if (sameUnderlay && (handshakeOk || lastRebindAtMs == 0L)) {
            // First-connect chatter (DHCP, VALIDATED) must not probe.
            lastUnderlayFingerprint = fingerprint
            lastUnderlayIdentity = identity
            return
        }
        if (gen != sessionGeneration || !sessionIntended()) return
        // Do not pin a Network during roam. Pinning the previous underlay (or
        // an unvalidated Wi‑Fi object) blackholes B→A until Disconnect.
        runCatching { setUnderlyingNetworks(null) }
        val current = EndpointSelector.endpointFromConfig(config)
        val probes = EndpointSelector.probeOrder(
            current = current,
            lan = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null),
            wan = vpnStatePrefs().getString(KEY_ENDPOINT_WAN, null),
            underlayIpv4s = ipv4s,
        )
        if (probes.isEmpty()) return
        Log.i(TAG, "path-adapt probe underlay=$fingerprint endpoints=$probes")
        var round = 0
        while (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
            val livePicked = if (round == 0) {
                network to ipv4s
            } else {
                val again = bestUnderlay(null, requireValidated = true) ?: break
                val nextIpv4s = ipv4Addresses(again.second)
                if (nextIpv4s.isEmpty()) break
                again.first to nextIpv4s
            }
            val liveNetwork = livePicked.first
            val liveIpv4s = livePicked.second
            val liveProbes = if (round == 0) probes else EndpointSelector.probeOrder(
                current = EndpointSelector.endpointFromConfig(
                    vpnStatePrefs().getString(KEY_CONFIG, null) ?: config
                ),
                lan = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null),
                wan = vpnStatePrefs().getString(KEY_ENDPOINT_WAN, null),
                underlayIpv4s = liveIpv4s,
            )
            val liveFp = underlayFingerprint(
                liveNetwork,
                connectivityManager().getLinkProperties(liveNetwork) ?: link,
                liveIpv4s,
            )
            for (endpoint in liveProbes) {
                if (gen != sessionGeneration || !sessionIntended()) return
                runCatching { setUnderlyingNetworks(null) }
                val liveConfig = EndpointSelector.replaceEndpoint(
                    vpnStatePrefs().getString(KEY_CONFIG, null) ?: config,
                    endpoint,
                )
                val parsed = runCatching {
                    Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
                }.getOrNull()
                if (parsed == null) {
                    Log.e(TAG, "path-adapt config parse failed for $endpoint")
                    continue
                }
                val reboundAt = System.currentTimeMillis()
                val recreateTun = round >= 1
                if (!rebindWithRetry(parsed, gen, recreateTun)) continue
                lastRebindAtMs = reboundAt
                reprotectBackendSockets()
                startStatsPolling()
                Log.i(TAG, "path-adapt rebound endpoint=$endpoint underlay=$liveFp")
                if (waitForHandshake(gen, reboundAt, HANDSHAKE_CONFIRM_MS)) {
                    synchronized(tunnelOpLock) {
                        if (gen != sessionGeneration || !sessionIntended()) return
                        vpnStatePrefs().edit().putString(KEY_CONFIG, liveConfig).apply()
                        lastUnderlayFingerprint = liveFp
                        lastUnderlayIdentity = underlayIdentity(
                            liveNetwork,
                            connectivityManager().getLinkProperties(liveNetwork) ?: link,
                        )
                        lastChosenEndpoint = endpoint
                    }
                    runCatching { setUnderlyingNetworks(arrayOf(liveNetwork)) }
                    reprotectBackendSockets()
                    startBackgroundEgressValidation()
                    Log.i(TAG, "path-adapt handshake ok endpoint=$endpoint")
                    return
                }
                Log.w(TAG, "path-adapt no handshake on $endpoint")
            }
            round++
            delay(PROBE_RETRY_DELAY_MS.coerceAtMost(8_000L))
        }
    }

    private fun rebindWithRetry(parsed: Config, gen: Long, recreateTun: Boolean = false): Boolean {
        return try {
            rebindLiveTunnel(parsed, gen, recreateTun)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "path-adapt rebound failed; retrying", e)
            runCatching { rebindLiveTunnel(parsed, gen, recreateTun) }
                .onFailure { retryError ->
                    Log.e(TAG, "path-adapt rebound retry failed", retryError)
                }
                .getOrDefault(false)
        }
    }

    private suspend fun waitForHandshake(gen: Long, afterMs: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (gen != sessionGeneration || !sessionIntended()) return false
            if (latestHandshakeMs() > afterMs) return true
            delay(400)
        }
        return gen == sessionGeneration && sessionIntended() && latestHandshakeMs() > afterMs
    }

    private fun latestHandshakeMs(): Long {
        val stats = runCatching { backend.getStatistics(this) }.getOrNull() ?: return 0L
        var handshakeMs = 0L
        for (key in stats.peers()) {
            val peer = stats.peer(key) ?: continue
            if (peer.latestHandshakeEpochMillis > handshakeMs) {
                handshakeMs = peer.latestHandshakeEpochMillis
            }
        }
        return handshakeMs
    }

    private fun applyBackendState(state: Tunnel.State, config: Config?): Tunnel.State {
        synchronized(tunnelOpLock) {
            return backend.setState(this, state, config)
        }
    }

    private fun closeTunKeeper() {
        synchronized(tunnelOpLock) {
            val old = tunKeeper
            tunKeeper = null
            runCatching { old?.close() }
        }
    }

    /**
     * Restart userspace WireGuard on the existing tun fd. Do not call
     * GoBackend.setState() / setStateInternal(): those establish() a new tun
     * (VPN key flicker) and DOWN calls stopSelf().
     * @return false if the user disconnected during the rebound.
     */
    private fun rebindLiveTunnel(parsed: Config, gen: Long, recreateTun: Boolean = false): Boolean {
        synchronized(tunnelOpLock) {
            if (gen != sessionGeneration || !sessionIntended()) return false
            try {
                val handleField = goField("currentTunnelHandle")
                val tunnelField = goField("currentTunnel")
                val configField = goField("currentConfig")
                val handle = handleField.getInt(backend)
                if (handle >= 0) {
                    goNative("wgTurnOff", Integer.TYPE).invoke(null, handle)
                    handleField.setInt(backend, -1)
                }
                tunnelField.set(backend, null)
                configField.set(backend, null)
                if (gen != sessionGeneration || !sessionIntended()) return false
                val keeper = tunKeeper
                if (keeper != null && !recreateTun) {
                    val goConfig = userspaceConfigOrThrow(parsed)
                    val dup = keeper.dup()
                    val fd = dup.detachFd()
                    val newHandle = try {
                        goNative(
                            "wgTurnOn",
                            String::class.java,
                            Integer.TYPE,
                            String::class.java,
                        ).invoke(null, TUNNEL_NAME, fd, goConfig) as Int
                    } catch (e: Exception) {
                        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                        throw e
                    }
                    if (newHandle < 0) {
                        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                        throw BackendException(
                            BackendException.Reason.GO_ACTIVATION_ERROR_CODE,
                            newHandle,
                        )
                    }
                    handleField.setInt(backend, newHandle)
                    tunnelField.set(backend, this)
                    configField.set(backend, parsed)
                    reprotectBackendSocketsLocked(newHandle)
                    return true
                }
                if (recreateTun) {
                    Log.w(TAG, "path-adapt recreating tun after keeper rebound failed")
                } else {
                    Log.w(TAG, "path-adapt missing tun keeper; falling back to setStateInternal")
                }
                val setStateInternal = GoBackend::class.java.getDeclaredMethod(
                    "setStateInternal",
                    Tunnel::class.java,
                    Config::class.java,
                    Tunnel.State::class.java,
                )
                setStateInternal.isAccessible = true
                setStateInternal.invoke(backend, this, parsed, Tunnel.State.UP)
                return true
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.targetException ?: e.cause ?: e
                if (cause is Exception) throw cause
                throw e
            }
        }
    }

    private fun userspaceConfigOrThrow(parsed: Config): String {
        for (peer in parsed.peers) {
            val ep = peer.endpoint.orElse(null) ?: continue
            if (!ep.resolved.isPresent) {
                throw BackendException(BackendException.Reason.DNS_RESOLUTION_FAILURE, ep.host)
            }
        }
        return parsed.toWgUserspaceString()
    }

    private fun goField(name: String) =
        GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun goNative(name: String, vararg types: Class<*>) =
        GoBackend::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

    private fun reprotectBackendSocketsLocked(handle: Int) {
        if (handle < 0) return
        val fd4 = goNative("wgGetSocketV4", Integer.TYPE).invoke(null, handle) as Int
        val fd6 = goNative("wgGetSocketV6", Integer.TYPE).invoke(null, handle) as Int
        if (fd4 >= 0) protect(fd4)
        if (fd6 >= 0) protect(fd6)
    }

    private fun reprotectBackendSockets() {
        runCatching {
            val handleField = GoBackend::class.java.getDeclaredField("currentTunnelHandle")
            handleField.isAccessible = true
            val handle = handleField.getInt(backend)
            if (handle < 0) return
            val v4 = GoBackend::class.java.getDeclaredMethod("wgGetSocketV4", Integer.TYPE)
            v4.isAccessible = true
            val v6 = GoBackend::class.java.getDeclaredMethod("wgGetSocketV6", Integer.TYPE)
            v6.isAccessible = true
            val fd4 = v4.invoke(null, handle) as Int
            val fd6 = v6.invoke(null, handle) as Int
            if (fd4 >= 0) protect(fd4)
            if (fd6 >= 0) protect(fd6)
        }.onFailure { error ->
            Log.w(TAG, "Could not reprotect WireGuard sockets", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun bestUnderlay(
        preferred: Network?,
        requireValidated: Boolean = false,
    ): Pair<Network, LinkProperties>? {
        val cm = connectivityManager()
        val seen = linkedSetOf<Network>()
        if (preferred != null) seen.add(preferred)
        cm.allNetworks.forEach { seen.add(it) }
        var bestValidated: Pair<Network, LinkProperties>? = null
        var bestValidatedScore = Int.MIN_VALUE
        var bestAny: Pair<Network, LinkProperties>? = null
        var bestAnyScore = Int.MIN_VALUE
        for (network in seen) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!isUsableUnderlay(caps)) continue
            val link = cm.getLinkProperties(network) ?: continue
            val pair = network to link
            val score = underlayScore(network, caps, preferred)
            if (score > bestAnyScore) {
                bestAny = pair
                bestAnyScore = score
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue
            if (score > bestValidatedScore) {
                bestValidated = pair
                bestValidatedScore = score
            }
        }
        return if (requireValidated) bestValidated else (bestValidated ?: bestAny)
    }

    private fun isUsableUnderlay(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    private fun underlayScore(
        network: Network,
        caps: NetworkCapabilities,
        preferred: Network?,
    ): Int = UnderlayRank.score(
        preferred = preferred != null && network == preferred,
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        foreground = isForegroundUnderlay(caps),
        wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
    )

    private fun startHealthWatch() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            while (sessionIntended()) {
                delay(HEALTH_WATCH_MS)
                if (!sessionIntended() || lastUnderlayIdentity == null) continue
                if (disconnectJob?.isActive == true || adaptJob?.isActive == true) continue
                val fg = bestUnderlay(null, requireValidated = true) ?: continue
                val id = underlayIdentity(fg.first, fg.second)
                if (id == lastUnderlayIdentity) continue
                Log.w(TAG, "path-adapt health: underlay moved $lastUnderlayIdentity -> $id")
                scheduleUnderlayAdapt(fg.first)
            }
        }
    }

    private fun isForegroundUnderlay(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
    }

    private fun ipv4Addresses(link: LinkProperties): List<String> =
        link.linkAddresses.mapNotNull { addr ->
            val inet = addr.address as? Inet4Address ?: return@mapNotNull null
            if (inet.isLoopbackAddress) null else inet.hostAddress?.takeIf { it.isNotBlank() }
        }

    private fun underlayIdentity(network: Network, link: LinkProperties): String =
        listOf(network.networkHandle.toString(), link.interfaceName.orEmpty()).joinToString("|")

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
        private const val UNDERLAY_ADAPT_DEBOUNCE_MS = 800L
        private const val HANDSHAKE_CONFIRM_MS = 5_000L
        private const val PROBE_RETRY_DELAY_MS = 1_500L
        private const val HEALTH_WATCH_MS = 4_000L
        private const val TAG = "VeritasVpnService"
        private val EGRESS_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
