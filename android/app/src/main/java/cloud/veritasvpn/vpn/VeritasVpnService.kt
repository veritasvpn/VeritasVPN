package cloud.veritasvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.Inet4Address
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import cloud.veritasvpn.MainActivity
import cloud.veritasvpn.R
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.UserFacingError
import cloud.veritasvpn.api.PeerResponse
import cloud.veritasvpn.auth.AuthRepository
import cloud.veritasvpn.auth.ControlAccess
import cloud.veritasvpn.auth.ControlRefresh
import cloud.veritasvpn.secure.SecurePrefs
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

class VeritasVpnService : GoBackend.VpnService(), Tunnel {

    class VpnError(override val userMessage: String) : Exception(userMessage), cloud.veritasvpn.api.UserVisibleError

    private val backend by lazy { GoBackend(applicationContext) }
    private val authRepo by lazy { AuthRepository(applicationContext) }
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
    @Volatile private var stealthTransport: StealthTransport? = null
    @Volatile private var activeTransport: String = ""
    @Volatile private var lastNotificationText: String = "Connecting…"
    @Volatile private var stealthSwitching = false
    private val stealthSwitchLock = Any()
    @Volatile private var bestMatchingNetwork: Network? = null
    @Volatile private var bestMatchingWatchRegistered = false
    private val underlayHandler = Handler(Looper.getMainLooper())
    @Volatile private var isReconnecting = false
    private var lastStallRebindAtMs = 0L
    private var stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
    private var lastKnownRx = 0L
    private var lastKnownTx = 0L
    private var lastStallCheckAtMs = 0L
    private var underlayWaitStartedAtMs = 0L
    private var hardLoopStartedAtMs = 0L
    private var stealthReconnectJob: Job? = null
    private var waitRecheckJob: Job? = null
    private var peerCheckJob: Job? = null
    private var reprovisionJob: Job? = null
    private var reprovisionBackoffMs = ReprovisionBackoff.INITIAL_MS
    private val reprovisionLock = Any()
    private var lastStatsLogAtMs = 0L
    private var healthUnderlayWaitStartedAtMs = 0L
    private var linkAttachedAtMs = 0L
    private var lastPeerVerifyAtMs = 0L
    private var underlayLostSeen = false
    private var rxAtLinkAttach = 0L
    /** NetId the UDP sockets were last bound to. Unset until a bind succeeds. */
    @Volatile private var socketsBoundHandle: Long = Long.MIN_VALUE
    /** NetId currently passed to setUnderlyingNetworks. Unset after an unpin. */
    @Volatile private var pinnedHandle: Long = Long.MIN_VALUE
    @Volatile private var lastPeerOutcome: PeerCheckOutcome.Outcome? = null
    @Volatile private var peerCheckSerial = 0
    private var lastPeerCheckAtMs = 0L
    @Volatile private var rebuildInProgress = false
    @Volatile private var rebuildDefaultHandle: Long = Long.MIN_VALUE
    /** Consecutive no-handshake rebuilds on one system-default handle. */
    private var rebuildStreakHandle: Long? = null
    private var rebuildsWithoutHandshake = 0
    /** Wakes the rebuild backoff when the default network or the peer result changes. */
    private val recoveryWakes = Channel<Unit>(Channel.CONFLATED)
    /**
     * Network callbacks only enqueue this. The pin itself runs on [scope] so a
     * rebuild holding [tunnelOpLock] cannot stall the callback thread.
     */
    private val defaultNetworkSignals = Channel<Unit>(Channel.CONFLATED)
    /** Signed-out control calls stop. The tunnel interface stays up. */
    @Volatile private var controlSignedOut = false
    /** OkHttp client bound to the current system-default network, if any. */
    @Volatile private var controlClient: OkHttpClient? = null
    @Volatile private var controlClientHandle: Long = Long.MIN_VALUE
    private val controlClientLock = Any()
    private val underlayCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            underlayLostSeen = false
            scheduleUnderlayAdapt()
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            scheduleUnderlayAdapt()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            scheduleUnderlayAdapt()
        override fun onLost(network: Network) {
            underlayLostSeen = true
            scheduleUnderlayAdapt()
        }
    }
    private val bestMatchingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            bestMatchingNetwork = network
            underlayLostSeen = false
            scheduleUnderlayAdapt()
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            bestMatchingNetwork = network
            scheduleUnderlayAdapt()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            bestMatchingNetwork = network
            scheduleUnderlayAdapt()
        }
        override fun onLost(network: Network) {
            if (bestMatchingNetwork == network) bestMatchingNetwork = null
            scheduleUnderlayAdapt()
        }
    }
    /** True while the user intends to stay connected (saved config present). */
    private fun sessionIntended(): Boolean =
        vpnStatePrefs().getString(KEY_CONFIG, null) != null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch {
            while (isActive) {
                defaultNetworkSignals.receive()
                if (!sessionIntended()) continue
                try {
                    noteDefaultNetworkForRecovery()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "default network pin failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Completes the shared VpnService future used by GoBackend so the backend
        // reuses THIS instance instead of starting the library's base service.
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_QUERY_STATE -> {
                if (!sessionIntended()) {
                    broadcastState(false, null)
                    return START_NOT_STICKY
                }

                // An activity can be recreated while this service stays alive,
                // so state broadcasts emitted before the receiver is registered
                // are intentionally queried again. A fresh service instance also
                // resumes the saved tunnel instead of reporting a stale state.
                if (tunKeeper == null &&
                    transitionJob?.isActive != true &&
                    restoreJob?.isActive != true
                ) {
                    restoreSavedSessionIfNeeded()
                }
                broadcastState(true, null)
                return START_STICKY
            }
            ACTION_AUTH_RESTORED -> {
                if (!sessionIntended()) return START_NOT_STICKY
                onAuthRestored()
                if (tunKeeper == null &&
                    transitionJob?.isActive != true &&
                    restoreJob?.isActive != true
                ) {
                    restoreSavedSessionIfNeeded()
                }
                return START_STICKY
            }
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: return START_STICKY
                val endpointLan = intent.getStringExtra(EXTRA_ENDPOINT_LAN).orEmpty()
                val endpointWan = intent.getStringExtra(EXTRA_ENDPOINT_WAN).orEmpty()
                val stealthEndpoint = intent.getStringExtra(EXTRA_STEALTH_ENDPOINT).orEmpty()
                val stealthPrefix = intent.getStringExtra(EXTRA_STEALTH_PREFIX).orEmpty()
                val stealthMode = intent.getStringExtra(EXTRA_STEALTH_MODE).orEmpty()
                val stealthAvailable = intent.getBooleanExtra(EXTRA_STEALTH_AVAILABLE, false)
                val connectGen = ++sessionGeneration
                cancelPeerRecovery()
                VpnSettings.clearPeerGoneAt(this)
                VpnSettings.clearReprovisionInProgressAt(this)
                lastUnderlayFingerprint = null
                lastUnderlayIdentity = null
                lastChosenEndpoint = null
                lastRebindAtMs = 0L
                isReconnecting = false
                lastStallRebindAtMs = 0L
                stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
                hardLoopStartedAtMs = 0L
                healthUnderlayWaitStartedAtMs = 0L
                underlayWaitStartedAtMs = 0L
                linkAttachedAtMs = System.currentTimeMillis()
                lastPeerVerifyAtMs = 0L
                underlayLostSeen = false
                rxAtLinkAttach = 0L
                socketsBoundHandle = Long.MIN_VALUE
                pinnedHandle = Long.MIN_VALUE
                lastPeerOutcome = null
                peerCheckSerial++
                lastPeerCheckAtMs = 0L
                rebuildInProgress = false
                rebuildDefaultHandle = Long.MIN_VALUE
                controlSignedOut = false
                dropUnderlyingControlClient()
                waitRecheckJob?.cancel()
                waitRecheckJob = null
                activeTransport = if (StealthMode.fromStored(stealthMode) == StealthMode.STEALTH) {
                    "stealth"
                } else {
                    "udp"
                }
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                validationJob?.cancel()
                val pendingDisconnect = disconnectJob?.takeIf { it.isActive }
                if (transitionJob?.isActive == true && transitionJob !== pendingDisconnect) {
                    transitionJob?.cancel()
                }
                transitionJob = scope.launch {
                    try {
                        // Encrypted prefs open the keystore. That work stays off
                        // the main thread so the connect-circle frame clock is
                        // not stalled while the session is saved. The same lock
                        // disconnect uses keeps a late clear from being overwritten.
                        synchronized(tunnelOpLock) {
                            if (connectGen != sessionGeneration) return@launch
                            vpnStatePrefs().edit()
                                .putString(KEY_CONFIG, config)
                                .putString(KEY_UDP_CONFIG, config)
                                .putString(KEY_ENDPOINT_LAN, endpointLan)
                                .putString(KEY_ENDPOINT_WAN, endpointWan)
                                .putString(KEY_STEALTH_ENDPOINT, stealthEndpoint)
                                .putString(KEY_STEALTH_PREFIX, stealthPrefix)
                                .putString(KEY_STEALTH_MODE, stealthMode.ifBlank { StealthMode.AUTO.stored() })
                                .putBoolean(KEY_STEALTH_AVAILABLE, stealthAvailable)
                                .putBoolean(KEY_RESUME_STEALTH, false)
                                .apply()
                        }
                        // Wait for a prior teardown, but keep the first startup
                        // path direct and free of an extra DOWN call.
                        pendingDisconnect?.join()
                        if (connectGen != sessionGeneration || !sessionIntended()) return@launch
                        val state = establishTunnel(config, connectGen)
                        if (connectGen != sessionGeneration || !sessionIntended()) {
                            runCatching {
                                applyBackendState(Tunnel.State.DOWN, null)
                            }
                            return@launch
                        }
                        if (state != Tunnel.State.UP) {
                            throw VpnError(
                                "The VPN tunnel didn't start. Try again."
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
                        // Bind protected sockets to the current system default only.
                        registerUnderlayWatch()
                        followSystemDefault()
                        broadcastState(true, null)
                        startStatsPolling()
                        startBackgroundEgressValidation()
                        VpnSettings.setSessionEstablishedAt(this@VeritasVpnService, System.currentTimeMillis())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Connect failed", e)
                        stopStatsPolling()
                        cancelStealthWork()
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
                                    establishTunnel(config, connectGen)
                                    if (connectGen != sessionGeneration || !sessionIntended()) {
                                        runCatching {
                                            applyBackendState(Tunnel.State.DOWN, null)
                                        }
                                        cancelStealthWork()
                                        return@runCatching
                                    }
                                    registerUnderlayWatch()
                                    followSystemDefault()
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
                        .remove(KEY_UDP_CONFIG)
                        .remove(KEY_ENDPOINT_LAN)
                        .remove(KEY_ENDPOINT_WAN)
                        .remove(KEY_STEALTH_ENDPOINT)
                        .remove(KEY_STEALTH_PREFIX)
                        .remove(KEY_STEALTH_MODE)
                        .remove(KEY_STEALTH_AVAILABLE)
                        .remove(KEY_RESUME_STEALTH)
                        .apply()
                }
                VpnSettings.clearSession(this)
                VpnSettings.clearPeerGoneAt(this)
                VpnSettings.clearReprovisionInProgressAt(this)
                cancelStealthWork()
                activeTransport = ""
                unregisterUnderlayWatch()
                lastUnderlayFingerprint = null
                lastUnderlayIdentity = null
                lastChosenEndpoint = null
                lastRebindAtMs = 0L
                isReconnecting = false
                lastStallRebindAtMs = 0L
                stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
                underlayWaitStartedAtMs = 0L
                hardLoopStartedAtMs = 0L
                healthUnderlayWaitStartedAtMs = 0L
                lastStatsLogAtMs = 0L
                socketsBoundHandle = Long.MIN_VALUE
                pinnedHandle = Long.MIN_VALUE
                lastPeerOutcome = null
                peerCheckSerial++
                lastPeerCheckAtMs = 0L
                rebuildInProgress = false
                rebuildDefaultHandle = Long.MIN_VALUE
                controlSignedOut = false
                dropUnderlyingControlClient()
                adaptJob?.cancel()
                healthJob?.cancel()
                cancelPeerRecovery()
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

    /**
     * A sign-in while the tunnel is still up. Clears the signed-out stop,
     * forces a peer check, and resumes recovery on the current system default
     * when the tunnel is not healthy. Does not tear the tun down and does not
     * switch to Stealth.
     */
    private fun onAuthRestored() {
        val wasSignedOut = controlSignedOut
        val decision = AuthRestore.afterSignIn(
            wasSignedOut = wasSignedOut,
            tunnelHealthy = controlTunnelHealthy(),
        )
        controlSignedOut = decision.signedOut
        synchronized(reprovisionLock) {
            lastPeerOutcome = AuthRestore.peerOutcomeAfterSignIn(lastPeerOutcome)
            peerCheckSerial++
        }
        Log.i(TAG, "auth restored")
        if (decision.resumeRecovery) {
            publishReconnecting(true)
            scheduleUnderlayAdapt(forceHard = true)
        } else {
            if (wasSignedOut) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Connected"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
            broadcastState(true, null)
        }
        if (decision.forcePeerCheck) ensurePeerCheck(force = true)
    }

    private fun restoreSavedSessionIfNeeded() {
        if (vpnStatePrefs().getString(KEY_CONFIG, null) == null) return
        if (VpnSettings.currentPeerId(this) == null) {
            synchronized(tunnelOpLock) {
                vpnStatePrefs().edit()
                    .remove(KEY_CONFIG)
                    .remove(KEY_UDP_CONFIG)
                    .remove(KEY_ENDPOINT_LAN)
                    .remove(KEY_ENDPOINT_WAN)
                    .remove(KEY_STEALTH_ENDPOINT)
                    .remove(KEY_STEALTH_PREFIX)
                    .remove(KEY_STEALTH_MODE)
                    .remove(KEY_STEALTH_AVAILABLE)
                    .remove(KEY_RESUME_STEALTH)
                    .apply()
            }
            return
        }
        if (restoreJob?.isActive == true || disconnectJob?.isActive == true) return
        val restoreGen = sessionGeneration
        startForeground(NOTIFICATION_ID, buildNotification("Restoring secure tunnel…"))
        restoreJob = scope.launch {
            while (restoreGen == sessionGeneration &&
                sessionIntended() &&
                disconnectJob?.isActive != true
            ) {
                try {
                    val savedConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                        ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                        ?: return@launch
                    establishTunnel(savedConfig, restoreGen)
                    if (restoreGen != sessionGeneration || !sessionIntended()) {
                        runCatching {
                            applyBackendState(Tunnel.State.DOWN, null)
                        }
                        return@launch
                    }
                    registerUnderlayWatch()
                    followSystemDefault()
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
                    cancelStealthWork()
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
        cancelPeerRecovery()
        unregisterUnderlayWatch()
        lastUnderlayFingerprint = null
        lastUnderlayIdentity = null
        lastChosenEndpoint = null
        lastRebindAtMs = 0L
        isReconnecting = false
        lastStallRebindAtMs = 0L
        stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
        lastKnownRx = 0L
        lastKnownTx = 0L
        lastStallCheckAtMs = 0L
        underlayWaitStartedAtMs = 0L
        hardLoopStartedAtMs = 0L
        healthUnderlayWaitStartedAtMs = 0L
        lastStatsLogAtMs = 0L
        socketsBoundHandle = Long.MIN_VALUE
        pinnedHandle = Long.MIN_VALUE
        lastPeerOutcome = null
        peerCheckSerial++
        lastPeerCheckAtMs = 0L
        rebuildInProgress = false
        rebuildDefaultHandle = Long.MIN_VALUE
        controlSignedOut = false
        dropUnderlyingControlClient()
        transitionJob?.cancel()
        restoreJob?.cancel()
        validationGeneration++
        validationJob?.cancel()
        stopStatsPolling()
        cancelStealthWork()
        runCatching { applyBackendState(Tunnel.State.DOWN, null) }
        closeTunKeeper()
        runCatching { setUnderlyingNetworks(null) }
        synchronized(tunnelOpLock) {
            vpnStatePrefs().edit()
                .remove(KEY_CONFIG)
                .remove(KEY_UDP_CONFIG)
                .remove(KEY_ENDPOINT_LAN)
                .remove(KEY_ENDPOINT_WAN)
                .remove(KEY_STEALTH_ENDPOINT)
                .remove(KEY_STEALTH_PREFIX)
                .remove(KEY_STEALTH_MODE)
                .remove(KEY_STEALTH_AVAILABLE)
                .remove(KEY_RESUME_STEALTH)
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
        cancelPeerRecovery()
        unregisterUnderlayWatch()
        stopStatsPolling()
        cancelStealthWork()
        // Do not turn the tunnel DOWN here when a session is still intended —
        // START_STICKY / Always-on will restart and restore from KEY_CONFIG.
        if (!sessionIntended()) {
            runCatching { applyBackendState(Tunnel.State.DOWN, null) }
            closeTunKeeper()
            runCatching { setUnderlyingNetworks(null) }
            cancelStealthWork()
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
                // The new tun has to exist before the previous keeper is closed.
                // Closing first drops the interface and, without lockdown, lets
                // app traffic out in the clear for the whole rebuild.
                var established: ParcelFileDescriptor? = null
                val duplicate = TunnelRebuild.replaceWhileHeld(
                    establishNew = {
                        val pfd = super.establish() ?: return@replaceWhileHeld null
                        established = pfd
                        runCatching { pfd.dup() }.getOrNull()
                    },
                    closePrevious = { newKeeper -> swapTunKeeper(newKeeper) },
                )
                if (TunnelRebuild.shouldClosePreviousKeeper(
                        newTunEstablished = established != null,
                        duplicateSucceeded = duplicate != null,
                    )
                ) {
                    // The new tun is already live. Drop the previous keeper so
                    // its fd does not stay open after the duplicate failed.
                    swapTunKeeper(null)
                }
                return established
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
        // Handshake age climbing toward ~2m is WireGuard's normal rekey window.
        // Never reconnect / tear down based on handshake age alone — that caused the
        // classic connect loop (HANDSHAKE_STALE_MS=120s in older builds). The stall
        // detector deliberately uses 180s together with rx/tx deltas to avoid that loop.
        val stats = runCatching { backend.getStatistics(this) }.getOrElse { e ->
            val now = System.currentTimeMillis()
            if (now - lastStatsLogAtMs >= STATS_LOG_INTERVAL_MS) {
                lastStatsLogAtMs = now
                Log.w(TAG, "stats read failed: ${e.javaClass.simpleName}")
            }
            return
        }
        var handshakeMs = 0L
        for (key in stats.peers()) {
            val peer = stats.peer(key) ?: continue
            if (peer.latestHandshakeEpochMillis > handshakeMs) {
                handshakeMs = peer.latestHandshakeEpochMillis
            }
        }
        sendBroadcast(
            Intent(ACTION_STATS).setPackage(packageName)
                .putExtra(EXTRA_RX_BYTES, stats.totalRx())
                .putExtra(EXTRA_TX_BYTES, stats.totalTx())
                .putExtra(EXTRA_HANDSHAKE_MS, handshakeMs)
                .putExtra(EXTRA_TRANSPORT, activeTransport)
                .putExtra(EXTRA_RECONNECTING, isReconnecting)
                .putExtra(EXTRA_HANDSHAKE_OK, handshakeOk())
                .putExtra(EXTRA_SIGN_IN_AGAIN, controlSignedOut)
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
                else -> UserFacingError.toUserMessage(e, this)
            }
        }
        return UserFacingError.toUserMessage(e, this)
    }

    private suspend fun verifyTunnelEgress(): String {
        repeat(3) { attempt ->
            val egressIp = raceEgressEndpoints()
            if (egressIp != null) {
                if (sessionIntended() && !isReconnecting) {
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
        throw VpnError(
            "Connected, but no traffic got through the VPN. Try again or switch server."
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
        intent.putExtra(EXTRA_SIGN_IN_AGAIN, controlSignedOut)
        if (egressIp != null) intent.putExtra(EXTRA_EGRESS_IP, egressIp)
        if (activeTransport.isNotEmpty()) intent.putExtra(EXTRA_TRANSPORT, activeTransport)
        intent.putExtra(EXTRA_RECONNECTING, isReconnecting)
        intent.putExtra(EXTRA_HANDSHAKE_OK, handshakeOk())
        sendBroadcast(intent)
    }

    private fun handshakeOk(): Boolean {
        val hs = latestHandshakeMs()
        if (hs <= 0L) return false
        val age = System.currentTimeMillis() - hs
        return age < HANDSHAKE_OK_AGE_MS
    }

    private fun buildNotification(text: String): Notification {
        lastNotificationText = text
        // A failed re-provision keeps isReconnecting set. Later status updates
        // must not paint "Connected" over Reconnecting while the peer is gone.
        // Signed out is its own state: not a raw error and not "Reconnecting…".
        val shown = if (controlSignedOut) {
            ControlPlane.SIGN_IN_AGAIN
        } else {
            transportSuffix(if (isReconnecting) "Reconnecting…" else text)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeritasVPN")
            .setContentText(shown)
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
        val cm = connectivityManager()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cm.registerBestMatchingNetworkCallback(
                    request,
                    bestMatchingCallback,
                    underlayHandler,
                )
                bestMatchingWatchRegistered = true
            }
            cm.registerNetworkCallback(request, underlayCallback)
            underlayWatchRegistered = true
            startHealthWatch()
        }.onFailure { error ->
            Log.w(TAG, "Could not watch underlay networks", error)
        }
    }

    private fun unregisterUnderlayWatch() {
        val cm = connectivityManager()
        if (bestMatchingWatchRegistered) {
            runCatching { cm.unregisterNetworkCallback(bestMatchingCallback) }
            bestMatchingWatchRegistered = false
            bestMatchingNetwork = null
        }
        if (underlayWatchRegistered) {
            runCatching { cm.unregisterNetworkCallback(underlayCallback) }
            underlayWatchRegistered = false
        }
        healthJob?.cancel()
        healthJob = null
    }

    private fun scheduleUnderlayAdapt(forceHard: Boolean = false) {
        // The callback thread must not take tunnelOpLock. A rebuild holds that
        // lock across wgTurnOn / establish. The pin runs on the service scope.
        if (sessionIntended()) defaultNetworkSignals.trySend(Unit)
        if (!sessionIntended() || controlSignedOut) return
        val gen = sessionGeneration
        if (rebuildInProgress) {
            // Same-network callbacks must not cancel the rebuild. A new default
            // wakes the loop so it can unpin, reset, and pin the new one.
            return
        }
        adaptJob?.cancel()
        adaptJob = scope.launch {
            delay(UNDERLAY_ADAPT_DEBOUNCE_MS)
            if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) {
                return@launch
            }
            // Wait for any active transition (connect/disconnect) to finish
            // before touching the tunnel.
            transitionJob?.takeIf { it.isActive && it !== adaptJob }?.join()
            if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) {
                return@launch
            }
            adaptUnderlay(gen, forceHard = forceHard)
        }
    }

    private suspend fun adaptUnderlay(gen: Long, forceHard: Boolean = false) {
        if (controlSignedOut || gen != sessionGeneration || !sessionIntended()) return
        if (stealthTransport != null || sessionMode() == StealthMode.STEALTH ||
            vpnStatePrefs().getBoolean(KEY_RESUME_STEALTH, false)
        ) {
            if (stealthTransport != null) {
                adaptWhileStealth(gen, forceHard)
            } else {
                val offer = currentOffer()
                if (offer.usable()) switchToStealth(gen, offer)
            }
            return
        }
        val config = vpnStatePrefs().getString(KEY_CONFIG, null) ?: return
        val live = liveUnderlay()
        val liveReady = live != null && ipv4Addresses(live.link).isNotEmpty() && live.validated
        val roam = UnderlayRoam.action(
            liveIdentity = live?.identity,
            liveReady = liveReady,
            lastIdentity = lastUnderlayIdentity,
        )

        if (lastUnderlayFingerprint == null) {
            // Same system default, even if it is not validated yet. Never a
            // different transport chosen by score.
            val record = live ?: systemDefaultSnap() ?: return
            val ipv4s = ipv4Addresses(record.link)
            if (ipv4s.isEmpty()) return
            followSystemDefault()
            lastUnderlayFingerprint = underlayFingerprint(record.network, record.link, ipv4s)
            lastUnderlayIdentity = record.identity
            lastChosenEndpoint = EndpointSelector.endpointFromConfig(config)
            Log.i(TAG, "path-adapt record net=${networkType(record.caps)} handle=${record.network.networkHandle}")
            return
        }

        if (roam == UnderlayRoam.Action.WAIT && !forceHard) {
            val now = System.currentTimeMillis()
            if (underlayWaitStartedAtMs == 0L) underlayWaitStartedAtMs = now
            if (now - underlayWaitStartedAtMs >= STALL_UNDERLAY_WAIT_FALLBACK_MS) {
                val fallback = systemDefaultSnap()
                if (fallback != null) {
                    val fbIpv4s = ipv4Addresses(fallback.link)
                    if (fbIpv4s.isNotEmpty()) {
                        Log.i(TAG, "path-adapt underlay wait ${now - underlayWaitStartedAtMs}ms, probing system default")
                        underlayWaitStartedAtMs = 0L
                        runRebindWithHandshake(gen, fallback, fbIpv4s, config, forceHard = true)
                        return
                    }
                }
            }
            Log.i(TAG, "path-adapt waiting for live underlay, will recheck in ${STALL_UNDERLAY_WAIT_FALLBACK_MS}ms")
            // Schedule a one-shot recheck after the fallback timeout to make it predictable
            // instead of relying solely on the health watch firing.
            // Use a tracked job to avoid stacking multiple timers (B3).
            waitRecheckJob?.cancel()
            waitRecheckJob = scope.launch {
                delay(STALL_UNDERLAY_WAIT_FALLBACK_MS)
                if (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
                    // Use forceHard=false so the normal WAIT→fallback logic runs
                    scheduleUnderlayAdapt(forceHard = false)
                }
            }
            return
        }
        underlayWaitStartedAtMs = 0L

        var effective = live
        if (effective == null) {
            val fallback = systemDefaultSnap() ?: return
            if (ipv4Addresses(fallback.link).isEmpty()) return
            effective = fallback
        }

        val ipv4s = ipv4Addresses(effective.link)
        if (ipv4s.isEmpty()) return
        if (roam == UnderlayRoam.Action.SKIP && !forceHard) {
            lastUnderlayFingerprint = underlayFingerprint(effective.network, effective.link, ipv4s)
            return
        }
        if (gen != sessionGeneration || !sessionIntended()) return

        runRebindWithHandshake(gen, effective, ipv4s, config, forceHard = forceHard)
    }

    private suspend fun runRebindWithHandshake(
        gen: Long,
        underlay: UnderlaySnap,
        ipv4s: List<String>,
        config: String,
        forceHard: Boolean,
    ) {
        var handshakeRecovered = false
        try {
            publishReconnecting(true)
            broadcastState(true, null)
            Log.i(
                TAG,
                "path-adapt roam handle=${underlay.network.networkHandle} forceHard=$forceHard",
            )
            followSystemDefault()

            if (!forceHard) {
                val attachedAt = System.currentTimeMillis()
                val softConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                    ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                    ?: config
                val softParsed = runCatching {
                    Config.parse(ByteArrayInputStream(softConfig.toByteArray(Charsets.UTF_8)))
                }.getOrNull()
                val softHandshakeBaseline = latestHandshakeMs()
                if (softParsed != null && rebindWithRetry(softParsed, gen, recreateTun = false)) {
                    if (waitForHandshake(gen, attachedAt, SOFT_ATTACH_MS) &&
                        RebindDecisions.softRebindSucceeded(latestHandshakeMs(), attachedAt, softHandshakeBaseline)
                    ) {
                        lastUnderlayFingerprint = underlayFingerprint(underlay.network, underlay.link, ipv4s)
                        lastUnderlayIdentity = underlay.identity
                        startStatsPolling()
                        Log.i(TAG, "path-adapt soft rebind ok handle=${underlay.network.networkHandle}")
                        handshakeRecovered = true
                        return
                    }
                }
                if (gen != sessionGeneration || !sessionIntended()) return
                Log.i(TAG, "path-adapt soft rebind no handshake -> hard")
            }

            val probes = EndpointSelector.probeOrder(
                current = EndpointSelector.endpointFromConfig(config),
                lan = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null),
                wan = vpnStatePrefs().getString(KEY_ENDPOINT_WAN, null),
                underlayIpv4s = ipv4s,
            )
            if (probes.isEmpty()) return
            hardLoopStartedAtMs = System.currentTimeMillis()
            var round = 0
            var probeRoundsCompleted = 0
            var pollDelay = STALL_UNDERLAY_POLL_MS
            var roundDelay = PROBE_RETRY_DELAY_MS
            while (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
                if (controlSignedOut) return
                if (stealthTransport != null) return
                when (recoveryStep(probeRoundsCompleted >= 1, rebuilding = false)) {
                    TunnelRebuild.RecoveryStep.REBUILD -> {
                        // Another probe would reuse the stuck socket. Rebuild on
                        // the current system default, then back off. A peer-check
                        // network error takes this path too. A deleted peer does not.
                        handshakeRecovered = rebuildUntilHandshake(gen)
                        return
                    }
                    TunnelRebuild.RecoveryStep.REPROVISION,
                    TunnelRebuild.RecoveryStep.STOP -> return
                    TunnelRebuild.RecoveryStep.WAIT,
                    TunnelRebuild.RecoveryStep.RESET -> Unit
                }
                val elapsed = System.currentTimeMillis() - hardLoopStartedAtMs
                val defaultSnap = systemDefaultSnap()
                val validatedSnap = defaultSnap?.takeIf {
                    it.validated && ipv4Addresses(it.link).isNotEmpty()
                }
                val unvalidatedSnap = if (elapsed >= STALL_UNDERLAY_WAIT_FALLBACK_MS && validatedSnap == null) {
                    defaultSnap?.takeIf { ipv4Addresses(it.link).isNotEmpty() }
                } else {
                    null
                }
                val resolved = RebindDecisions.resolveHardLoopUnderlay(
                    elapsedMs = elapsed,
                    fallbackAfterMs = STALL_UNDERLAY_WAIT_FALLBACK_MS,
                    validatedUnderlay = validatedSnap?.identity,
                    unvalidatedUnderlay = unvalidatedSnap?.identity,
                )
                if (resolved == null) {
                    delay(pollDelay)
                    pollDelay = (pollDelay * 2).coerceAtMost(STALL_UNDERLAY_MAX_POLL_MS)
                    continue
                }
                pollDelay = STALL_UNDERLAY_POLL_MS
                val currentLive = if (resolved == validatedSnap?.identity) validatedSnap else unvalidatedSnap
                if (currentLive == null || currentLive.network != systemDefaultNetwork()) {
                    delay(STALL_UNDERLAY_POLL_MS)
                    continue
                }
                val liveIpv4s = ipv4Addresses(currentLive.link)
                if (liveIpv4s.isEmpty()) {
                    delay(STALL_UNDERLAY_POLL_MS)
                    continue
                }
                followSystemDefault()
                val liveProbes = if (round == 0) probes else EndpointSelector.probeOrder(
                    current = EndpointSelector.endpointFromConfig(
                        vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                            ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                            ?: config
                    ),
                    lan = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null),
                    wan = vpnStatePrefs().getString(KEY_ENDPOINT_WAN, null),
                    underlayIpv4s = liveIpv4s,
                )
                val liveFp = underlayFingerprint(currentLive.network, currentLive.link, liveIpv4s)
                for (endpoint in liveProbes) {
                    if (controlSignedOut || gen != sessionGeneration || !sessionIntended() || stealthTransport != null) return
                    val baseConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                        ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                        ?: config
                    val liveConfig = EndpointSelector.replaceEndpoint(baseConfig, endpoint)
                    val parsed = runCatching {
                        Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
                    }.getOrNull()
                    if (parsed == null) {
                        logHardProbe(baseConfig, endpoint, currentLive)
                        Log.e(TAG, "path-adapt config parse failed")
                        continue
                    }
                    val reboundAt = System.currentTimeMillis()
                    val recreateTun = round >= 1
                    val rebound = rebindWithRetry(parsed, gen, recreateTun)
                    lastRebindAtMs = reboundAt
                    logHardProbe(baseConfig, endpoint, currentLive)
                    if (!rebound) continue
                    startStatsPolling()
                    if (waitForHandshake(gen, reboundAt, HANDSHAKE_CONFIRM_MS)) {
                        if (stealthTransport != null || vpnStatePrefs().getBoolean(KEY_RESUME_STEALTH, false)) {
                            return
                        }
                        synchronized(tunnelOpLock) {
                            if (gen != sessionGeneration || !sessionIntended()) return
                            val prefs = vpnStatePrefs()
                            prefs.edit()
                                .putString(
                                    KEY_CONFIG,
                                    SavedPeerConfig.coalesce(prefs.getString(KEY_CONFIG, null), liveConfig),
                                )
                                .putString(
                                    KEY_UDP_CONFIG,
                                    SavedPeerConfig.coalesce(prefs.getString(KEY_UDP_CONFIG, null), liveConfig),
                                )
                                .putBoolean(KEY_RESUME_STEALTH, false)
                                .apply()
                            lastUnderlayFingerprint = liveFp
                            lastUnderlayIdentity = currentLive.identity
                            lastChosenEndpoint = endpoint
                        }
                        publishTransport("udp")
                        followSystemDefault()
                        startBackgroundEgressValidation()
                        Log.i(TAG, "path-adapt handshake ok")
                        handshakeRecovered = true
                        return
                    }
                    Log.w(TAG, "path-adapt no handshake")
                }
                if (stealthTransport != null) return
                probeRoundsCompleted++
                if (lastPeerOutcome == null) ensurePeerCheck()
                when (recoveryStep(probeRoundCompleted = true, rebuilding = false)) {
                    TunnelRebuild.RecoveryStep.REBUILD -> continue
                    TunnelRebuild.RecoveryStep.REPROVISION,
                    TunnelRebuild.RecoveryStep.STOP -> return
                    TunnelRebuild.RecoveryStep.WAIT,
                    TunnelRebuild.RecoveryStep.RESET -> Unit
                }
                // Failed UDP rounds stay on UDP. Stealth is only a user choice
                // or a resumed Stealth session, never a recovery step.
                round++
                delay(roundDelay)
                roundDelay = (roundDelay * 2).coerceAtMost(30_000L)
            }
        } finally {
            if (handshakeRecovered ||
                gen != sessionGeneration ||
                !sessionIntended() ||
                disconnectJob?.isActive == true
            ) {
                publishReconnecting(false)
            }
        }
    }

    /**
     * Tear userspace WireGuard down and bring a new tun up from the saved
     * config on the current system-default network, before the old tun is closed.
     *
     * Lockdown stays fail-closed: this never calls setState(DOWN) (that
     * stops the service) and never allows bypass. Repeats one at a time with
     * backoff from 5s to 60s until a handshake, the user disconnects, or the
     * peer check says the peer was deleted. Backoff resets when the system
     * default changes. The peer check keeps running through the waits.
     */
    private suspend fun rebuildUntilHandshake(gen: Long): Boolean {
        var backoff = TunnelRebuild.MIN_BACKOFF_MS
        var waiting = false
        var trackedHandle: Long? = null
        rebuildInProgress = true
        try {
            while (true) {
                if (controlSignedOut) return false
                ensurePeerCheck()
                val handle = systemDefaultNetwork()?.networkHandle
                val step = TunnelRebuild.decide(
                    sessionActive = sessionStillActive(gen),
                    peer = lastPeerOutcome,
                    handshakeCompleted = false,
                    probeRoundCompleted = true,
                    rebuilding = true,
                    waiting = waiting,
                    defaultHandle = handle,
                    trackedHandle = trackedHandle,
                )
                when (step) {
                    TunnelRebuild.RecoveryStep.STOP -> return false
                    TunnelRebuild.RecoveryStep.REPROVISION -> {
                        requestReprovision(gen)
                        return false
                    }
                    TunnelRebuild.RecoveryStep.RESET -> {
                        applyRecoveryReset(handle)
                        backoff = TunnelRebuild.MIN_BACKOFF_MS
                        waiting = false
                        trackedHandle = handle
                        continue
                    }
                    TunnelRebuild.RecoveryStep.WAIT -> {
                        val changed = awaitRecoveryWake(backoff, trackedHandle)
                        if (changed) {
                            backoff = TunnelRebuild.MIN_BACKOFF_MS
                        } else if (lastPeerOutcome != PeerCheckOutcome.Outcome.PEER_GONE) {
                            backoff = TunnelRebuild.nextBackoff(backoff)
                        }
                        waiting = false
                        continue
                    }
                    TunnelRebuild.RecoveryStep.REBUILD -> {
                        val snap = systemDefaultSnap()
                        if (snap == null || ipv4Addresses(snap.link).isEmpty() ||
                            snap.network.networkHandle != handle
                        ) {
                            if (handle == null) pinUnderlyingToSystemDefault()
                            waiting = true
                            continue
                        }
                        trackedHandle = snap.network.networkHandle
                        rebuildDefaultHandle = trackedHandle ?: Long.MIN_VALUE
                        publishReconnecting(true)
                        broadcastState(true, null)
                        val saved = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                            ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                            ?: return false
                        val parsed = runCatching {
                            Config.parse(ByteArrayInputStream(saved.toByteArray(Charsets.UTF_8)))
                        }.getOrNull()
                        if (parsed == null) {
                            Log.w(TAG, "path-adapt full rebuild config unreadable")
                            waiting = true
                            continue
                        }
                        val started = System.currentTimeMillis()
                        val rebuilt = try {
                            rebuildTunnelInterface(parsed, gen)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "path-adapt full rebuild failed: ${e.javaClass.simpleName}")
                            false
                        }
                        logHardProbe(saved, EndpointSelector.endpointFromConfig(saved), snap)
                        if (stealthTransport != null ||
                            vpnStatePrefs().getBoolean(KEY_RESUME_STEALTH, false)
                        ) {
                            return false
                        }
                        val handshake = rebuilt && waitForHandshake(gen, started, HANDSHAKE_CONFIRM_MS)
                        if (rebuilt) {
                            lastRebindAtMs = started
                            startStatsPolling()
                        }
                        val after = TunnelRebuild.decide(
                            sessionActive = sessionStillActive(gen),
                            peer = lastPeerOutcome,
                            handshakeCompleted = handshake,
                            probeRoundCompleted = true,
                            rebuilding = true,
                            waiting = !handshake,
                            defaultHandle = systemDefaultNetwork()?.networkHandle,
                            trackedHandle = trackedHandle,
                        )
                        when (after) {
                            TunnelRebuild.RecoveryStep.STOP -> {
                                if (controlSignedOut || !handshake) return false
                                synchronized(tunnelOpLock) {
                                    if (!sessionStillActive(gen)) return false
                                    val prefs = vpnStatePrefs()
                                    prefs.edit()
                                        .putString(
                                            KEY_CONFIG,
                                            SavedPeerConfig.coalesce(prefs.getString(KEY_CONFIG, null), saved),
                                        )
                                        .putString(
                                            KEY_UDP_CONFIG,
                                            SavedPeerConfig.coalesce(prefs.getString(KEY_UDP_CONFIG, null), saved),
                                        )
                                        .putBoolean(KEY_RESUME_STEALTH, false)
                                        .apply()
                                    val fresh = ipv4Addresses(snap.link)
                                    lastUnderlayFingerprint = underlayFingerprint(snap.network, snap.link, fresh)
                                    lastUnderlayIdentity = snap.identity
                                    lastChosenEndpoint = EndpointSelector.endpointFromConfig(saved)
                                }
                                publishTransport("udp")
                                followSystemDefault()
                                startBackgroundEgressValidation()
                                resetRebuildStreak()
                                Log.i(TAG, "path-adapt full rebuild handshake ok")
                                return true
                            }
                            TunnelRebuild.RecoveryStep.REPROVISION -> {
                                requestReprovision(gen)
                                return false
                            }
                            TunnelRebuild.RecoveryStep.RESET -> {
                                val now = systemDefaultNetwork()?.networkHandle
                                applyRecoveryReset(now)
                                backoff = TunnelRebuild.MIN_BACKOFF_MS
                                waiting = false
                                trackedHandle = now
                                continue
                            }
                            TunnelRebuild.RecoveryStep.WAIT,
                            TunnelRebuild.RecoveryStep.REBUILD -> {
                                Log.w(TAG, "path-adapt full rebuild no handshake")
                                val streak = TunnelRebuild.noteFailedRebuild(
                                    TunnelRebuild.RebuildStreak(rebuildStreakHandle, rebuildsWithoutHandshake),
                                    trackedHandle,
                                )
                                rebuildStreakHandle = streak.handle
                                rebuildsWithoutHandshake = streak.count
                                // The rate limit is bypassed after every failed rebuild.
                                // After a few on this same default network, the check is
                                // forced onto the protected client bound to that network.
                                // A missing or failed check is not treated as peer-gone.
                                ensurePeerCheck(
                                    force = TunnelRebuild.bypassPeerCheckRateLimit(failedRebuildRound = true),
                                    forceUnderlying = TunnelRebuild.shouldForceUnderlyingPeerCheck(streak.count),
                                )
                                waiting = true
                            }
                        }
                    }
                }
            }
        } finally {
            rebuildInProgress = false
        }
    }

    private fun sessionStillActive(gen: Long): Boolean =
        gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true

    private fun recoveryStep(probeRoundCompleted: Boolean, rebuilding: Boolean): TunnelRebuild.RecoveryStep =
        TunnelRebuild.decide(
            sessionActive = sessionIntended() && disconnectJob?.isActive != true,
            peer = lastPeerOutcome,
            handshakeCompleted = false,
            probeRoundCompleted = probeRoundCompleted,
            rebuilding = rebuilding,
            waiting = false,
            defaultHandle = systemDefaultNetwork()?.networkHandle,
            trackedHandle = null,
        )

    private fun applyRecoveryReset(handle: Long?) {
        rebuildDefaultHandle = handle ?: Long.MIN_VALUE
        resetRebuildStreak()
        clearCachedPeerOutcome()
        ensurePeerCheck(force = true)
        if (handle == null) pinUnderlyingToSystemDefault() else followSystemDefault()
    }

    private fun resetRebuildStreak() {
        rebuildStreakHandle = null
        rebuildsWithoutHandshake = 0
    }

    /**
     * Wait [backoffMs] or until the default network or a deleted-peer result
     * wakes the loop. Returns true when the system default handle changed.
     */
    private suspend fun awaitRecoveryWake(backoffMs: Long, handle: Long?): Boolean {
        val deadline = System.currentTimeMillis() + backoffMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return false
            withTimeoutOrNull(remaining) { recoveryWakes.receive() }
            if (systemDefaultNetwork()?.networkHandle != handle) return true
            if (lastPeerOutcome == PeerCheckOutcome.Outcome.PEER_GONE) return false
        }
        return systemDefaultNetwork()?.networkHandle != handle
    }

    /**
     * Unpin when the phone has no system default, and pin again when a new
     * one appears. A handle change drops a stale "alive" peer result so a
     * deleted peer can still be re-provisioned.
     */
    private fun noteDefaultNetworkForRecovery() {
        val next = systemDefaultNetwork()?.networkHandle
        val previous = pinnedHandle
        val changed = previous != Long.MIN_VALUE && next != previous
        if (controlClient != null && (next == null || next != controlClientHandle)) {
            dropUnderlyingControlClient()
        }
        if (changed) {
            // Drop a sticky network error (and any other non-alive result) and
            // check again on this default network. Do not keep the old answer.
            clearCachedPeerOutcome()
            ensurePeerCheck(force = true)
        }
        if (next == null) {
            if (previous != Long.MIN_VALUE) pinUnderlyingToSystemDefault()
        } else if (next != previous) {
            followSystemDefault()
        }
        recoveryWakes.trySend(Unit)
    }

    /**
     * A default-network change drops every cached peer result except signed-out.
     * The in-flight check is invalidated so its late answer cannot stick.
     */
    private fun clearCachedPeerOutcome() {
        synchronized(reprovisionLock) {
            if (!TunnelRebuild.shouldClearCachedPeerOutcome(lastPeerOutcome)) return
            peerCheckSerial++
            lastPeerOutcome = null
        }
    }

    private fun recordPeerOutcome(serial: Int, outcome: PeerCheckOutcome.Outcome): Boolean {
        val recorded = synchronized(reprovisionLock) {
            if (serial != peerCheckSerial) {
                false
            } else {
                lastPeerOutcome = outcome
                true
            }
        }
        if (recorded &&
            (outcome == PeerCheckOutcome.Outcome.PEER_GONE ||
                outcome == PeerCheckOutcome.Outcome.SIGNED_OUT)
        ) {
            recoveryWakes.trySend(Unit)
        }
        return recorded
    }

    private fun logHardProbe(config: String, endpoint: String, snap: UnderlaySnap) {
        val prefs = vpnStatePrefs()
        Log.i(
            TAG,
            ProbeDiagnostics.format(
                keyPrefix = ProbeDiagnostics.publicKeyPrefix(config),
                kind = ProbeDiagnostics.endpointKind(
                    endpoint,
                    prefs.getString(KEY_ENDPOINT_LAN, null),
                    prefs.getString(KEY_ENDPOINT_WAN, null),
                ),
                networkType = networkType(snap.caps),
                handle = snap.network.networkHandle,
                socketBound = socketsBoundHandle == snap.network.networkHandle,
            ),
        )
    }

    /**
     * Bring the tunnel up on the transport the user selected. Auto and UDP
     * stay on UDP. Stealth is used only when the user picked it, or when this
     * process is resuming a session that is already on Stealth.
     */
    private suspend fun establishTunnel(udpConfig: String, gen: Long): Tunnel.State {
        cancelStealthWork()
        if (gen != sessionGeneration || !sessionIntended()) return Tunnel.State.DOWN
        val offer = currentOffer()
        val mode = sessionMode()
        val resumeStealth = vpnStatePrefs().getBoolean(KEY_RESUME_STEALTH, false)
        return when (StealthPlanner.initial(mode, offer, resumeStealth)) {
            InitialTransport.STEALTH_UNAVAILABLE ->
                throw VpnError("Stealth is not available on the VPN node yet.")
            InitialTransport.STEALTH -> bringUp(udpConfig, gen, stealth = true, offer = offer)
            InitialTransport.UDP -> bringUp(udpConfig, gen, stealth = false, offer = null)
        }
    }

    private fun bringUp(
        udpConfig: String,
        gen: Long,
        stealth: Boolean,
        offer: StealthOffer?,
    ): Tunnel.State {
        val liveConfig = if (stealth) {
            EndpointSelector.replaceEndpoint(udpConfig, startStealth(requireNotNull(offer)))
        } else {
            udpConfig
        }
        if (gen != sessionGeneration || !sessionIntended()) {
            if (stealth) stopStealthTransport()
            return Tunnel.State.DOWN
        }
        val parsed = Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
        followSystemDefault()
        val state = applyBackendState(Tunnel.State.UP, parsed)
        followSystemDefault()
        synchronized(tunnelOpLock) {
            if (gen == sessionGeneration && sessionIntended()) {
                val prefs = vpnStatePrefs()
                prefs.edit()
                    .putString(KEY_CONFIG, SavedPeerConfig.coalesce(prefs.getString(KEY_CONFIG, null), liveConfig))
                    .putString(KEY_UDP_CONFIG, SavedPeerConfig.coalesce(prefs.getString(KEY_UDP_CONFIG, null), udpConfig))
                    .putBoolean(KEY_RESUME_STEALTH, stealth)
                    .apply()
                lastChosenEndpoint = EndpointSelector.endpointFromConfig(liveConfig)
            }
        }
        activeTransport = if (stealth) "stealth" else "udp"
        return state
    }

    private suspend fun switchToStealth(gen: Long, offer: StealthOffer) {
        if (gen != sessionGeneration || !sessionIntended() || !offer.usable()) return
        synchronized(stealthSwitchLock) {
            if (stealthTransport != null || stealthSwitching) return
            stealthSwitching = true
        }
        publishTransport("switching")
        try {
            val udpConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                ?: run {
                    if (gen == sessionGeneration && sessionIntended()) publishTransport("udp")
                    return
                }
            val local = startStealth(offer)
            if (gen != sessionGeneration || !sessionIntended()) {
                stopStealthTransport()
                return
            }
            val live = EndpointSelector.replaceEndpoint(udpConfig, local)
            val parsed = Config.parse(ByteArrayInputStream(live.toByteArray(Charsets.UTF_8)))
            if (!rebindWithRetry(parsed, gen, recreateTun = false)) {
                Log.w(TAG, "Stealth rebind did not stick; restoring the UDP tunnel")
                restoreUdpTunnel(udpConfig, gen)
                return
            }
            synchronized(tunnelOpLock) {
                if (gen != sessionGeneration || !sessionIntended()) return
                val prefs = vpnStatePrefs()
                prefs.edit()
                    .putString(KEY_CONFIG, SavedPeerConfig.coalesce(prefs.getString(KEY_CONFIG, null), live))
                    .putBoolean(KEY_RESUME_STEALTH, true)
                    .apply()
                lastChosenEndpoint = local
            }
            publishTransport("stealth")
            publishReconnecting(false)
            startBackgroundEgressValidation()
            Log.i(TAG, "Stealth active")
        } catch (e: CancellationException) {
            stopStealthTransport()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Stealth fallback failed; tunnel stays up on UDP", e)
            val udpConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
            if (udpConfig != null) restoreUdpTunnel(udpConfig, gen) else stopStealthTransport()
        } finally {
            stealthSwitching = false
        }
    }

    private fun restoreUdpTunnel(udpConfig: String, gen: Long) {
        stopStealthTransport()
        if (gen != sessionGeneration || !sessionIntended()) return
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(udpConfig.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return
        if (rebindWithRetry(parsed, gen, recreateTun = false)) {
            synchronized(tunnelOpLock) {
                if (gen == sessionGeneration && sessionIntended()) {
                    val prefs = vpnStatePrefs()
                    prefs.edit()
                        .putString(
                            KEY_CONFIG,
                            SavedPeerConfig.coalesce(prefs.getString(KEY_CONFIG, null), udpConfig),
                        )
                        .putBoolean(KEY_RESUME_STEALTH, false)
                        .apply()
                }
            }
        }
        if (gen == sessionGeneration && sessionIntended()) publishTransport("udp")
    }

    private fun startStealth(offer: StealthOffer): String {
        val endpoint = WstunnelProtocol.parseEndpoint(offer.endpoint)
            ?: throw VpnError("Stealth mode isn't available right now.")
        WstunnelProtocol.upgradePath(offer.pathPrefix)
        stopStealthTransport()
        val transport = StealthTransport(
            remoteHost = endpoint.host,
            remotePort = endpoint.port,
            pathPrefix = offer.pathPrefix,
            protectSocket = { socket -> protect(socket) },
        )
        transport.start()
        stealthTransport = transport
        return transport.localEndpoint
    }

    private fun stopStealthTransport() {
        val transport = stealthTransport
        stealthTransport = null
        transport?.stop()
    }

    private fun cancelStealthWork() {
        stealthSwitching = false
        stealthReconnectJob?.cancel()
        stealthReconnectJob = null
        waitRecheckJob?.cancel()
        waitRecheckJob = null
        stopStealthTransport()
    }

    private fun publishTransport(transport: String) {
        activeTransport = transport
        if (!sessionIntended()) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(lastNotificationText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        broadcastState(true, null)
    }

    private fun publishReconnecting(reconnecting: Boolean) {
        if (controlSignedOut) return
        isReconnecting = reconnecting
        if (!sessionIntended()) return
        val text = if (reconnecting) "Reconnecting…" else lastNotificationText
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        broadcastState(true, null)
    }

    private fun transportSuffix(text: String): String {
        val label = when (activeTransport) {
            "stealth" -> "Stealth"
            "switching" -> "Switching to Stealth"
            "udp" -> "Direct UDP"
            else -> return text
        }
        if (text.contains(label)) return text
        return "$text · $label"
    }

    private fun sessionMode(): StealthMode =
        StealthMode.fromStored(vpnStatePrefs().getString(KEY_STEALTH_MODE, null))

    private fun currentOffer(): StealthOffer {
        val prefs = vpnStatePrefs()
        return StealthOffer(
            endpoint = prefs.getString(KEY_STEALTH_ENDPOINT, null).orEmpty(),
            pathPrefix = prefs.getString(KEY_STEALTH_PREFIX, null).orEmpty(),
            available = prefs.getBoolean(KEY_STEALTH_AVAILABLE, false),
        )
    }

    /** Redial the protected TLS socket. WireGuard stays pointed at localhost. */
    private fun adaptWhileStealth(gen: Long, forceHard: Boolean = false) {
        if (gen != sessionGeneration || !sessionIntended()) return
        val live = liveUnderlay() ?: systemDefaultSnap()
        if (live == null) {
            Log.i(TAG, "path-adapt stealth waiting for underlay")
            return
        }
        val ipv4s = ipv4Addresses(live.link)
        if (ipv4s.isEmpty()) return
        val fp = underlayFingerprint(live.network, live.link, ipv4s)
        if (lastUnderlayFingerprint == null) {
            followSystemDefault()
            lastUnderlayFingerprint = fp
            lastUnderlayIdentity = live.identity
            lastChosenEndpoint = stealthTransport?.localEndpoint ?: lastChosenEndpoint
            Log.i(TAG, "path-adapt stealth record underlay=${live.identity}")
            return
        }
        // When forceHard is true, skip the identity/fingerprint early-return.
        // This allows the stall detector to redial a Stealth tunnel that is
        // stalled on an unchanged network (R1).
        // Also redial when reconnecting, when underlay was lost, or when the
        // handshake predates the current link (no working handshake on this link).
        val hs = latestHandshakeMs()
        val handshakeStaleForLink = linkAttachedAtMs > 0L && (hs <= 0L || hs <= linkAttachedAtMs)
        if (!forceHard && !isReconnecting && !underlayLostSeen && !handshakeStaleForLink &&
            live.identity == lastUnderlayIdentity && fp == lastUnderlayFingerprint
        ) return
        Log.i(TAG, "path-adapt stealth redial ${lastUnderlayIdentity} -> ${live.identity}")
        followSystemDefault()
        publishReconnecting(true)
        stealthTransport?.reconnect()
        lastUnderlayFingerprint = fp
        lastUnderlayIdentity = live.identity
        lastChosenEndpoint = stealthTransport?.localEndpoint ?: lastChosenEndpoint
        val redialAt = System.currentTimeMillis()
        linkAttachedAtMs = redialAt
        underlayLostSeen = false
        stealthReconnectJob?.cancel()
        stealthReconnectJob = scope.launch {
            var succeeded = false
            try {
                val stealthTimeout = 9_000L
                val backoff = StealthBackoff.sequence().iterator()
                var attempt = 0
                while (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
                    attempt++
                    val ok = waitForHandshake(gen, redialAt, stealthTimeout)
                    if (ok) {
                        Log.i(TAG, "stealth redial n=$attempt handshake ok")
                        succeeded = true
                        return@launch
                    }
                    if (gen != sessionGeneration || !sessionIntended()) return@launch
                    val live = liveUnderlay()
                    if (live == null) {
                        Log.i(TAG, "stealth redial n=$attempt no underlay, waiting with fallback")
                        val waitStartedAt = System.currentTimeMillis()
                        var fallbackUsed = false
                        while (gen == sessionGeneration && sessionIntended()) {
                            val validated = liveUnderlay()
                            if (validated != null) break
                            val elapsed = System.currentTimeMillis() - waitStartedAt
                            if (!fallbackUsed && elapsed >= STALL_UNDERLAY_WAIT_FALLBACK_MS) {
                                val fallback = systemDefaultSnap()
                                if (fallback != null) {
                                    Log.i(TAG, "stealth redial using unvalidated fallback after ${elapsed}ms")
                                    fallbackUsed = true
                                    break
                                }
                            }
                            if (elapsed >= 30_000L) {
                                Log.w(TAG, "stealth redial underlay timeout 30s, continuing anyway")
                                break
                            }
                            delay(1_000)
                        }
                        if (gen != sessionGeneration || !sessionIntended()) return@launch
                        Log.i(TAG, "stealth redial underlay back, resuming")
                        linkAttachedAtMs = System.currentTimeMillis()
                        val s0 = readBackendStats()
                        rxAtLinkAttach = s0.first
                        continue
                    }
                    val delayMs = if (backoff.hasNext()) backoff.next() else StealthBackoff.MAX_MS
                    val jitter = (Math.random() * StealthBackoff.JITTER_MS).toLong()
                    Log.w(TAG, "stealth redial n=$attempt no handshake, backoff=${delayMs}ms")
                    delay(delayMs + jitter)
                    stealthTransport?.reconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "stealth redial exception: ${e.javaClass.simpleName}")
            } finally {
                if (succeeded) {
                    publishReconnecting(false)
                }
            }
        }
    }

    private fun rebindWithRetry(parsed: Config, gen: Long, recreateTun: Boolean = false): Boolean {
        return try {
            rebindLiveTunnel(parsed, gen, recreateTun)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "path-adapt rebound failed: ${e.javaClass.simpleName}")
            runCatching { rebindLiveTunnel(parsed, gen, recreateTun) }
                .onFailure { retryError ->
                    Log.w(TAG, "path-adapt rebound retry failed: ${retryError.javaClass.simpleName}")
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
        swapTunKeeper(null)
    }

    private fun swapTunKeeper(newKeeper: ParcelFileDescriptor?) {
        synchronized(tunnelOpLock) {
            val old = tunKeeper
            tunKeeper = newKeeper
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
            // Drop the stale netId before the new sockets exist. protect() on
            // HyperOS binds to whatever underlying network is set right now.
            socketsBoundHandle = Long.MIN_VALUE
            pinUnderlyingToSystemDefault()
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
                    bindBackendSocketsToSystemDefault()
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
                bindBackendSocketsToSystemDefault()
                return true
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.targetException ?: e.cause ?: e
                if (cause is Exception) throw cause
                throw e
            }
        }
    }

    /**
     * Full tunnel rebuild on the current system-default network. Userspace is
     * stopped first, but the existing tun stays open until [getBuilder]
     * establish() has created the replacement. The new sockets are then bound
     * to that same default and to no other network.
     */
    private fun rebuildTunnelInterface(parsed: Config, gen: Long): Boolean {
        synchronized(tunnelOpLock) {
            if (gen != sessionGeneration || !sessionIntended()) return false
            socketsBoundHandle = Long.MIN_VALUE
            try {
                val handleField = goField("currentTunnelHandle")
                val handle = handleField.getInt(backend)
                if (handle >= 0) {
                    goNative("wgTurnOff", Integer.TYPE).invoke(null, handle)
                    handleField.setInt(backend, -1)
                }
                goField("currentTunnel").set(backend, null)
                goField("currentConfig").set(backend, null)
                if (gen != sessionGeneration || !sessionIntended()) return false
                val setStateInternal = GoBackend::class.java.getDeclaredMethod(
                    "setStateInternal",
                    Tunnel::class.java,
                    Config::class.java,
                    Tunnel.State::class.java,
                )
                setStateInternal.isAccessible = true
                setStateInternal.invoke(backend, this, parsed, Tunnel.State.UP)
                followSystemDefault()
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

    /**
     * Keep protected UDP sockets on the phone's current system-default network.
     *
     * HyperOS lockdown `protect()` binds a socket to the VPN underlying netId
     * and does not move that binding when the netId goes stale.
     * `setUnderlyingNetworks(null)` leaves the previous binding in place, so
     * handshake packets stay on the network the phone already left.
     *
     * Point the underlying network at the current system default only — never
     * a different transport — and bind the sockets to that same network. When
     * the default handle changes, callers create a new socket and bind again.
     */
    private fun followSystemDefault() {
        pinUnderlyingToSystemDefault()
        bindBackendSocketsToSystemDefault()
    }

    private fun pinUnderlyingToSystemDefault() {
        synchronized(tunnelOpLock) {
            val network = systemDefaultNetwork()
            val handle = TunnelRebuild.pinHandle(network?.networkHandle)
            if (network == null || handle == null) {
                runCatching { setUnderlyingNetworks(null) }
                socketsBoundHandle = Long.MIN_VALUE
                pinnedHandle = Long.MIN_VALUE
                return
            }
            runCatching { setUnderlyingNetworks(arrayOf(network)) }.onSuccess {
                pinnedHandle = handle
            }
        }
    }

    private fun bindBackendSocketsToSystemDefault() {
        val network = systemDefaultNetwork()
        val handle = TunnelRebuild.pinHandle(network?.networkHandle)
        if (network == null || handle == null) {
            socketsBoundHandle = Long.MIN_VALUE
            return
        }
        runCatching {
            val tunnelHandle = goField("currentTunnelHandle").getInt(backend)
            if (tunnelHandle < 0) {
                socketsBoundHandle = Long.MIN_VALUE
                return
            }
            val v4 = goNative("wgGetSocketV4", Integer.TYPE).invoke(null, tunnelHandle) as Int
            val v6 = goNative("wgGetSocketV6", Integer.TYPE).invoke(null, tunnelHandle) as Int
            val boundV4 = bindFdToNetwork(network, v4)
            val boundV6 = bindFdToNetwork(network, v6)
            socketsBoundHandle = if (boundV4 || boundV6) handle else Long.MIN_VALUE
        }.onFailure { error ->
            socketsBoundHandle = Long.MIN_VALUE
            Log.w(TAG, "Could not bind WireGuard sockets: ${error.javaClass.simpleName}")
        }
    }

    /**
     * protect() then bindSocket, in that order. protect() can stamp the old
     * netId; bindSocket forces the fd onto [network]. adopt/detach so the
     * WireGuard fd stays open.
     */
    private fun bindFdToNetwork(network: Network, fd: Int): Boolean {
        if (fd < 0) return false
        protect(fd)
        val adopted = ParcelFileDescriptor.adoptFd(fd)
        return try {
            network.bindSocket(adopted.fileDescriptor)
            true
        } catch (e: Exception) {
            Log.w(TAG, "socket bind failed: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { adopted.detachFd() }
        }
    }

    private data class UnderlaySnap(
        val network: Network,
        val link: LinkProperties,
        val caps: NetworkCapabilities,
    ) {
        val identity: String
            get() = listOf(
                network.networkHandle.toString(),
                link.interfaceName.orEmpty(),
            ).joinToString("|")
        val validated: Boolean
            get() = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun snapshotUnderlay(network: Network?): UnderlaySnap? {
        if (network == null) return null
        val caps = connectivityManager().getNetworkCapabilities(network) ?: return null
        if (!isUsableUnderlay(caps)) return null
        val link = connectivityManager().getLinkProperties(network) ?: return null
        return UnderlaySnap(network, link, caps)
    }

    /** Validated system-default underlay. Never a scored alternate transport. */
    private fun liveUnderlay(): UnderlaySnap? {
        val snap = systemDefaultSnap() ?: return null
        if (!snap.validated || ipv4Addresses(snap.link).isEmpty()) return null
        return snap
    }

    /**
     * The phone's system-default underlay. On Android 12+ that is the
     * best-matching NOT_VPN network. Older releases have no such callback:
     * the active non-VPN network is used, or the only non-VPN network when
     * there is just one. Two transports are never ranked against each other.
     */
    @Suppress("DEPRECATION")
    private fun systemDefaultNetwork(): Network? {
        bestMatchingNetwork?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        val cm = connectivityManager()
        val active = cm.activeNetwork
        if (active != null && isNonVpnInternet(active)) return active
        var only: Network? = null
        for (network in cm.allNetworks) {
            if (!isNonVpnInternet(network)) continue
            if (only != null) return null
            only = network
        }
        return only
    }

    private fun isNonVpnInternet(network: Network): Boolean {
        val caps = connectivityManager().getNetworkCapabilities(network) ?: return false
        return isUsableUnderlay(caps)
    }

    /** Unvalidated is still this same network — never the other transport. */
    private fun systemDefaultSnap(): UnderlaySnap? = snapshotUnderlay(systemDefaultNetwork())

    private fun controlTunnelHealthy(): Boolean = ControlPlane.tunnelHealthy(
        reconnecting = isReconnecting,
        rebuilding = rebuildInProgress,
        adapting = adaptJob?.isActive == true,
        handshakeFresh = handshakeOk(),
    )

    /**
     * OkHttp client bound to [systemDefaultNetwork]. Null when that network
     * is gone. Never a client for any other network.
     *
     * Each socket is protect()'d and then bindSocket()'d to that network
     * before it connects. protect() alone can stamp a stale VPN netId;
     * bindSocket pins the system default. Some OEM builds reject a bound
     * socket that was not protected, so both steps run.
     */
    private fun underlyingControlClient(): OkHttpClient? {
        val network = systemDefaultNetwork() ?: run {
            dropUnderlyingControlClient()
            return null
        }
        val handle = TunnelRebuild.pinHandle(network.networkHandle) ?: return null
        synchronized(controlClientLock) {
            val cached = controlClient
            if (cached != null && controlClientHandle == handle) return cached
            val created = ControlPlane.clientForSystemDefault(
                socketFactory = ControlPlane.protectThenBind(
                    delegate = network.socketFactory,
                    protect = { socket -> this@VeritasVpnService.protect(socket) },
                    bind = { socket -> network.bindSocket(socket) },
                ),
            ) { host ->
                if (!ControlPlane.hostMayLeaveTunnel(host)) {
                    throw java.net.UnknownHostException(host)
                }
                network.getAllByName(host).toList()
            }
            if (systemDefaultNetwork()?.networkHandle != handle) {
                created.connectionPool.evictAll()
                return null
            }
            val previous = controlClient
            controlClient = created
            controlClientHandle = handle
            if (previous != null && previous !== created) {
                previous.connectionPool.evictAll()
            }
            return created
        }
    }

    private fun dropUnderlyingControlClient() {
        synchronized(controlClientLock) {
            val previous = controlClient
            controlClient = null
            controlClientHandle = Long.MIN_VALUE
            previous?.connectionPool?.evictAll()
        }
    }

    private fun isUsableUnderlay(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    private fun networkType(caps: NetworkCapabilities): String = ProbeDiagnostics.networkType(
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)),
    )

    private fun cancelPeerRecovery() {
        peerCheckJob?.cancel()
        peerCheckJob = null
        synchronized(reprovisionLock) {
            ReprovisionFlight.cancelCurrent()
            reprovisionJob?.cancel()
            reprovisionJob = null
        }
        VpnSettings.clearReprovisionInProgressAt(this)
        resetRebuildStreak()
        reprovisionBackoffMs = ReprovisionBackoff.INITIAL_MS
    }

    private fun ensurePeerCheck(force: Boolean = false, forceUnderlying: Boolean = false) {
        if (controlSignedOut || !sessionIntended() || disconnectJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force) {
            if (peerCheckJob?.isActive == true) return
            if (lastPeerCheckAtMs != 0L &&
                now - lastPeerCheckAtMs < TunnelRebuild.PEER_CHECK_INTERVAL_MS
            ) {
                return
            }
        }
        schedulePeerCheck(forceUnderlying)
    }

    private fun schedulePeerCheck(forceUnderlying: Boolean = false) {
        if (controlSignedOut) return
        val serial = synchronized(reprovisionLock) { ++peerCheckSerial }
        lastPeerCheckAtMs = System.currentTimeMillis()
        peerCheckJob?.cancel()
        // Captured before the peer request so a disconnect during the call is visible.
        val gen = sessionGeneration
        peerCheckJob = scope.launch {
            fun stillCurrent() = serial == peerCheckSerial && sessionStillActive(gen) && !controlSignedOut
            var via = if (forceUnderlying) "underlying" else "tunnel"
            var handle: Long? = systemDefaultNetwork()?.networkHandle
            fun finish(outcome: PeerCheckOutcome.Outcome, code: Int) {
                Log.i(TAG, PeerCheckLog.line(outcome.name, code, via, handle))
                if (!stillCurrent()) return
                VpnSettings.notePeerCheck(this@VeritasVpnService, outcome.name, code, via)
                applyPeerPlan(serial, gen, PeerRecovery.plan(outcome))
            }
            if (!stillCurrent()) return@launch
            val peerId = VpnSettings.currentPeerId(this@VeritasVpnService)
            if (peerId == null) {
                finish(PeerCheckOutcome.Outcome.NETWORK_ERROR, 0)
                return@launch
            }

            // A forced check always uses the client bound to the current
            // system default. It does not fall back to another network, and
            // a failure to bind is a network error, not a deleted peer.
            val healthy = if (forceUnderlying) false else controlTunnelHealthy()
            val underlying = if (forceUnderlying || !healthy) underlyingControlClient() else null
            if (forceUnderlying && underlying == null) {
                via = "underlying"
                handle = null
                finish(PeerCheckOutcome.Outcome.NETWORK_ERROR, 0)
                return@launch
            }
            val http = if (forceUnderlying) {
                underlying!!
            } else {
                ControlPlane.selectClient(
                    tunnelHealthy = healthy,
                    underlying = underlying,
                    normal = ApiClient.sharedClient(),
                )
            }
            via = if (underlying != null && http === underlying) "underlying" else "tunnel"
            handle = if (via == "underlying") {
                controlClientHandle.takeIf { it != Long.MIN_VALUE }
            } else {
                systemDefaultNetwork()?.networkHandle
            }
            val access = try {
                authRepo.accessForControl(underlying, tunnelHealthy = healthy)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ControlAccess.Unavailable
            }
            val early = PeerRecovery.planForAccess(
                signedOut = access is ControlAccess.SignedOut,
                unavailable = access is ControlAccess.Unavailable,
            )
            if (early != null) {
                if (stillCurrent()) finish(early.outcome, 0)
                return@launch
            }
            val token = (access as ControlAccess.Ready).token

            try {
                val res = ApiClient.get("/api/v1/wg/peers/$peerId", token, httpClient = http)
                val outcome = PeerCheckOutcome.map(res.code)
                val code = res.code
                when (outcome) {
                    PeerCheckOutcome.Outcome.PEER_ALIVE -> {
                        res.close()
                        if (stillCurrent()) finish(outcome, code)
                    }
                    PeerCheckOutcome.Outcome.REFRESH_AND_RETRY -> {
                        res.close()
                        if (stillCurrent()) {
                            Log.i(TAG, PeerCheckLog.line(outcome.name, code, via, handle))
                        }
                        if (!stillCurrent()) return@launch
                        // A 401 means the server rejected the token. Refresh on the
                        // same client as the check, then retry the peer check once.
                        when (val refreshed = authRepo.refreshForControl(http, force = true)) {
                            ControlRefresh.Refreshed -> {
                                val nextToken = authRepo.getAccessToken()?.takeIf { it.isNotBlank() }
                                if (nextToken == null) {
                                    if (stillCurrent()) finish(PeerCheckOutcome.Outcome.SIGNED_OUT, 401)
                                    return@launch
                                }
                                val retry = ApiClient.get("/api/v1/wg/peers/$peerId", nextToken, httpClient = http)
                                val retryOutcome = PeerCheckOutcome.map(retry.code)
                                val retryCode = retry.code
                                retry.close()
                                if (!stillCurrent()) return@launch
                                finish(retryOutcome, retryCode)
                            }
                            ControlRefresh.SignedOut -> {
                                if (stillCurrent()) finish(PeerCheckOutcome.Outcome.SIGNED_OUT, 401)
                            }
                            ControlRefresh.Unavailable -> {
                                if (stillCurrent()) finish(PeerCheckOutcome.Outcome.NETWORK_ERROR, 401)
                            }
                        }
                    }
                    PeerCheckOutcome.Outcome.PEER_GONE,
                    PeerCheckOutcome.Outcome.NETWORK_ERROR,
                    PeerCheckOutcome.Outcome.SIGNED_OUT -> {
                        res.close()
                        if (stillCurrent()) finish(outcome, code)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "peer check failed: ${e.javaClass.simpleName}")
                if (stillCurrent()) finish(PeerCheckOutcome.Outcome.NETWORK_ERROR, 0)
            }
        }
    }

    private fun applyPeerPlan(serial: Int, gen: Long, plan: PeerRecovery.Plan) {
        if (!recordPeerOutcome(serial, plan.outcome)) return
        when (plan.next) {
            PeerRecovery.Next.REPROVISION -> {
                // Only an actual peer-gone answer reaches here. A network
                // error stays CONTINUE and does not create a peer.
                if (serial == peerCheckSerial && !controlSignedOut) requestReprovision(gen)
            }
            PeerRecovery.Next.STOP -> stopForSignedOut()
            PeerRecovery.Next.CONTINUE -> Unit
        }
    }

    /**
     * Stop peer checks, rebuilds, and re-provision retries. The VPN interface
     * stays up so lockdown remains fail-closed. The UI is told to sign in
     * again, with no exception text.
     */
    private fun stopForSignedOut() {
        controlSignedOut = true
        isReconnecting = false
        synchronized(reprovisionLock) {
            reprovisionJob?.cancel()
            reprovisionJob = null
        }
        reprovisionBackoffMs = ReprovisionBackoff.INITIAL_MS
        recoveryWakes.trySend(Unit)
        if (!sessionIntended()) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ControlPlane.SIGN_IN_AGAIN),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        broadcastState(true, ControlPlane.SIGN_IN_AGAIN)
    }

    private fun logReprovision(outcome: String, code: Int) {
        Log.i(TAG, PeerCheckLog.reprovision(outcome, code))
        // "started" is the log line only. The terminal outcome is what the
        // diagnostic snapshot counts, so one attempt is not recorded twice.
        if (outcome == "started") return
        VpnSettings.noteReprovision(this, outcome, code)
    }

    private fun requestReprovision(gen: Long) {
        if (controlSignedOut) return
        val now = System.currentTimeMillis()
        synchronized(reprovisionLock) {
            val epoch = ReprovisionFlight.tryClaim(
                persistedAtMs = VpnSettings.reprovisionInProgressAt(this),
                nowMs = now,
                replaceWaiting = true,
            ) { claimedAt ->
                VpnSettings.setReprovisionInProgressAt(this, claimedAt)
            }
            if (epoch == null) {
                logReprovision("skipped", 0)
                return
            }
            val previous = reprovisionJob
            reprovisionJob = scope.launch { runReprovision(gen, epoch) }
            if (previous != null && previous !== reprovisionJob) previous.cancel()
        }
    }

    private suspend fun runReprovision(gen: Long, epoch: Int) {
        val keep = try {
            handlePeerGone(gen, epoch)
        } catch (e: CancellationException) {
            ReprovisionFlight.release(epoch) { VpnSettings.clearReprovisionInProgressAt(this) }
            throw e
        }
        if (!keep) {
            ReprovisionFlight.release(epoch) { VpnSettings.clearReprovisionInProgressAt(this) }
        }
    }

    private fun scheduleReprovisionRetry(gen: Long, epoch: Int): Boolean {
        if (controlSignedOut) return false
        val now = System.currentTimeMillis()
        if (!ReprovisionFlight.markWaiting(epoch, now) { claimedAt ->
                VpnSettings.setReprovisionInProgressAt(this, claimedAt)
            }
        ) {
            return false
        }
        val waitMs = reprovisionBackoffMs
        reprovisionBackoffMs = ReprovisionBackoff.next(reprovisionBackoffMs)
        synchronized(reprovisionLock) {
            reprovisionJob = scope.launch {
                try {
                    delay(waitMs)
                } catch (e: CancellationException) {
                    return@launch
                }
                if (controlSignedOut || !sessionStillActive(gen)) {
                    ReprovisionFlight.release(epoch) {
                        VpnSettings.clearReprovisionInProgressAt(this@VeritasVpnService)
                    }
                    return@launch
                }
                if (!ReprovisionFlight.claimWaiting(epoch)) return@launch
                runReprovision(gen, epoch)
            }
        }
        return true
    }

    /** @return true when a retry owns the flight and the caller must not release it. */
    private suspend fun handlePeerGone(gen: Long, epoch: Int): Boolean {
        var attempted = false
        var succeeded = false
        var signedOut = controlSignedOut
        try {
            if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) return false
            if (controlSignedOut) {
                signedOut = true
                return false
            }

            val healthy = controlTunnelHealthy()
            val underlying = if (healthy) null else underlyingControlClient()
            val http = ControlPlane.selectClient(
                tunnelHealthy = healthy,
                underlying = underlying,
                normal = ApiClient.sharedClient(),
            )
            val access = try {
                authRepo.accessForControl(underlying, tunnelHealthy = healthy)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ControlAccess.Unavailable
            }
            val early = PeerRecovery.planForAccess(
                signedOut = access is ControlAccess.SignedOut,
                unavailable = access is ControlAccess.Unavailable,
            )
            if (early?.stopRetries == true) {
                signedOut = true
                stopForSignedOut()
                return false
            }
            if (access !is ControlAccess.Ready) {
                // Transport failure, not signed out. Count an attempt so the
                // backoff retry still runs.
                logReprovision("failed", 0)
                attempted = true
                return false
            }
            val token = access.token

            attempted = true
            VpnSettings.setPeerGoneAt(this, System.currentTimeMillis())
            logReprovision("started", 0)

            publishReconnecting(true)
            ServiceCompat.startForeground(
                this@VeritasVpnService,
                NOTIFICATION_ID,
                buildNotification("Reconnecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )

            val keyPair = KeyPair()
            val deviceId = VpnSettings.deviceId(this)
            val shieldBody = VpnSettings.shieldPolicy(this).wireBody()
            val metadata = WireGuardSession.deviceMetadata(this)

            val peer = ApiClient.post(
                "/api/v1/wg/peers",
                mapOf<String, Any>(
                    "public_key" to keyPair.publicKey.toBase64(),
                    "device_id" to deviceId,
                ) + metadata + shieldBody,
                token,
                httpClient = http,
            ).use { res ->
                if (!res.isSuccessful) {
                    logReprovision("failed", res.code)
                    return false
                }
                ApiClient.parse<PeerResponse>(res)
            } ?: run {
                logReprovision("failed", 0)
                return false
            }

            if (gen != sessionGeneration || !sessionIntended()) return false

            val config = WireGuardSession.buildConfig(this, peer, keyPair)
            val endpointLan = peer.serverEndpointLan?.trim().orEmpty()
            val endpointWan = peer.serverEndpointWan?.trim().orEmpty()
            val stealthEndpoint = peer.stealthEndpoint?.trim().orEmpty()
            val stealthPrefix = peer.stealthPathPrefix?.trim().orEmpty()
            val stealthMode = VpnSettings.stealthMode(this).stored()
            val stealthAvailable = peer.stealthAvailable

            synchronized(tunnelOpLock) {
                if (gen != sessionGeneration || !sessionIntended()) return false
                vpnStatePrefs().edit()
                    .putString(KEY_CONFIG, config)
                    .putString(KEY_UDP_CONFIG, config)
                    .putString(KEY_ENDPOINT_LAN, endpointLan)
                    .putString(KEY_ENDPOINT_WAN, endpointWan)
                    .putString(KEY_STEALTH_ENDPOINT, stealthEndpoint)
                    .putString(KEY_STEALTH_PREFIX, stealthPrefix)
                    .putString(KEY_STEALTH_MODE, stealthMode)
                    .putBoolean(KEY_STEALTH_AVAILABLE, stealthAvailable)
                    .putBoolean(KEY_RESUME_STEALTH, false)
                    .apply()
            }
            VpnSettings.setCurrentPeerId(this, peer.peerId)

            val state = establishTunnel(config, gen)
            if (gen != sessionGeneration || !sessionIntended()) {
                runCatching { applyBackendState(Tunnel.State.DOWN, null) }
                return false
            }
            if (state != Tunnel.State.UP) {
                logReprovision("failed", 0)
                return false
            }
            succeeded = true
            VpnSettings.clearPeerGoneAt(this)
            reprovisionBackoffMs = ReprovisionBackoff.INITIAL_MS
            lastPeerOutcome = PeerCheckOutcome.Outcome.PEER_ALIVE
            registerUnderlayWatch()
            followSystemDefault()
            ServiceCompat.startForeground(
                this@VeritasVpnService,
                NOTIFICATION_ID,
                buildNotification("Connected · checking route…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            // Drop Reconnecting before the egress probe so a fast result can
            // publish "Connected" instead of being held back as Not protected.
            publishReconnecting(false)
            broadcastState(true, null)
            startStatsPolling()
            startBackgroundEgressValidation()
            VpnSettings.setSessionEstablishedAt(this, System.currentTimeMillis())
            logReprovision("ok", 200)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logReprovision("failed", 0)
        } finally {
            if (attempted) {
                val retry = coroutineContext.isActive && PeerRecovery.shouldScheduleRetry(
                    succeeded = succeeded,
                    generationMatches = gen == sessionGeneration,
                    sessionIntended = sessionIntended(),
                    disconnectInProgress = disconnectJob?.isActive == true,
                    signedOut = signedOut || controlSignedOut,
                )
                if (succeeded) {
                    publishReconnecting(false)
                } else if (retry) {
                    // Stay on Reconnecting / Not protected. The old peer is gone.
                    // The flight stays claimed through the backoff so a second
                    // create cannot start.
                    publishReconnecting(true)
                    scheduleReprovisionRetry(gen, epoch)
                } else if (!sessionIntended() || gen != sessionGeneration) {
                    isReconnecting = false
                }
            }
        }
        return false
    }

    private fun startHealthWatch() {
        if (healthJob?.isActive == true) return
        val initial = readBackendStats()
        lastKnownRx = initial.first
        lastKnownTx = initial.second
        lastStallCheckAtMs = System.currentTimeMillis()
        healthUnderlayWaitStartedAtMs = 0L
        linkAttachedAtMs = System.currentTimeMillis()
        healthJob = scope.launch {
            while (sessionIntended()) {
                delay(HEALTH_WATCH_MS)
                if (!sessionIntended()) continue
                if (controlSignedOut) continue
                if (disconnectJob?.isActive == true) {
                    healthUnderlayWaitStartedAtMs = 0L
                    continue
                }
                if (adaptJob?.isActive == true) {
                    // Stall rebind would fight an in-flight rebuild. The peer
                    // check still runs, including between rebuild attempts.
                    healthUnderlayWaitStartedAtMs = 0L
                    ensurePeerCheck()
                    continue
                }

                val live = liveUnderlay()
                val now = System.currentTimeMillis()

                if (live != null && lastUnderlayIdentity != null && live.identity != lastUnderlayIdentity) {
                    Log.w(TAG, "path-adapt health: underlay moved $lastUnderlayIdentity -> ${live.identity}")
                    healthUnderlayWaitStartedAtMs = 0L
                    linkAttachedAtMs = now
                    underlayLostSeen = false
                    rxAtLinkAttach = currentRxAtLinkAttach(live)
                    scheduleUnderlayAdapt()
                    lastStallCheckAtMs = now
                    val s = readBackendStats()
                    lastKnownRx = s.first
                    lastKnownTx = s.second
                    continue
                }

                if (live == null) {
                    if (lastUnderlayIdentity != null) underlayLostSeen = true
                    if (healthUnderlayWaitStartedAtMs == 0L) healthUnderlayWaitStartedAtMs = now
                    if (now - healthUnderlayWaitStartedAtMs > STALL_UNDERLAY_WAIT_FALLBACK_MS) {
                        val fallback = systemDefaultSnap()
                        if (fallback != null) {
                            Log.w(TAG, "path-adapt health: underlay absent ${now - healthUnderlayWaitStartedAtMs}ms, scheduling adapt")
                            healthUnderlayWaitStartedAtMs = 0L
                            linkAttachedAtMs = now
                            underlayLostSeen = false
                            val s0 = readBackendStats()
                            rxAtLinkAttach = s0.first
                            scheduleUnderlayAdapt()
                            lastStallCheckAtMs = now
                            lastKnownRx = s0.first
                            lastKnownTx = s0.second
                        }
                    }
                    continue
                }
                healthUnderlayWaitStartedAtMs = 0L

                val s = readBackendStats()
                val currentRx = s.first
                val currentTx = s.second
                val handshakeMs = s.third
                val rxDelta = currentRx - lastKnownRx
                val txDelta = currentTx - lastKnownTx
                val elapsed = now - lastStallCheckAtMs
                val handshakeAge = if (handshakeMs > 0) now - handshakeMs else Long.MAX_VALUE
                val stalled = TunnelStall.isStalled(
                    elapsedMs = elapsed,
                    rxDelta = rxDelta,
                    txDelta = txDelta,
                    handshakeAgeMs = handshakeAge,
                )

                val peerDecision = if (lastUnderlayIdentity != null) {
                    PeerHealth.decide(
                        handshakeMs = handshakeMs,
                        linkAttachedAtMs = linkAttachedAtMs,
                        nowMs = now,
                        networkUsable = true,
                        lastVerifyAtMs = lastPeerVerifyAtMs,
                        rxDeltaSinceAttach = currentRx - rxAtLinkAttach,
                    )
                } else {
                    PeerHealth.Decision.OK
                }

                if (peerDecision == PeerHealth.Decision.VERIFY_PEER) {
                    lastPeerVerifyAtMs = now
                    Log.i(TAG, "peer check trigger")
                    schedulePeerCheck()
                }

                if (stalled && now - lastStallRebindAtMs >= stallBackoffMs) {
                    Log.w(TAG, "path-adapt health: stall detected rx=$rxDelta tx=$txDelta handshakeAge=$handshakeAge currentBackoff=${stallBackoffMs}ms")
                    lastStallRebindAtMs = now
                    stallBackoffMs = TunnelStall.nextBackoff(stallBackoffMs)
                    scheduleUnderlayAdapt(forceHard = true)
                    lastStallCheckAtMs = now
                    val s2 = readBackendStats()
                    lastKnownRx = s2.first
                    lastKnownTx = s2.second
                } else {
                    if (elapsed >= STALL_DETECT_WINDOW_MS) {
                        lastStallCheckAtMs = now
                        lastKnownRx = currentRx
                        lastKnownTx = currentTx
                    }
                    if (TunnelStall.shouldResetBackoff(rxDelta, handshakeAge)) {
                        stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
                    }
                }
            }
        }
    }

    private fun readBackendStats(): Triple<Long, Long, Long> {
        val stats = runCatching { backend.getStatistics(this) }.getOrNull()
            ?: return Triple(0L, 0L, 0L)
        var handshakeMs = 0L
        for (key in stats.peers()) {
            val peer = stats.peer(key) ?: continue
            if (peer.latestHandshakeEpochMillis > handshakeMs) {
                handshakeMs = peer.latestHandshakeEpochMillis
            }
        }
        return Triple(stats.totalRx(), stats.totalTx(), handshakeMs)
    }

    private fun currentRxAtLinkAttach(live: UnderlaySnap): Long {
        val s = readBackendStats()
        return s.first
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
        const val ACTION_AUTH_RESTORED = "cloud.veritasvpn.AUTH_RESTORED"
        const val ACTION_QUERY_STATE = "cloud.veritasvpn.QUERY_STATE"
        const val ACTION_STATE = "cloud.veritasvpn.STATE"
        const val ACTION_STATS = "cloud.veritasvpn.STATS"
        const val ACTION_RECONNECT_NEEDED = "cloud.veritasvpn.RECONNECT_NEEDED"
        const val ACTION_PEER_CHECK = "cloud.veritasvpn.PEER_CHECK"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_ENDPOINT_LAN = "endpoint_lan"
        const val EXTRA_ENDPOINT_WAN = "endpoint_wan"
        const val EXTRA_STEALTH_ENDPOINT = "stealth_endpoint"
        const val EXTRA_STEALTH_PREFIX = "stealth_prefix"
        const val EXTRA_STEALTH_MODE = "stealth_mode"
        const val EXTRA_STEALTH_AVAILABLE = "stealth_available"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_ERROR = "error"
        const val EXTRA_SIGN_IN_AGAIN = "sign_in_again"
        const val EXTRA_EGRESS_IP = "egress_ip"
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_HANDSHAKE_MS = "handshake_ms"
        const val EXTRA_RECONNECTING = "reconnecting"
        const val EXTRA_HANDSHAKE_OK = "handshake_ok"
        const val PREFS_NAME = "veritas_vpn_state"
        const val KEY_CONFIG = "last_approved_config"
        const val KEY_UDP_CONFIG = "udp_config"
        const val KEY_ENDPOINT_LAN = "endpoint_lan"
        const val KEY_ENDPOINT_WAN = "endpoint_wan"
        const val KEY_STEALTH_ENDPOINT = "stealth_endpoint"
        const val KEY_STEALTH_PREFIX = "stealth_prefix"
        const val KEY_STEALTH_MODE = "stealth_mode"
        const val KEY_STEALTH_AVAILABLE = "stealth_available"
        const val KEY_RESUME_STEALTH = "resume_stealth"

        /**
         * The durable intent is the app's source of truth across Activity
         * recreation. Android does not replay non-sticky broadcasts to a new
         * Activity, so the UI must not default to an unrelated "disconnected"
         * state while the foreground VPN service is still running.
         */
        fun hasSavedSession(context: Context): Boolean {
            val prefs = SecurePrefs.open(context.applicationContext, PREFS_NAME)
            if (prefs.getString(KEY_CONFIG, null) == null) return false
            return VpnSettings.currentPeerId(context) != null
        }
        private const val UNDERLAY_ADAPT_DEBOUNCE_MS = 400L
        private const val SOFT_ATTACH_MS = 6_000L
        private const val HANDSHAKE_CONFIRM_MS = 3_500L
        private const val PROBE_RETRY_DELAY_MS = 1_200L
        private const val HEALTH_WATCH_MS = 2_000L
        private const val HANDSHAKE_OK_AGE_MS = 200_000L
        private const val STALL_DETECT_WINDOW_MS = TunnelStall.DETECT_WINDOW_MS
        private const val STALL_UNDERLAY_POLL_MS = 1_500L
        private const val STALL_UNDERLAY_MAX_POLL_MS = 10_000L
        private const val STALL_UNDERLAY_WAIT_FALLBACK_MS = 10_000L
        private const val STATS_LOG_INTERVAL_MS = 30_000L
        private const val TAG = "VeritasVpnService"
        private val EGRESS_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
