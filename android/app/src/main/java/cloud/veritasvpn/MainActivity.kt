package cloud.veritasvpn

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.api.PeerInfo
import cloud.veritasvpn.api.PeerListResponse
import cloud.veritasvpn.api.PeerResponse
import cloud.veritasvpn.api.PortForwardInfo
import cloud.veritasvpn.api.PortForwardListResponse
import cloud.veritasvpn.auth.AuthRepository
import cloud.veritasvpn.auth.AuthenticatedApi
import cloud.veritasvpn.auth.SessionExpiredException
import cloud.veritasvpn.billing.BillingRepository
import java.io.IOException
import cloud.veritasvpn.ui.AuthScreen
import cloud.veritasvpn.ui.DashboardScreen
import cloud.veritasvpn.ui.DevicesScreen
import cloud.veritasvpn.ui.PlansScreen
import cloud.veritasvpn.ui.PortForwardsScreen
import cloud.veritasvpn.ui.PaymentCheckoutScreen
import cloud.veritasvpn.ui.TunnelSettingsScreen
import cloud.veritasvpn.ui.theme.VeritasVPNTheme
import cloud.veritasvpn.vpn.VeritasVpnService
import cloud.veritasvpn.vpn.VpnKillSwitch
import cloud.veritasvpn.vpn.VpnSettings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import cloud.veritasvpn.secure.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val BILLING_CACHE_PREFS = "veritas_billing_cache"

private fun billingCacheKey(accountId: String, field: String): String =
    "billing_" + accountId + "_" + field

private fun readCachedBillingStatus(context: Context, accountId: String): BillingStatus? {
    val prefs = SecurePrefs.open(context, BILLING_CACHE_PREFS)
    if (!prefs.contains(billingCacheKey(accountId, "premium"))) return null
    return BillingStatus(
        tier = prefs.getString(billingCacheKey(accountId, "tier"), "free") ?: "free",
        status = prefs.getString(billingCacheKey(accountId, "status"), "active") ?: "active",
        paymentMethod = prefs.getString(billingCacheKey(accountId, "payment_method"), "none") ?: "none",
        currentPeriodEnd = prefs.getString(billingCacheKey(accountId, "period_end"), null),
        cancelAtPeriodEnd = prefs.getBoolean(billingCacheKey(accountId, "cancel_at_end"), false),
        isPremium = prefs.getBoolean(billingCacheKey(accountId, "premium"), false)
    )
}

private fun writeCachedBillingStatus(
    context: Context,
    accountId: String,
    status: BillingStatus
) {
    SecurePrefs.open(context, BILLING_CACHE_PREFS)
        .edit()
        .putString(billingCacheKey(accountId, "tier"), status.tier)
        .putString(billingCacheKey(accountId, "status"), status.status)
        .putString(billingCacheKey(accountId, "payment_method"), status.paymentMethod)
        .putString(billingCacheKey(accountId, "period_end"), status.currentPeriodEnd)
        .putBoolean(billingCacheKey(accountId, "cancel_at_end"), status.cancelAtPeriodEnd)
        .putBoolean(billingCacheKey(accountId, "premium"), status.isPremium)
        .apply()
}

class MainActivity : ComponentActivity() {
    private lateinit var authRepo: AuthRepository
    private var peerCleanupJob: Job? = null
    private var reconnectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authRepo = AuthRepository(this)
        currentPeerId = VpnSettings.currentPeerId(this)

        setContent {
            VeritasVPNTheme {
                var user by remember { mutableStateOf(authRepo.getStoredUser()) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val billingRepo = remember { BillingRepository(authRepo) }
                var connected by remember { mutableStateOf(false) }
                var connecting by remember { mutableStateOf(false) }
                var reconnecting by remember { mutableStateOf(false) }
                var userWantsConnected by remember { mutableStateOf(false) }
                var hadEstablishedSession by remember { mutableStateOf(false) }
                var reconnectAttempt by remember { mutableStateOf(0) }
                var hardReconnectRequested by remember { mutableStateOf(false) }
                var statusMsg by remember { mutableStateOf<String?>(null) }
                var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                var showPlans by remember { mutableStateOf(false) }
                var showDevices by remember { mutableStateOf(false) }
                var showPortForwards by remember { mutableStateOf(false) }
                var showTunnelSettings by remember { mutableStateOf(false) }
                var showKillSwitchRequired by remember { mutableStateOf(false) }
                var pendingConnectAfterKillSwitch by remember { mutableStateOf(false) }
                var killSwitchEnabled by remember { mutableStateOf(VpnKillSwitch.isLockdownEnabled(context)) }
                var devices by remember { mutableStateOf<List<PeerInfo>>(emptyList()) }
                var devicesLoading by remember { mutableStateOf(false) }
                var devicesError by remember { mutableStateOf<String?>(null) }
                var revokingPeerId by remember { mutableStateOf<String?>(null) }
                var portForwards by remember { mutableStateOf<List<PortForwardInfo>>(emptyList()) }
                var portForwardsLoading by remember { mutableStateOf(false) }
                var portForwardsError by remember { mutableStateOf<String?>(null) }
                var portForwardCreating by remember { mutableStateOf(false) }
                var deletingForwardId by remember { mutableStateOf<String?>(null) }
                var rxBytes by remember { mutableStateOf(0L) }
                var txBytes by remember { mutableStateOf(0L) }
                var handshakeMs by remember { mutableStateOf(0L) }
                var dnsBlockedCount by remember { mutableStateOf<Long?>(null) }
                var dnsBlockedBaseline by remember { mutableStateOf<Long?>(null) }
                var shieldPreset by remember { mutableStateOf("standard") }
                var dnsGateway by remember { mutableStateOf<String?>(null) }
                var excludeLan by remember { mutableStateOf(VpnSettings.excludeLan(context)) }
                var bypassAppsText by remember {
                    mutableStateOf(VpnSettings.bypassApps(context).joinToString("\n"))
                }
                var appliedExcludeLan by remember { mutableStateOf(VpnSettings.excludeLan(context)) }
                var appliedBypassAppsText by remember {
                    mutableStateOf(VpnSettings.bypassApps(context).joinToString("\n"))
                }
                var billingStatus by remember { mutableStateOf<BillingStatus?>(null) }
                var billingRefreshing by remember { mutableStateOf(false) }
                var cancellationInProgress by remember { mutableStateOf(false) }
                var billingError by remember { mutableStateOf<String?>(null) }
                var checkoutMethod by remember { mutableStateOf<String?>(null) }
                var checkoutUrl by remember { mutableStateOf<String?>(null) }

                fun disconnectVpnService() {
                    context.startService(
                        Intent(context, VeritasVpnService::class.java).apply {
                            action = VeritasVpnService.ACTION_DISCONNECT
                        }
                    )
                    connected = false
                    connecting = false
                    rxBytes = 0
                    txBytes = 0
                    handshakeMs = 0
                    dnsBlockedCount = null
                    dnsBlockedBaseline = null
                    dnsGateway = null
                }

                fun cancelReconnect() {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnecting = false
                    hardReconnectRequested = false
                    reconnectAttempt = 0
                }

                fun deletePeerBestEffort(peerId: String?) {
                    if (peerId.isNullOrBlank()) return
                    peerCleanupJob = scope.launch(Dispatchers.IO) {
                        try {
                            AuthenticatedApi.execute(authRepo, { token ->
                                ApiClient.delete("/api/v1/wg/peers/$peerId", token)
                            }) { it.close() }
                        } catch (_: Exception) {
                        }
                    }
                }

                fun performLocalSignOut() {
                    userWantsConnected = false
                    hadEstablishedSession = false
                    cancelReconnect()
                    val peerId = peerIdForDisconnect()
                    disconnectVpnService()
                    // Revoke server peer while auth is still available, then clear local session.
                    scope.launch {
                        try {
                            if (!peerId.isNullOrBlank()) {
                                withContext(Dispatchers.IO) {
                                    AuthenticatedApi.execute(authRepo, { token ->
                                        ApiClient.delete("/api/v1/wg/peers/$peerId", token)
                                    }) { it.close() }
                                }
                            }
                        } catch (_: Exception) {
                        }
                        authRepo.signOut()
                        billingStatus = null
                        checkoutUrl = null
                        billingError = null
                        checkoutMethod = null
                        showPlans = false
                        showDevices = false
                        showPortForwards = false
                        showTunnelSettings = false
                        user = null
                    }
                }

                fun handleSessionExpired() {
                    performLocalSignOut()
                }

                fun ensureSessionFresh() {
                    scope.launch(Dispatchers.IO) {
                        if (user != null && !authRepo.validateSessionOnResume()) {
                            withContext(Dispatchers.Main) { handleSessionExpired() }
                        }
                    }
                }

                fun refreshBilling() {
                    if (user == null || billingRefreshing) return
                    billingRefreshing = true
                    billingError = null
                    scope.launch {
                        try {
                            val status = withTimeout(8_000) {
                                withContext(Dispatchers.IO) {
                                    billingRepo.status()
                                }
                            }
                            billingStatus = status
                            writeCachedBillingStatus(context, user!!.accountId, status)
                            billingError = null
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) {
                                handleSessionExpired()
                                return@launch
                            }
                            // Preserve the last verified plan during a transient
                            // network failure. It is safer and clearer than
                            // replacing an active cached plan with an error state.
                            val hadCachedStatus = billingStatus != null
                            if (!hadCachedStatus) billingStatus = BillingStatus()
                            billingError = if (!hadCachedStatus) {
                                e.message ?: "Could not load your plan."
                            } else {
                                null
                            }
                        } finally {
                            billingRefreshing = false
                        }
                    }
                }

                fun loadDevices() {
                    devicesLoading = true
                    devicesError = null
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                AuthenticatedApi.execute(authRepo, { token ->
                                    ApiClient.get("/api/v1/wg/peers", token)
                                }) { res ->
                                    if (!res.isSuccessful) {
                                        throw IllegalStateException("Could not load devices (${res.code})")
                                    }
                                    ApiClient.parse<PeerListResponse>(res)?.peers.orEmpty()
                                }
                            }
                            devices = list
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) {
                                handleSessionExpired()
                                return@launch
                            }
                            devicesError = e.message ?: "Could not load devices."
                        } finally {
                            devicesLoading = false
                        }
                    }
                }

                fun loadPortForwards() {
                    portForwardsLoading = true
                    portForwardsError = null
                    scope.launch {
                        try {
                            val (peerList, forwardList) = withContext(Dispatchers.IO) {
                                val peersResult = AuthenticatedApi.execute(authRepo, { token ->
                                    ApiClient.get("/api/v1/wg/peers", token)
                                }) { res ->
                                    if (!res.isSuccessful) {
                                        throw IllegalStateException("Could not load devices (${res.code})")
                                    }
                                    ApiClient.parse<PeerListResponse>(res)?.peers.orEmpty()
                                }
                                val forwardsResult = AuthenticatedApi.execute(authRepo, { token ->
                                    ApiClient.get("/api/v1/wg/port-forwards", token)
                                }) { res ->
                                    if (!res.isSuccessful) {
                                        val err = ApiClient.parse<PortForwardListResponse>(res)?.error
                                        throw IllegalStateException(err ?: "Could not load port forwards (${res.code})")
                                    }
                                    ApiClient.parse<PortForwardListResponse>(res)?.portForwards.orEmpty()
                                }
                                peersResult to forwardsResult
                            }
                            devices = peerList
                            portForwards = forwardList
                        } catch (e: Exception) {
                            portForwardsError = e.message ?: "Could not load port forwards."
                            if (e is SessionExpiredException) handleSessionExpired()
                        } finally {
                            portForwardsLoading = false
                        }
                    }
                }

                fun startCheckout(paymentMethod: String, planId: String) {
                    if (checkoutMethod != null) return
                    checkoutMethod = paymentMethod
                    billingError = null
                    scope.launch {
                        try {
                            val createdCheckoutUrl = withContext(Dispatchers.IO) {
                                billingRepo.createCheckout(paymentMethod, planId)
                            }
                            checkoutUrl = createdCheckoutUrl
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) {
                                handleSessionExpired()
                                return@launch
                            }
                            billingError = e.message ?: "Could not open checkout."
                        } finally {
                            checkoutMethod = null
                        }
                    }
                }

                LaunchedEffect(user?.accountId) {
                    if (user != null) {
                        // Use the last verified plan for this account immediately,
                        // then refresh it in the background without blocking the
                        // dashboard or the Connect button.
                        billingStatus = readCachedBillingStatus(context, user!!.accountId)
                        billingRefreshing = false
                        refreshBilling()
                    }
                }

                LaunchedEffect(checkoutUrl) {
                    while (checkoutUrl != null && user != null) {
                        kotlinx.coroutines.delay(3000)
                        try {
                            val status = withTimeout(7_000) {
                                withContext(Dispatchers.IO) {
                                    billingRepo.status()
                                }
                            }
                            billingStatus = status
                            writeCachedBillingStatus(context, user!!.accountId, status)
                            if (status.isPremium) {
                                checkoutUrl = null
                                billingError = null
                            }
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) {
                                handleSessionExpired()
                                return@LaunchedEffect
                            }
                        }
                    }
                }

                fun cancelSubscription() {
                    if (cancellationInProgress) return
                    cancellationInProgress = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                billingRepo.cancel()
                            }
                            billingStatus = withContext(Dispatchers.IO) {
                                billingRepo.status()
                            }
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) {
                                handleSessionExpired()
                                return@launch
                            }
                            billingError = e.message ?: "Could not cancel your subscription."
                        } finally { cancellationInProgress = false }
                    }
                }

                var pendingNotificationStart by remember { mutableStateOf(false) }
                var pendingNotificationReconnect by remember { mutableStateOf(false) }

                fun markReconnectNeeded() {
                    // Product rule: after Connect, never auto-tear the session.
                    if (!userWantsConnected || !hadEstablishedSession) {
                        if (!hadEstablishedSession) userWantsConnected = false
                        reconnecting = false
                        hardReconnectRequested = false
                        return
                    }
                    Log.i("VeritasVPN", "Ignoring auto-reconnect; session stays intended")
                    reconnecting = false
                    hardReconnectRequested = false
                    connected = true
                    connecting = false
                    statusMsg = null
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    val shouldStart = pendingNotificationStart
                    val isReconnect = pendingNotificationReconnect
                    pendingNotificationStart = false
                    pendingNotificationReconnect = false
                    if (shouldStart) {
                        startConnection(
                            context, scope,
                            setStatus = { msg -> statusMsg = msg },
                            setConnecting = { connecting = it },
                            isReconnect = isReconnect,
                            onFailure = { markReconnectNeeded() },
                            onSessionExpired = { handleSessionExpired() },
                            onDnsGateway = {
                                dnsGateway = it
                                dnsBlockedBaseline = null
                            }
                        )
                    }
                }

                fun startVpnAfterPermissions(isReconnect: Boolean = false) {
                    val notificationManager =
                        context.getSystemService(NotificationManager::class.java)
                    val permissionPrefs = SecurePrefs.open(
                        context,
                        "veritasvpn_permissions"
                    )
                    val promptAlreadyShown = permissionPrefs.getBoolean(
                        "notification_permission_prompted",
                        false
                    )
                    val needsNotificationPermission =
                        Build.VERSION.SDK_INT >= 33 &&
                            notificationManager != null &&
                            !notificationManager.areNotificationsEnabled() &&
                            !promptAlreadyShown
                    if (needsNotificationPermission) {
                        permissionPrefs.edit()
                            .putBoolean("notification_permission_prompted", true)
                            .apply()
                        pendingNotificationStart = true
                        pendingNotificationReconnect = isReconnect
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } else {
                        startConnection(
                            context, scope,
                            setStatus = { msg -> statusMsg = msg },
                            setConnecting = { connecting = it },
                            isReconnect = isReconnect,
                            onFailure = { markReconnectNeeded() },
                            onSessionExpired = { handleSessionExpired() },
                            onDnsGateway = {
                                dnsGateway = it
                                dnsBlockedBaseline = null
                            }
                        )
                    }
                }

                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        statusMsg = null
                        startVpnAfterPermissions()
                    } else {
                        connecting = false
                        reconnecting = false
                        userWantsConnected = false
                        statusMsg = "VPN permission not granted."
                    }
                }

                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) requestDeviceLocation(context) { deviceLocation = it }
                }

                LaunchedEffect(connecting) {
                    if (connecting) {
                        // First-connect only. Never timeout-disconnect an established session.
                        kotlinx.coroutines.delay(25_000)
                        if (connecting && !hadEstablishedSession) {
                            connecting = false
                            userWantsConnected = false
                            statusMsg = "Connection timed out. Check your network and try again."
                            runCatching {
                                context.startService(
                                    Intent(context, VeritasVpnService::class.java).apply {
                                        action = VeritasVpnService.ACTION_DISCONNECT
                                    }
                                )
                            }
                            peerIdForDisconnect()?.let { timedOutPeerId ->
                                peerCleanupJob = scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        AuthenticatedApi.execute(authRepo, { token ->
                                            ApiClient.delete("/api/v1/wg/peers/$timedOutPeerId", token)
                                        }) { it.close() }
                                    }
                                }
                            }
                        } else if (connecting && hadEstablishedSession) {
                            connecting = false
                            reconnecting = false
                            connected = true
                            statusMsg = null
                        }
                    }
                }

                LaunchedEffect(user) {
                    if (user != null) {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            requestDeviceLocation(context) { deviceLocation = it }
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                }

                DisposableEffect(context) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            when (intent?.action) {
                                VeritasVpnService.ACTION_STATE -> {
                                    val nowConnected =
                                        intent.getBooleanExtra(VeritasVpnService.EXTRA_CONNECTED, false)
                                    val error = intent.getStringExtra(VeritasVpnService.EXTRA_ERROR)
                                    if (nowConnected) {
                                        connected = true
                                        connecting = false
                                        reconnecting = false
                                        hardReconnectRequested = false
                                        reconnectAttempt = 0
                                        reconnectJob?.cancel()
                                        reconnectJob = null
                                        userWantsConnected = true
                                        hadEstablishedSession = true
                                        appliedExcludeLan = VpnSettings.excludeLan(this@MainActivity)
                                        appliedBypassAppsText =
                                            VpnSettings.bypassApps(this@MainActivity).joinToString("\n")
                                        excludeLan = appliedExcludeLan
                                        bypassAppsText = appliedBypassAppsText
                                        statusMsg = null
                                    } else if (error != null && error.contains("revoked", ignoreCase = true)) {
                                        connected = false
                                        connecting = false
                                        reconnecting = false
                                        userWantsConnected = false
                                        hadEstablishedSession = false
                                        statusMsg = error
                                        peerIdForDisconnect()
                                    } else if (userWantsConnected && hadEstablishedSession) {
                                        // Ignore unintended disconnects — stay connected in UI.
                                        connected = true
                                        connecting = false
                                        reconnecting = false
                                        hardReconnectRequested = false
                                        statusMsg = null
                                    } else {
                                        connected = false
                                        connecting = false
                                        reconnecting = false
                                        rxBytes = 0
                                        txBytes = 0
                                        handshakeMs = 0
                                        dnsBlockedCount = null
                                        dnsBlockedBaseline = null
                                        dnsGateway = null
                                        statusMsg = error
                                    }
                                }
                                VeritasVpnService.ACTION_STATS -> {
                                    rxBytes = intent.getLongExtra(VeritasVpnService.EXTRA_RX_BYTES, 0L)
                                    txBytes = intent.getLongExtra(VeritasVpnService.EXTRA_TX_BYTES, 0L)
                                    handshakeMs = intent.getLongExtra(VeritasVpnService.EXTRA_HANDSHAKE_MS, 0L)
                                }
                                VeritasVpnService.ACTION_RECONNECT_NEEDED -> {
                                    if (userWantsConnected && hadEstablishedSession) {
                                        connected = true
                                        connecting = false
                                        reconnecting = false
                                        statusMsg = null
                                    }
                                }
                            }
                        }
                    }
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter().apply {
                            addAction(VeritasVpnService.ACTION_STATE)
                            addAction(VeritasVpnService.ACTION_STATS)
                            addAction(VeritasVpnService.ACTION_RECONNECT_NEEDED)
                        },
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose { context.unregisterReceiver(receiver) }
                }

                LaunchedEffect(connected, user?.accountId) {
                    if (!connected || user == null) {
                        dnsBlockedCount = null
                        dnsBlockedBaseline = null
                        return@LaunchedEffect
                    }
                    while (isActive && connected) {
                        val peerId = currentPeerId ?: VpnSettings.currentPeerId(context)
                        if (peerId != null) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AuthenticatedApi.execute(authRepo, { token ->
                                        ApiClient.get("/api/v1/wg/peers", token)
                                    }) { res ->
                                        if (!res.isSuccessful) return@execute null
                                        ApiClient.parse<PeerListResponse>(res)
                                            ?.peers
                                            ?.firstOrNull { it.id == peerId }
                                    }
                                }
                            }.onSuccess { peer ->
                                if (peer != null) {
                                    val count = peer.dnsBlockedCount
                                    if (dnsBlockedBaseline == null) dnsBlockedBaseline = count
                                    dnsBlockedCount = count
                                    if (peer.shieldPreset.isNotBlank()) {
                                        shieldPreset = peer.shieldPreset
                                    }
                                }
                            }.onFailure {
                                if (it is SessionExpiredException) handleSessionExpired()
                            }
                        }
                        delay(5_000)
                    }
                }

                fun requestConnect() {
                    if (connecting || connected || reconnecting) return
                    if (billingStatus?.isPremium != true) {
                        statusMsg = "An active subscription is required. Open Plans to subscribe."
                        return
                    }
                    killSwitchEnabled = VpnKillSwitch.isLockdownEnabled(context)
                    if (!killSwitchEnabled) {
                        pendingConnectAfterKillSwitch = true
                        showKillSwitchRequired = true
                        statusMsg = null
                        return
                    }
                    userWantsConnected = true
                    cancelReconnect()
                    connecting = true
                    VpnService.prepare(context)?.let { consentIntent ->
                        vpnPermissionLauncher.launch(consentIntent)
                        return
                    }
                    startVpnAfterPermissions()
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            killSwitchEnabled = VpnKillSwitch.isLockdownEnabled(context)
                            if (killSwitchEnabled && pendingConnectAfterKillSwitch) {
                                pendingConnectAfterKillSwitch = false
                                showKillSwitchRequired = false
                                requestConnect()
                            } else if (killSwitchEnabled) {
                                showKillSwitchRequired = false
                            }
                            if (user != null) ensureSessionFresh()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val tunnelSettingsDirty =
                    excludeLan != appliedExcludeLan || bypassAppsText != appliedBypassAppsText

                if (user == null) {
                    AuthScreen(onAuthenticated = {
                        billingStatus = null
                        billingRefreshing = false
                        cancellationInProgress = false
                        user = authRepo.getStoredUser()
                    })
                } else if (checkoutUrl != null) {
                    PaymentCheckoutScreen(
                        checkoutUrl = checkoutUrl!!,
                        onClose = { checkoutUrl = null; refreshBilling() },
                        onRefreshPlan = { refreshBilling() }
                    )
                } else if (showTunnelSettings) {
                    TunnelSettingsScreen(
                        excludeLan = excludeLan,
                        bypassAppsText = bypassAppsText,
                        showReconnectBanner = connected && tunnelSettingsDirty,
                        connected = connected,
                        dnsGateway = dnsGateway,
                        dnsBlockedThisSession = if (dnsBlockedCount != null && dnsBlockedBaseline != null) {
                            (dnsBlockedCount!! - dnsBlockedBaseline!!).coerceAtLeast(0)
                        } else null,
                        shieldPreset = shieldPreset,
                        onShieldPresetChange = { next ->
                            val peerId = currentPeerId ?: VpnSettings.currentPeerId(context) ?: return@TunnelSettingsScreen
                            shieldPreset = next
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        AuthenticatedApi.execute(authRepo, { token ->
                                            ApiClient.patch(
                                                "/api/v1/wg/peers/$peerId",
                                                mapOf("shield_preset" to next),
                                                token
                                            )
                                        }) { res ->
                                            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                                            true
                                        }
                                    }
                                }.onFailure {
                                    if (it is SessionExpiredException) handleSessionExpired()
                                    else statusMsg = "Could not update Veritas Shield preset"
                                }
                            }
                        },
                        onExcludeLanChange = {
                            excludeLan = it
                            VpnSettings.setExcludeLan(context, it)
                        },
                        onBypassAppsChange = {
                            bypassAppsText = it
                            VpnSettings.setBypassApps(
                                context,
                                it.lineSequence().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }.toList()
                            )
                        },
                        onBack = { showTunnelSettings = false }
                    )
                } else if (showDevices) {
                    LaunchedEffect(Unit) { loadDevices() }
                    DevicesScreen(
                        peers = devices,
                        loading = devicesLoading,
                        error = devicesError,
                        currentPeerId = currentPeerId ?: VpnSettings.currentPeerId(context),
                        revokingId = revokingPeerId,
                        onBack = { showDevices = false },
                        onRefresh = { loadDevices() },
                        onRevoke = { peer ->
                            if (revokingPeerId != null) return@DevicesScreen
                            revokingPeerId = peer.id
                            scope.launch {
                                try {
                                    val isCurrent = peer.id == (currentPeerId ?: VpnSettings.currentPeerId(context))
                                    if (isCurrent) {
                                        userWantsConnected = false
                                        hadEstablishedSession = false
                                        cancelReconnect()
                                        disconnectVpnService()
                                        peerIdForDisconnect()
                                    }
                                    withContext(Dispatchers.IO) {
                                        AuthenticatedApi.execute(authRepo, { token ->
                                            ApiClient.delete("/api/v1/wg/peers/${peer.id}", token)
                                        }) { res ->
                                            if (!res.isSuccessful) {
                                                throw IllegalStateException("Revoke failed (${res.code})")
                                            }
                                        }
                                    }
                                    devices = devices.filterNot { it.id == peer.id }
                                } catch (e: Exception) {
                                    if (e is SessionExpiredException) {
                                        handleSessionExpired()
                                        return@launch
                                    }
                                    devicesError = e.message ?: "Could not revoke device."
                                } finally {
                                    revokingPeerId = null
                                }
                            }
                        }
                    )
                } else if (showPortForwards) {
                    LaunchedEffect(Unit) { loadPortForwards() }
                    PortForwardsScreen(
                        forwards = portForwards,
                        peers = devices,
                        loading = portForwardsLoading,
                        creating = portForwardCreating,
                        deletingId = deletingForwardId,
                        error = portForwardsError,
                        currentPeerId = currentPeerId ?: VpnSettings.currentPeerId(context),
                        onBack = { showPortForwards = false },
                        onRefresh = { loadPortForwards() },
                        onCreate = { peerId, protocol, externalPort, internalPort ->
                            if (portForwardCreating) return@PortForwardsScreen
                            portForwardCreating = true
                            portForwardsError = null
                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) {
                                        val body = mutableMapOf<String, Any>(
                                            "peer_id" to peerId,
                                            "protocol" to protocol,
                                            "external_port" to externalPort
                                        )
                                        if (internalPort != null) body["internal_port"] = internalPort
                                        AuthenticatedApi.execute(authRepo, { token ->
                                            ApiClient.post("/api/v1/wg/port-forwards", body, token)
                                        }) { res ->
                                            val parsed = ApiClient.parse<PortForwardInfo>(res)
                                            if (!res.isSuccessful) {
                                                throw IllegalStateException(parsed?.error ?: "Could not create port forward (${res.code})")
                                            }
                                            parsed ?: throw IllegalStateException("Empty create response")
                                        }
                                    }
                                    portForwards = listOf(created) + portForwards.filterNot { it.id == created.id }
                                } catch (e: Exception) {
                                    if (e is SessionExpiredException) {
                                        handleSessionExpired()
                                        return@launch
                                    }
                                    portForwardsError = e.message ?: "Could not create port forward."
                                } finally {
                                    portForwardCreating = false
                                }
                            }
                        },
                        onDelete = { pf ->
                            if (deletingForwardId != null) return@PortForwardsScreen
                            deletingForwardId = pf.id
                            portForwardsError = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        AuthenticatedApi.execute(authRepo, { token ->
                                            ApiClient.delete("/api/v1/wg/port-forwards/${pf.id}", token)
                                        }) { res ->
                                            if (!res.isSuccessful) {
                                                val err = ApiClient.parse<PortForwardInfo>(res)?.error
                                                throw IllegalStateException(err ?: "Delete failed (${res.code})")
                                            }
                                        }
                                    }
                                    portForwards = portForwards.filterNot { it.id == pf.id }
                                } catch (e: Exception) {
                                    if (e is SessionExpiredException) {
                                        handleSessionExpired()
                                        return@launch
                                    }
                                    portForwardsError = e.message ?: "Could not delete port forward."
                                } finally {
                                    deletingForwardId = null
                                }
                            }
                        }
                    )
                } else if (showPlans) {
                    PlansScreen(
                        billingStatus = billingStatus,
                        refreshing = billingRefreshing,
                        cancelling = cancellationInProgress,
                        checkoutMethod = checkoutMethod,
                        error = billingError,
                        onBack = { showPlans = false },
                        onRefresh = { refreshBilling() },
                        onCheckout = { method, plan -> startCheckout(method, plan) },
                        onCancel = { cancelSubscription() }
                    )
                } else {
                    DashboardScreen(
                        connected = connected,
                        connecting = connecting,
                        onConnect = { requestConnect() },
                        onDisconnect = {
                            userWantsConnected = false
                            hadEstablishedSession = false
                            cancelReconnect()
                            statusMsg = null
                            val disconnectedPeerId = peerIdForDisconnect()
                            disconnectVpnService()
                            deletePeerBestEffort(disconnectedPeerId)
                        },
                        onSignOut = { performLocalSignOut() },
                        onSignOutEverywhere = {
                            scope.launch {
                                userWantsConnected = false
                                hadEstablishedSession = false
                                cancelReconnect()
                                val peerId = peerIdForDisconnect()
                                deletePeerBestEffort(peerId)
                                peerCleanupJob?.join()
                                try {
                                    withContext(Dispatchers.IO) {
                                        authRepo.logoutAllSessions()
                                    }
                                } catch (_: Exception) {
                                    // Still clear local auth even if the API call fails.
                                    authRepo.signOut()
                                }
                                disconnectVpnService()
                                billingStatus = null
                                showPlans = false
                                showDevices = false
                                showPortForwards = false
                                showTunnelSettings = false
                                user = null
                            }
                        },
                        onPlans = {
                            showPlans = true
                            if (billingStatus == null) refreshBilling()
                        },
                        onDevices = {
                            showPortForwards = false
                            showDevices = true
                            loadDevices()
                        },
                        onPortForwards = {
                            showDevices = false
                            showPortForwards = true
                            loadPortForwards()
                        },
                        onTunnelSettings = { showTunnelSettings = true },
                        onOpenKillSwitchSettings = {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        },
                        killSwitchEnabled = killSwitchEnabled,
                        showKillSwitchRequired = showKillSwitchRequired,
                        onDismissKillSwitchRequired = {
                            showKillSwitchRequired = false
                            pendingConnectAfterKillSwitch = false
                            userWantsConnected = false
                            connecting = false
                            reconnecting = false
                        },
                        isPremium = billingStatus?.isPremium == true,
                        billingReady = billingStatus != null,
                        statusMsg = statusMsg,
                        deviceLatitude = deviceLocation?.first,
                        deviceLongitude = deviceLocation?.second,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        handshakeMs = handshakeMs,
                        dnsBlockedCount = dnsBlockedCount,
                        dnsBlockedBaseline = dnsBlockedBaseline,
                        dnsGateway = dnsGateway
                    )
                }
            }
        }
    }

    private fun requestDeviceLocation(
        context: Context,
        onLocation: (Pair<Double, Double>) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(
                provider,
                CancellationSignal(),
                ContextCompat.getMainExecutor(context)
            ) { location ->
                if (location != null) onLocation(location.latitude to location.longitude)
            }
        } else {
            @Suppress("DEPRECATION")
            manager.getLastKnownLocation(provider)?.let {
                onLocation(it.latitude to it.longitude)
            }
        }
    }

    private var currentPeerId: String? = null

    private fun peerIdForDisconnect(): String? {
        val id = currentPeerId ?: VpnSettings.currentPeerId(this)
        currentPeerId = null
        VpnSettings.setCurrentPeerId(this, null)
        return id
    }

    private fun startConnection(
        context: Context,
        scope: CoroutineScope,
        setStatus: (String) -> Unit,
        setConnecting: (Boolean) -> Unit,
        isReconnect: Boolean = false,
        onFailure: (() -> Unit)? = null,
        onSessionExpired: (() -> Unit)? = null,
        onDnsGateway: ((String) -> Unit)? = null,
    ) {
        if (currentPeerId != null) return
        setStatus(if (isReconnect) "Reconnecting…" else "Connecting...")
        scope.launch {
            try {
                // Wait for prior DELETE so we do not race ourselves; server upserts
                // by (account_id, device_id) so other installs stay untouched.
                peerCleanupJob?.join()
                val (keyPair, peer) = withContext(Dispatchers.IO) {
                    val generated = KeyPair()
                    val deviceId = VpnSettings.deviceId(context)
                    val createdPeer = AuthenticatedApi.execute(authRepo, { token ->
                        ApiClient.post(
                            "/api/v1/wg/peers",
                            mapOf(
                                "public_key" to generated.publicKey.toBase64(),
                                "device_id" to deviceId,
                            ),
                            token
                        )
                    }) { res ->
                        if (!res.isSuccessful) {
                            val err = ApiClient.parse<PeerResponse>(res)?.error
                            throw IllegalStateException(err ?: "Failed to create peer")
                        }
                        ApiClient.parse<PeerResponse>(res)
                            ?: throw IllegalStateException("Invalid VPN server response")
                    }
                    generated to createdPeer
                }

                val config = buildWireGuardConfig(context, peer, keyPair)
                val intent = Intent(context, VeritasVpnService::class.java).apply {
                    action = VeritasVpnService.ACTION_CONNECT
                    putExtra(VeritasVpnService.EXTRA_CONFIG, config)
                }
                currentPeerId = peer.peerId
                VpnSettings.setCurrentPeerId(context, peer.peerId)
                peer.dnsServer?.trim()?.takeIf { it.isNotEmpty() }?.let { onDnsGateway?.invoke(it) }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                if (e is SessionExpiredException) {
                    onSessionExpired?.invoke()
                    return@launch
                }
                setConnecting(false)
                setStatus(e.message?.takeIf { it.isNotBlank() }
                    ?: "Connection failed. Check your network and try again.")
                onFailure?.invoke()
            }
        }
    }

    private fun buildWireGuardConfig(context: Context, peer: PeerResponse, keyPair: KeyPair): String {
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
