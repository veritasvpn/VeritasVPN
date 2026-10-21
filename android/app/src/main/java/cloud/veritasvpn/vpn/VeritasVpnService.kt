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
import android.os.Process
import java.io.FileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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
import cloud.veritasvpn.api.PeerEndpointView
import cloud.veritasvpn.api.PeerListResponse
import cloud.veritasvpn.api.PeerResponse
import cloud.veritasvpn.auth.AuthenticatedApi
import cloud.veritasvpn.auth.AuthRepository
import cloud.veritasvpn.auth.SessionExpiredException
import cloud.veritasvpn.auth.ControlAccess
import cloud.veritasvpn.auth.ControlRefresh
import cloud.veritasvpn.secure.SecurePrefs
import cloud.veritasvpn.support.ConnectTiming
import cloud.veritasvpn.support.DiagnosticLog
import cloud.veritasvpn.ui.DisconnectUi
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

class VeritasVpnService : GoBackend.VpnService(), Tunnel {

    class VpnError(override val userMessage: String) : Exception(userMessage), cloud.veritasvpn.api.UserVisibleError

    private val backend by lazy { GoBackend(applicationContext) }
    private val authRepo by lazy { AuthRepository(applicationContext) }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "vpn scope: ${error.javaClass.simpleName}")
        },
    )
    /**
     * TLS close and OkHttp pool eviction. Not [scope]: cancelling that scope
     * must not drop the close, and the close must never run on the main thread.
     * A close on the main thread throws NetworkOnMainThreadException and kills
     * the process.
     */
    private val socketCloseScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "socket close: ${error.javaClass.simpleName}")
        },
    )
    private var transitionJob: Job? = null
    private var disconnectJob: Job? = null
    private var validationJob: Job? = null
    private var statsJob: Job? = null
    private var shieldJob: Job? = null
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

    /** Set only for the post-rebuild escalation. A normal reconnect leaves this false. */
    @Volatile private var nextTunnelIsFresh = false

    private data class DataPlaneStatus(
        val v4Present: Boolean = false,
        val v6Present: Boolean = false,
        val protectV4: Boolean = false,
        val protectV6: Boolean = false,
        val bindAttempted: Boolean = false,
        val bindV4: Boolean = false,
        val bindV6: Boolean = false,
        val boundHandle: Long? = null,
        val loopbackRelay: Boolean = false,
        val config: DataPlaneHealth.ConfigCheck = DataPlaneHealth.ConfigCheck.UNREAD,
    )

    @Volatile private var dataPlaneStatus = DataPlaneStatus()

    /** establish() refused the tun. The activity asks for consent after the home frame. */
    @Volatile private var vpnConsentRequired = false
    /** One teardown per disconnect. A second DOWN on the same fds can abort the process. */
    @Volatile private var tunnelReleased = false
    /** Always-on lockdown: the tun stays open so the system does not drop this task. */
    @Volatile private var holdInterface = false
    /** The held tun disallows this app, so sign-up is not blackholed with everyone else. */
    @Volatile private var lockdownHoldActive = false
    @Volatile private var stealthTransport: StealthTransport? = null
    @Volatile private var lastStealthLogged: String? = null
    @Volatile private var activeTransport: String = ""
    @Volatile private var lastNotificationText: String = "Connecting…"
    @Volatile private var stealthSwitching = false
    @Volatile private var stealthKickLogged = false
    private val stealthSwitchLock = Any()
    @Volatile private var bestMatchingNetwork: Network? = null
    @Volatile private var bestMatchingWatchRegistered = false
    private val underlayHandler = Handler(Looper.getMainLooper())
    @Volatile private var isReconnecting = false
    @Volatile private var networkMayBeBlocking = false
    private var reconnectEpisodeStartedMs = 0L
    private var blockedClock = BlockedNetworkNotice.Clock()

    /** Set only from the Switch to Stealth action, before any tunnel work. */
    @Volatile private var userAskedStealth = false
    /** In-flight provisioning read. A newer default network supersedes it. */
    @Volatile private var endpointFlight: EndpointRefresh.Flight? = null
    @Volatile private var endpointCall: Call? = null
    @Volatile private var reconnectNotice: String? = null
    private val endpointCallLock = Any()
    private var lastStallRebindAtMs = 0L
    private var stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
    private var lastKnownRx = 0L
    private var lastKnownTx = 0L
    private var lastStallCheckAtMs = 0L
    private var underlayWaitStartedAtMs = 0L
    private var hardLoopStartedAtMs = 0L
    private var stealthReconnectJob: Job? = null
    /** One Stealth dial per underlay handle. Repeat callbacks read this and wait. */
    private var stealthRoamMemory = StealthRoam.Memory()
    private var natKeepaliveJob: Job? = null
    private var handshakeBurstJob: Job? = null
    @Volatile private var userspaceInstalling = false
    private var waitRecheckJob: Job? = null
    private var peerCheckJob: Job? = null
    private var reprovisionJob: Job? = null
    private var upgradeGateJob: Job? = null
    private var pendingFreshTunnel = false
    private var pendingReprovision = false
    private var reprovisionBackoffMs = ReprovisionBackoff.INITIAL_MS
    private val reprovisionLock = Any()
    private var lastStatsLogAtMs = 0L
    private var healthUnderlayWaitStartedAtMs = 0L
    private var linkAttachedAtMs = 0L
    /** Handle we last treated as a roam. Repeats do not move the arm time. */
    @Volatile private var roamWatchHandle: Long = Long.MIN_VALUE
    /** Wall time of the latest default-network change. A handshake must be newer. */
    @Volatile private var roamArmedAtMs: Long = 0L
    private var roamRetryDelayMs = RoamRecovery.Retry.INITIAL_MS
    private var lastPeerVerifyAtMs = 0L
    private var underlayLostSeen = false
    private var rxAtLinkAttach = 0L
    /**
     * Last system-default handle this session has reacted to. Not a pin that
     * skips later work: every observation protect()'s the UDP sockets and
     * bindSocket's them to this handle only.
     */
    @Volatile private var watchedDefault: TunnelRebuild.DefaultWatch = TunnelRebuild.DefaultWatch()
    /**
     * Network from onAvailable or onCapabilities before it is the validated
     * default. The data plane binds to it, then again when it becomes default.
     */
    @Volatile private var earlyUnderlay: EarlyUnderlay.State = EarlyUnderlay.State()
    @Volatile private var earlyNetwork: Network? = null
    /** Callback network adopted as the bind target when it is not an early Wi-Fi. */
    @Volatile private var defaultUnderlayNetwork: Network? = null
    /** Host address currently on the tun. A kill-switch hold is not the peer address. */
    @Volatile private var currentInterfaceAddress: String? = null
    /** Roam already running for this handle. Same-network callbacks do not restart it. */
    @Volatile private var activeRoamHandle: Long = Long.MIN_VALUE
    /** Failed rebuilds on [roamFailHandle]. Survives a same-handle restart. */
    private var roamFailHandle: Long? = null
    private var roamFailRebuilds: Int = 0
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
     * Network callbacks only enqueue this. Following the system default runs
     * on [scope] so a rebuild holding [tunnelOpLock] cannot stall the callback thread.
     */
    private val defaultNetworkSignals = Channel<Unit>(Channel.CONFLATED)
    /** Signed-out control calls stop. The tunnel interface stays up. */
    @Volatile private var controlSignedOut = false
    /** OkHttp client bound to the current system-default network, if any. */
    @Volatile private var controlClient: OkHttpClient? = null
    @Volatile private var controlClientHandle: Long = Long.MIN_VALUE
    private val controlClientLock = Any()
    /**
     * VPN network for the public-IP check only. Listen-only: this does not
     * request a network, bind the process, or change the underlay.
     */
    @Volatile private var observedVpnNetwork: Network? = null
    private var vpnWatchRegistered = false
    private val vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            noteVpnNetwork(network)
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                noteVpnNetwork(network)
            }
        }
        override fun onLost(network: Network) {
            if (observedVpnNetwork == network) observedVpnNetwork = null
        }
    }
    private val underlayCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            underlayLostSeen = false
            observeUnderlay(network, newlyAvailable = true)
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            observeUnderlay(network, newlyAvailable = false)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            observeUnderlay(network, newlyAvailable = false)
        override fun onLost(network: Network) {
            underlayLostSeen = true
            observeUnderlayLost(network)
        }
    }
    private val bestMatchingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            bestMatchingNetwork = network
            underlayLostSeen = false
            observeUnderlay(network, newlyAvailable = true)
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            bestMatchingNetwork = network
            observeUnderlay(network, newlyAvailable = false)
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            bestMatchingNetwork = network
            observeUnderlay(network, newlyAvailable = false)
        }
        override fun onLost(network: Network) {
            if (bestMatchingNetwork == network) bestMatchingNetwork = null
            observeUnderlayLost(network)
        }
    }
    /** True while the user intends to stay connected (saved config present). */
    private fun sessionIntended(): Boolean =
        !sessionEndedByUser && vpnStatePrefs().getString(KEY_CONFIG, null) != null

    override fun onCreate() {
        super.onCreate()
        active = this
        createNotificationChannel()
        registerVpnNetworkWatch()
        scope.launch {
            while (isActive) {
                defaultNetworkSignals.receive()
                if (!sessionIntended()) continue
                try {
                    noteDefaultNetworkForRecovery()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "default network follow failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // API 36 kills the process if a foreground start does not call
        // startForeground promptly, including a null-intent Always-on restart
        // and the circle-tap disconnect. The type has to match the manifest.
        enterForeground(
            when (intent?.action) {
                ACTION_DISCONNECT -> "Disconnected"
                ACTION_CONNECT -> "Connecting…"
                else -> lastNotificationText.ifBlank { "VeritasVPN" }
            },
        )
        // Completes the shared VpnService future used by GoBackend so the backend
        // reuses THIS instance instead of starting the library's base service.
        runCatching { super.onStartCommand(intent, flags, startId) }.onFailure { error ->
            DiagnosticLog.recordCrash(error)
            Log.w(TAG, "onStartCommand super: ${error.javaClass.simpleName}")
        }

        when (intent?.action) {
            ACTION_QUERY_STATE -> {
                if (!sessionIntended()) {
                    openLockdownHold(sessionGeneration, alreadyLockdown = false)
                    broadcastState(false, null)
                    return if (lockdownHoldActive) START_STICKY else START_NOT_STICKY
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
                broadcastShieldUpdate()
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
            ACTION_ENSURE_LOCKDOWN_HOLD -> {
                if (!sessionIntended()) {
                    openLockdownHold(sessionGeneration, alreadyLockdown = false)
                }
                return if (sessionIntended() || lockdownHoldActive) START_STICKY else START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                userAskedStealth = false
                markSessionStarted()
                tunnelReleased = false
                holdInterface = false
                lockdownHoldActive = false
                natKeepaliveJob?.cancel()
                handshakeBurstJob?.cancel()
                nextTunnelIsFresh = false
                roamRetryDelayMs = RoamRecovery.Retry.INITIAL_MS
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
                networkMayBeBlocking = false
                reconnectEpisodeStartedMs = 0L
                blockedClock = BlockedNetworkNotice.Clock()
                lastStallRebindAtMs = 0L
                stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
                hardLoopStartedAtMs = 0L
                healthUnderlayWaitStartedAtMs = 0L
                underlayWaitStartedAtMs = 0L
                linkAttachedAtMs = System.currentTimeMillis()
                roamWatchHandle = Long.MIN_VALUE
                roamArmedAtMs = 0L
                lastPeerVerifyAtMs = 0L
                underlayLostSeen = false
                rxAtLinkAttach = 0L
                watchedDefault = TunnelRebuild.DefaultWatch()
                clearEarlyRoam()
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
                enterForeground("Connecting…")
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
                            val askedOnThisConnect = userAskedStealth ||
                                VpnSettings.stealthMode(this@VeritasVpnService) == StealthMode.STEALTH
                            val modeToStore = if (askedOnThisConnect) {
                                StealthMode.STEALTH.stored()
                            } else {
                                stealthMode.ifBlank { StealthMode.AUTO.stored() }
                            }
                            vpnStatePrefs().edit()
                                .putString(KEY_CONFIG, config)
                                .putString(KEY_UDP_CONFIG, config)
                                .putString(KEY_ENDPOINT_LAN, endpointLan)
                                .putString(KEY_ENDPOINT_WAN, endpointWan)
                                .putString(KEY_STEALTH_ENDPOINT, stealthEndpoint)
                                .putString(KEY_STEALTH_PREFIX, stealthPrefix)
                                .putString(KEY_STEALTH_MODE, modeToStore)
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
                            runCatching { turnUserspaceOff() }
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
                        // Protect the WireGuard sockets and leave them on the system default.
                        registerUnderlayWatch()
                        followSystemDefault()
                        broadcastState(true, null)
                        startStatsPolling()
                        startBackgroundEgressValidation()
                        VpnSettings.setSessionEstablishedAt(this@VeritasVpnService, System.currentTimeMillis())
                        noteTunnelReady(connectGen)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Connect failed", e)
                        stopStatsPolling()
                        cancelStealthWork()
                        // Keep KEY_CONFIG so sticky/Always-on can retry. Only the
                        // user's Disconnect clears the intended session.
                        runCatching { turnUserspaceOff() }
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
                                        runCatching { turnUserspaceOff() }
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
                                    noteTunnelReady(connectGen)
                                }.onFailure { retryError ->
                                    Log.e(TAG, "Connect retry failed; keeping session intended", retryError)
                                }
                            }
                        } else {
                            // The circle tap, or a newer connect, owns the
                            // session. Stopping the service here restarts an
                            // Always-on VPN and takes the task off screen.
                            broadcastState(false, if (sessionEndedByUser) null else friendlyError(e))
                            leaveForeground()
                        }
                    }
                }
                return START_STICKY
            }
            ACTION_APPLY_STEALTH -> {
                // User tapped Switch to Stealth. Nothing else may take this branch.
                userAskedStealth = true
                if (!sessionIntended()) return START_STICKY
                val gen = sessionGeneration
                scope.launch {
                    synchronized(tunnelOpLock) {
                        if (!sessionStillActive(gen)) return@launch
                        vpnStatePrefs().edit()
                            .putString(KEY_STEALTH_MODE, StealthMode.STEALTH.stored())
                            .commit()
                    }
                    // A connect that already chose UDP must finish before the
                    // rebind, or the two writes fight over the same tunnel.
                    val inflight = transitionJob
                    if (inflight != null && inflight.isActive) {
                        try {
                            inflight.join()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    if (!sessionStillActive(gen)) return@launch
                    if (usesStealth() && stealthTransport != null) return@launch
                    var applied = false
                    repeat(8) {
                        if (!sessionStillActive(gen) || applied) return@launch
                        val offer = currentOffer()
                        val hasConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null) != null ||
                            vpnStatePrefs().getString(KEY_CONFIG, null) != null
                        if (hasConfig && offer.usable()) {
                            switchToStealth(gen, offer)
                            applied = true
                            return@launch
                        }
                        delay(250)
                    }
                    if (sessionStillActive(gen) && !currentOffer().usable()) {
                        broadcastState(true, "Stealth is not available on the VPN node yet.")
                    }
                }
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                // Leave the process and the activity up. stopSelf while Always-on
                // is set restarts this service and, on some builds, takes the
                // task off screen. The tunnel and the notification still go away.
                // enterForeground already ran. Framework calls here must not
                // escape onto the main thread.
                val disconnectGen = ++sessionGeneration
                // Snapshot before any cancel nulls the job fields. wgTurnOff
                // while a roam rebuild is inside wgTurnOn kills the process.
                val pendingTunnelJobs = tunnelJobs()
                runCatching {
                    markSessionEnded()
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
                    cancelEndpointFetch()
                    lastStallRebindAtMs = 0L
                    stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
                    underlayWaitStartedAtMs = 0L
                    hardLoopStartedAtMs = 0L
                    healthUnderlayWaitStartedAtMs = 0L
                    lastStatsLogAtMs = 0L
                    watchedDefault = TunnelRebuild.DefaultWatch()
                    clearEarlyRoam()
                    lastPeerOutcome = null
                    peerCheckSerial++
                    lastPeerCheckAtMs = 0L
                    rebuildInProgress = false
                    rebuildDefaultHandle = Long.MIN_VALUE
                    controlSignedOut = false
                    dropUnderlyingControlClient()
                    cancelTunnelJobs()
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    DiagnosticLog.recordCrash(error)
                    Log.w(TAG, "disconnect start: ${error.javaClass.simpleName}")
                }
                disconnectJob = scope.launch {
                    var keepInterface = true
                    try {
                        // Join off the tunnel lock. The roam job holds that
                        // lock across the native call; joining it here waits
                        // until that call has returned.
                        joinBeforeTurnOff(pendingTunnelJobs)
                        DiagnosticLog.record("disconnect roam-joined")
                        if (disconnectGen != sessionGeneration) return@launch
                        synchronized(tunnelOpLock) {
                            if (disconnectGen != sessionGeneration) return@launch
                            runCatching {
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
                                    .commit()
                            }
                        }
                        if (disconnectGen != sessionGeneration) return@launch
                        validationGeneration++
                        stopStatsPolling()
                        keepInterface = DisconnectUi.keepVpnInterface(
                            runCatching {
                                VpnKillSwitch.isLockdownEnabled(
                                    this@VeritasVpnService,
                                    vpnPrepared = true,
                                    allowPrepare = false,
                                )
                            }.getOrElse {
                                DiagnosticLog.record("disconnect lockdown-read-failed")
                                true
                            },
                        )
                        DiagnosticLog.record("disconnect turn-off")
                        releaseTunnelOnce(disconnectGen, keepInterface = keepInterface)
                        if (disconnectGen != sessionGeneration) return@launch
                        if (keepInterface) {
                            // The activity's peer delete is still on the shared
                            // client. That socket is now blackholed. Close it
                            // here, on this IO job, so the read cannot sit for
                            // the 15s timeout.
                            retireSharedClient()
                        }
                        broadcastState(false, null)
                        if (keepInterface) {
                            DiagnosticLog.record("disconnect interface=kept")
                            // Lockdown still blackholes unprotected sockets.
                            // Keep the system-default callback so a reconnect
                            // can protect and bind its peer request.
                            keepBestMatchingWatch()
                            enterForeground("Disconnected")
                        } else {
                            DiagnosticLog.record("disconnect interface=closed")
                            leaveForeground()
                        }
                    } catch (e: CancellationException) {
                        // The click path and this scope must not surface
                        // cancellation. A newer disconnect owns the session
                        // when the generation has moved.
                    } catch (e: Exception) {
                        DiagnosticLog.recordCrash(e)
                        Log.w(TAG, "disconnect teardown: ${e.javaClass.simpleName}")
                        leaveForeground()
                    } finally {
                        if (disconnectGen == sessionGeneration && !tunnelReleased) {
                            DiagnosticLog.record("disconnect turn-off")
                            runCatching {
                                releaseTunnelOnce(disconnectGen, keepInterface = keepInterface)
                            }
                        }
                    }
                }
                transitionJob = disconnectJob
                return START_NOT_STICKY
            }
            else -> {
                // Android may restart an Always-on VPN service without the original Intent.
                // enterForeground already ran, so this restart is not killed for
                // missing startForeground. Keep the last approved configuration.
                if (!sessionIntended()) {
                    openLockdownHold(sessionGeneration, alreadyLockdown = false)
                    return if (lockdownHoldActive) START_STICKY else START_NOT_STICKY
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
            startShieldPolling()
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
        enterForeground("Restoring secure tunnel…")
        restoreJob = scope.launch {
            while (restoreGen == sessionGeneration &&
                sessionIntended() &&
                disconnectJob?.isActive != true
            ) {
                try {
                    val savedConfig = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
                        ?: vpnStatePrefs().getString(KEY_CONFIG, null)
                        ?: return@launch
                    val restored = establishTunnel(savedConfig, restoreGen)
                    if (restoreGen != sessionGeneration || !sessionIntended()) {
                        runCatching { turnUserspaceOff() }
                        return@launch
                    }
                    if (restored != Tunnel.State.UP) {
                        // Already-granted consent needs no dialog. prepare()
                        // from this service is what covered the undrawn window.
                        if (vpnConsentRequired) {
                            broadcastConsentNeeded()
                            return@launch
                        }
                        Log.w(TAG, "Automatic VPN restore failed; retrying")
                        broadcastState(true, null)
                        delay(3_000)
                        continue
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
        // Closing the tun on the circle tap makes the platform revoke the
        // VPN. VpnService.onRevoke() stops this service. Always-on restarts
        // it and the task leaves the screen, so this override never calls
        // super and never stops itself. The user stays on the offline home.
        if (sessionEndedByUser) {
            return
        }
        sessionGeneration++
        adaptJob?.cancel()
        cancelPeerRecovery()
        unregisterUnderlayWatch()
        lastUnderlayFingerprint = null
        lastUnderlayIdentity = null
        lastChosenEndpoint = null
        lastRebindAtMs = 0L
        isReconnecting = false
        cancelEndpointFetch()
        lastStallRebindAtMs = 0L
        stallBackoffMs = TunnelStall.MIN_REBIND_INTERVAL_MS
        lastKnownRx = 0L
        lastKnownTx = 0L
        lastStallCheckAtMs = 0L
        underlayWaitStartedAtMs = 0L
        hardLoopStartedAtMs = 0L
        healthUnderlayWaitStartedAtMs = 0L
        lastStatsLogAtMs = 0L
        watchedDefault = TunnelRebuild.DefaultWatch()
        clearEarlyRoam()
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
        // wgTurnOff and the tun close stay off this thread. Socket close is
        // network I/O, and this callback runs on the main thread.
        socketCloseScope.launch(Dispatchers.IO) {
            runCatching { turnUserspaceOff() }
            closeTunKeeper()
            runCatching { setUnderlyingNetworks(null) }
        }
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
        leaveForeground()
    }

    override fun onDestroy() {
        if (active === this) active = null
        unregisterVpnNetworkWatch()
        adaptJob?.cancel()
        cancelPeerRecovery()
        unregisterUnderlayWatch()
        stopStatsPolling()
        cancelStealthWork()
        dropUnderlyingControlClient()
        // Do not turn the tunnel DOWN here when a session is still intended —
        // START_STICKY / Always-on will restart and restore from KEY_CONFIG.
        // The close runs on [socketCloseScope], not the main thread, and not
        // on [scope], which this method cancels next.
        if (!sessionIntended() && !holdInterface) {
            val gen = sessionGeneration
            socketCloseScope.launch(Dispatchers.IO) {
                runCatching { releaseTunnelOnce(gen) }
            }
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
                    // setState(DOWN) would stopSelf() and take the task off screen.
                    runCatching { turnUserspaceOff() }
                    leaveForeground()
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
                leaveForeground()
                broadcastState(false, null)
            }
        }
    }

    private fun startStatsPolling() {
        if (!sessionIntended()) return
        if (statsJob?.isActive != true) {
            statsJob = scope.launch {
                while (sessionIntended()) {
                    broadcastStats()
                    delay(1_500)
                }
            }
        }
        // Reconnect, roam, and a later network change all come through here
        // while stats are already running. A shield job that already stopped
        // still has to start again. A live policy change is read on the next poll.
        startShieldPolling()
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
        stopShieldPolling()
    }

    /**
     * Polls block notices while the tunnel is up. The job follows the VPN
     * service, not the activity, so a closed screen still gets a notice.
     * It does not touch WireGuard, the kill switch, or the shared API client.
     * While reconnecting it only waits, so it does not compete with recovery.
     */
    private fun startShieldPolling() {
        if (!sessionIntended() || controlSignedOut) return
        if (shieldJob?.isActive == true) return
        shieldJob = scope.launch {
            var failures = 0
            val seen = LinkedHashSet<String>()
            var lastSentAt = 0L
            while (isActive && sessionIntended() && !controlSignedOut) {
                if (isReconnecting || disconnectJob?.isActive == true) {
                    delay(ShieldPollPolicy.RECONNECT_SLICE_MS)
                    continue
                }
                when (val outcome = pollShieldOnce(seen, lastSentAt)) {
                    ShieldPollOutcome.SignedOut -> {
                        stopForSignedOut()
                        break
                    }
                    ShieldPollOutcome.Stop -> break
                    ShieldPollOutcome.Ok -> {
                        failures = 0
                        lastSentAt = shieldLastSentAt
                        delay(ShieldPollPolicy.INTERVAL_MS)
                    }
                    ShieldPollOutcome.Retry -> {
                        failures++
                        delay(ShieldPollPolicy.failureDelayMs(failures))
                    }
                }
            }
        }
    }

    private fun stopShieldPolling() {
        shieldJob?.cancel()
        shieldJob = null
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(SHIELD_NOTIFICATION_ID)
        }
    }

    @Volatile private var shieldLastSentAt: Long = 0L
    private var lastShieldCount: Long = -1L
    private var lastShieldBlocksJson: String? = null
    private var lastShieldBanner: String? = null
    private val shieldHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private enum class ShieldPollOutcome { Ok, Retry, Stop, SignedOut }

    private class ShieldFetch(
        val peer: cloud.veritasvpn.api.PeerInfo?,
        val stop: Boolean,
    )

    private suspend fun pollShieldOnce(seen: LinkedHashSet<String>, lastSentAt: Long): ShieldPollOutcome {
        val peerId = VpnSettings.currentPeerId(this) ?: return ShieldPollOutcome.Retry
        return try {
            val parsed = AuthenticatedApi.execute(authRepo, { token ->
                ApiClient.getOnce("/api/v1/wg/peers", token, shieldHttpClient, timeoutSeconds = 4)
            }) { res ->
                when {
                    // Refresh already ran. Another 401 is not a reason to loop or sign out.
                    res.code == 401 -> ShieldFetch(null, stop = true)
                    !res.isSuccessful -> ShieldFetch(null, stop = false)
                    else -> ShieldFetch(
                        ApiClient.parse<PeerListResponse>(res)?.peers?.firstOrNull { it.id == peerId },
                        stop = false,
                    )
                }
            }
            if (parsed.stop) return ShieldPollOutcome.Stop
            val peer = parsed.peer ?: return ShieldPollOutcome.Retry
            val blocks = peer.shieldBlocks?.toBlocks()
            val plan = planShieldNotices(
                incoming = peer.shieldNotices.map { it.toNotice() },
                seen = seen,
                prefs = VpnSettings.shieldNoticePrefs(this),
                policy = VpnSettings.shieldPolicy(this),
                nowMs = System.currentTimeMillis(),
                lastSentAtMs = lastSentAt,
            )
            if (sessionIntended() && !controlSignedOut) {
                val posted = plan.notification?.let { postShieldNotification(it) } ?: true
                if (shieldPollAccepted(plan, posted)) {
                    seen.clear()
                    seen.addAll(plan.seen)
                    shieldLastSentAt = plan.lastSentAtMs
                }
                lastShieldCount = peer.dnsBlockedCount
                lastShieldBlocksJson = blocks?.let { ApiClient.gson.toJson(it) }
                lastShieldBanner = plan.banner
                broadcastShieldUpdate()
            }
            ShieldPollOutcome.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            ShieldPollOutcome.SignedOut
        } catch (e: Exception) {
            Log.i(TAG, "shield poll skipped: ${e.javaClass.simpleName}")
            ShieldPollOutcome.Retry
        }
    }

    private fun broadcastShieldUpdate() {
        if (lastShieldCount < 0 && lastShieldBlocksJson == null && lastShieldBanner == null) return
        val intent = Intent(ACTION_SHIELD).setPackage(packageName)
        if (lastShieldCount >= 0) intent.putExtra(EXTRA_DNS_BLOCKED, lastShieldCount)
        lastShieldBlocksJson?.let { intent.putExtra(EXTRA_SHIELD_BLOCKS, it) }
        lastShieldBanner?.let { intent.putExtra(EXTRA_SHIELD_BANNER, it) }
        sendBroadcast(intent)
    }

    private fun postShieldNotification(notice: PreparedShieldNotice): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        // The VPN foreground notification is exempt from POST_NOTIFICATIONS.
        // This channel is not. A denied permission drops the post with no throw.
        if (!manager.areNotificationsEnabled()) return false
        return try {
            val open = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SHIELD, true)
            }
            val pending = PendingIntent.getActivity(
                this,
                2,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(this, SHIELD_CHANNEL_ID)
                .setContentTitle(notice.title)
                .setContentText(notice.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
                .setSmallIcon(R.drawable.ic_stat_veritas)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            if (notice.redactOnLockScreen) {
                builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
            }
            manager.notify(SHIELD_NOTIFICATION_ID, builder.build())
            true
        } catch (e: Exception) {
            Log.i(TAG, "shield notification not posted: ${e.javaClass.simpleName}")
            false
        }
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
                .apply {
                    if (isReconnecting) putExtra(EXTRA_ERROR, reconnectNotice.orEmpty())
                    putStealthDetail()
                }
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
        if (EgressProbe.allowsPhysicalFallback()) return@coroutineScope null
        val network = vpnNetworkForProbe() ?: return@coroutineScope null
        if (!EgressProbe.mayProbe(vpnNetworkAvailable = true)) return@coroutineScope null
        val client = EgressProbe.client(
            bind = { socket -> EgressProbe.bind(network, socket) },
            lookup = { host ->
                if (!EgressProbe.allowsHost(host)) throw java.net.UnknownHostException(host)
                network.getAllByName(host).toList()
            },
        )
        val results = Channel<String>(EgressProbe.ENDPOINTS.size)
        val jobs = EgressProbe.ENDPOINTS.map { endpoint ->
            launch(Dispatchers.IO) {
                runCatching { EgressProbe.fetch(endpoint, client) }
                    .onSuccess { egressIp ->
                        if (egressIp != null) results.trySend(egressIp)
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

    /**
     * The tun's network, not the system default. In Stealth the default is
     * the physical network because this package is excluded from the tun.
     */
    private suspend fun vpnNetworkForProbe(): Network? {
        repeat(8) { attempt ->
            findVpnNetwork()?.let { return it }
            if (attempt < 7) delay(100)
        }
        return null
    }

    private fun findVpnNetwork(): Network? {
        val cm = connectivityManager()
        val byHandle = LinkedHashMap<Long, Network>()
        fun add(network: Network?) {
            if (network == null) return
            byHandle.putIfAbsent(network.networkHandle, network)
        }
        add(observedVpnNetwork)
        cm.allNetworks.forEach { add(it) }
        val uid = Process.myUid()
        val candidates = byHandle.values.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val owned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                caps.ownerUid == uid
            } else {
                null
            }
            EgressProbe.Candidate(network.networkHandle, owned)
        }
        val chosen = EgressProbe.choose(candidates) ?: return null
        return byHandle[chosen]
    }

    private fun noteVpnNetwork(network: Network) {
        val caps = connectivityManager().getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps.ownerUid != Process.myUid()) return
        observedVpnNetwork = network
    }

    private fun registerVpnNetworkWatch() {
        if (vpnWatchRegistered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager().registerNetworkCallback(request, vpnNetworkCallback)
            vpnWatchRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Could not watch the VPN network: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterVpnNetworkWatch() {
        if (!vpnWatchRegistered) return
        runCatching { connectivityManager().unregisterNetworkCallback(vpnNetworkCallback) }
        vpnWatchRegistered = false
        observedVpnNetwork = null
    }

    private fun broadcastState(connected: Boolean, error: String?, egressIp: String? = null) {
        if (connected && !sessionIntended()) return
        val intent = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_CONNECTED, connected)
        val shownError = EndpointRefresh.displayStatus(error)
            ?: if (isReconnecting) reconnectNotice else null
        if (shownError != null) intent.putExtra(EXTRA_ERROR, shownError)
        intent.putStealthDetail()
        intent.putExtra(EXTRA_SIGN_IN_AGAIN, controlSignedOut)
        if (egressIp != null) intent.putExtra(EXTRA_EGRESS_IP, egressIp)
        if (activeTransport.isNotEmpty()) intent.putExtra(EXTRA_TRANSPORT, activeTransport)
        intent.putExtra(EXTRA_RECONNECTING, isReconnecting)
        intent.putExtra(EXTRA_HANDSHAKE_OK, handshakeOk())
        sendBroadcast(intent)
    }

    /**
     * The tun was refused because this package is not the prepared VPN.
     * The activity launches the system consent dialog after its first frame.
     * Calling [android.net.VpnService.prepare] here would raise that dialog
     * from a service, over the still-black window.
     */
    private fun broadcastConsentNeeded() {
        if (!sessionIntended()) return
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, false)
                .putExtra(EXTRA_NEEDS_VPN_CONSENT, true),
        )
    }

    private fun handshakeOk(): Boolean {
        val hs = latestHandshakeMs()
        if (hs <= 0L) return false
        val age = System.currentTimeMillis() - hs
        if (age >= HANDSHAKE_OK_AGE_MS) return false
        // A timestamp with no received bytes is not a working tunnel.
        return DeadPathRoam.workingSession(
            handshakeFresh = true,
            rxSinceAttempt = readBackendStats().first,
        )
    }

    /**
     * Promote this service before any other start work. The type matches the
     * manifest. A thrown framework exception is recorded and does not escape.
     */
    private fun enterForeground(text: String) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            DiagnosticLog.recordCrash(error)
            Log.w(TAG, "startForeground: ${error.javaClass.simpleName}")
        }
    }

    private fun leaveForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            DiagnosticLog.recordCrash(error)
            Log.w(TAG, "stopForeground: ${error.javaClass.simpleName}")
        }
    }

    private fun buildNotification(text: String): Notification {
        lastNotificationText = text
        // A failed re-provision keeps isReconnecting set. Later status updates
        // must not paint "Connected" over Reconnecting while the peer is gone.
        // Signed out is its own state: not a raw error and not "Reconnecting…".
        val shown = if (controlSignedOut) {
            ControlPlane.SIGN_IN_AGAIN
        } else {
            appendStealthUserLine(
                transportSuffix(
                    if (isReconnecting) BlockedNetworkNotice.reconnectingText(networkMayBeBlocking) else text,
                ),
            )
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
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "VeritasVPN", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN connection status"
        }
        manager.createNotificationChannel(channel)
        val shield = NotificationChannel(
            SHIELD_CHANNEL_ID, "Veritas Shield", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Sites Veritas Shield blocked"
        }
        manager.createNotificationChannel(shield)
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
                synchronized(controlClientLock) {
                    if (!bestMatchingWatchRegistered) {
                        cm.registerBestMatchingNetworkCallback(
                            request,
                            bestMatchingCallback,
                            underlayHandler,
                        )
                        bestMatchingWatchRegistered = true
                    }
                }
            }
            cm.registerNetworkCallback(request, underlayCallback)
            underlayWatchRegistered = true
            startHealthWatch()
        }.onFailure { error ->
            Log.w(TAG, "Could not watch underlay networks", error)
        }
    }

    /**
     * Best-matching NOT_VPN network only. The full underlay watch also starts
     * health checks and roam rebuilds, which must stay off after disconnect.
     * Callbacks still update [bestMatchingNetwork]; [scheduleUnderlayAdapt]
     * returns while the session is ended.
     */
    private fun keepBestMatchingWatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        synchronized(controlClientLock) {
            if (bestMatchingWatchRegistered) return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            runCatching {
                connectivityManager().registerBestMatchingNetworkCallback(
                    request,
                    bestMatchingCallback,
                    underlayHandler,
                )
                bestMatchingWatchRegistered = true
            }.onFailure { error ->
                Log.w(TAG, "provision watch: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun unregisterUnderlayWatch() {
        val cm = connectivityManager()
        synchronized(controlClientLock) {
            if (bestMatchingWatchRegistered) {
                runCatching { cm.unregisterNetworkCallback(bestMatchingCallback) }
                bestMatchingWatchRegistered = false
                bestMatchingNetwork = null
            }
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
        // lock across wgTurnOn / establish. Following the default runs on the service scope.
        if (sessionIntended()) defaultNetworkSignals.trySend(Unit)
        if (!sessionIntended() || controlSignedOut) return
        val gen = sessionGeneration
        if (rebuildInProgress) {
            // Same-network callbacks must not cancel the rebuild. A new default
            // wakes the loop so it can reset and rebuild on that default.
            return
        }
        val target = EarlyUnderlay.bindTarget(earlyUnderlay)
        // A fresh handshake only suppresses repeats for a bind target that is
        // still addressed. A dead Wi-Fi handle must not hide mobile data.
        val targetAlive = bindTargetAddressed(target)
        // Wi-Fi validation fires onCapabilities many times for one switch.
        // Cancelling an adapt that is already running turns userspace off
        // again, so the handshake never finishes. Stealth keeps the dial that
        // already owns this handle: cancelling it opens a second websocket.
        // Direct mode still restarts a hard stall. A dead target still starts
        // a roam when nothing is in flight.
        if (adaptJob?.isActive == true && target != null && target == activeRoamHandle &&
            (!forceHard || usesStealth())
        ) {
            return
        }
        if (targetAlive && !forceHard && adaptJob?.isActive != true && target != null && target == activeRoamHandle &&
            handshakeOk()
        ) {
            return
        }
        adaptJob?.cancel()
        if (target != null) activeRoamHandle = target
        adaptJob = scope.launch {
            try {
                delay(UNDERLAY_ADAPT_DEBOUNCE_MS)
                if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) {
                    return@launch
                }
                // Wait for a connect to finish before touching the tunnel.
                // Never join the disconnect job: it joins this one, and the
                // two waits deadlock with wgTurnOn still in flight.
                val gate = transitionJob
                if (gate != null && gate.isActive && gate !== adaptJob && gate !== disconnectJob) {
                    gate.join()
                }
                if (gen != sessionGeneration || !sessionIntended() || disconnectJob?.isActive == true) {
                    return@launch
                }
                adaptUnderlay(gen, forceHard = forceHard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "path-adapt failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun adaptUnderlay(gen: Long, forceHard: Boolean = false) {
        if (controlSignedOut || gen != sessionGeneration || !sessionIntended()) return
        if (usesStealth()) {
            adaptWhileStealth(gen, forceHard)
            return
        }
        val config = vpnStatePrefs().getString(KEY_CONFIG, null) ?: return
        // An address is enough. Waiting for NET_CAPABILITY_VALIDATED stalls
        // Wi-Fi to cellular: those probes can be captured by the tunnel.
        // The callback network is used before it is the validated default.
        // dataPlaneNetwork already ignores an early handle that has no address.
        val available = dataPlaneSnap()?.takeIf { ipv4Addresses(it.link).isNotEmpty() }
        val roam = UnderlayRoam.action(
            liveIdentity = available?.identity,
            liveReady = available != null,
            lastIdentity = lastUnderlayIdentity,
        )

        if (lastUnderlayFingerprint == null) {
            val record = available ?: return
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
            waitRecheckJob?.cancel()
            waitRecheckJob = scope.launch {
                delay(EndpointRefresh.HANDSHAKE_POLL_MS)
                if (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
                    scheduleUnderlayAdapt(forceHard = false)
                }
            }
            return
        }
        underlayWaitStartedAtMs = 0L

        val effective = available ?: return

        val ipv4s = ipv4Addresses(effective.link)
        if (ipv4s.isEmpty()) return
        if (roam == UnderlayRoam.Action.SKIP &&
            (RoamRecovery.sameNetworkLeavesTunnelAlone(sameHandle = true, handshakeHealthy = handshakeOk()) ||
                !forceHard)
        ) {
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
        Log.i(
            TAG,
            "path-adapt re-resolve handle=${underlay.network.networkHandle} " +
                "addrs=${ipv4s.size} forceHard=$forceHard saved=${config.isNotEmpty()}",
        )
        recoverWithFreshEndpoint(gen, underlay.network.networkHandle)
    }

    private sealed class EndpointFetch {
        data class Ready(
            val offer: EndpointRefresh.Offer,
            val flight: EndpointRefresh.Flight,
        ) : EndpointFetch()
        object Stale : EndpointFetch()
        object Failed : EndpointFetch()
        object Stop : EndpointFetch()
    }

    private data class Installed(val liveConfig: String, val endpoint: String)

    /**
     * Install the endpoint for this underlay immediately, and read the peer on
     * the current system-default network at the same time. With no fresh read,
     * that endpoint is the public one. A private LAN endpoint is installed
     * only when this read returned it as server_endpoint and this underlay
     * can reach it. A healthy handshake on the endpoint that was installed
     * finishes the roam, including a public handshake on a Wi-Fi that shares
     * a common /24. One socket swap that does not handshake is followed by a
     * full rebuild that does not turn the tunnel down, unless the peer read
     * has already returned a different endpoint. The give-up line waits until
     * the roam has been failing for [RoamRecovery.NOTICE_AFTER_MS]. Later
     * attempts continue on this network.
     */
    private suspend fun recoverWithFreshEndpoint(gen: Long, initialHandle: Long?) {
        if (RoamRecovery.sameNetworkLeavesTunnelAlone(
                sameHandle = initialHandle != null && lastUnderlayIdentity == initialHandle.toString(),
                handshakeHealthy = handshakeOk(),
            )
        ) {
            return
        }
        var handshakeRecovered = false
        var tracked = initialHandle
        var failedSocketSwaps = 0
        if (initialHandle != null) activeRoamHandle = initialHandle
        stealthReconnectJob?.cancel()
        stealthReconnectJob = null
        if (controlClientHandle != (initialHandle ?: Long.MIN_VALUE)) dropUnderlyingControlClient()
        try {
            reconnectNotice = null
            roamRetryDelayMs = RoamRecovery.Retry.INITIAL_MS
            publishReconnecting(true)
            var armedAt = roamArmedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            linkAttachedAtMs = armedAt
            while (sessionStillActive(gen)) {
                if (controlSignedOut) return
                when (recoveryStep(probeRoundCompleted = false, rebuilding = false)) {
                    // Peer-gone still stops. The tun stays up across a roam.
                    TunnelRebuild.RecoveryStep.REPROVISION,
                    TunnelRebuild.RecoveryStep.STOP -> return
                    TunnelRebuild.RecoveryStep.REBUILD,
                    TunnelRebuild.RecoveryStep.WAIT,
                    TunnelRebuild.RecoveryStep.RESET -> Unit
                }
                val snap = dataPlaneSnap()
                val handle = snap?.network?.networkHandle
                if (snap == null || ipv4Addresses(snap.link).isEmpty() ||
                    (handle != null && !roamTargetStill(handle))
                ) {
                    delay(EndpointRefresh.HANDSHAKE_POLL_MS)
                    continue
                }
                if (handle != tracked) {
                    failedSocketSwaps = 0
                    if (handle != roamFailHandle) {
                        roamFailHandle = handle
                        roamFailRebuilds = 0
                    }
                    tracked = handle
                    if (handle != null) activeRoamHandle = handle
                    roamRetryDelayMs = RoamRecovery.Retry.INITIAL_MS
                    reconnectNotice = null
                    armedAt = roamArmedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                    linkAttachedAtMs = armedAt
                    if (controlClientHandle != (handle ?: Long.MIN_VALUE)) dropUnderlyingControlClient()
                    followSystemDefault()
                }
                if (noteHandshake(armedAt) && endpointAcceptable(snap)) {
                    handshakeRecovered = true
                    return
                }
                followSystemDefault()
                val round = roamRound(gen, snap, handle, armedAt, failedSocketSwaps)
                armedAt = round.armedAt
                failedSocketSwaps = round.failedSocketSwaps
                when (round.outcome) {
                    InstallOutcome.HANDSHOOK -> {
                        roamRetryDelayMs = RoamRecovery.Retry.INITIAL_MS
                        handshakeRecovered = true
                        return
                    }
                    InstallOutcome.STOPPED -> return
                    InstallOutcome.STALE -> {
                        failedSocketSwaps = 0
                        continue
                    }
                    InstallOutcome.RETRY, InstallOutcome.FAILED -> {
                        // The swap and, when it missed, the rebuild have finished.
                        // The line is not a stop. After repeated misses on this
                        // same network, replace the peer and the tun and keep going.
                        if (round.rebuildTried) {
                            val streak = TunnelRebuild.noteFailedRebuild(
                                TunnelRebuild.RebuildStreak(roamFailHandle, roamFailRebuilds),
                                tracked,
                            )
                            roamFailHandle = streak.handle
                            roamFailRebuilds = streak.count
                        }
                        if (TunnelRebuild.shouldReprovisionFreshTunnel(roamFailRebuilds)) {
                            roamFailRebuilds = 0
                            val freshSince = System.currentTimeMillis()
                            if (escalateFreshTunnel(gen) &&
                                waitForHandshake(gen, freshSince, 3_000L, handle)
                            ) {
                                roamRetryDelayMs = RoamRecovery.Retry.INITIAL_MS
                                handshakeRecovered = true
                                return
                            }
                        }
                        noteResolveFailure(rebuildTried = round.rebuildTried)
                        val wait = if (round.rebuildTried) {
                            val current = roamRetryDelayMs
                            roamRetryDelayMs = RoamRecovery.Retry.next(current)
                            current
                        } else {
                            EndpointRefresh.RETRY_DELAY_MS
                        }
                        delay(wait)
                    }
                }
            }
        } finally {
            if (handshakeRecovered) {
                roamFailHandle = null
                roamFailRebuilds = 0
            }
            if (handshakeRecovered || !sessionStillActive(gen)) {
                publishReconnecting(false)
            }
        }
    }

    private data class RoamRound(
        val outcome: InstallOutcome,
        val failedSocketSwaps: Int,
        val armedAt: Long,
        val rebuildTried: Boolean,
    )

    /**
     * Socket swap (then one rebuild) runs at once. The peer GET is in flight
     * beside it and cannot hold the first handshake.
     */
    private suspend fun roamRound(
        gen: Long,
        snap: UnderlaySnap,
        handle: Long?,
        armedAtIn: Long,
        failedIn: Int,
    ): RoamRound = coroutineScope {
        var armedAt = armedAtIn
        var failedSocketSwaps = failedIn
        var rebuildTried = false
        val fetch = async { fetchFreshEndpoint(handle) }
        try {
            val preset = offerForUnderlay(snap, advertised = null)
            if (preset != null) {
                RoamLog.record("endpoint class=${preset.kind}")
                val watched = installSwapThenRebuild(gen, snap, preset.offer, failedSocketSwaps) {
                    // The peer read finished with its own endpoint. Rebuilding
                    // the preset would ignore it and, on a miss, create another
                    // peer. Install the fresh answer instead.
                    !fetch.isCompleted
                }
                armedAt = watched.armedAt
                failedSocketSwaps = watched.failedSocketSwaps
                rebuildTried = watched.rebuildTried
                when (watched.outcome) {
                    InstallOutcome.HANDSHOOK -> {
                        // The handshake is for the endpoint just installed.
                        // A public handshake finishes the roam. A later move
                        // to LAN happens only after that LAN handshake succeeds,
                        // which is a separate install, not a reason to keep
                        // rebuilding a tunnel that is already up.
                        if (endpointAcceptable(snap)) {
                            return@coroutineScope RoamRound(
                                InstallOutcome.HANDSHOOK,
                                failedSocketSwaps,
                                armedAt,
                                rebuildTried,
                            )
                        }
                    }
                    InstallOutcome.STOPPED ->
                        return@coroutineScope RoamRound(watched.outcome, failedSocketSwaps, armedAt, rebuildTried)
                    InstallOutcome.STALE ->
                        return@coroutineScope RoamRound(InstallOutcome.STALE, 0, armedAt, rebuildTried)
                    InstallOutcome.RETRY, InstallOutcome.FAILED -> Unit
                }
            }
            val fresh = withTimeoutOrNull(EndpointRefresh.PEER_READ_BUDGET_MS) { fetch.await() }
                ?: EndpointFetch.Failed
            when (fresh) {
                is EndpointFetch.Stop ->
                    RoamRound(InstallOutcome.STOPPED, failedSocketSwaps, armedAt, rebuildTried)
                is EndpointFetch.Stale ->
                    RoamRound(InstallOutcome.STALE, 0, armedAt, rebuildTried)
                is EndpointFetch.Failed -> {
                    if (noteHandshake(armedAt) && endpointAcceptable(snap)) {
                        commitUnderlay(snap)
                        RoamLog.record("handshake ok")
                        RoamRound(InstallOutcome.HANDSHOOK, failedSocketSwaps, armedAt, rebuildTried)
                    } else {
                        RoamRound(InstallOutcome.RETRY, failedSocketSwaps, armedAt, rebuildTried)
                    }
                }
                is EndpointFetch.Ready -> {
                    if (!EndpointRefresh.stillCurrent(fresh.flight, endpointFlight) ||
                        (handle != null && !roamTargetStill(handle))
                    ) {
                        return@coroutineScope RoamRound(InstallOutcome.STALE, 0, armedAt, rebuildTried)
                    }
                    val sameAsPreset = preset != null && !EndpointRefresh.endpointChanged(
                        preset.offer.serverEndpoint,
                        fresh.offer.serverEndpoint,
                    )
                    if (noteHandshake(armedAt) &&
                        !EndpointRefresh.endpointChanged(currentUdpEndpoint(), fresh.offer.serverEndpoint) &&
                        endpointAcceptable(snap)
                    ) {
                        commitUnderlay(snap)
                        RoamLog.record("handshake ok")
                        return@coroutineScope RoamRound(
                            InstallOutcome.HANDSHOOK,
                            failedSocketSwaps,
                            armedAt,
                            rebuildTried,
                        )
                    }
                    if (sameAsPreset) {
                        return@coroutineScope RoamRound(InstallOutcome.RETRY, failedSocketSwaps, armedAt, rebuildTried)
                    }
                    RoamLog.record("endpoint class=${freshEndpointKind(snap, fresh.offer)}")
                    val watched = installSwapThenRebuild(gen, snap, fresh.offer, failedSocketSwaps)
                    RoamRound(
                        watched.outcome,
                        if (watched.outcome == InstallOutcome.STALE) 0 else watched.failedSocketSwaps,
                        watched.armedAt,
                        rebuildTried || watched.rebuildTried,
                    )
                }
            }
        } finally {
            fetch.cancel()
            cancelEndpointCall()
        }
    }

    private fun freshEndpointKind(snap: UnderlaySnap, offer: EndpointRefresh.Offer): String =
        EndpointRefresh.chooseForUnderlay(
            advertised = offer.serverEndpoint,
            lan = offer.lan,
            wan = offer.wan,
            underlayIpv4s = ipv4Addresses(snap.link),
            stealth = usesStealth(),
            stealthEndpoint = offer.stealthEndpoint,
        )?.kind ?: "public"

    private data class SwapWatch(
        val outcome: InstallOutcome,
        val failedSocketSwaps: Int,
        val armedAt: Long,
        val rebuildTried: Boolean,
    )

    /** First install swaps sockets. A miss rebuilds once before this round ends. */
    private suspend fun installSwapThenRebuild(
        gen: Long,
        snap: UnderlaySnap,
        offer: EndpointRefresh.Offer,
        failedSocketSwaps: Int,
        allowRebuild: () -> Boolean = { true },
    ): SwapWatch {
        val first = installAndWatch(gen, snap, offer, failedSocketSwaps)
        if (first.outcome != InstallOutcome.RETRY) {
            return SwapWatch(first.outcome, first.failedSocketSwaps, first.armedAt, rebuildTried = false)
        }
        if (!allowRebuild()) {
            return SwapWatch(first.outcome, first.failedSocketSwaps, first.armedAt, rebuildTried = false)
        }
        if (RoamRecovery.installAfterFailedSwaps(first.failedSocketSwaps) != RoamRecovery.Install.FULL_REBUILD) {
            return SwapWatch(first.outcome, first.failedSocketSwaps, first.armedAt, rebuildTried = false)
        }
        val rebuilt = installAndWatch(gen, snap, offer, first.failedSocketSwaps)
        return SwapWatch(
            rebuilt.outcome,
            rebuilt.failedSocketSwaps,
            rebuilt.armedAt,
            rebuildTried = true,
        )
    }

    private enum class InstallOutcome { HANDSHOOK, RETRY, FAILED, STOPPED, STALE }

    private data class InstallWatch(
        val outcome: InstallOutcome,
        val failedSocketSwaps: Int,
        val armedAt: Long,
    )

    private data class ChosenOffer(val offer: EndpointRefresh.Offer, val kind: String)

    /**
     * Install [offer] on the tun that is already up. The first miss swaps
     * sockets. The next miss rebuilds without turning the tunnel down.
     */
    private suspend fun installAndWatch(
        gen: Long,
        snap: UnderlaySnap,
        offer: EndpointRefresh.Offer,
        failedSocketSwaps: Int,
    ): InstallWatch {
        if (!sessionStillActive(gen)) {
            return InstallWatch(InstallOutcome.STOPPED, failedSocketSwaps, roamArmedAtMs)
        }
        if (stealthUpgradeInProgress()) {
            val armedAt = System.currentTimeMillis()
            linkAttachedAtMs = armedAt
            publishReconnecting(true)
            RoamLog.record("stealth upgrade")
            val handle = snap.network.networkHandle
            if (awaitFreshHandshake(gen, armedAt, handle)) {
                if (!sessionStillActive(gen)) {
                    return InstallWatch(InstallOutcome.STOPPED, failedSocketSwaps, armedAt)
                }
                commitUnderlay(snap)
                publishReconnecting(false)
                RoamLog.record("handshake ok")
                Log.i(TAG, "path-adapt handshake ok handle=$handle stealth=upgrade")
                return InstallWatch(InstallOutcome.HANDSHOOK, failedSocketSwaps, armedAt)
            }
            if (!sessionStillActive(gen) || controlSignedOut) {
                return InstallWatch(InstallOutcome.STOPPED, failedSocketSwaps, armedAt)
            }
            if (!roamTargetStill(handle)) {
                return InstallWatch(InstallOutcome.STALE, 0, armedAt)
            }
            // The upgrade is still running. Do not count a failed swap or rebuild.
            return InstallWatch(InstallOutcome.RETRY, failedSocketSwaps, armedAt)
        }
        val install = RoamRecovery.installAfterFailedSwaps(failedSocketSwaps)
        val rebuildTun = install == RoamRecovery.Install.FULL_REBUILD
        var nextFailed = failedSocketSwaps
        if (!rebuildTun) nextFailed++
        val armedAt = System.currentTimeMillis()
        linkAttachedAtMs = armedAt
        publishReconnecting(true)
        RoamLog.record(if (rebuildTun) "rebuild" else "socket swap")
        val installed = applyResolved(gen, offer, rebuildTun)
        if (installed == null) {
            Log.w(TAG, "path-adapt install failed rebuild=$rebuildTun")
            return if (rebuildTun) {
                InstallWatch(InstallOutcome.FAILED, 0, armedAt)
            } else {
                InstallWatch(InstallOutcome.RETRY, nextFailed, armedAt)
            }
        }
        logHardProbe(installed.liveConfig, installed.endpoint, snap)
        protectBackendSockets()
        val handle = snap.network.networkHandle
        if (awaitFreshHandshake(gen, armedAt, handle)) {
            if (!sessionStillActive(gen)) {
                return InstallWatch(InstallOutcome.STOPPED, nextFailed, armedAt)
            }
            commitUnderlay(snap)
            publishReconnecting(false)
            RoamLog.record("handshake ok")
            Log.i(TAG, "path-adapt handshake ok handle=$handle rebuild=$rebuildTun")
            return InstallWatch(InstallOutcome.HANDSHOOK, nextFailed, armedAt)
        }
        recordHandshakeFailure(handle)
        if (!sessionStillActive(gen) || controlSignedOut) {
            return InstallWatch(InstallOutcome.STOPPED, nextFailed, armedAt)
        }
        if (!roamTargetStill(handle)) {
            return InstallWatch(InstallOutcome.STALE, 0, armedAt)
        }
        if (!rebuildTun) {
            Log.w(TAG, "path-adapt socket swap had no handshake; rebuilding")
            return InstallWatch(InstallOutcome.RETRY, nextFailed, armedAt)
        }
        Log.w(TAG, "path-adapt no handshake")
        return InstallWatch(InstallOutcome.FAILED, 0, armedAt)
    }

    /**
     * The installed endpoint must be the one chosen for this underlay.
     * A stored LAN address is not selected from a /24 match. A public
     * handshake is acceptable when the choice is the public endpoint.
     */
    private fun endpointAcceptable(snap: UnderlaySnap): Boolean {
        val prefs = vpnStatePrefs()
        val endpoint = if (usesStealth()) {
            prefs.getString(KEY_STEALTH_ENDPOINT, null)
        } else {
            currentUdpEndpoint()
        }
        return EndpointRefresh.endpointFitsUnderlay(
            installed = endpoint,
            advertised = null,
            lan = prefs.getString(KEY_ENDPOINT_LAN, null),
            wan = prefs.getString(KEY_ENDPOINT_WAN, null),
            underlayIpv4s = ipv4Addresses(snap.link),
            stealth = usesStealth(),
            stealthEndpoint = prefs.getString(KEY_STEALTH_ENDPOINT, null),
        )
    }

    /**
     * Pick the endpoint for [snap]. [advertised] null means this network has
     * no fresh peer read yet, so the public endpoint is used. A stored LAN
     * address is installed only when [advertised] itself is that LAN endpoint.
     */
    private fun offerForUnderlay(snap: UnderlaySnap, advertised: PeerEndpointView?): ChosenOffer? {
        val prefs = vpnStatePrefs()
        val choice = EndpointRefresh.chooseForUnderlay(
            advertised = advertised?.serverEndpoint,
            lan = EndpointRefresh.storedOrFresh(
                advertised?.serverEndpointLan,
                prefs.getString(KEY_ENDPOINT_LAN, null),
            ),
            wan = EndpointRefresh.storedOrFresh(
                advertised?.serverEndpointWan,
                prefs.getString(KEY_ENDPOINT_WAN, null),
            ),
            underlayIpv4s = ipv4Addresses(snap.link),
            stealth = usesStealth(),
            stealthEndpoint = EndpointRefresh.storedOrFresh(
                advertised?.stealthEndpoint,
                prefs.getString(KEY_STEALTH_ENDPOINT, null),
            ),
        ) ?: return null
        val offer = EndpointRefresh.offer(
            serverEndpoint = choice.udpEndpoint,
            lan = EndpointRefresh.storedOrFresh(
                advertised?.serverEndpointLan,
                prefs.getString(KEY_ENDPOINT_LAN, null),
            ),
            wan = EndpointRefresh.storedOrFresh(
                advertised?.serverEndpointWan,
                prefs.getString(KEY_ENDPOINT_WAN, null),
            ),
            stealthEndpoint = if (usesStealth()) {
                choice.stealthEndpoint
            } else {
                advertised?.stealthEndpoint ?: prefs.getString(KEY_STEALTH_ENDPOINT, null)
            },
            stealthAvailable = advertised?.stealthAvailable
                ?: prefs.getBoolean(KEY_STEALTH_AVAILABLE, false),
            stealthPrefix = advertised?.stealthPathPrefix ?: prefs.getString(KEY_STEALTH_PREFIX, null),
            ignoredPrevious = currentUdpEndpoint(),
            serverPublicKey = advertised?.server?.publicKey,
        ) ?: return null
        return ChosenOffer(offer, choice.kind)
    }

    private fun noteResolveFailure(failures: Int = 1, rebuildTried: Boolean = false) {
        // A finished rebuild is not enough. The line waits out a normal roam.
        val recoveryPending = !rebuildTried
        val elapsed = if (roamArmedAtMs > 0L) System.currentTimeMillis() - roamArmedAtMs else 0L
        if (RoamRecovery.failureEligible(
                socketSwapTried = failures > 0,
                rebuildTried = rebuildTried,
                recoveryPending = recoveryPending,
                elapsedMs = elapsed,
            )
        ) {
            EndpointRefresh.noticeAfter(
                failures,
                rebuildTried = rebuildTried,
                recoveryPending = recoveryPending,
                elapsedMs = elapsed,
            )?.let { showReconnectNotice(it) }
        }
        if (lastPeerOutcome == null) ensurePeerCheck(force = true, forceUnderlying = true)
    }

    private fun showReconnectNotice(message: String) {
        val shown = EndpointRefresh.displayStatus(message) ?: return
        if (shown == reconnectNotice) return
        reconnectNotice = shown
        if (isReconnecting) broadcastState(true, shown)
    }

    /**
     * The TLS upgrade has not finished and the underlay is unchanged.
     * A roam must not close the socket or create a peer during that window.
     * A new underlay handle returns false so the next dial can bind to it.
     */
    private fun stealthUpgradeInProgress(): Boolean {
        val transport = stealthTransport ?: return false
        if (!usesStealth()) return false
        return !transport.mayRedial(dataPlaneNetwork()?.networkHandle, System.currentTimeMillis())
    }

    private fun usesStealth(): Boolean = EndpointRefresh.selectedTransport(
        modeStored = vpnStatePrefs().getString(KEY_STEALTH_MODE, null),
        resumeStealth = vpnStatePrefs().getBoolean(KEY_RESUME_STEALTH, false),
        transportRunning = stealthTransport != null,
    ) == EndpointRefresh.Transport.STEALTH

    private fun applyResolved(gen: Long, offer: EndpointRefresh.Offer, rebuildTun: Boolean): Installed? {
        if (!sessionStillActive(gen)) return null
        rememberFreshAdvertisement(offer)
        val template = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
            ?: vpnStatePrefs().getString(KEY_CONFIG, null)
            ?: return null
        return if (usesStealth()) {
            applyStealthResolved(gen, offer, template, rebuildTun)
        } else {
            applyUdpResolved(gen, offer, template, rebuildTun)
        }
    }

    private fun applyUdpResolved(
        gen: Long,
        offer: EndpointRefresh.Offer,
        template: String,
        rebuildTun: Boolean,
    ): Installed? {
        val replaced = EndpointSelector.replaceEndpoint(template, offer.serverEndpoint)
        val liveConfig = BackendConfig.select(
            vpnStatePrefs().getString(KEY_CONFIG, null),
            replaced,
            offer.serverPublicKey,
        )
        if (EndpointSelector.endpointFromConfig(liveConfig) != offer.serverEndpoint) return null
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return null
        followSystemDefault()
        val rebound = installUserspace(parsed, gen, rebuildTun)
        if (!rebound || !sessionStillActive(gen)) return null
        synchronized(tunnelOpLock) {
            if (!sessionStillActive(gen)) return null
            val prefs = vpnStatePrefs()
            prefs.edit()
                .putString(KEY_CONFIG, liveConfig)
                .putString(KEY_UDP_CONFIG, liveConfig)
                .apply()
            lastChosenEndpoint = offer.serverEndpoint
        }
        publishTransport("udp")
        lastRebindAtMs = System.currentTimeMillis()
        return Installed(liveConfig, offer.serverEndpoint)
    }

    private fun applyStealthResolved(
        gen: Long,
        offer: EndpointRefresh.Offer,
        template: String,
        rebuildTun: Boolean,
    ): Installed? {
        val stealthEp = EndpointRefresh.freshEndpoint(offer.stealthEndpoint) ?: return null
        val parsedEp = WstunnelProtocol.parseEndpoint(stealthEp) ?: return null
        val prefix = offer.stealthPrefix
            ?: vpnStatePrefs().getString(KEY_STEALTH_PREFIX, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val stealthOffer = StealthOffer(endpoint = stealthEp, pathPrefix = prefix, available = true)
        if (!stealthOffer.usable()) return null
        val transport = stealthTransport
        if (transport != null && transport.targets(parsedEp.host, parsedEp.port, prefix) &&
            !transport.mayRedial(dataPlaneNetwork()?.networkHandle, System.currentTimeMillis())
        ) {
            // The TLS upgrade has not finished on this underlay. Rebinding
            // userspace here calls transport.reconnect() and closes the socket.
            RoamLog.record("stealth upgrade")
            val stored = vpnStatePrefs().getString(KEY_CONFIG, null)
            if (!stored.isNullOrBlank()) return Installed(stored, transport.localEndpoint)
        }
        val local = if (transport != null && transport.targets(parsedEp.host, parsedEp.port, prefix)) {
            followSystemDefault()
            transport.reconnect()
            transport.localEndpoint
        } else {
            try {
                startStealth(stealthOffer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stealth retarget failed: ${e.javaClass.simpleName}")
                return null
            }
        }
        val liveConfig = BackendConfig.select(
            vpnStatePrefs().getString(KEY_CONFIG, null),
            EndpointSelector.replaceEndpoint(template, local),
            offer.serverPublicKey,
        )
        val udpConfig = BackendConfig.select(
            vpnStatePrefs().getString(KEY_UDP_CONFIG, null),
            EndpointSelector.replaceEndpoint(template, offer.serverEndpoint),
            offer.serverPublicKey,
        )
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return null
        followSystemDefault()
        val rebound = installUserspace(parsed, gen, rebuildTun)
        if (!rebound || !sessionStillActive(gen)) return null
        synchronized(tunnelOpLock) {
            if (!sessionStillActive(gen)) return null
            val prefs = vpnStatePrefs()
            prefs.edit()
                .putString(KEY_CONFIG, liveConfig)
                .putString(KEY_UDP_CONFIG, udpConfig)
                .putString(KEY_STEALTH_ENDPOINT, stealthEp)
                .putString(KEY_STEALTH_PREFIX, prefix)
                .apply()
            lastChosenEndpoint = local
        }
        publishTransport("stealth")
        lastRebindAtMs = System.currentTimeMillis()
        return Installed(liveConfig, local)
    }

    private fun rememberFreshAdvertisement(offer: EndpointRefresh.Offer) {
        val edit = vpnStatePrefs().edit()
        offer.lan?.let { edit.putString(KEY_ENDPOINT_LAN, it) }
        offer.wan?.let { edit.putString(KEY_ENDPOINT_WAN, it) }
        offer.stealthEndpoint?.let { edit.putString(KEY_STEALTH_ENDPOINT, it) }
        offer.stealthPrefix?.let { edit.putString(KEY_STEALTH_PREFIX, it) }
        offer.stealthAvailable?.let { edit.putBoolean(KEY_STEALTH_AVAILABLE, it) }
        edit.apply()
    }

    private fun commitUnderlay(snap: UnderlaySnap) {
        val ipv4s = ipv4Addresses(snap.link)
        lastUnderlayFingerprint = underlayFingerprint(snap.network, snap.link, ipv4s)
        lastUnderlayIdentity = snap.identity
        val hs = latestHandshakeMs()
        if (hs > 0L) linkAttachedAtMs = hs - 1L
        underlayLostSeen = false
        rxAtLinkAttach = readBackendStats().first
        startStatsPolling()
        followSystemDefault()
        startBackgroundEgressValidation()
    }

    private fun beginEndpointFlight(handle: Long?): EndpointRefresh.Flight {
        val next = EndpointRefresh.sameNetwork(endpointFlight, handle)
        if (endpointFlight?.handle != handle) reconnectNotice = null
        endpointFlight = next
        return next
    }

    private fun noteEndpointNetwork(handle: Long?) {
        val latest = endpointFlight
        if (latest != null && latest.handle == handle) return
        endpointFlight = EndpointRefresh.supersede(latest, handle)
        reconnectNotice = null
        cancelEndpointCall()
    }

    private fun cancelEndpointCall() {
        val call = synchronized(endpointCallLock) {
            val current = endpointCall
            endpointCall = null
            current
        } ?: return
        // Call.cancel closes the socket. That write crashes the process on
        // the main thread, and onStartCommand / network callbacks run there.
        socketCloseScope.launch(Dispatchers.IO) {
            runCatching { call.cancel() }
        }
    }

    private fun cancelEndpointFetch() {
        endpointFlight = EndpointRefresh.supersede(endpointFlight, null)
        reconnectNotice = null
        cancelEndpointCall()
    }

    private fun endpointAttemptCurrent(flight: EndpointRefresh.Flight, expectedHandle: Long?): Boolean =
        EndpointRefresh.stillCurrent(flight, endpointFlight) &&
            (expectedHandle == null || roamTargetStill(expectedHandle)) &&
            sessionIntended() &&
            disconnectJob?.isActive != true &&
            !controlSignedOut

    private fun staleOrFailed(flight: EndpointRefresh.Flight, expectedHandle: Long?): EndpointFetch =
        if (endpointAttemptCurrent(flight, expectedHandle)) EndpointFetch.Failed else EndpointFetch.Stale

    /**
     * GET the existing peer on the protected client bound to the current
     * system default, outside the tunnel. The server picks the endpoint from
     * that source address, the same way a fresh connect does.
     */
    private suspend fun fetchFreshEndpoint(expectedHandle: Long?): EndpointFetch {
        if (controlSignedOut || !sessionIntended() || disconnectJob?.isActive == true) {
            return EndpointFetch.Stop
        }
        val flight = beginEndpointFlight(expectedHandle)
        if (!endpointAttemptCurrent(flight, expectedHandle)) return EndpointFetch.Stale
        val peerId = VpnSettings.currentPeerId(this)
        if (peerId.isNullOrBlank()) return EndpointFetch.Failed
        if (controlClientHandle != (expectedHandle ?: Long.MIN_VALUE)) dropUnderlyingControlClient()
        val http = underlyingControlClient() ?: return EndpointFetch.Failed
        if (!endpointAttemptCurrent(flight, expectedHandle)) return EndpointFetch.Stale
        val readSnap = systemDefaultSnap()
        val readNet = if (readSnap != null && readSnap.network.networkHandle == expectedHandle) {
            networkType(readSnap.caps)
        } else {
            "OTHER"
        }
        RoamLog.record("peer-read net=$readNet handle=${expectedHandle ?: 0}")
        val access = try {
            authRepo.accessForControl(http, tunnelHealthy = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "endpoint refresh failed: ${e.javaClass.simpleName}")
            return staleOrFailed(flight, expectedHandle)
        }
        when (access) {
            is ControlAccess.SignedOut -> {
                stopForSignedOut()
                return EndpointFetch.Stop
            }
            is ControlAccess.Unavailable -> return EndpointFetch.Failed
            is ControlAccess.Ready -> Unit
        }
        var token = (access as ControlAccess.Ready).token
        val first = try {
            getPeerOnce(http, token, peerId, flight)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "endpoint refresh failed: ${e.javaClass.simpleName}")
            return staleOrFailed(flight, expectedHandle)
        }
        val response = if (first.code == 401) {
            first.close()
            if (!endpointAttemptCurrent(flight, expectedHandle)) return EndpointFetch.Stale
            when (val refreshed = authRepo.refreshForControl(http, force = true)) {
                ControlRefresh.SignedOut -> {
                    stopForSignedOut()
                    return EndpointFetch.Stop
                }
                ControlRefresh.Unavailable -> return EndpointFetch.Failed
                ControlRefresh.Refreshed -> {
                    token = authRepo.getAccessToken()?.takeIf { it.isNotBlank() } ?: run {
                        stopForSignedOut()
                        return EndpointFetch.Stop
                    }
                    try {
                        getPeerOnce(http, token, peerId, flight)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "endpoint refresh failed: ${e.javaClass.simpleName}")
                        return staleOrFailed(flight, expectedHandle)
                    }
                }
            }
        } else {
            first
        }
        try {
            if (!endpointAttemptCurrent(flight, expectedHandle)) return EndpointFetch.Stale
            when (response.code) {
                404, 410 -> {
                    // The peer is gone. One new peer is created here, including
                    // while a Stealth dial is held or the reprovision window is
                    // still open. A second 404 finds that create already running.
                    lastPeerOutcome = PeerCheckOutcome.Outcome.PEER_GONE
                    requestReprovision(sessionGeneration, peerGone = true)
                    return EndpointFetch.Stop
                }
            }
            if (response.code !in 200..299) return EndpointFetch.Failed
            val view = ApiClient.parse<PeerEndpointView>(response)
            val snap = dataPlaneSnap()
            if (snap == null || snap.network.networkHandle != expectedHandle) return EndpointFetch.Stale
            val chosen = offerForUnderlay(snap, view)
            if (chosen == null) {
                RoamLog.record("endpoint rejected")
                return EndpointFetch.Failed
            }
            RoamLog.record("endpoint class=${chosen.kind}")
            if (!endpointAttemptCurrent(flight, expectedHandle)) return EndpointFetch.Stale
            return EndpointFetch.Ready(chosen.offer, flight)
        } finally {
            response.close()
        }
    }

    private suspend fun getPeerOnce(
        http: OkHttpClient,
        token: String,
        peerId: String,
        flight: EndpointRefresh.Flight,
    ): Response {
        var mine: Call? = null
        return try {
            ApiClient.getOnce(
                path = "/api/v1/wg/peers/$peerId",
                token = token,
                httpClient = http,
                timeoutSeconds = EndpointRefresh.PEER_READ_TIMEOUT_SECONDS,
                onCall = { call ->
                    mine = call
                    synchronized(endpointCallLock) {
                        if (!EndpointRefresh.stillCurrent(flight, endpointFlight)) {
                            call.cancel()
                        } else {
                            endpointCall = call
                        }
                    }
                },
            )
        } finally {
            synchronized(endpointCallLock) {
                if (endpointCall === mine) endpointCall = null
            }
        }
    }


    /**
     * Tear userspace WireGuard down and bring a new tun up on the current
     * system-default network. The endpoint is re-read for that network first.
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
        var resolveFailures = 0
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
                        requestReprovision(gen, peerGone = true)
                        return false
                    }
                    TunnelRebuild.RecoveryStep.RESET -> {
                        applyRecoveryReset(handle)
                        backoff = TunnelRebuild.MIN_BACKOFF_MS
                        waiting = false
                        trackedHandle = handle
                        resolveFailures = 0
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
                            if (handle == null) followSystemDefault()
                            waiting = true
                            continue
                        }
                        trackedHandle = snap.network.networkHandle
                        rebuildDefaultHandle = trackedHandle ?: Long.MIN_VALUE
                        publishReconnecting(true)
                        val fresh = fetchFreshEndpoint(snap.network.networkHandle)
                        val offer = when (fresh) {
                            is EndpointFetch.Stop -> return false
                            is EndpointFetch.Stale -> {
                                waiting = false
                                continue
                            }
                            is EndpointFetch.Failed -> {
                                resolveFailures++
                                noteResolveFailure(resolveFailures)
                                delay(EndpointRefresh.RETRY_DELAY_MS)
                                waiting = false
                                continue
                            }
                            is EndpointFetch.Ready -> fresh.offer
                        }
                        if (systemDefaultNetwork()?.networkHandle != snap.network.networkHandle) {
                            waiting = false
                            continue
                        }
                        val started = System.currentTimeMillis()
                        val installed = try {
                            applyResolved(gen, offer, rebuildTun = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "path-adapt full rebuild failed: ${e.javaClass.simpleName}")
                            null
                        }
                        if (installed != null) {
                            logHardProbe(installed.liveConfig, installed.endpoint, snap)
                        } else {
                            Log.w(TAG, "path-adapt full rebuild did not install")
                        }
                        val handshake = installed != null &&
                            waitForHandshake(
                                gen,
                                started,
                                EndpointRefresh.HANDSHAKE_WAIT_MS,
                                snap.network.networkHandle,
                            )
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
                                if (!sessionStillActive(gen)) return false
                                commitUnderlay(snap)
                                resetRebuildStreak()
                                resolveFailures = 0
                                Log.i(TAG, "path-adapt full rebuild handshake ok")
                                return true
                            }
                            TunnelRebuild.RecoveryStep.REPROVISION -> {
                                requestReprovision(gen, peerGone = true)
                                return false
                            }
                            TunnelRebuild.RecoveryStep.RESET -> {
                                val now = systemDefaultNetwork()?.networkHandle
                                applyRecoveryReset(now)
                                backoff = TunnelRebuild.MIN_BACKOFF_MS
                                waiting = false
                                trackedHandle = now
                                resolveFailures = 0
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
        followSystemDefault()
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

    private fun clearEarlyRoam() {
        earlyUnderlay = EarlyUnderlay.State()
        earlyNetwork = null
        defaultUnderlayNetwork = null
        activeRoamHandle = Long.MIN_VALUE
        roamFailHandle = null
        roamFailRebuilds = 0
        synchronized(stealthSwitchLock) {
            stealthRoamMemory = StealthRoam.Memory()
        }
    }

    /**
     * Record the network a callback just delivered. Wi-Fi with an address is
     * bound before it is the validated default. Becoming the default re-binds
     * that same network and does not start another userspace turn.
     */
    private fun observeUnderlay(network: Network, newlyAvailable: Boolean) {
        val hasAddress = runCatching { addressedUnderlay(network) != null }.getOrDefault(false)
        val isDefault = bestMatchingNetwork?.networkHandle == network.networkHandle
        val adopt = hasAddress && !isDefault && adoptsEarly(network)
        val next = if (newlyAvailable) {
            EarlyUnderlay.onAvailable(
                earlyUnderlay,
                network.networkHandle,
                hasAddress,
                isDefault,
                adoptEarly = adopt,
            )
        } else {
            EarlyUnderlay.onCapabilities(
                earlyUnderlay,
                network.networkHandle,
                hasAddress,
                isDefault,
                adoptEarly = adopt,
            )
        }
        val becameDefault = isDefault && earlyUnderlay.early == network.networkHandle && next.early == null
        if (next.early != null && next.early != earlyUnderlay.early) {
            RoamLog.record("early-bind")
        } else if (becameDefault) {
            RoamLog.record("rebind-default")
        }
        earlyUnderlay = next
        if (next.early == network.networkHandle) {
            earlyNetwork = network
        } else if (earlyNetwork?.networkHandle == network.networkHandle) {
            earlyNetwork = null
        }
        if (next.defaultHandle == network.networkHandle) {
            defaultUnderlayNetwork = network
        } else if (defaultUnderlayNetwork?.networkHandle == network.networkHandle) {
            defaultUnderlayNetwork = null
        }
        noteRoamHandle(EarlyUnderlay.bindTarget(next))
        scheduleUnderlayAdapt()
    }

    private fun observeUnderlayLost(network: Network) {
        val next = EarlyUnderlay.onLost(earlyUnderlay, network.networkHandle)
        earlyUnderlay = next
        if (earlyNetwork?.networkHandle == network.networkHandle) earlyNetwork = null
        if (defaultUnderlayNetwork?.networkHandle == network.networkHandle) defaultUnderlayNetwork = null
        noteRoamHandle(EarlyUnderlay.bindTarget(next))
        scheduleUnderlayAdapt()
    }

    /** Home Wi-Fi, or any Wi-Fi the callback just delivered. Not the other transport. */
    private fun adoptsEarly(network: Network): Boolean {
        val caps = connectivityManager().getNetworkCapabilities(network) ?: return false
        if (!isUsableUnderlay(caps)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        val host = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null)
            ?.let { EndpointSelector.host(it) }
            ?: return false
        if (!EndpointSelector.isRfc1918(host)) return false
        val link = connectivityManager().getLinkProperties(network) ?: return false
        return ipv4Addresses(link).any { EndpointSelector.sameIpv4Slash24(it, host) }
    }

    private fun noteRoamHandle(handle: Long?) {
        if (handle == null || handle == roamWatchHandle) return
        val previous = roamWatchHandle
        roamWatchHandle = handle
        roamArmedAtMs = System.currentTimeMillis()
        if (previous != Long.MIN_VALUE) {
            RoamLog.record("handle $previous -> $handle")
        } else {
            RoamLog.record("handle $handle")
        }
    }

    /**
     * A new bind target drops cached peer results and wakes recovery.
     * Data-plane sockets are re-protected on every observation, including a
     * repeat of the same handle.
     */
    private fun noteDefaultNetworkForRecovery() {
        val next = dataPlaneNetwork()?.networkHandle
        noteRoamHandle(next)
        noteEndpointNetwork(next)
        val reaction = TunnelRebuild.onSystemDefault(
            watch = watchedDefault,
            nextHandle = next,
            controlClientOpen = controlClient != null,
            controlClientHandle = controlClientHandle.takeIf { it != Long.MIN_VALUE },
        )
        if (reaction.dropControlClient) dropUnderlyingControlClient()
        if (reaction.clearCachedPeerOutcome) {
            // Drop a sticky network error (and any other non-alive result) and
            // check again on this default network. Do not keep the old answer.
            clearCachedPeerOutcome()
            ensurePeerCheck(force = true)
        }
        if (reaction.followDataPlane) followSystemDefault()
        watchedDefault = reaction.next
        if (reaction.wakeRecovery) recoveryWakes.trySend(Unit)
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
                // Data-plane sockets are protect()'d and unbound.
                socketBound = false,
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
        val mode = if (userAskedStealth) StealthMode.STEALTH else sessionMode()
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
        val chosenUdp = BackendConfig.select(
            vpnStatePrefs().getString(KEY_UDP_CONFIG, null),
            udpConfig,
            null,
        )
        val liveIncoming = if (stealth) {
            EndpointSelector.replaceEndpoint(chosenUdp, startStealth(requireNotNull(offer)))
        } else {
            chosenUdp
        }
        val liveConfig = BackendConfig.select(
            vpnStatePrefs().getString(KEY_CONFIG, null),
            liveIncoming,
            null,
        )
        if (gen != sessionGeneration || !sessionIntended()) {
            if (stealth) stopStealthTransport()
            return Tunnel.State.DOWN
        }
        val parsed = Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
        // The kept tun is still the lockdown interface. setState runs wgTurnOn
        // before protect(), and the keepalive makes that first handshake enter
        // the tun and start the 5s retransmit. Rebind strips the keepalive,
        // protects, binds to the current default, then kicks. A fresh tun is
        // only the escalation after repeated failed rebuilds, not a reconnect.
        // A held tun whose address is not this peer's is rebuilt first: the
        // handshake does not carry the inner address, and the server drops
        // every data packet from any other source.
        val freshTun = nextTunnelIsFresh.also { nextTunnelIsFresh = false }
        val keptTun = !stealth && tunKeeper != null && !freshTun
        val state = when {
            keptTun -> {
                if (gen != sessionGeneration || !sessionIntended()) return Tunnel.State.DOWN
                if (InterfacePlan.needsRebuild(currentInterfaceAddress, liveConfig)) {
                    if (!openInterface(parsed)) return Tunnel.State.DOWN
                }
                if (rebindLiveTunnel(parsed, gen)) {
                    followSystemDefault()
                    Tunnel.State.UP
                } else if (gen != sessionGeneration || !sessionIntended()) {
                    return Tunnel.State.DOWN
                } else {
                    followSystemDefault()
                    val brought = applyBackendState(Tunnel.State.UP, parsed, gen)
                    followSystemDefault()
                    if (brought == Tunnel.State.UP) kickHandshakeAfterProtect()
                    brought
                }
            }
            else -> {
                followSystemDefault()
                // Stealth rebuilds the tun so this app is excluded from it.
                // A kept direct tun would swallow the loopback relay.
                val brought = applyBackendState(
                    Tunnel.State.UP,
                    parsed,
                    gen,
                    replaceTun = freshTun || stealth,
                )
                followSystemDefault()
                brought
            }
        }
        synchronized(tunnelOpLock) {
            if (gen == sessionGeneration && sessionIntended()) {
                val prefs = vpnStatePrefs()
                prefs.edit()
                    .putString(KEY_CONFIG, liveConfig)
                    .putString(KEY_UDP_CONFIG, chosenUdp)
                    .putBoolean(KEY_RESUME_STEALTH, stealth)
                    .apply()
                lastChosenEndpoint = EndpointSelector.endpointFromConfig(liveConfig)
            }
        }
        activeTransport = if (stealth) "stealth" else "udp"
        if (stealth && state == Tunnel.State.UP) repinStealthCarrier()
        return state
    }

    /**
     * The stealth TCP socket was pinned before this tun existed. establish()
     * installs a new route, and every later segment from that socket is
     * captured unless it is protected and bound again. The app is also
     * excluded from the tun; this pin covers the socket directly.
     */
    private fun repinStealthCarrier() {
        val carrier = stealthTransport?.carrierSocket() ?: return
        val ok = StealthUnderlay.repin(carrier, dataPlaneNetwork()) { socket ->
            protect(socket)
        }
        traceStealth("stealth repin ok=${if (ok) 1 else 0}")
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
            val live = BackendConfig.select(
                vpnStatePrefs().getString(KEY_CONFIG, null),
                EndpointSelector.replaceEndpoint(udpConfig, local),
                null,
            )
            val parsed = Config.parse(ByteArrayInputStream(live.toByteArray(Charsets.UTF_8)))
            turnUserspaceOff()
            if (!openInterface(parsed)) {
                Log.w(TAG, "Stealth tun rebuild failed; restoring the UDP tunnel")
                restoreUdpTunnel(udpConfig, gen)
                return
            }
            if (!rebindWithRetry(parsed, gen, recreateTun = false)) {
                Log.w(TAG, "Stealth rebind did not stick; restoring the UDP tunnel")
                restoreUdpTunnel(udpConfig, gen)
                return
            }
            synchronized(tunnelOpLock) {
                if (gen != sessionGeneration || !sessionIntended()) return
                val prefs = vpnStatePrefs()
                prefs.edit()
                    .putString(KEY_CONFIG, live)
                    .putBoolean(KEY_RESUME_STEALTH, true)
                    .apply()
                lastChosenEndpoint = local
            }
            repinStealthCarrier()
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
        val restored = BackendConfig.select(
            vpnStatePrefs().getString(KEY_CONFIG, null),
            udpConfig,
            null,
        )
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(restored.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return
        turnUserspaceOff()
        // Drop the Stealth exclusion so Direct's kick packet enters the tun again.
        openInterface(parsed)
        if (rebindWithRetry(parsed, gen, recreateTun = false)) {
            synchronized(tunnelOpLock) {
                if (gen == sessionGeneration && sessionIntended()) {
                    vpnStatePrefs().edit()
                        .putString(KEY_CONFIG, restored)
                        .putBoolean(KEY_RESUME_STEALTH, false)
                        .apply()
                    // The failed Stealth switch may already have saved 127.0.0.1.
                    // Later refreshes call protectBackendSockets() with no
                    // endpoint and would leave this UDP tunnel unbound.
                    TunnelRebuild.endpointToSaveAfterUdpRestore(restored)?.let {
                        lastChosenEndpoint = it
                    }
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
            attachUnderlay = { socket ->
                // Resolved on this dial, not when the transport was built.
                StealthUnderlay.attach(socket, dataPlaneNetwork()) { candidate ->
                    protect(candidate)
                }
            },
            trace = { line -> traceStealth(line) },
        )
        transport.start()
        stealthKickLogged = false
        val pinned = pinRelayToLoopback(transport.relaySocket())
        traceStealth("stealth relay lo=${if (pinned) 1 else 0}")
        stealthTransport = transport
        return transport.localEndpoint
    }

    private fun stopStealthTransport() {
        val transport = stealthTransport
        stealthTransport = null
        if (transport == null) return
        // onStartCommand runs on the main thread. Closing the socket there
        // throws and kills the process, so that caller only schedules stop().
        // A roam is already on the IO scope: close this socket before the
        // replacement dial, or the server keeps the old tunnel.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            socketCloseScope.launch(Dispatchers.IO) {
                runCatching { transport.stop() }
            }
            return
        }
        transport.detach()
        socketCloseScope.launch(Dispatchers.IO) {
            runCatching { transport.stop() }
        }
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
        // A successful return clears the line. Another attempt on the same
        // network keeps it, so the retry does not look like a silent wait.
        if (!reconnecting) reconnectNotice = null
        if (reconnecting) {
            if (reconnectEpisodeStartedMs == 0L) {
                reconnectEpisodeStartedMs = System.currentTimeMillis()
                blockedClock = BlockedNetworkNotice.Clock()
            }
        } else {
            reconnectEpisodeStartedMs = 0L
            blockedClock = BlockedNetworkNotice.Clock()
            networkMayBeBlocking = false
        }
        if (!sessionIntended()) return
        val text = if (reconnecting) {
            BlockedNetworkNotice.reconnectingText(networkMayBeBlocking)
        } else {
            lastNotificationText
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        broadcastState(true, null)
    }

    /** Plain Stealth sentence. The stage stays in the diagnostic log. */
    private fun appendStealthUserLine(text: String): String {
        val friendly = StealthDial.userLine(stealthDetail()) ?: return text
        if (text.contains(friendly)) return text
        return "$text · $friendly"
    }

    private fun Intent.putStealthDetail() {
        val detail = stealthDetail()
        noteStealthProgress(detail)
        if (!detail.isNullOrBlank()) putExtra(EXTRA_STEALTH_DETAIL, detail)
    }

    private fun stealthDetail(): String? {
        val transport = stealthTransport ?: return null
        if (activeTransport != "stealth" && activeTransport != "switching") return null
        return StealthDial.detail(transport.stage, transport.lastError)
    }

    private fun noteStealthProgress(detail: String?) {
        if (detail.isNullOrBlank() || detail == lastStealthLogged) return
        lastStealthLogged = detail
        Log.i(TAG, "stealth $detail")
        RoamLog.record("stealth $detail")
        DiagnosticLog.record("stealth $detail")
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

    /** Re-resolve Stealth on the current default network. The stored host is not redialed first. */
    private suspend fun adaptWhileStealth(gen: Long, forceHard: Boolean = false) {
        if (gen != sessionGeneration || !sessionIntended()) return
        // An address is enough. Validation probes can be captured by the tunnel.
        val live = dataPlaneSnap()?.takeIf { ipv4Addresses(it.link).isNotEmpty() }
        if (live == null) {
            waitRecheckJob?.cancel()
            waitRecheckJob = scope.launch {
                delay(EndpointRefresh.HANDSHAKE_POLL_MS)
                if (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
                    scheduleUnderlayAdapt(forceHard = false)
                }
            }
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
            Log.i(TAG, "path-adapt stealth record handle=${live.network.networkHandle}")
            return
        }
        if (RoamRecovery.sameNetworkLeavesTunnelAlone(
                sameHandle = live.identity == lastUnderlayIdentity,
                handshakeHealthy = handshakeOk(),
            )
        ) return
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
        // The Direct recovery loop is the wrong tool here. It demands a
        // handshake newer than the instant it starts, waits 1.2s, then closes
        // the websocket and tries again. A Stealth tunnel that is already
        // carrying bytes fails that test, so the client drops it every few
        // seconds and the UI stays on Reconnecting.
        val transport = stealthTransport
        val stats = readBackendStats()
        val action = synchronized(stealthSwitchLock) {
            val step = StealthRoam.next(
                stealthRoamMemory,
                StealthRoam.Observation(
                    liveHandle = live.network.networkHandle,
                    boundHandle = transport?.boundNetworkHandle(),
                    open = transport?.sessionOpen() == true,
                    dialing = transport?.dialInProgress() == true,
                    handshakeMs = stats.third,
                    rx = stats.first,
                    tx = stats.second,
                    nowMs = System.currentTimeMillis(),
                    force = forceHard,
                ),
            )
            stealthRoamMemory = step.first
            step.second
        }
        when (action) {
            StealthRoam.Action.HEALTHY -> {
                commitUnderlay(live)
                if (isReconnecting) {
                    traceStealth("stealth roam up=1")
                    publishReconnecting(false)
                }
                reconnectNotice = null
            }
            StealthRoam.Action.WAIT -> {
                val liveTransport = stealthTransport
                if (isReconnecting ||
                    liveTransport?.dialInProgress() == true ||
                    liveTransport?.sessionOpen() != true
                ) {
                    scheduleStealthRecheck(gen)
                }
            }
            StealthRoam.Action.DIAL -> {
                traceStealth("stealth roam dial=1")
                reconnectNotice = null
                publishReconnecting(true)
                dialStealthForRoam(gen, live)
                scheduleStealthRecheck(gen)
            }
        }
    }

    /** Look again soon. Repeat observations do not open another websocket. */
    private fun scheduleStealthRecheck(gen: Long) {
        waitRecheckJob?.cancel()
        waitRecheckJob = scope.launch {
            delay(EndpointRefresh.HANDSHAKE_POLL_MS)
            if (gen == sessionGeneration && sessionIntended() && disconnectJob?.isActive != true) {
                scheduleUnderlayAdapt(forceHard = false)
            }
        }
    }

    /**
     * One websocket on [live]. The same host keeps the relay port, so
     * WireGuard is left running and its counters are not reset. A different
     * host replaces the transport once and rebinds userspace once.
     */
    private fun dialStealthForRoam(gen: Long, live: UnderlaySnap) {
        if (!sessionStillActive(gen)) return
        val chosen = offerForUnderlay(live, advertised = null) ?: return
        val stealthEp = EndpointRefresh.freshEndpoint(chosen.offer.stealthEndpoint) ?: return
        val parsedEp = WstunnelProtocol.parseEndpoint(stealthEp) ?: return
        val prefix = chosen.offer.stealthPrefix
            ?: vpnStatePrefs().getString(KEY_STEALTH_PREFIX, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        val stealthOffer = StealthOffer(endpoint = stealthEp, pathPrefix = prefix, available = true)
        if (!stealthOffer.usable()) return
        val transport = stealthTransport
        if (transport != null && transport.targets(parsedEp.host, parsedEp.port, prefix)) {
            followSystemDefault()
            transport.reconnect()
            return
        }
        val local = try {
            startStealth(stealthOffer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stealth roam dial failed: ${e.javaClass.simpleName}")
            return
        }
        if (!sessionStillActive(gen)) return
        val template = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
            ?: vpnStatePrefs().getString(KEY_CONFIG, null)
            ?: return
        val liveConfig = BackendConfig.select(
            vpnStatePrefs().getString(KEY_CONFIG, null),
            EndpointSelector.replaceEndpoint(template, local),
            chosen.offer.serverPublicKey,
        )
        val udpConfig = BackendConfig.select(
            vpnStatePrefs().getString(KEY_UDP_CONFIG, null),
            EndpointSelector.replaceEndpoint(template, chosen.offer.serverEndpoint),
            chosen.offer.serverPublicKey,
        )
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(liveConfig.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return
        followSystemDefault()
        if (!rebindWithRetry(parsed, gen, recreateTun = false)) return
        synchronized(tunnelOpLock) {
            if (!sessionStillActive(gen)) return
            vpnStatePrefs().edit()
                .putString(KEY_CONFIG, liveConfig)
                .putString(KEY_UDP_CONFIG, udpConfig)
                .putString(KEY_STEALTH_ENDPOINT, stealthEp)
                .putString(KEY_STEALTH_PREFIX, prefix)
                .apply()
            lastChosenEndpoint = local
        }
        // Userspace just restarted, so the counters the dial observed are gone.
        synchronized(stealthSwitchLock) {
            stealthRoamMemory = stealthRoamMemory.copy(baselineRx = 0L, baselineTx = 0L)
        }
        publishTransport("stealth")
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

    private fun noteHandshake(armedAt: Long): Boolean {
        if (!EndpointRefresh.handshakeRestoresConnected(latestHandshakeMs(), armedAt)) return false
        if (isReconnecting) publishReconnecting(false)
        return true
    }

    /** Returns as soon as stats show a handshake newer than [sinceMs]. */
    private suspend fun awaitFreshHandshake(gen: Long, sinceMs: Long, handle: Long?): Boolean {
        val deadline = System.currentTimeMillis() + EndpointRefresh.HANDSHAKE_WAIT_MS
        while (sessionStillActive(gen)) {
            if (handle != null && !roamTargetStill(handle)) return false
            if (noteHandshake(sinceMs)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(EndpointRefresh.HANDSHAKE_POLL_MS)
        }
        return false
    }

    private fun currentUdpEndpoint(): String? {
        val config = vpnStatePrefs().getString(KEY_UDP_CONFIG, null)
            ?: vpnStatePrefs().getString(KEY_CONFIG, null)
            ?: return null
        return EndpointSelector.endpointFromConfig(config)
    }

    private suspend fun waitForHandshake(
        gen: Long,
        afterMs: Long,
        timeoutMs: Long,
        handle: Long? = null,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (gen != sessionGeneration || !sessionIntended()) return false
            if (handle != null && !roamTargetStill(handle)) return false
            if (latestHandshakeMs() > afterMs) return true
            delay(400)
        }
        if (handle != null && !roamTargetStill(handle)) return false
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

    /**
     * Bring userspace up on a tun we establish here. [GoBackend.setState]
     * calls [android.net.VpnService.prepare] on the application context, and
     * on this device that consent dialog covers a black window. Consent stays
     * on the activity, after the home UI has drawn.
     */
    private fun applyBackendState(
        state: Tunnel.State,
        config: Config?,
        gen: Long,
        replaceTun: Boolean = false,
    ): Tunnel.State {
        if (state != Tunnel.State.UP || config == null) return Tunnel.State.DOWN
        if (replaceTun || tunKeeper == null) {
            if (replaceTun) turnUserspaceOff()
            if (!openInterface(config)) return Tunnel.State.DOWN
        }
        vpnConsentRequired = false
        return if (rebindLiveTunnel(config, gen)) Tunnel.State.UP else Tunnel.State.DOWN
    }

    private fun openInterface(parsed: Config): Boolean {
        vpnConsentRequired = false
        return try {
            val builder = getBuilder()
            builder.setSession(TUNNEL_NAME)
            val iface = parsed.getInterface()
            for (app in iface.excludedApplications) {
                builder.addDisallowedApplication(app)
            }
            // Stealth's WireGuard endpoint is 127.0.0.1, and the TLS socket
            // lives in this process. While this app is inside the tun, the
            // relay's reply and every TCP segment after the tun comes up are
            // captured. Other apps stay on the tun.
            val loopback = parsed.peers.any { peer ->
                TunnelRebuild.loopbackRelay(peer.endpoint.orElse(null)?.toString())
            }
            if (loopback &&
                iface.includedApplications.isEmpty() &&
                packageName !in iface.excludedApplications
            ) {
                builder.addDisallowedApplication(packageName)
                traceStealth("stealth iface self=1")
            } else if (loopback) {
                traceStealth("stealth iface self=0")
            }
            for (app in iface.includedApplications) {
                builder.addAllowedApplication(app)
            }
            for (addr in iface.addresses) {
                builder.addAddress(addr.address, addr.mask)
            }
            for (dns in iface.dnsServers) {
                val host = dns.hostAddress ?: continue
                builder.addDnsServer(host)
            }
            for (domain in iface.dnsSearchDomains) {
                builder.addSearchDomain(domain)
            }
            var hasDefaultRoute = false
            for (peer in parsed.peers) {
                for (net in peer.allowedIps) {
                    if (net.mask == 0) hasDefaultRoute = true
                    builder.addRoute(net.address, net.mask)
                }
            }
            if (!hasDefaultRoute || parsed.peers.size != 1) {
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowFamily(OsConstants.AF_INET6)
            }
            builder.setMtu(iface.mtu.orElse(1280))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            setUnderlyingNetworks(null)
            builder.setBlocking(true)
            val established = builder.establish() ?: return false
            established.close()
            val host = iface.addresses
                .firstOrNull { it.address is Inet4Address }
                ?.address?.hostAddress
                ?: iface.addresses.firstOrNull()?.address?.hostAddress
            if (tunKeeper != null && !host.isNullOrBlank()) currentInterfaceAddress = host
            tunKeeper != null
        } catch (e: SecurityException) {
            vpnConsentRequired = true
            Log.w(TAG, "tun establish: SecurityException")
            false
        } catch (e: Exception) {
            Log.w(TAG, "tun establish: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Replace a signed-out lockdown tun with one that disallows this app.
     * Other apps stay on the tun. WireGuard stays off, so their packets are
     * discarded. A failed establish leaves the previous tun in place.
     */
    private fun openLockdownHold(expectedGen: Long, alreadyLockdown: Boolean) {
        synchronized(tunnelOpLock) {
            if (expectedGen != sessionGeneration || sessionIntended()) return
            if (lockdownHoldActive && tunKeeper != null) return
            val lockdown = alreadyLockdown || runCatching {
                isLockdownEnabled() || VpnKillSwitch.isLockdownEnabled(
                    this,
                    vpnPrepared = true,
                    allowPrepare = false,
                )
            }.getOrDefault(false)
            if (!lockdown) return
            if (!establishLockdownHoldLocked()) return
            if (expectedGen != sessionGeneration || sessionIntended()) return
            holdInterface = true
            lockdownHoldActive = true
        }
    }

    private fun establishLockdownHoldLocked(): Boolean {
        return try {
            val builder = getBuilder()
            builder.setSession(TUNNEL_NAME)
            val own = packageName
            builder.addDisallowedApplication(own)
            for (app in LockdownHold.disallowedPackages(own, VpnSettings.bypassApps(this))) {
                if (app == own) continue
                runCatching { builder.addDisallowedApplication(app) }
            }
            builder.addAddress(LockdownHold.IPV4_ADDRESS, LockdownHold.IPV4_PREFIX)
            builder.addRoute(LockdownHold.IPV4_ROUTE, 0)
            builder.addDnsServer(LockdownHold.DNS)
            builder.addAddress(LockdownHold.IPV6_ADDRESS, LockdownHold.IPV6_PREFIX)
            builder.addRoute(LockdownHold.IPV6_ROUTE, 0)
            builder.setMtu(LockdownHold.MTU)
            builder.setBlocking(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            setUnderlyingNetworks(null)
            val established = builder.establish() ?: return false
            established.close()
            if (tunKeeper != null) currentInterfaceAddress = LockdownHold.IPV4_ADDRESS
            tunKeeper != null
        } catch (e: Exception) {
            Log.w(TAG, "lockdown hold: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun closeTunKeeper() {
        swapTunKeeper(null)
    }

    /**
     * Drop the tunnel once. A later connect bumps [sessionGeneration] under the
     * same lock, so this cannot turn that new tunnel down. The service stays
     * running so the activity is not destroyed with it.
     */
    private fun releaseTunnelOnce(expectedGen: Long, keepInterface: Boolean = false) {
        val replaceWithAppExemptHold = synchronized(tunnelOpLock) {
            if (expectedGen != sessionGeneration || tunnelReleased) return
            tunnelReleased = true
            // GoBackend.setState(DOWN) calls stopSelf(). That destroys this
            // service and, with Always-on, takes the activity off screen.
            runCatching { turnUserspaceOffLocked() }
            if (keepInterface) {
                holdInterface = true
                lockdownHoldActive = false
                true
            } else {
                holdInterface = false
                lockdownHoldActive = false
                currentInterfaceAddress = null
                val old = tunKeeper
                tunKeeper = null
                runCatching { old?.close() }
                runCatching { setUnderlyingNetworks(null) }
                false
            }
        }
        if (replaceWithAppExemptHold) openLockdownHold(expectedGen, alreadyLockdown = true)
    }

    /**
     * wgTurnOff and clear the backend handle. Does not call setState, so the
     * service and the activity stay up.
     */
    private fun turnUserspaceOff() {
        synchronized(tunnelOpLock) {
            turnUserspaceOffLocked()
        }
    }

    private fun turnUserspaceOffLocked() {
        natKeepaliveJob?.cancel()
        handshakeBurstJob?.cancel()
        runCatching { turnOffCurrentHandleLocked() }
    }

    /**
     * Publish "no tunnel" before the native call. A second teardown then
     * sees a negative handle and does not free it again.
     */
    private fun detachUserspaceHandleLocked(): Int {
        val handleField = goField("currentTunnelHandle")
        val handle = handleField.getInt(backend)
        if (handle >= 0) {
            handleField.setInt(backend, -1)
        }
        goField("currentTunnel").set(backend, null)
        goField("currentConfig").set(backend, null)
        return handle
    }

    private fun turnOffCurrentHandleLocked() {
        val handle = detachUserspaceHandleLocked()
        if (handle < 0) return
        DiagnosticLog.record("userspace turn-off")
        goNative("wgTurnOff", Integer.TYPE).invoke(null, handle)
    }

    private fun swapTunKeeper(newKeeper: ParcelFileDescriptor?) {
        synchronized(tunnelOpLock) {
            val old = tunKeeper
            tunKeeper = newKeeper
            runCatching { old?.close() }
        }
    }

    private fun installUserspace(parsed: Config, gen: Long, rebuildTun: Boolean): Boolean =
        if (rebuildTun) rebuildKeepingKillSwitch(parsed, gen) else rebindWithRetry(parsed, gen)

    /**
     * New protected sockets on the tun that is already up. Does not call
     * setState, so the library does not run its consent check or stop the
     * service. A failed socket swap calls this once.
     */
    private fun rebuildKeepingKillSwitch(parsed: Config, gen: Long): Boolean {
        return try {
            rebindLiveTunnel(parsed, gen)
        } catch (e: Exception) {
            Log.w(TAG, "path-adapt rebuild failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Restart userspace WireGuard on the existing tun fd.
     * A new interface would drop the kill switch, so a missing keeper leaves
     * whatever is already up and returns false. [recreateTun] does not change that.
     * @return false if the user disconnected or the tun keeper is missing.
     */
    private fun rebindLiveTunnel(
        parsed: Config,
        gen: Long,
        @Suppress("UNUSED_PARAMETER") recreateTun: Boolean = false,
    ): Boolean {
        synchronized(tunnelOpLock) {
            if (gen != sessionGeneration || !sessionIntended()) return false
            val keeper = tunKeeper
            if (keeper == null) {
                Log.w(TAG, "path-adapt tun keeper missing; leaving the interface up")
                return false
            }
            // New UDP sockets must not inherit a pinned underlying network.
            // protect() on HyperOS stamps whatever is set right now. Clear it,
            // then protect and bindSocket to the current system default only.
            releaseUnderlyingPin()
            try {
                turnOffCurrentHandleLocked()
                if (gen != sessionGeneration || !sessionIntended()) return false
                val handleField = goField("currentTunnelHandle")
                val tunnelField = goField("currentTunnel")
                val configField = goField("currentConfig")
                val goConfig = userspaceConfigOrThrow(parsed)
                val dup = keeper.dup()
                val fd = dup.detachFd()
                userspaceInstalling = true
                try {
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
                    val endpoint = parsed.peers.firstOrNull()?.endpoint?.orElse(null)?.toString()
                    protectBackendSockets(endpoint)
                } finally {
                    userspaceInstalling = false
                }
                kickHandshakeAfterProtect()
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
        return HandshakeKick.withoutKeepalive(parsed.toWgUserspaceString())
    }

    /** Tun is up. The handshake mark waits for a handshake newer than the tap. */
    private fun noteTunnelReady(gen: Long) {
        ConnectTiming.mark("tun")
        scope.launch {
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline &&
                gen == sessionGeneration &&
                sessionIntended()
            ) {
                if (ConnectTiming.handshakeIsNew(latestHandshakeMs())) {
                    ConnectTiming.mark("handshake")
                    break
                }
                delay(200)
            }
            ConnectTiming.finish()
        }
    }

    /**
     * One unprotected datagram enters the tun. Userspace then sends the first
     * initiation on the socket protect() already excluded from the VPN.
     * Calling this before protect() would start the 5s retransmit hold.
     */
    private fun kickHandshakeAfterProtect() {
        RoamLog.record("handshake kick")
        kickHandshakePacket()
        val gen = sessionGeneration
        handshakeBurstJob?.cancel()
        handshakeBurstJob = scope.launch {
            var waited = 0L
            for (at in HandshakeKick.retransmitDelaysMs()) {
                delay(at - waited)
                waited = at
                if (!sessionStillActive(gen) || userspaceInstalling) return@launch
                kickHandshakePacket()
            }
        }
        armNatKeepalive()
    }

    private fun kickHandshakePacket() {
        if (userspaceInstalling) return
        if (!sessionIntended() || disconnectJob?.isActive == true) return
        if (stealthTransport != null) {
            val wrote = writeTunTrigger()
            if (!stealthKickLogged) {
                stealthKickLogged = true
                traceStealth("stealth kick tun=${if (wrote) 1 else 0}")
            }
            if (wrote) return
        }
        Thread {
            runCatching {
                DatagramSocket().use { socket ->
                    val payload = ByteArray(1)
                    val discard = InetAddress.getByAddress(byteArrayOf(240.toByte(), 0, 0, 1))
                    socket.send(DatagramPacket(payload, payload.size, discard, 9))
                }
            }
        }.apply {
            isDaemon = true
            name = "wg-handshake-kick"
            start()
        }
    }

    /** Write one datagram onto the tun so WireGuard handshakes without this app being routed. */
    private fun writeTunTrigger(): Boolean {
        val keeper = tunKeeper ?: return false
        val source = currentInterfaceAddress ?: return false
        val packet = HandshakeKick.tunTrigger(source) ?: return false
        return runCatching {
            Os.write(keeper.fileDescriptor, packet, 0, packet.size)
        }.isSuccess
    }

    private fun armNatKeepalive() {
        if (natKeepaliveJob?.isActive == true) return
        val gen = sessionGeneration
        natKeepaliveJob = scope.launch {
            while (isActive && sessionStillActive(gen)) {
                delay(HandshakeKick.KEEPALIVE_EVERY_MS)
                if (userspaceInstalling || !sessionStillActive(gen)) continue
                kickHandshakePacket()
            }
        }
    }

    private fun goField(name: String) =
        GoBackend::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun goNative(name: String, vararg types: Class<*>) =
        GoBackend::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

    /**
     * Keep the WireGuard UDP sockets on the callback network.
     *
     * Never `setUnderlyingNetworks(arrayOf(...))`. protect() on this device
     * stamps a stale VPN netId, so each fd is protect()'d and then bindSocket'd
     * to [dataPlaneNetwork]: the network from onAvailable or onCapabilities
     * even before it is the validated default, and again once it is. A network
     * the phone already left is not a bind target. There is no pin-success
     * gate: every call does this again.
     */
    private fun followSystemDefault() {
        releaseUnderlyingPin()
        protectBackendSockets()
    }

    private fun releaseUnderlyingPin() {
        runCatching { setUnderlyingNetworks(null) }
    }

    /**
     * protect(), then bindSocket to the current system default. A missing fd
     * is skipped. The lock keeps this off an fd that [rebindLiveTunnel] is
     * replacing. [intendedEndpoint] is compared to the running userspace
     * config and is never written to a log.
     */
    private fun protectBackendSockets(intendedEndpoint: String? = null) {
        val network = dataPlaneNetwork()
        val expected = network?.networkHandle
        synchronized(tunnelOpLock) {
            val status = runCatching {
                val handle = goField("currentTunnelHandle").getInt(backend)
                if (handle < 0) {
                    return@runCatching DataPlaneStatus()
                }
                val v4 = socketFd("wgGetSocketV4", handle)
                val v6 = socketFd("wgGetSocketV6", handle)
                val endpoint = intendedEndpoint ?: lastChosenEndpoint
                val loopback = stealthTransport != null || TunnelRebuild.loopbackRelay(endpoint)
                val previous = dataPlaneStatus.config
                val config = if (intendedEndpoint.isNullOrBlank()) {
                    previous
                } else {
                    DataPlaneHealth.configCheck(readUserspaceConfig(handle), intendedEndpoint)
                }
                if (loopback) {
                    // Leave the Go sockets as wireguard-go bound them (0.0.0.0
                    // and [::], same port). Do not protect, bindSocket, set
                    // UDP_GRO, or SO_BINDTODEVICE: those calls either pin the
                    // fd to the underlay or reject the reply. Only a queued
                    // socket error is cleared, so the next send is not failed
                    // by a stale errno.
                    val tuned4 = prepareLoopbackSocket(v4)
                    val tuned6 = prepareLoopbackSocket(v6)
                    if (!dataPlaneStatus.loopbackRelay) {
                        val soerr = listOf(tuned4, tuned6).firstOrNull { it >= 0 } ?: -1
                        traceStealth(
                            "stealth dataplane loopback=1 v4=${if (v4 >= 0) 1 else 0} " +
                                "v6=${if (v6 >= 0) 1 else 0} soerr=$soerr tune=0",
                        )
                    }
                    return@runCatching DataPlaneStatus(
                        v4Present = v4 >= 0,
                        v6Present = v6 >= 0,
                        loopbackRelay = true,
                        config = config,
                    )
                }
                val mayBind = network != null &&
                    TunnelRebuild.dataPlaneMayBind(expected, network.networkHandle)
                val boundV4 = protectAndBind(v4, if (mayBind) network else null)
                val boundV6 = protectAndBind(v6, if (mayBind) network else null)
                val observed = readBoundHandle(v4) ?: readBoundHandle(v6)
                val bound = observed ?: boundV4.handle ?: boundV6.handle
                DataPlaneStatus(
                    v4Present = v4 >= 0,
                    v6Present = v6 >= 0,
                    protectV4 = boundV4.protectedOk,
                    protectV6 = boundV6.protectedOk,
                    bindAttempted = mayBind && v4 >= 0,
                    bindV4 = boundV4.boundOk,
                    bindV6 = boundV6.boundOk,
                    boundHandle = bound,
                    config = config,
                )
            }.getOrElse { error ->
                Log.w(TAG, "Could not reprotect WireGuard sockets: ${error.javaClass.simpleName}")
                DataPlaneStatus()
            }
            dataPlaneStatus = status
        }
    }

    private data class BoundSocket(
        val protectedOk: Boolean,
        val boundOk: Boolean,
        val handle: Long?,
    )

    private fun socketFd(nativeName: String, handle: Int): Int =
        runCatching { goNative(nativeName, Integer.TYPE).invoke(null, handle) as Int }.getOrDefault(-1)

    /**
     * Read and clear SO_ERROR. wireguard-go on Android does not enable
     * UDP_GRO (that code is Linux-only). SO_BINDTODEVICE on this fd can
     * succeed and then drop the handshake response, so it is not set.
     * Returns the pending errno, or -1 when the fd or the read is missing.
     * Direct mode never calls this.
     */
    private fun prepareLoopbackSocket(fd: Int): Int {
        if (fd < 0) return -1
        val raw = rawFileDescriptor(fd)
        val pending = runCatching {
            Os::class.java.getMethod(
                "getsockoptInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(null, raw, OsConstants.SOL_SOCKET, OsConstants.SO_ERROR) as Int
        }.getOrDefault(-1)
        return pending
    }

    /**
     * Pin the relay socket to lo. The reply has to leave the loopback device.
     * A failure is logged and the tun exclusion still keeps the packet local.
     * Closing the returned dup does not close the channel.
     */
    private fun pinRelayToLoopback(socket: DatagramSocket): Boolean {
        val pfd = runCatching { ParcelFileDescriptor.fromDatagramSocket(socket) }.getOrNull()
            ?: return false
        return try {
            val name = ByteArray(16)
            "lo".toByteArray(Charsets.US_ASCII).copyInto(name)
            Os::class.java.getMethod(
                "setsockoptBytes",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            ).invoke(null, pfd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, name)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun traceStealth(line: String) {
        Log.i(TAG, line)
        RoamLog.record(line)
        DiagnosticLog.record(line)
    }

    private fun protectAndBind(fd: Int, network: Network?): BoundSocket {
        if (fd < 0) return BoundSocket(protectedOk = false, boundOk = false, handle = null)
        val protectedOk = runCatching { protect(fd) }.getOrDefault(false)
        if (!protectedOk || network == null) {
            return BoundSocket(protectedOk, boundOk = false, handle = null)
        }
        val boundOk = runCatching { network.bindSocket(rawFileDescriptor(fd)) }.isSuccess
        return BoundSocket(
            protectedOk = protectedOk,
            boundOk = boundOk,
            handle = if (boundOk) network.networkHandle else null,
        )
    }

    private fun rawFileDescriptor(fd: Int): FileDescriptor {
        val descriptor = FileDescriptor()
        val field = FileDescriptor::class.java.declaredFields.firstOrNull {
            it.type == Int::class.javaPrimitiveType && (it.name == "descriptor" || it.name == "fd")
        } ?: throw NoSuchFieldException("descriptor")
        field.isAccessible = true
        field.setInt(descriptor, fd)
        return descriptor
    }

    /** Platform read-back of the network this fd is bound to. Null if unknown. */
    private fun readBoundHandle(fd: Int): Long? {
        if (fd < 0) return null
        return runCatching {
            val cm = connectivityManager()
            val raw = rawFileDescriptor(fd)
            val method = cm.javaClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.size == 1 &&
                    FileDescriptor::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                    (candidate.name == "getBoundNetworkForSocket" || candidate.name == "getBoundNetwork")
            } ?: return null
            val network = method.invoke(cm, raw) as? Network
            network?.networkHandle ?: 0L
        }.getOrNull()
    }

    private fun readUserspaceConfig(handle: Int): String? =
        runCatching { goNative("wgGetConfig", Integer.TYPE).invoke(null, handle) as? String }.getOrNull()

    private fun recordHandshakeFailure(expectedHandle: Long?) {
        val status = dataPlaneStatus
        val stats = runCatching { backend.getStatistics(this) }.getOrNull()
        val tx = stats?.totalTx() ?: 0L
        val rx = stats?.totalRx() ?: 0L
        val err = DataPlaneHealth.failure(
            v4Present = status.v4Present,
            protectV4 = status.protectV4,
            bindAttempted = status.bindAttempted,
            bindV4 = status.bindV4,
            boundHandle = status.boundHandle,
            expectedHandle = expectedHandle,
            tx = tx,
            rx = rx,
            config = status.config,
            loopbackRelay = status.loopbackRelay,
        )
        val bound = status.boundHandle ?: 0L
        val line = "handshake fail err=$err protect4=${if (status.protectV4) 1 else 0} " +
            "protect6=${if (status.protectV6) 1 else 0} bound=$bound tx=$tx rx=$rx"
        RoamLog.record(line)
        DiagnosticLog.record(line)
    }

    private data class UnderlaySnap(
        val network: Network,
        val link: LinkProperties,
        val caps: NetworkCapabilities,
    ) {
        /** Network handle only. Link-property and capability updates do not change it. */
        val identity: String
            get() = network.networkHandle.toString()
    }

    private fun snapshotUnderlay(network: Network?): UnderlaySnap? {
        if (network == null) return null
        val caps = connectivityManager().getNetworkCapabilities(network) ?: return null
        if (!isUsableUnderlay(caps)) return null
        val link = connectivityManager().getLinkProperties(network) ?: return null
        return UnderlaySnap(network, link, caps)
    }

    /**
     * The phone's system-default underlay. On Android 12+ that is the
     * best-matching NOT_VPN network, and a snapshot miss does not forget it.
     * With no best-match, the only addressed network is used, or the single
     * network that shares a /24 with the known LAN endpoint. Two unrelated
     * transports are not ranked.
     */
    @Suppress("DEPRECATION")
    private fun systemDefaultNetwork(): Network? {
        val pinned = bestMatchingNetwork
        if (pinned != null && snapshotUnderlay(pinned) != null) return pinned
        val addressed = connectivityManager().allNetworks.mapNotNull { network ->
            val snap = addressedUnderlay(network) ?: return@mapNotNull null
            DefaultUnderlay.Candidate(
                id = network.networkHandle.toString(),
                ipv4s = ipv4Addresses(snap.link),
            ) to network
        }
        val chosen = DefaultUnderlay.select(
            bestMatchId = null,
            candidates = addressed.map { it.first },
            lanEndpoint = vpnStatePrefs().getString(KEY_ENDPOINT_LAN, null),
        ) ?: return null
        return addressed.firstOrNull { it.first.id == chosen }?.second
    }

    private fun addressedUnderlay(network: Network): UnderlaySnap? {
        val snap = snapshotUnderlay(network) ?: return null
        if (ipv4Addresses(snap.link).isEmpty()) return null
        return snap
    }

    private fun isNonVpnInternet(network: Network): Boolean {
        val caps = connectivityManager().getNetworkCapabilities(network) ?: return false
        return isUsableUnderlay(caps)
    }

    /** True when [handle] still has an address. A lost Wi-Fi handle is not alive. */
    private fun bindTargetAddressed(handle: Long?): Boolean {
        if (handle == null) return false
        val early = earlyNetwork
        if (early != null && early.networkHandle == handle && addressedUnderlay(early) != null) return true
        val best = bestMatchingNetwork
        if (best != null && best.networkHandle == handle && addressedUnderlay(best) != null) return true
        val def = systemDefaultNetwork()
        return def?.networkHandle == handle && addressedUnderlay(def) != null
    }

    /** Unvalidated is still this same network — never the other transport. */
    private fun systemDefaultSnap(): UnderlaySnap? = snapshotUnderlay(systemDefaultNetwork())

    /**
     * Socket bind target. The callback network while it is waiting to become
     * the validated default, otherwise the system default. Never a third network.
     */
    private fun dataPlaneNetwork(): Network? {
        val target = EarlyUnderlay.bindTarget(earlyUnderlay)
        val early = earlyNetwork
        if (target != null && early != null && early.networkHandle == target &&
            addressedUnderlay(early) != null
        ) {
            return early
        }
        // Cellular is not an early Wi-Fi adopt. The callback still has to be
        // the bind target, or protect() runs without bindSocket and the data
        // packets loop back into the tun.
        val adopted = defaultUnderlayNetwork
        if (target != null && adopted != null && adopted.networkHandle == target &&
            addressedUnderlay(adopted) != null
        ) {
            return adopted
        }
        val def = systemDefaultNetwork()
        if (target == null || def?.networkHandle == target) return def
        return def
    }

    private fun dataPlaneSnap(): UnderlaySnap? = snapshotUnderlay(dataPlaneNetwork())

    /** True while [handle] is the callback target or the validated default. */
    private fun roamTargetStill(handle: Long): Boolean = EarlyUnderlay.attemptStillCurrent(
        attempt = handle,
        bindTarget = dataPlaneNetwork()?.networkHandle,
        systemDefault = systemDefaultNetwork()?.networkHandle,
    )

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
                retireControlClient(created)
                return null
            }
            val previous = controlClient
            controlClient = created
            controlClientHandle = handle
            if (previous != null && previous !== created) {
                retireControlClient(previous)
            }
            return created
        }
    }

    /**
     * Drop the client reference here. [OkHttpClient.connectionPool] eviction
     * closes TLS sockets, and that write throws NetworkOnMainThreadException
     * on the thread that calls it. onStartCommand, onRevoke, onDestroy, and
     * the network callbacks are that thread.
     */
    private fun retireControlClient(client: OkHttpClient?) {
        if (client == null) return
        socketCloseScope.launch(Dispatchers.IO) {
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.connectionPool.evictAll() }
        }
    }

    private fun dropUnderlyingControlClient() {
        val previous = synchronized(controlClientLock) {
            val current = controlClient
            controlClient = null
            controlClientHandle = Long.MIN_VALUE
            current
        }
        retireControlClient(previous)
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

    /**
     * The shared client still holds the socket from the previous API call.
     * Under lockdown that socket never answers. Closing it is network I/O,
     * so this runs on [socketCloseScope], not on the caller.
     */
    private fun retireSharedClient() {
        socketCloseScope.launch(Dispatchers.IO) {
            // Idle sockets only. cancelAll on this dispatcher would also abort
            // a roam peer read that shares it, and the delete's own coroutine
            // already cancels the in-flight call.
            val shared = ApiClient.sharedClient()
            runCatching { shared.connectionPool.evictAll() }
        }
    }

    /** Jobs that can be inside a native tunnel call. Snapshot before cancel. */
    private fun tunnelJobs(): List<Job> = listOfNotNull(
        adaptJob,
        healthJob,
        waitRecheckJob,
        restoreJob,
        validationJob,
        stealthReconnectJob,
        peerCheckJob,
        reprovisionJob,
        natKeepaliveJob,
        statsJob,
        transitionJob,
        disconnectJob,
    )

    private fun cancelTunnelJobs() {
        adaptJob?.cancel()
        healthJob?.cancel()
        waitRecheckJob?.cancel()
        restoreJob?.cancel()
        validationJob?.cancel()
        stealthReconnectJob?.cancel()
        natKeepaliveJob?.cancel()
        handshakeBurstJob?.cancel()
        statsJob?.cancel()
        shieldJob?.cancel()
        cancelPeerRecovery()
        transitionJob?.cancel()
        disconnectJob?.cancel()
    }

    /**
     * Wait until roam, rebuild, connect, and the previous disconnect have
     * left the native tunnel calls. Does not join the caller, and does not
     * take [tunnelOpLock].
     */
    private suspend fun joinBeforeTurnOff(jobs: List<Job>) {
        val self = coroutineContext[Job]
        for (job in jobs.distinct()) {
            if (job === self) continue
            job.join()
        }
    }

    private fun cancelPeerRecovery() {
        peerCheckJob?.cancel()
        peerCheckJob = null
        synchronized(reprovisionLock) {
            ReprovisionFlight.cancelCurrent()
            reprovisionJob?.cancel()
            reprovisionJob = null
            upgradeGateJob?.cancel()
            upgradeGateJob = null
            pendingFreshTunnel = false
            pendingReprovision = false
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
                        val advertisedKey = runCatching {
                            ApiClient.parse<PeerEndpointView>(res)?.server?.publicKey
                        }.getOrNull()
                        res.close()
                        if (stillCurrent()) {
                            finish(outcome, code)
                            applyAdvertisedServerKey(gen, advertisedKey)
                        }
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
                                val retryKey = if (retryOutcome == PeerCheckOutcome.Outcome.PEER_ALIVE) {
                                    runCatching {
                                        ApiClient.parse<PeerEndpointView>(retry)?.server?.publicKey
                                    }.getOrNull()
                                } else {
                                    null
                                }
                                retry.close()
                                if (!stillCurrent()) return@launch
                                finish(retryOutcome, retryCode)
                                if (retryOutcome == PeerCheckOutcome.Outcome.PEER_ALIVE) {
                                    applyAdvertisedServerKey(gen, retryKey)
                                }
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
                // error stays CONTINUE and does not create a peer. The hold
                // and the reprovision window do not drop this one create.
                if (serial == peerCheckSerial && !controlSignedOut) {
                    requestReprovision(gen, peerGone = true)
                }
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
        stopShieldPolling()
        isReconnecting = false
        synchronized(reprovisionLock) {
            reprovisionJob?.cancel()
            reprovisionJob = null
            upgradeGateJob?.cancel()
            upgradeGateJob = null
            pendingFreshTunnel = false
            pendingReprovision = false
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

    private fun requestReprovision(gen: Long, peerGone: Boolean = false) {
        if (controlSignedOut) return
        val upgradeHeld = stealthUpgradeInProgress()
        if (!peerGone && upgradeHeld) {
            Log.i(TAG, "reprovision held for stealth upgrade")
            scheduleAfterUpgrade(gen, fresh = false)
            return
        }
        val now = System.currentTimeMillis()
        synchronized(reprovisionLock) {
            val epoch = ReprovisionFlight.tryClaim(
                persistedAtMs = VpnSettings.reprovisionInProgressAt(this),
                nowMs = now,
                replaceWaiting = true,
                peerGone = peerGone,
                upgradeHeld = upgradeHeld,
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

    /**
     * The upgrade gate dropped this peer recreation. Run it when the gate
     * opens instead of waiting for the next peer check.
     */
    private fun scheduleAfterUpgrade(gen: Long, fresh: Boolean) {
        synchronized(reprovisionLock) {
            if (fresh) pendingFreshTunnel = true else pendingReprovision = true
            if (upgradeGateJob?.isActive == true) return
            upgradeGateJob = scope.launch {
                while (isActive && sessionStillActive(gen) && !controlSignedOut) {
                    while (isActive && sessionStillActive(gen) && !controlSignedOut &&
                        stealthUpgradeInProgress()
                    ) {
                        delay(250)
                    }
                    if (!sessionStillActive(gen) || controlSignedOut) return@launch
                    val (doFresh, doReprovision) = synchronized(reprovisionLock) {
                        if (stealthUpgradeInProgress()) {
                            pendingFreshTunnel to pendingReprovision
                        } else {
                            val wanted = pendingFreshTunnel to pendingReprovision
                            pendingFreshTunnel = false
                            pendingReprovision = false
                            wanted
                        }
                    }
                    if (stealthUpgradeInProgress()) continue
                    if (!doFresh && !doReprovision) return@launch
                    if (doFresh) escalateFreshTunnel(gen) else requestReprovision(gen, peerGone = true)
                    val more = synchronized(reprovisionLock) {
                        pendingFreshTunnel || pendingReprovision
                    }
                    if (!more) return@launch
                }
            }
        }
    }

    /**
     * New peer and a new tun on the current system default. Does not switch
     * network or mode. The roam loop keeps running when this does not handshake.
     */
    private suspend fun escalateFreshTunnel(gen: Long): Boolean {
        if (!sessionStillActive(gen) || controlSignedOut) return false
        if (stealthUpgradeInProgress()) {
            scheduleAfterUpgrade(gen, fresh = true)
            return false
        }
        if (reprovisionJob?.isActive == true) return false
        val now = System.currentTimeMillis()
        val epoch = synchronized(reprovisionLock) {
            ReprovisionFlight.tryClaim(
                persistedAtMs = VpnSettings.reprovisionInProgressAt(this),
                nowMs = now,
                replaceWaiting = true,
            ) { claimedAt ->
                VpnSettings.setReprovisionInProgressAt(this, claimedAt)
            }
        } ?: return false
        RoamLog.record("reprovision fresh")
        DiagnosticLog.record("reprovision fresh")
        val keep = try {
            handlePeerGone(
                gen,
                epoch,
                freshInterface = true,
                markGone = false,
                scheduleRetry = false,
            )
        } catch (e: CancellationException) {
            ReprovisionFlight.release(epoch) { VpnSettings.clearReprovisionInProgressAt(this) }
            throw e
        }
        if (!keep) {
            ReprovisionFlight.release(epoch) { VpnSettings.clearReprovisionInProgressAt(this) }
        }
        val up = synchronized(tunnelOpLock) {
            runCatching { goField("currentTunnelHandle").getInt(backend) }.getOrDefault(-1) >= 0
        }
        return up && sessionStillActive(gen)
    }

    private suspend fun handlePeerGone(
        gen: Long,
        epoch: Int,
        freshInterface: Boolean = false,
        markGone: Boolean = true,
        scheduleRetry: Boolean = true,
    ): Boolean {
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
            if (markGone) VpnSettings.setPeerGoneAt(this, System.currentTimeMillis())
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

            if (freshInterface) nextTunnelIsFresh = true
            val state = try {
                establishTunnel(config, gen)
            } finally {
                nextTunnelIsFresh = false
            }
            if (gen != sessionGeneration || !sessionIntended()) {
                runCatching { turnUserspaceOff() }
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
                } else if (retry && scheduleRetry) {
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

    /**
     * A peer read advertised a different server public key. Rebuild the saved
     * tunnel with that key and rebind. The mode and the network stay as they are.
     */
    private fun applyAdvertisedServerKey(gen: Long, advertised: String?) {
        if (!sessionStillActive(gen) || controlSignedOut) return
        val prefs = vpnStatePrefs()
        val udp = prefs.getString(KEY_UDP_CONFIG, null)
        val live = prefs.getString(KEY_CONFIG, null)
        if (!ServerKeyConfig.needsRebuild(udp, live, advertised)) return
        val base = live ?: udp ?: return
        val nextLive = BackendConfig.select(base, base, advertised)
        val nextUdp = udp?.let { BackendConfig.select(it, it, advertised) }
        if (BackendConfig.fields(nextLive) == BackendConfig.fields(base) &&
            nextLive == base
        ) {
            return
        }
        val before = BackendConfig.fields(base) ?: return
        val after = BackendConfig.fields(nextLive) ?: return
        if (after.privateKey != before.privateKey ||
            after.presharedKey != before.presharedKey ||
            after.address != before.address
        ) {
            return
        }
        val parsed = runCatching {
            Config.parse(ByteArrayInputStream(nextLive.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return
        followSystemDefault()
        if (!installUserspace(parsed, gen, rebuildTun = false) || !sessionStillActive(gen)) return
        synchronized(tunnelOpLock) {
            if (!sessionStillActive(gen)) return
            val edit = vpnStatePrefs().edit().putString(KEY_CONFIG, nextLive)
            if (nextUdp != null) edit.putString(KEY_UDP_CONFIG, nextUdp)
            edit.apply()
        }
        Log.i(TAG, "server key refreshed")
    }

    /** Refresh the reconnecting notification once a network looks blocked. */
    private fun noteBlockedNetwork() {
        if (!isReconnecting || !sessionIntended() || controlSignedOut) {
            if (networkMayBeBlocking || blockedClock.stallSinceMs != 0L) {
                networkMayBeBlocking = false
                blockedClock = BlockedNetworkNotice.Clock()
            }
            return
        }
        val stats = readBackendStats()
        val step = BlockedNetworkNotice.advance(
            BlockedNetworkNotice.Sample(
                nowMs = System.currentTimeMillis(),
                connecting = false,
                reconnecting = true,
                connected = true,
                handshakeOk = false,
                handshakeMs = stats.third,
                attemptStartedMs = reconnectEpisodeStartedMs,
                rxBytes = stats.first,
                networkValidated = ValidatedNetworks.deviceHasInternet(this),
                alreadyStealth = usesStealth(),
                dismissed = false,
                clock = blockedClock,
            ),
        )
        blockedClock = step.clock
        if (step.visible == networkMayBeBlocking) return
        networkMayBeBlocking = step.visible
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(BlockedNetworkNotice.reconnectingText(step.visible)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
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
                noteBlockedNetwork()
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

                val addressed = systemDefaultSnap()?.takeIf { ipv4Addresses(it.link).isNotEmpty() }
                val now = System.currentTimeMillis()

                if (addressed != null && lastUnderlayIdentity != null && addressed.identity != lastUnderlayIdentity) {
                    Log.w(TAG, "path-adapt health: underlay moved handle=${addressed.network.networkHandle}")
                    healthUnderlayWaitStartedAtMs = 0L
                    noteRoamHandle(addressed.network.networkHandle)
                    linkAttachedAtMs = roamArmedAtMs.takeIf { it > 0L } ?: now
                    underlayLostSeen = false
                    rxAtLinkAttach = currentRxAtLinkAttach(addressed)
                    val recordedGone = !bindTargetAddressed(lastUnderlayIdentity?.toLongOrNull())
                    val moved = DeadPathRoam.redial(
                        DeadPathRoam.Attempt(
                            recordedHandle = lastUnderlayIdentity,
                            liveHandle = addressed.identity,
                            liveReady = true,
                            handshakeFresh = handshakeOk(),
                            rxSinceAttempt = readBackendStats().first,
                        ),
                    )
                    // The old handle can still look "current" after Wi-Fi is
                    // gone. forceHard is what gets past the fresh-handshake guard.
                    scheduleUnderlayAdapt(forceHard = moved && recordedGone)
                    lastStallCheckAtMs = now
                    val s = readBackendStats()
                    lastKnownRx = s.first
                    lastKnownTx = s.second
                    continue
                }

                if (addressed == null) {
                    if (lastUnderlayIdentity != null) underlayLostSeen = true
                    if (healthUnderlayWaitStartedAtMs == 0L) healthUnderlayWaitStartedAtMs = now
                    if (now - healthUnderlayWaitStartedAtMs > STALL_UNDERLAY_WAIT_FALLBACK_MS) {
                        Log.w(TAG, "path-adapt health: underlay absent ${now - healthUnderlayWaitStartedAtMs}ms, scheduling adapt")
                        healthUnderlayWaitStartedAtMs = 0L
                        underlayLostSeen = false
                        val s0 = readBackendStats()
                        rxAtLinkAttach = s0.first
                        scheduleUnderlayAdapt()
                        lastStallCheckAtMs = now
                        lastKnownRx = s0.first
                        lastKnownTx = s0.second
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
        @Volatile
        private var active: VeritasVpnService? = null

        /** True while this process still holds the VPN interface. */
        fun interfaceHeld(): Boolean = active?.tunKeeper != null

        /**
         * Always-on and lockdown as the live VPN service reports them.
         * Null when this process has no service instance, or the platform
         * call fails. Null means the service is not ready, not that the
         * switches are off. Callers must not start the service only to read this.
         */
        fun readServiceLockdown(): Pair<Boolean, Boolean>? {
            val service = active ?: return null
            return try {
                service.isAlwaysOn() to service.isLockdownEnabled()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Drop the cached system-default client. The next [provisioningClient]
         * builds a new one. The close itself runs off the caller.
         */
        fun retireProvisioningClient() {
            active?.dropUnderlyingControlClient()
        }

        /**
         * Protected client for a peer request while the interface is held.
         * Null when the interface is closed, so the caller uses the normal
         * client. Null when the interface is held but the system default is
         * gone: the normal client cannot leave a lockdown interface.
         */
        fun provisioningClient(): OkHttpClient? {
            val service = active ?: return null
            if (service.tunKeeper == null) return null
            service.keepBestMatchingWatch()
            repeat(8) { attempt ->
                if (service.tunKeeper == null) return null
                val client = runCatching { service.underlyingControlClient() }.getOrNull()
                if (client != null) return client
                if (attempt == 7) return null
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return null
        }

        const val TUNNEL_NAME = "veritas"
        const val CHANNEL_ID = "veritas_vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "cloud.veritasvpn.CONNECT"
        const val ACTION_APPLY_STEALTH = "cloud.veritasvpn.APPLY_STEALTH"
        const val ACTION_DISCONNECT = "cloud.veritasvpn.DISCONNECT"
        const val ACTION_AUTH_RESTORED = "cloud.veritasvpn.AUTH_RESTORED"
        const val ACTION_QUERY_STATE = "cloud.veritasvpn.QUERY_STATE"
        const val ACTION_ENSURE_LOCKDOWN_HOLD = "cloud.veritasvpn.ENSURE_LOCKDOWN_HOLD"
        const val ACTION_STATE = "cloud.veritasvpn.STATE"
        const val ACTION_STATS = "cloud.veritasvpn.STATS"
        const val ACTION_SHIELD = "cloud.veritasvpn.SHIELD"
        const val SHIELD_CHANNEL_ID = "veritas_shield"
        const val SHIELD_NOTIFICATION_ID = 2
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
        const val EXTRA_STEALTH_DETAIL = "stealth_detail"
        const val EXTRA_NEEDS_VPN_CONSENT = "needs_vpn_consent"
        const val EXTRA_OPEN_SHIELD = "open_shield"
        const val EXTRA_DNS_BLOCKED = "dns_blocked"
        const val EXTRA_SHIELD_BLOCKS = "shield_blocks"
        const val EXTRA_SHIELD_BANNER = "shield_banner"
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
        @Volatile
        private var sessionEndedByUser: Boolean = false

        /** The circle tap sets this before the service runs, so a recreated activity stays offline. */
        fun markSessionEnded() {
            sessionEndedByUser = true
        }

        fun markSessionStarted() {
            sessionEndedByUser = false
        }

        fun hasSavedSession(context: Context): Boolean {
            if (sessionEndedByUser) return false
            val prefs = SecurePrefs.open(context.applicationContext, PREFS_NAME)
            if (prefs.getString(KEY_CONFIG, null) == null) return false
            return VpnSettings.currentPeerId(context) != null
        }
        private const val UNDERLAY_ADAPT_DEBOUNCE_MS = EndpointRefresh.DEBOUNCE_MS
        private const val HEALTH_WATCH_MS = 2_000L
        private const val HANDSHAKE_OK_AGE_MS = 200_000L
        private const val STALL_DETECT_WINDOW_MS = TunnelStall.DETECT_WINDOW_MS
        private const val STALL_UNDERLAY_POLL_MS = 1_500L
        private const val STALL_UNDERLAY_MAX_POLL_MS = 10_000L
        private const val STALL_UNDERLAY_WAIT_FALLBACK_MS = 10_000L
        private const val STATS_LOG_INTERVAL_MS = 30_000L
        private const val TAG = "VeritasVpnService"
    }
}
