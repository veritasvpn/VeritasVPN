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
import cloud.veritasvpn.api.PeerResponse
import cloud.veritasvpn.auth.AuthRepository
import cloud.veritasvpn.billing.BillingRepository
import cloud.veritasvpn.ui.AuthScreen
import cloud.veritasvpn.ui.DashboardScreen
import cloud.veritasvpn.ui.PlansScreen
import cloud.veritasvpn.ui.PaymentCheckoutScreen
import cloud.veritasvpn.ui.theme.VeritasVPNTheme
import cloud.veritasvpn.vpn.VeritasVpnService
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var authRepo: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authRepo = AuthRepository(this)

        setContent {
            VeritasVPNTheme {
                var user by remember { mutableStateOf(authRepo.getStoredUser()) }
                var connected by remember { mutableStateOf(false) }
                var connecting by remember { mutableStateOf(false) }
                var statusMsg by remember { mutableStateOf<String?>(null) }
                var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                var showPlans by remember { mutableStateOf(false) }
                var billingStatus by remember { mutableStateOf<BillingStatus?>(null) }
                var billingLoading by remember { mutableStateOf(false) }
                var billingError by remember { mutableStateOf<String?>(null) }
                var checkoutMethod by remember { mutableStateOf<String?>(null) }
                var checkoutUrl by remember { mutableStateOf<String?>(null) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val billingRepo = remember { BillingRepository() }

                fun refreshBilling() {
                    if (user == null || billingLoading) return
                    billingLoading = true
                    billingError = null
                    scope.launch {
                        try {
                            billingStatus = withContext(Dispatchers.IO) {
                                val token = authRepo.getAccessToken()
                                    ?: throw IllegalStateException("Your session expired. Sign in again.")
                                billingRepo.status(token)
                            }
                        } catch (e: Exception) {
                            billingError = e.message ?: "Could not load your plan."
                        } finally {
                            billingLoading = false
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
                                authRepo.refreshSession()
                                val token = authRepo.getAccessToken()
                                    ?: throw IllegalStateException("Your session expired. Sign in again.")
                                billingRepo.createCheckout(token, paymentMethod, planId)
                            }
                            checkoutUrl = createdCheckoutUrl
                        } catch (e: Exception) {
                            billingError = e.message ?: "Could not open checkout."
                        } finally {
                            checkoutMethod = null
                        }
                    }
                }

                LaunchedEffect(user, showPlans) {
                    if (user != null) refreshBilling()
                }

                LaunchedEffect(connecting) {
                    if (connecting) {
                        kotlinx.coroutines.delay(45_000)
                        if (connecting) {
                            runCatching {
                                context.startService(
                                    Intent(context, VeritasVpnService::class.java).apply {
                                        action = VeritasVpnService.ACTION_DISCONNECT
                                    }
                                )
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
                            val status = withContext(Dispatchers.IO) {
                                authRepo.refreshSession()
                                val token = authRepo.getAccessToken() ?: return@withContext null
                                billingRepo.status(token)
                            }
                            if (status != null) {
                                billingStatus = status
                                if (status.isPremium) {
                                    checkoutUrl = null
                                    billingError = null
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }

                fun cancelSubscription() {
                    if (billingLoading) return
                    billingLoading = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                authRepo.refreshSession()
                                val token = authRepo.getAccessToken() ?: throw IllegalStateException("Your session expired. Sign in again.")
                                billingRepo.cancel(token)
                            }
                            billingStatus = withContext(Dispatchers.IO) {
                                val token = authRepo.getAccessToken() ?: throw IllegalStateException("Your session expired. Sign in again.")
                                billingRepo.status(token)
                            }
                        } catch (e: Exception) {
                            billingError = e.message ?: "Could not cancel your subscription."
                        } finally { billingLoading = false }
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
                            if (intent?.action != VeritasVpnService.ACTION_STATE) return
                            connected = intent.getBooleanExtra(VeritasVpnService.EXTRA_CONNECTED, false)
                            connecting = false
                            statusMsg = if (connected) {
                                null
                            } else {
                                val error = intent.getStringExtra(VeritasVpnService.EXTRA_ERROR)
                                if (error != null) {
                                    val failedPeerId = peerIdForDisconnect()
                                    if (failedPeerId != null) {
                                        scope.launch(Dispatchers.IO) {
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
                    }
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(VeritasVpnService.ACTION_STATE),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose { context.unregisterReceiver(receiver) }
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
                        billingLoading = false
                        user = authRepo.getStoredUser()
                    })
                } else if (checkoutUrl != null) {
                    PaymentCheckoutScreen(
                        checkoutUrl = checkoutUrl!!,
                        onClose = { checkoutUrl = null; refreshBilling() },
                        onRefreshPlan = { refreshBilling() }
                    )
                } else if (showPlans) {
                    PlansScreen(
                        billingStatus = billingStatus,
                        loading = billingLoading,
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
                            val intent = Intent(context, VeritasVpnService::class.java).apply {
                                action = VeritasVpnService.ACTION_DISCONNECT
                            }
                            context.startService(intent)
                            connected = false
                            scope.launch(Dispatchers.IO) {
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
                        onSignOut = {
                            val intent = Intent(context, VeritasVpnService::class.java).apply {
                                action = VeritasVpnService.ACTION_DISCONNECT
                            }
                            context.startService(intent)
                            connected = false
                            authRepo.signOut()
                            billingStatus = null
                            showPlans = false
                            user = null
                        },
                        onPlans = { showPlans = true },
                        onKillSwitchSettings = {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        },
                        isPremium = billingStatus?.isPremium == true,
                        billingReady = billingStatus != null && !billingLoading,
                        statusMsg = statusMsg,
                        deviceLatitude = deviceLocation?.first,
                        deviceLongitude = deviceLocation?.second
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
        val id = currentPeerId
        currentPeerId = null
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

                val config = buildWireGuardConfig(peer, keyPair)
                val intent = Intent(context, VeritasVpnService::class.java).apply {
                    action = VeritasVpnService.ACTION_CONNECT
                    putExtra(VeritasVpnService.EXTRA_CONFIG, config)
                }
                currentPeerId = peer.peerId
                context.startForegroundService(intent)
            } catch (e: Exception) {
                setConnecting(false)
                setStatus(e.message?.takeIf { it.isNotBlank() }
                    ?: "Connection failed. Check your network and try again.")
            }
        }
    }

    private fun buildWireGuardConfig(peer: PeerResponse, keyPair: KeyPair): String {
        val dns = peer.dnsServer ?: "1.1.1.1"
        val allowed = (peer.clientAllowedIps ?: peer.allowedIps ?: listOf("0.0.0.0/0", "::/0"))
            .joinToString(",")
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${keyPair.privateKey.toBase64()}")
            appendLine("Address = ${peer.assignedIp}")
            appendLine("DNS = $dns")
            appendLine("MTU = 1420")
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
