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
import android.provider.Settings
import android.content.pm.PackageManager
import android.location.LocationManager
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
import cloud.veritasvpn.billing.BillingRepository
import cloud.veritasvpn.ui.AuthScreen
import cloud.veritasvpn.ui.DashboardScreen
import cloud.veritasvpn.ui.DevicesScreen
import cloud.veritasvpn.ui.PlansScreen
import cloud.veritasvpn.ui.PortForwardsScreen
import cloud.veritasvpn.ui.PaymentCheckoutScreen
import cloud.veritasvpn.ui.TunnelSettingsScreen
import cloud.veritasvpn.ui.theme.VeritasVPNTheme
import cloud.veritasvpn.vpn.VeritasVpnService
import cloud.veritasvpn.vpn.VpnSettings
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
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
    val prefs = context.getSharedPreferences(BILLING_CACHE_PREFS, Context.MODE_PRIVATE)
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
    context.getSharedPreferences(BILLING_CACHE_PREFS, Context.MODE_PRIVATE)
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
                val billingRepo = remember { BillingRepository() }
                var connected by remember { mutableStateOf(false) }
                var connecting by remember { mutableStateOf(false) }
                var statusMsg by remember { mutableStateOf<String?>(null) }
                var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                var showPlans by remember { mutableStateOf(false) }
                var showDevices by remember { mutableStateOf(false) }
                var showPortForwards by remember { mutableStateOf(false) }
                var showTunnelSettings by remember { mutableStateOf(false) }
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
                var excludeLan by remember { mutableStateOf(VpnSettings.excludeLan(context)) }
                var bypassAppsText by remember {
                    mutableStateOf(VpnSettings.bypassApps(context).joinToString("\n"))
                }
                var billingStatus by remember { mutableStateOf<BillingStatus?>(null) }
                var billingRefreshing by remember { mutableStateOf(false) }
                var cancellationInProgress by remember { mutableStateOf(false) }
                var billingError by remember { mutableStateOf<String?>(null) }
                var checkoutMethod by remember { mutableStateOf<String?>(null) }
                var checkoutUrl by remember { mutableStateOf<String?>(null) }

                fun requireBillingToken(): String {
                    authRepo.getAccessToken()?.takeIf { it.isNotBlank() }?.let { return it }
                    if (authRepo.refreshSession()) {
                        return authRepo.getAccessToken()
                            ?: throw IllegalStateException("Your session expired. Sign in again.")
                    }
                    throw IllegalStateException("Your session expired. Sign in again.")
                }

                fun refreshBilling() {
                    if (user == null || billingRefreshing) return
                    billingRefreshing = true
                    billingError = null
                    scope.launch {
                        try {
                            val status = withTimeout(8_000) {
                                withContext(Dispatchers.IO) {
                                    billingRepo.status(requireBillingToken())
                                }
                            }
                            billingStatus = status
                            writeCachedBillingStatus(context, user!!.accountId, status)
                            billingError = null
                        } catch (e: Exception) {
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
                                authRepo.refreshSession()
                                val token = authRepo.getAccessToken()
                                    ?: throw IllegalStateException("Not signed in")
                                ApiClient.get("/api/v1/wg/peers", token).use { res ->
                                    if (!res.isSuccessful) {
                                        throw IllegalStateException("Could not load devices (${res.code})")
                                    }
                                    ApiClient.parse<PeerListResponse>(res)?.peers.orEmpty()
                                }
                            }
                            devices = list
                        } catch (e: Exception) {
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
                                authRepo.refreshSession()
                                val token = authRepo.getAccessToken()
                                    ?: throw IllegalStateException("Not signed in")
                                val peersResult = ApiClient.get("/api/v1/wg/peers", token).use { res ->
                                    if (!res.isSuccessful) {
                                        throw IllegalStateException("Could not load devices (${res.code})")
                                    }
                                    ApiClient.parse<PeerListResponse>(res)?.peers.orEmpty()
                                }
                                val forwardsResult = ApiClient.get("/api/v1/wg/port-forwards", token).use { res ->
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
                        } finally {
                            portForwardsLoading = false
                        }
                    }
                }

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
                }

                fun performLocalSignOut() {
                    disconnectVpnService()
                    peerIdForDisconnect()
                    authRepo.signOut()
                    billingStatus = null
                    showPlans = false
                    showDevices = false
                    showPortForwards = false
                    showTunnelSettings = false
                    user = null
                }

                fun startCheckout(paymentMethod: String, planId: String) {
                    if (checkoutMethod != null) return
                    checkoutMethod = paymentMethod
                    billingError = null
                    scope.launch {
                        try {
                            val createdCheckoutUrl = withContext(Dispatchers.IO) {
                                billingRepo.createCheckout(requireBillingToken(), paymentMethod, planId)
                            }
                            checkoutUrl = createdCheckoutUrl
                        } catch (e: Exception) {
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

                LaunchedEffect(connecting) {
                    if (connecting) {
                        // The service has a bounded validation/recovery window;
                        // clean up the server peer if Android never reports a result.
                        kotlinx.coroutines.delay(25_000)
                        if (connecting) {
                            val timedOutPeerId = peerIdForDisconnect()
                            runCatching {
                                context.startService(
                                    Intent(context, VeritasVpnService::class.java).apply {
                                        action = VeritasVpnService.ACTION_DISCONNECT
                                    }
                                )
                            }
                            if (timedOutPeerId != null) {
                                peerCleanupJob = scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val token = authRepo.getAccessToken()
                                            ?: return@runCatching
                                        ApiClient.delete(
                                            "/api/v1/wg/peers/$timedOutPeerId", token
                                        ).close()
                                    }
                                }
                            }
                            connecting = false
                            statusMsg = "Connection timed out. Check your network and try again."
                        }
                    }
                }
                LaunchedEffect(checkoutUrl) {
                    while (checkoutUrl != null && user != null) {
                        kotlinx.coroutines.delay(3000)
                        try {
                            val status = withTimeout(7_000) {
                                withContext(Dispatchers.IO) {
                                    billingRepo.status(requireBillingToken())
                                }
                            }
                            billingStatus = status
                            writeCachedBillingStatus(context, user!!.accountId, status)
                            if (status.isPremium) {
                                checkoutUrl = null
                                billingError = null
                            }
                        } catch (_: Exception) { }
                    }
                }

                fun cancelSubscription() {
                    if (cancellationInProgress) return
                    cancellationInProgress = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                billingRepo.cancel(requireBillingToken())
                            }
                            billingStatus = withContext(Dispatchers.IO) {
                                billingRepo.status(requireBillingToken())
                            }
                        } catch (e: Exception) {
                            billingError = e.message ?: "Could not cancel your subscription."
                        } finally { cancellationInProgress = false }
                    }
                }

                var pendingNotificationStart by remember { mutableStateOf(false) }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    val shouldStart = pendingNotificationStart
                    pendingNotificationStart = false
                    if (shouldStart) {
                        startConnection(
                            context, scope,
                            setStatus = { msg -> statusMsg = msg },
                            setConnecting = { connecting = it }
                        )
                    }
                }

                fun startAfterPermissions() {
                    val notificationManager =
                        context.getSystemService(NotificationManager::class.java)
                    val permissionPrefs = context.getSharedPreferences(
                        "veritasvpn_permissions",
                        Context.MODE_PRIVATE
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
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } else {
                        startConnection(
                            context, scope,
                            setStatus = { msg -> statusMsg = msg },
                            setConnecting = { connecting = it }
                        )
                    }
                }

                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        statusMsg = null
                        startAfterPermissions()
                    } else {
                        connecting = false
                        statusMsg = "VPN permission not granted."
                    }
                }

                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) requestDeviceLocation(context) { deviceLocation = it }
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
                                    connected = intent.getBooleanExtra(VeritasVpnService.EXTRA_CONNECTED, false)
                                    connecting = false
                                    if (!connected) {
                                        rxBytes = 0
                                        txBytes = 0
                                        handshakeMs = 0
                                        dnsBlockedCount = null
                                    }
                                    statusMsg = if (connected) {
                                        null
                                    } else {
                                        val error = intent.getStringExtra(VeritasVpnService.EXTRA_ERROR)
                                        if (error != null) {
                                            val failedPeerId = peerIdForDisconnect()
                                            if (failedPeerId != null) {
                                                peerCleanupJob = scope.launch(Dispatchers.IO) {
                                                    runCatching {
                                                        val token = authRepo.getAccessToken()
                                                            ?: return@runCatching
                                                        ApiClient.delete(
                                                            "/api/v1/wg/peers/$failedPeerId", token
                                                        ).close()
                                                    }
                                                }
                                            }
                                        }
                                        error
                                    }
                                }
                                VeritasVpnService.ACTION_STATS -> {
                                    rxBytes = intent.getLongExtra(VeritasVpnService.EXTRA_RX_BYTES, 0L)
                                    txBytes = intent.getLongExtra(VeritasVpnService.EXTRA_TX_BYTES, 0L)
                                    handshakeMs = intent.getLongExtra(VeritasVpnService.EXTRA_HANDSHAKE_MS, 0L)
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
                        },
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose { context.unregisterReceiver(receiver) }
                }

                LaunchedEffect(connected, user?.accountId) {
                    if (!connected || user == null) {
                        dnsBlockedCount = null
                        return@LaunchedEffect
                    }
                    while (isActive && connected) {
                        val peerId = currentPeerId ?: VpnSettings.currentPeerId(context)
                        if (peerId != null) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    authRepo.refreshSession()
                                    val token = authRepo.getAccessToken() ?: return@withContext null
                                    ApiClient.get("/api/v1/wg/peers", token).use { res ->
                                        if (!res.isSuccessful) return@use null
                                        ApiClient.parse<PeerListResponse>(res)
                                            ?.peers
                                            ?.firstOrNull { it.id == peerId }
                                            ?.dnsBlockedCount
                                    }
                                }
                            }.onSuccess { count ->
                                if (count != null) dnsBlockedCount = count
                            }
                        }
                        delay(5_000)
                    }
                }

                fun requestConnect() {
                    if (connecting || connected) return
                    if (billingStatus?.isPremium != true) {
                        statusMsg = "An active subscription is required. Open Plans to subscribe."
                        return
                    }
                    connecting = true
                    VpnService.prepare(context)?.let { consentIntent ->
                        vpnPermissionLauncher.launch(consentIntent)
                        return
                    }
                    startAfterPermissions()
                }

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
                                        disconnectVpnService()
                                        peerIdForDisconnect()
                                    }
                                    withContext(Dispatchers.IO) {
                                        authRepo.refreshSession()
                                        val token = authRepo.getAccessToken()
                                            ?: throw IllegalStateException("Not signed in")
                                        ApiClient.delete("/api/v1/wg/peers/${peer.id}", token).use { res ->
                                            if (!res.isSuccessful) {
                                                throw IllegalStateException("Revoke failed (${res.code})")
                                            }
                                        }
                                    }
                                    devices = devices.filterNot { it.id == peer.id }
                                } catch (e: Exception) {
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
                                        authRepo.refreshSession()
                                        val token = authRepo.getAccessToken()
                                            ?: throw IllegalStateException("Not signed in")
                                        val body = mutableMapOf<String, Any>(
                                            "peer_id" to peerId,
                                            "protocol" to protocol,
                                            "external_port" to externalPort
                                        )
                                        if (internalPort != null) body["internal_port"] = internalPort
                                        ApiClient.post("/api/v1/wg/port-forwards", body, token).use { res ->
                                            val parsed = ApiClient.parse<PortForwardInfo>(res)
                                            if (!res.isSuccessful) {
                                                throw IllegalStateException(parsed?.error ?: "Could not create port forward (${res.code})")
                                            }
                                            parsed ?: throw IllegalStateException("Empty create response")
                                        }
                                    }
                                    portForwards = listOf(created) + portForwards.filterNot { it.id == created.id }
                                } catch (e: Exception) {
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
                                        authRepo.refreshSession()
                                        val token = authRepo.getAccessToken()
                                            ?: throw IllegalStateException("Not signed in")
                                        ApiClient.delete("/api/v1/wg/port-forwards/${pf.id}", token).use { res ->
                                            if (!res.isSuccessful) {
                                                val err = ApiClient.parse<PortForwardInfo>(res)?.error
                                                throw IllegalStateException(err ?: "Delete failed (${res.code})")
                                            }
                                        }
                                    }
                                    portForwards = portForwards.filterNot { it.id == pf.id }
                                } catch (e: Exception) {
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
                            statusMsg = null
                            val disconnectedPeerId = peerIdForDisconnect()
                            disconnectVpnService()
                            peerCleanupJob = scope.launch(Dispatchers.IO) {
                                try {
                                    val token = authRepo.getAccessToken()
                                    if (token != null && disconnectedPeerId != null) {
                                        ApiClient.delete(
                                            "/api/v1/wg/peers/$disconnectedPeerId", token
                                        ).close()
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        onSignOut = { performLocalSignOut() },
                        onSignOutEverywhere = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        authRepo.logoutAllSessions()
                                    }
                                } catch (_: Exception) {
                                    // Still clear local auth even if the API call fails.
                                    authRepo.signOut()
                                }
                                disconnectVpnService()
                                peerIdForDisconnect()
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
                        onKillSwitchSettings = {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        },
                        isPremium = billingStatus?.isPremium == true,
                        billingReady = billingStatus != null,
                        statusMsg = statusMsg,
                        deviceLatitude = deviceLocation?.first,
                        deviceLongitude = deviceLocation?.second,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        handshakeMs = handshakeMs,
                        dnsBlockedCount = dnsBlockedCount
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
        setConnecting: (Boolean) -> Unit
    ) {
        if (currentPeerId != null) return
        setStatus("Connecting...")
        scope.launch {
            try {
                // Do not create a replacement peer until the previous DELETE has
                // completed; the API upserts by account/server and an old
                // asynchronous cleanup could otherwise remove the new peer.
                peerCleanupJob?.join()
                val (keyPair, peer) = withContext(Dispatchers.IO) {
                    authRepo.refreshSession()
                    val token = authRepo.getAccessToken()
                        ?: throw IllegalStateException("Not signed in")
                    val generated = KeyPair()
                    val createdPeer = ApiClient.post(
                        "/api/v1/wg/peers",
                        mapOf("public_key" to generated.publicKey.toBase64()),
                        token
                    ).use { res ->
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
                context.startForegroundService(intent)
            } catch (e: Exception) {
                setConnecting(false)
                setStatus(e.message?.takeIf { it.isNotBlank() }
                    ?: "Connection failed. Check your network and try again.")
            }
        }
    }

    private fun buildWireGuardConfig(context: Context, peer: PeerResponse, keyPair: KeyPair): String {
        val dns = peer.dnsServer ?: "1.1.1.1"
        val serverAllowed = peer.clientAllowedIps ?: peer.allowedIps ?: listOf("0.0.0.0/0")
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
