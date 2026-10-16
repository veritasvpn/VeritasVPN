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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.api.PurchaseHistoryItem
import cloud.veritasvpn.api.PeerListResponse
import cloud.veritasvpn.api.PeerResponse
import cloud.veritasvpn.auth.AuthRepository
import cloud.veritasvpn.auth.AuthenticatedApi
import cloud.veritasvpn.auth.ControlAccess
import cloud.veritasvpn.auth.ControlRefresh
import cloud.veritasvpn.auth.SessionExpiredException
import cloud.veritasvpn.BuildConfig
import cloud.veritasvpn.billing.BillingRepository
import cloud.veritasvpn.billing.PlanEntitlement
import cloud.veritasvpn.billing.StoreBillingCallbacks
import cloud.veritasvpn.billing.createStoreBilling
import java.io.IOException
import cloud.veritasvpn.support.ConnectTiming
import cloud.veritasvpn.support.DiagnosticLog
import cloud.veritasvpn.support.SupportLinks
import cloud.veritasvpn.support.isRecordableError
import cloud.veritasvpn.support.sanitizeError
import cloud.veritasvpn.ui.AuthScreen
import cloud.veritasvpn.ui.CircleDisconnect
import cloud.veritasvpn.ui.SignInAgainSignal
import cloud.veritasvpn.vpn.ControlPlane
import cloud.veritasvpn.vpn.ProvisionRoute
import cloud.veritasvpn.vpn.EndpointRefresh
import cloud.veritasvpn.vpn.PeerCheckLog
import cloud.veritasvpn.vpn.ReprovisionFlight
import cloud.veritasvpn.ui.VpnDisclosureScreen
import cloud.veritasvpn.ui.DashboardScreen
import cloud.veritasvpn.ui.DisconnectUi
import cloud.veritasvpn.ui.DiagnosticsScreen
import cloud.veritasvpn.ui.HelpScreen
import cloud.veritasvpn.ui.ReleaseLockdownDialog
import cloud.veritasvpn.ui.AccountScreen
import cloud.veritasvpn.ui.ExternalCheckout
import cloud.veritasvpn.ui.ShieldSettingsScreen
import cloud.veritasvpn.ui.StealthSettingsScreen
import cloud.veritasvpn.ui.TunnelSettingsScreen
import cloud.veritasvpn.ui.OnboardingGate
import cloud.veritasvpn.ui.theme.VeritasVPNTheme
import cloud.veritasvpn.vpn.ReconnectDecisions
import cloud.veritasvpn.vpn.VeritasVpnService
import cloud.veritasvpn.vpn.VpnKillSwitch
import cloud.veritasvpn.vpn.VpnSettings
import cloud.veritasvpn.vpn.WireGuardSession
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
        isPremium = prefs.getBoolean(billingCacheKey(accountId, "premium"), false),
        paymentState = prefs.getString(billingCacheKey(accountId, "payment_state"), "none") ?: "none",
        paymentMessage = prefs.getString(billingCacheKey(accountId, "payment_message"), null),
        pollAfterSeconds = prefs.getInt(billingCacheKey(accountId, "poll_after_seconds"), 0),
        payments = readCachedPurchaseHistory(prefs, accountId)
    )
}

private fun readCachedPurchaseHistory(prefs: android.content.SharedPreferences, accountId: String): List<PurchaseHistoryItem>? {
    val key = billingCacheKey(accountId, "payments")
    if (!prefs.contains(key)) return null
    val raw = prefs.getString(key, null) ?: return null
    return runCatching {
        ApiClient.gson.fromJson(raw, Array<PurchaseHistoryItem>::class.java)?.toList() ?: emptyList()
    }.getOrNull()
}

private fun writeCachedBillingStatus(
    context: Context,
    accountId: String,
    status: BillingStatus
) {
    val editor = SecurePrefs.open(context, BILLING_CACHE_PREFS)
        .edit()
        .putString(billingCacheKey(accountId, "tier"), status.tier)
        .putString(billingCacheKey(accountId, "status"), status.status)
        .putString(billingCacheKey(accountId, "payment_method"), status.paymentMethod)
        .putString(billingCacheKey(accountId, "period_end"), status.currentPeriodEnd)
        .putBoolean(billingCacheKey(accountId, "cancel_at_end"), status.cancelAtPeriodEnd)
        .putBoolean(billingCacheKey(accountId, "premium"), status.isPremium)
        .putString(billingCacheKey(accountId, "payment_state"), status.paymentState)
        .putString(billingCacheKey(accountId, "payment_message"), status.paymentMessage)
        .putInt(billingCacheKey(accountId, "poll_after_seconds"), status.pollAfterSeconds)
    val payments = status.payments
    if (payments != null) {
        editor.putString(billingCacheKey(accountId, "payments"), ApiClient.gson.toJson(payments))
    }
    editor.apply()
}

private fun clearBillingCache(context: Context) {
    SecurePrefs.open(context, BILLING_CACHE_PREFS).edit().clear().apply()
}

class MainActivity : ComponentActivity() {
    private lateinit var authRepo: AuthRepository
    private var peerCleanupJob: Job? = null
    private var reconnectJob: Job? = null
    private var billingReturnVersion by mutableIntStateOf(0)

    private fun handleBillingReturn(intent: Intent?) {
        val uri = intent?.data ?: return
        val isCustomSchemeReturn =
            uri.scheme == "veritasvpn" && uri.host == "billing" && uri.path == "/success"
        val isVerifiedAppLinkReturn =
            uri.scheme == "https" && uri.host == "veritasvpn.cloud" && uri.path == "/billing/app-return"
        if (isCustomSchemeReturn || isVerifiedAppLinkReturn) {
            // The deep link is intentionally data-free. Entitlement is always
            // re-read from the authenticated billing API before the UI changes.
            billingReturnVersion += 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authRepo = AuthRepository(this)
        currentPeerId = VpnSettings.currentPeerId(this)
        handleBillingReturn(intent)

        setContent {
            VeritasVPNTheme {
                var user by remember { mutableStateOf(authRepo.getStoredUser()) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val billingRepo = remember { BillingRepository(authRepo) }
                val storeBilling = remember { createStoreBilling(context.applicationContext, billingRepo) }
                DisposableEffect(storeBilling) {
                    storeBilling.connect()
                    onDispose { storeBilling.close() }
                }
                val restoringSavedVpnSession = remember(context) {
                    VeritasVpnService.hasSavedSession(context)
                }
                var connected by remember { mutableStateOf(false) }
                var connecting by remember { mutableStateOf(restoringSavedVpnSession) }
                var reconnecting by remember { mutableStateOf(false) }
                var userWantsConnected by remember { mutableStateOf(restoringSavedVpnSession) }
                var hadEstablishedSession by remember { mutableStateOf(restoringSavedVpnSession) }
                var reconnectAttempt by remember { mutableStateOf(0) }
                var hardReconnectRequested by remember { mutableStateOf(false) }
                var statusMsg by remember {
                    mutableStateOf(
                        if (restoringSavedVpnSession) "Restoring secure connection…" else null
                    )
                }
                var showPlans by remember { mutableStateOf(false) }
                var showVpnDisclosure by remember { mutableStateOf(false) }
                var showOnboardingDisclosure by rememberSaveable { mutableStateOf(false) }
                var onboardingStep by rememberSaveable { mutableStateOf(OnboardingGate.Step.SKIP) }
                var onboardingConsentIntent by remember { mutableStateOf<Intent?>(null) }
                var deletingAccount by remember { mutableStateOf(false) }
                var deleteAccountError by remember { mutableStateOf<String?>(null) }
                var showStealthSettings by remember { mutableStateOf(false) }
                var showShieldSettings by remember { mutableStateOf(false) }
                var showTunnelSettings by remember { mutableStateOf(false) }
                var showHelp by remember { mutableStateOf(false) }
                var showDiagnostics by remember { mutableStateOf(false) }
                var shareDiagnostics by remember { mutableStateOf(VpnSettings.shareDiagnostics(context)) }
                var lastError by remember { mutableStateOf(VpnSettings.lastError(context)) }
                var showKillSwitchRequired by remember { mutableStateOf(false) }
                var pendingConnectAfterKillSwitch by remember { mutableStateOf(false) }
                var showReleaseLockdown by remember { mutableStateOf(false) }
                var awaitingLockdownRelease by remember { mutableStateOf(false) }
                var releaseLockdownError by remember { mutableStateOf<String?>(null) }
                var lockdownEnabled by remember { mutableStateOf(false) }
                val killSwitchRecheckGeneration = remember { intArrayOf(0) }
                var awaitingVpnConsent by remember { mutableStateOf(false) }
                var rxBytes by remember { mutableStateOf(0L) }
                var txBytes by remember { mutableStateOf(0L) }
                var handshakeMs by remember { mutableStateOf(0L) }
                var handshakeOk by remember { mutableStateOf(false) }
                var signInAgain by remember { mutableStateOf(false) }
                var reauthenticate by remember { mutableStateOf(false) }
                var awaitingAuthClear by remember { mutableStateOf(false) }
                var dnsBlockedCount by remember { mutableStateOf<Long?>(null) }
                var dnsBlockedBaseline by remember { mutableStateOf<Long?>(null) }
                var dnsGateway by remember { mutableStateOf<String?>(null) }
                var excludeLan by remember { mutableStateOf(VpnSettings.excludeLan(context)) }
                var bypassApps by remember { mutableStateOf(VpnSettings.bypassApps(context)) }
                var stealthMode by remember { mutableStateOf(VpnSettings.stealthMode(context)) }
                var shieldPolicy by remember { mutableStateOf(VpnSettings.shieldPolicy(context)) }
                var shieldError by remember { mutableStateOf<String?>(null) }
                val shieldWriteGeneration = remember { intArrayOf(0) }
                var appliedExcludeLan by remember { mutableStateOf(VpnSettings.excludeLan(context)) }
                var appliedBypassApps by remember { mutableStateOf(VpnSettings.bypassApps(context)) }
                var appliedStealthMode by remember { mutableStateOf(VpnSettings.stealthMode(context)) }
                var transport by remember { mutableStateOf("") }
                var billingStatus by remember { mutableStateOf<BillingStatus?>(null) }
                var billingSettled by remember { mutableStateOf(false) }
                var billingRefreshing by remember { mutableStateOf(false) }
                var cancellationInProgress by remember { mutableStateOf(false) }
                var billingError by remember { mutableStateOf<String?>(null) }
                var purchaseHistoryFailed by remember { mutableStateOf(false) }
                var checkoutMethod by remember { mutableStateOf<String?>(null) }
                var checkoutUrl by remember { mutableStateOf<String?>(null) }
                var playPurchasePending by remember { mutableStateOf(false) }
                var waitingForCheckoutSettlement by remember { mutableStateOf(false) }
                var trialOffers by remember { mutableStateOf<Map<String, cloud.veritasvpn.billing.TrialOffer>>(emptyMap()) }
                val observedBillingReturnVersion = billingReturnVersion

                fun disconnectVpnService() {
                    // startService, not startForegroundService. A foreground
                    // start that does not call startForeground in time is a
                    // process-killing RuntimeException on API 36.
                    VeritasVpnService.markSessionEnded()
                    runCatching {
                        context.startService(
                            Intent(context, VeritasVpnService::class.java).apply {
                                action = VeritasVpnService.ACTION_DISCONNECT
                            },
                        )
                    }
                    connected = false
                    connecting = false
                    reconnecting = false
                    rxBytes = 0
                    txBytes = 0
                    handshakeMs = 0
                    dnsBlockedCount = null
                    dnsBlockedBaseline = null
                    dnsGateway = null
                    transport = ""
                }

                fun cancelReconnect() {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    connectJob?.cancel()
                    connectJob = null
                    reconnecting = false
                    hardReconnectRequested = false
                    reconnectAttempt = 0
                }

                fun deletePeerBestEffort(peerId: String?) {
                    VpnSettings.clearSession(context)
                    if (peerId.isNullOrBlank()) return
                    // Capture the token before a local sign-out clears secure storage.
                    // Peer revocation is best-effort and must never hold up returning the
                    // user to the sign-in screen.
                    val accessToken = authRepo.getAccessToken()?.takeIf { it.isNotBlank() } ?: return
                    peerCleanupJob = scope.launch(Dispatchers.IO) {
                        try {
                            // The normal client's pooled socket is blackholed once
                            // lockdown keeps the interface and userspace is off.
                            // Skip the delete rather than wait out that read.
                            // The next peer POST upserts this device.
                            val held = VeritasVpnService.interfaceHeld()
                            val protected = if (held) VeritasVpnService.provisioningClient() else null
                            if (held && protected == null) return@launch
                            ApiClient.delete(
                                "/api/v1/wg/peers/$peerId",
                                accessToken,
                                httpClient = protected,
                            ).close()
                        } catch (_: Exception) {
                        }
                    }
                }

                fun clearLocalSessionUi() {
                    authRepo.signOut()
                    billingStatus = null
                    billingSettled = false
                    checkoutUrl = null
                    billingError = null
                    purchaseHistoryFailed = false
                    checkoutMethod = null
                    playPurchasePending = false
                    showPlans = false
                    showStealthSettings = false
                    showShieldSettings = false
                    showTunnelSettings = false
                    showHelp = false
                    showDiagnostics = false
                    user = null
                }

                fun noteIntentionalDisconnect() {
                    // The circle tap stays in the app. Always-on cannot be
                    // cleared from here. Do not read lockdown with
                    // VpnService.prepare, and do not open system settings.
                    val presentation = DisconnectUi.afterCircleTap(lockdownEnabled)
                    showReleaseLockdown = false
                    awaitingLockdownRelease = false
                    releaseLockdownError = null
                    if (!presentation.leavesApp && !presentation.callsPrepare && !presentation.launchSettings) {
                        statusMsg = presentation.note
                    }
                }

                fun performLocalSignOut() {
                    userWantsConnected = false
                    hadEstablishedSession = false
                    cancelReconnect()
                    deletePeerBestEffort(peerIdForDisconnect())
                    disconnectVpnService()
                    // Local sign-out must be immediate. Network cleanup continues in the
                    // background so a delayed request cannot leave the app authenticated.
                    clearLocalSessionUi()
                    noteIntentionalDisconnect()
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

                fun refreshBilling(force: Boolean = false) {
                    if (user == null || (billingRefreshing && !force)) return
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
                            billingSettled = true
                            writeCachedBillingStatus(context, user!!.accountId, status)
                            billingError = null
                            purchaseHistoryFailed = false
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) {
                                handleSessionExpired()
                                return@launch
                            }
                            // A failed read is not a free plan. Installing the
                            // default status makes the home say Premium required
                            // before the account endpoint has answered.
                            if (billingStatus == null) {
                                billingError = cloud.veritasvpn.api.UserFacingError.toUserMessage(e, context)
                            }
                            if (billingStatus?.payments == null) purchaseHistoryFailed = true
                        } finally {
                            billingRefreshing = false
                        }
                    }
                }

                fun storeCallbacks(): StoreBillingCallbacks = object : StoreBillingCallbacks {
                    override fun onCheckoutUrl(url: String) {
                        checkoutUrl = url
                        checkoutMethod = null
                    }

                    override fun onPending(message: String) {
                        playPurchasePending = true
                        billingError = null
                        checkoutMethod = null
                    }

                    override fun onVerified() {
                        playPurchasePending = false
                        checkoutMethod = null
                        billingError = null
                        refreshBilling(force = true)
                    }

                    override fun onError(message: String) {
                        playPurchasePending = false
                        checkoutMethod = null
                        billingError = message
                    }

                    override fun onSessionExpired() {
                        checkoutMethod = null
                        handleSessionExpired()
                    }
                }

                fun startStorePurchase(planId: String) {
                    val accountId = user?.accountId ?: return
                    if (checkoutMethod != null) return
                    val activity = context as? Activity ?: return
                    checkoutMethod = "store"
                    billingError = null
                    val trialAllowed = billingStatus?.trialEligible == true && trialOffers.containsKey(planId)
                    storeBilling.purchase(activity, planId, accountId, storeCallbacks(), trialAllowed)
                }

                LaunchedEffect(user?.accountId) {
                    if (user != null) {
                        // A cached free plan must not become "Premium required"
                        // before this sign-in's status read. A cached Premium
                        // plan can show immediately, then the read confirms it.
                        val cached = readCachedBillingStatus(context, user!!.accountId)
                        billingSettled = false
                        billingStatus = cached?.takeIf { PlanEntitlement.grantsPremium(it) }
                        billingRefreshing = false
                        refreshBilling()
                        if (BuildConfig.PLAY_BILLING) {
                            storeBilling.restore(user!!.accountId, storeCallbacks())
                        }
                    }
                }

                LaunchedEffect(observedBillingReturnVersion, user?.accountId) {
                    if (observedBillingReturnVersion > 0 && user != null) {
                        // A billing deep link is a native completion event, not
                        // a request to keep displaying the browser checkout.
                        checkoutUrl = null
                        waitingForCheckoutSettlement = true
                        billingError = null
                        showPlans = true
                        // Do not wait for the polling interval after BTCPay
                        // returns. Fetch the authoritative account status now.
                        refreshBilling(force = true)
                    }
                }

                val billingPollDelayMs = (
                    billingStatus?.pollAfterSeconds?.coerceIn(3, 30) ?: 3
                ) * 1000L
                val pendingPaymentNeedsPolling = billingStatus?.paymentState in setOf(
                    "awaiting_payment",
                    "awaiting_confirmation",
                    "checking"
                )

                LaunchedEffect(
                    checkoutUrl,
                    waitingForCheckoutSettlement,
                    pendingPaymentNeedsPolling,
                    billingPollDelayMs
                ) {
                    // A process/activity recreation can happen while Android is
                    // returning from BTCPay.  Continue the account-scoped
                    // reconciliation whenever the API says a payment is pending,
                    // rather than relying only on in-memory checkout state.
                    while (
                        (checkoutUrl != null || waitingForCheckoutSettlement || pendingPaymentNeedsPolling) &&
                            user != null
                    ) {
                        kotlinx.coroutines.delay(billingPollDelayMs)
                        try {
                            val status = withTimeout(7_000) {
                                withContext(Dispatchers.IO) {
                                    billingRepo.status()
                                }
                            }
                            billingStatus = status
                            billingSettled = true
                            writeCachedBillingStatus(context, user!!.accountId, status)
                            purchaseHistoryFailed = false
                            if (status.isPremium) {
                                checkoutUrl = null
                                waitingForCheckoutSettlement = false
                                showPlans = true
                                billingError = null
                            } else if (status.paymentState == "failed") {
                                waitingForCheckoutSettlement = false
                                billingError = status.paymentMessage
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
                            billingError = cloud.veritasvpn.api.UserFacingError.toUserMessage(e, context)
                        } finally { cancellationInProgress = false }
                    }
                }

                var pendingNotificationStart by remember { mutableStateOf(false) }
                var pendingNotificationReconnect by remember { mutableStateOf(false) }

                fun showKillSwitchBlock() {
                    pendingConnectAfterKillSwitch = true
                    showKillSwitchRequired = true
                    connecting = false
                    reconnecting = false
                    userWantsConnected = false
                    statusMsg = null
                }

                fun blockConnectForKillSwitch(): Boolean {
                    if (VpnKillSwitch.isLockdownEnabled(context, vpnPrepared = true)) {
                        showKillSwitchRequired = false
                        return false
                    }
                    showKillSwitchBlock()
                    return true
                }

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
                    if (!shouldStart) return@rememberLauncherForActivityResult
                    scope.launch {
                        val lockdownOn = withContext(Dispatchers.IO) {
                            VpnKillSwitch.isLockdownEnabled(context, allowPrepare = true)
                        }
                        if (!lockdownOn) {
                            showKillSwitchBlock()
                            return@launch
                        }
                        if (!userWantsConnected && !isReconnect) return@launch
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
                            },
                            shouldContinue = { userWantsConnected },
                        )
                    }
                }

                fun startVpnAfterPermissions(
                    isReconnect: Boolean = false,
                    lockdownVerified: Boolean = false,
                ) {
                    // VPN consent is already granted here. The tunnel still does
                    // not start until Always-on + Block connections without VPN
                    // are on for this package. Callers that just read lockdown
                    // off the UI thread pass lockdownVerified so this does not
                    // block the hero animation with another settings read.
                    if (!lockdownVerified && blockConnectForKillSwitch()) return
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
                            },
                            shouldContinue = { userWantsConnected },
                        )
                    }
                }

                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    awaitingVpnConsent = false
                    if (result.resultCode == Activity.RESULT_OK) {
                        statusMsg = null
                        // If we're in onboarding mode, just mark onboarding as done and go to dashboard.
                        // Don't start a connection.
                        if (showOnboardingDisclosure) {
                            VpnSettings.setOnboardingVpnDone(context, true)
                            showOnboardingDisclosure = false
                            onboardingConsentIntent = null
                            return@rememberLauncherForActivityResult
                        }
                        scope.launch {
                            val lockdownOn = withContext(Dispatchers.IO) {
                                VpnKillSwitch.isLockdownEnabled(context, vpnPrepared = true)
                            }
                            if (!userWantsConnected) return@launch
                            if (!lockdownOn) {
                                showKillSwitchBlock()
                                return@launch
                            }
                            startVpnAfterPermissions(lockdownVerified = true)
                        }
                    } else {
                        // If we're in onboarding mode, mark onboarding as done even on cancel.
                        // Don't nag the user; they can still connect later via the circle.
                        if (showOnboardingDisclosure) {
                            VpnSettings.setOnboardingVpnDone(context, true)
                            showOnboardingDisclosure = false
                            onboardingConsentIntent = null
                            return@rememberLauncherForActivityResult
                        }
                        connecting = false
                        reconnecting = false
                        userWantsConnected = false
                        statusMsg = "VPN permission not granted."
                    }
                }

                LaunchedEffect(connecting, awaitingVpnConsent) {
                    if (connecting && !awaitingVpnConsent) {
                        // First-connect only. Never timeout-disconnect an established session.
                        // The system VPN consent dialog is excluded: it can sit open while
                        // the user reads it, and it is not a tunnel attempt yet.
                        kotlinx.coroutines.delay(25_000)
                        val stableEstablished = VpnSettings.sessionEstablishedAt(context) > 0L
                        if (connecting && !hadEstablishedSession && !stableEstablished && !awaitingVpnConsent) {
                            connecting = false
                            userWantsConnected = false
                            statusMsg = EndpointRefresh.GIVE_UP_MESSAGE
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
                        } else if (connecting && (hadEstablishedSession || stableEstablished)) {
                            connecting = false
                            reconnecting = false
                            connected = true
                            hadEstablishedSession = true
                            statusMsg = null
                        }
                    }
                }

                DisposableEffect(context) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            when (intent?.action) {
                                VeritasVpnService.ACTION_STATE -> {
                                    val nowConnected =
                                        intent.getBooleanExtra(VeritasVpnService.EXTRA_CONNECTED, false)
                                    val error = intent.getStringExtra(VeritasVpnService.EXTRA_ERROR)
                                    val svcReconnecting =
                                        intent.getBooleanExtra(VeritasVpnService.EXTRA_RECONNECTING, false)
                                    val svcHandshakeOk =
                                        intent.getBooleanExtra(VeritasVpnService.EXTRA_HANDSHAKE_OK, false)
                                    val svcSignInAgain =
                                        intent.getBooleanExtra(VeritasVpnService.EXTRA_SIGN_IN_AGAIN, false)
                                    if (nowConnected) {
                                        if (!userWantsConnected) {
                                            // User already tapped Disconnect; ignore a late
                                            // service broadcast that would re-arm the session.
                                            return
                                        }
                                        connected = true
                                        connecting = false
                                        // The service's reconnecting flag drives the UI label,
                                        // but does not cancel the activity's reconnectJob logic.
                                        val signInView = SignInAgainSignal.merge(
                                            showing = signInAgain,
                                            awaitingClear = awaitingAuthClear,
                                            broadcast = svcSignInAgain,
                                        )
                                        signInAgain = signInView.showing
                                        awaitingAuthClear = signInView.awaitingClear
                                        reconnecting = svcReconnecting && !signInAgain
                                        hardReconnectRequested = false
                                        reconnectAttempt = 0
                                        reconnectJob?.cancel()
                                        reconnectJob = null
                                        userWantsConnected = true
                                        hadEstablishedSession = true
                                        // Copy the in-memory tunnel prefs. Re-opening
                                        // encrypted storage here runs on the main thread
                                        // and freezes the hero pulse until Protected paints.
                                        appliedExcludeLan = excludeLan
                                        appliedBypassApps = bypassApps
                                        appliedStealthMode = stealthMode
                                        intent.getStringExtra(VeritasVpnService.EXTRA_TRANSPORT)
                                            ?.let { transport = it }
                                        handshakeOk = svcHandshakeOk
                                        statusMsg = when {
                                            signInAgain -> ControlPlane.SIGN_IN_AGAIN
                                            svcReconnecting -> EndpointRefresh.statusWhileReconnecting(error)
                                            else -> null
                                        }
                                    } else if (error != null && error.contains("revoked", ignoreCase = true)) {
                                        connected = false
                                        connecting = false
                                        reconnecting = false
                                        signInAgain = false
                                        awaitingAuthClear = false
                                        userWantsConnected = false
                                        hadEstablishedSession = false
                                        transport = ""
                                        statusMsg = error
                                        peerIdForDisconnect()
                                    } else if (userWantsConnected && hadEstablishedSession) {
                                        // Ignore unintended disconnects — stay connected in UI.
                                        connected = true
                                        connecting = false
                                        reconnecting = svcReconnecting
                                        hardReconnectRequested = false
                                        statusMsg = if (svcReconnecting) {
                                            EndpointRefresh.statusWhileReconnecting(error)
                                        } else {
                                            null
                                        }
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
                                        transport = ""
                                        signInAgain = false
                                        awaitingAuthClear = false
                                        statusMsg = error ?: statusMsg?.takeIf { it == DisconnectUi.LOCKDOWN_NOTE }
                                    }
                                }
                                VeritasVpnService.ACTION_STATS -> {
                                    rxBytes = intent.getLongExtra(VeritasVpnService.EXTRA_RX_BYTES, 0L)
                                    txBytes = intent.getLongExtra(VeritasVpnService.EXTRA_TX_BYTES, 0L)
                                    handshakeMs = intent.getLongExtra(VeritasVpnService.EXTRA_HANDSHAKE_MS, 0L)
                                    if (intent.hasExtra(VeritasVpnService.EXTRA_HANDSHAKE_OK)) {
                                        handshakeOk = intent.getBooleanExtra(VeritasVpnService.EXTRA_HANDSHAKE_OK, false)
                                    }
                                    if (intent.hasExtra(VeritasVpnService.EXTRA_TRANSPORT)) {
                                        transport = intent.getStringExtra(VeritasVpnService.EXTRA_TRANSPORT).orEmpty()
                                    }
                                    if (intent.hasExtra(VeritasVpnService.EXTRA_SIGN_IN_AGAIN)) {
                                        val signInView = SignInAgainSignal.merge(
                                            showing = signInAgain,
                                            awaitingClear = awaitingAuthClear,
                                            broadcast = intent.getBooleanExtra(
                                                VeritasVpnService.EXTRA_SIGN_IN_AGAIN,
                                                false,
                                            ),
                                        )
                                        signInAgain = signInView.showing
                                        awaitingAuthClear = signInView.awaitingClear
                                        if (signInAgain) {
                                            reconnecting = false
                                            if (connected) statusMsg = ControlPlane.SIGN_IN_AGAIN
                                        } else if (statusMsg == ControlPlane.SIGN_IN_AGAIN) {
                                            statusMsg = null
                                        }
                                    }
                                    if (!signInAgain && intent.hasExtra(VeritasVpnService.EXTRA_RECONNECTING)) {
                                        val svcReconnecting = intent.getBooleanExtra(VeritasVpnService.EXTRA_RECONNECTING, false)
                                        reconnecting = svcReconnecting
                                        if (connected) {
                                            val notice = intent.getStringExtra(VeritasVpnService.EXTRA_ERROR)
                                            statusMsg = if (svcReconnecting) {
                                                EndpointRefresh.statusWhileReconnecting(notice)
                                            } else {
                                                null
                                            }
                                        }
                                    }
                                }
                                VeritasVpnService.ACTION_PEER_CHECK -> {
                                    val needsReconnect = intent.getBooleanExtra("needs_reconnect", false)
                                    if (needsReconnect && userWantsConnected && user != null) {
                                        Log.i("VeritasVPN", "peer check: needs reconnect, starting fresh connection")
                                        currentPeerId = null
                                        VpnSettings.setCurrentPeerId(context, null)
                                        scope.launch {
                                            startConnection(
                                                context, scope,
                                                setStatus = { msg -> statusMsg = msg },
                                                setConnecting = { connecting = it },
                                                isReconnect = true,
                                                onSessionExpired = { handleSessionExpired() },
                                                shouldContinue = { userWantsConnected },
                                            )
                                        }
                                    }
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
                        addAction(VeritasVpnService.ACTION_PEER_CHECK)
                    },
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    onDispose { context.unregisterReceiver(receiver) }
                }

                // The service's previous state broadcast may have occurred
                // while this Activity was closed. Ask it for the durable state
                // after registering the receiver so the dashboard cannot show
                // "Connect now" while Android still shows an active VPN.
                LaunchedEffect(restoringSavedVpnSession) {
                    if (restoringSavedVpnSession) {
                        context.startService(
                            Intent(context, VeritasVpnService::class.java).apply {
                                action = VeritasVpnService.ACTION_QUERY_STATE
                            }
                        )
                    }
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
                    if (!PlanEntitlement.grantsPremium(billingStatus)) {
                        statusMsg = "An active subscription is required. Open Plans to subscribe."
                        return
                    }
                    if (!VpnSettings.vpnDisclosureAccepted(context)) {
                        showVpnDisclosure = true
                        return
                    }
                    // Show the connecting hero before any binder or settings work.
                    // prepare() and the lockdown read both hit system processes and
                    // stall the frame clock if they run on the UI thread.
                    userWantsConnected = true
                    cancelReconnect()
                    connecting = true
                    statusMsg = null
                    scope.launch(Dispatchers.IO) {
                        val consentIntent = try {
                            VpnService.prepare(context)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                if (!userWantsConnected) return@withContext
                                connecting = false
                                userWantsConnected = false
                                statusMsg = cloud.veritasvpn.api.UserFacingError.toUserMessage(e, context)
                            }
                            return@launch
                        }
                        // Consent before the Always-on gate. prepare() is what adds
                        // VeritasVPN to Settings → VPN and shows the system allow
                        // dialog. Checking lockdown first leaves a fresh install
                        // unregistered, so the user cannot turn the switches on.
                        val lockdownOn = VpnKillSwitch.isLockdownEnabled(
                            context,
                            vpnPrepared = consentIntent == null,
                        )
                        val gate = VpnKillSwitch.nextConnectGate(
                            vpnPrepared = consentIntent == null,
                            lockdownEnabled = lockdownOn,
                        )
                        withContext(Dispatchers.Main) {
                            if (!userWantsConnected || !connecting) return@withContext
                            if (gate == VpnKillSwitch.ConnectGate.VpnConsent) {
                                awaitingVpnConsent = true
                                vpnPermissionLauncher.launch(requireNotNull(consentIntent))
                                return@withContext
                            }
                            if (!lockdownOn) {
                                showKillSwitchBlock()
                                return@withContext
                            }
                            startVpnAfterPermissions(lockdownVerified = true)
                        }
                    }
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                            // Some builds publish Always-on and lockdown slightly after
                            // the VPN screen closes. Re-read before leaving the modal up.
                            // Resume must not call VpnService.prepare: that call can
                            // revoke the tunnel or open the system consent screen.
                            val generation = ++killSwitchRecheckGeneration[0]
                            val waitingForSettings = pendingConnectAfterKillSwitch
                            val preparedNow = connected || connecting || reconnecting ||
                                userWantsConnected || waitingForSettings
                            scope.launch {
                                var enabled = VpnKillSwitch.isLockdownEnabled(
                                    context,
                                    vpnPrepared = preparedNow,
                                )
                                if (!enabled && waitingForSettings) {
                                    var reads = 0
                                    while (!enabled && reads < 4) {
                                        reads += 1
                                        delay(300)
                                        if (generation != killSwitchRecheckGeneration[0]) return@launch
                                        if (!pendingConnectAfterKillSwitch) return@launch
                                        enabled = VpnKillSwitch.isLockdownEnabled(
                                            context,
                                            vpnPrepared = true,
                                        )
                                    }
                                }
                                if (generation != killSwitchRecheckGeneration[0]) return@launch
                                if (enabled) {
                                    val continueConnect = pendingConnectAfterKillSwitch
                                    pendingConnectAfterKillSwitch = false
                                    showKillSwitchRequired = false
                                    if (continueConnect) requestConnect()
                                } else if (pendingConnectAfterKillSwitch) {
                                    showKillSwitchRequired = true
                                }
                                if (awaitingLockdownRelease) {
                                    var stillBlocking = VpnKillSwitch.isLockdownEnabled(
                                        context,
                                        vpnPrepared = preparedNow,
                                    )
                                    var reads = 0
                                    while (stillBlocking && reads < 4) {
                                        reads += 1
                                        delay(300)
                                        if (generation != killSwitchRecheckGeneration[0]) return@launch
                                        stillBlocking = VpnKillSwitch.isLockdownEnabled(
                                            context,
                                            vpnPrepared = preparedNow,
                                        )
                                    }
                                    if (generation != killSwitchRecheckGeneration[0]) return@launch
                                    if (!stillBlocking) {
                                        awaitingLockdownRelease = false
                                        showReleaseLockdown = false
                                        releaseLockdownError = null
                                    }
                                }
                            }
                            if (user != null) {
                                ensureSessionFresh()
                                refreshBilling()
                            }
                            // Update lockdown status for UI without VpnService.prepare.
                            lockdownEnabled = VpnKillSwitch.isLockdownEnabled(
                                context,
                                vpnPrepared = preparedNow,
                            )
                            if (user != null && ReconnectDecisions.shouldActivityReconnect(
                                    userWantsConnected = userWantsConnected,
                                    connected = connected,
                                    connecting = connecting,
                                    peerGoneAt = VpnSettings.peerGoneAt(context),
                                    reprovisionInProgressAt = VpnSettings.reprovisionInProgressAt(context),
                                    nowMs = System.currentTimeMillis(),
                                    hasSavedSession = VeritasVpnService.hasSavedSession(context),
                                )
                            ) {
                                Log.i("VeritasVPN", "resume: peer gone, service not re-provisioning, reconnecting")
                                currentPeerId = null
                                VpnSettings.setCurrentPeerId(context, null)
                                VpnSettings.clearPeerGoneAt(context)
                                scope.launch {
                                    startConnection(
                                        context, scope,
                                        setStatus = { msg -> statusMsg = msg },
                                        setConnecting = { connecting = it },
                                        isReconnect = true,
                                        onSessionExpired = { handleSessionExpired() },
                                        shouldContinue = { userWantsConnected },
                                    )
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val splitTunnelDirty =
                    excludeLan != appliedExcludeLan || bypassApps != appliedBypassApps
                val stealthDirty = stealthMode != appliedStealthMode

                LaunchedEffect(statusMsg) {
                    val message = statusMsg
                    if (!isRecordableError(message)) return@LaunchedEffect
                    val safe = sanitizeError(message)
                    if (safe == "none" || safe == lastError) return@LaunchedEffect
                    lastError = safe
                    VpnSettings.setLastError(context, safe)
                }

                // On cold start, check if onboarding is needed for a logged-in user.
                LaunchedEffect(user) {
                    if (user != null) {
                        val disclosureAccepted = VpnSettings.vpnDisclosureAccepted(context)
                        val onboardingDone = VpnSettings.onboardingVpnDone(context)
                        if (OnboardingGate.shouldStart(disclosureAccepted, onboardingDone)) {
                            showOnboardingDisclosure = true
                            onboardingStep = OnboardingGate.Step.SHOW_DISCLOSURE
                        }
                    }
                }

                // Query trial offers when plans screen is shown (Play billing only)
                LaunchedEffect(showPlans, user?.accountId) {
                    if (!showPlans || !BuildConfig.PLAY_BILLING || user == null) return@LaunchedEffect
                    val planIds = listOf("premium_monthly", "premium_annual")
                    storeBilling.queryTrialOffers(planIds, object : StoreBillingCallbacks {
                        override fun onTrialOffers(offers: Map<String, cloud.veritasvpn.billing.TrialOffer>) {
                            trialOffers = offers
                        }
                    })
                }

                fun finishAuthentication() {
                    val fromReauth = reauthenticate
                    reauthenticate = false
                    val cleared = SignInAgainSignal.afterLocalSignIn()
                    signInAgain = cleared.showing
                    awaitingAuthClear = cleared.awaitingClear
                    if (statusMsg == ControlPlane.SIGN_IN_AGAIN) statusMsg = null
                    if (!fromReauth) {
                        billingStatus = null
                        billingSettled = false
                        billingRefreshing = false
                        cancellationInProgress = false
                    }
                    user = authRepo.getStoredUser()
                    if (connected || hadEstablishedSession || restoringSavedVpnSession) {
                        context.startService(
                            Intent(context, VeritasVpnService::class.java).apply {
                                action = VeritasVpnService.ACTION_AUTH_RESTORED
                            }
                        )
                    }
                    val disclosureAccepted = VpnSettings.vpnDisclosureAccepted(context)
                    val onboardingDone = VpnSettings.onboardingVpnDone(context)
                    if (OnboardingGate.shouldStart(disclosureAccepted, onboardingDone)) {
                        showOnboardingDisclosure = true
                        onboardingStep = OnboardingGate.Step.SHOW_DISCLOSURE
                    }
                }

                if (user == null || reauthenticate) {
                    AuthScreen(
                        onAuthenticated = { finishAuthentication() },
                        onCancel = if (user != null) {
                            { reauthenticate = false }
                        } else {
                            null
                        },
                    )
                } else if (!BuildConfig.PLAY_BILLING && checkoutUrl != null) {
                    ExternalCheckout(
                        checkoutUrl = checkoutUrl!!,
                        onClose = { checkoutUrl = null; refreshBilling() },
                        onRefreshPlan = { refreshBilling() }
                    )
                } else if (showOnboardingDisclosure) {
                    // Drive the onboarding flow with LaunchedEffect to avoid side effects in composable body.
                    LaunchedEffect(onboardingStep) {
                        when (onboardingStep) {
                            OnboardingGate.Step.SHOW_DISCLOSURE -> {
                                // Stay on disclosure screen, wait for user action.
                            }
                            OnboardingGate.Step.SHOW_SYSTEM_DIALOG -> {
                                // Call prepare() and launch the system dialog.
                                val consentIntent = try {
                                    withContext(Dispatchers.IO) {
                                        VpnService.prepare(context)
                                    }
                                } catch (e: Exception) {
                                    // If prepare() fails, mark onboarding as done and go to dashboard.
                                    VpnSettings.setOnboardingVpnDone(context, true)
                                    showOnboardingDisclosure = false
                                    return@LaunchedEffect
                                }
                                if (consentIntent != null) {
                                    // Show the system dialog.
                                    onboardingConsentIntent = consentIntent
                                    awaitingVpnConsent = true
                                    vpnPermissionLauncher.launch(consentIntent)
                                } else {
                                    // Already prepared; onboarding is done.
                                    VpnSettings.setOnboardingVpnDone(context, true)
                                    showOnboardingDisclosure = false
                                }
                            }
                            OnboardingGate.Step.DONE, OnboardingGate.Step.SKIP -> {
                                // Should not reach here, but handle gracefully.
                                VpnSettings.setOnboardingVpnDone(context, true)
                                showOnboardingDisclosure = false
                            }
                        }
                    }

                    // Render the disclosure screen if needed.
                    if (onboardingStep == OnboardingGate.Step.SHOW_DISCLOSURE) {
                        VpnDisclosureScreen(
                            onAccept = {
                                VpnSettings.setVpnDisclosureAccepted(context, true)
                                // Move to next step: check if VPN is prepared.
                                scope.launch(Dispatchers.IO) {
                                    val prepared = try {
                                        VpnService.prepare(context) == null
                                    } catch (e: Exception) {
                                        false
                                    }
                                    withContext(Dispatchers.Main) {
                                        onboardingStep = OnboardingGate.afterAccept(prepared)
                                    }
                                }
                            },
                            onDecline = {
                                // Decline: mark onboarding as done, don't set disclosure accepted.
                                // User can still connect later via the circle (will show disclosure again).
                                VpnSettings.setOnboardingVpnDone(context, true)
                                showOnboardingDisclosure = false
                            },
                            onOpenPrivacy = {
                                if (!SupportLinks.openHttps(context, SupportLinks.PRIVACY)) {
                                    statusMsg = "Could not open the privacy policy."
                                }
                            },
                            title = context.getString(R.string.onboarding_disclosure_title),
                        )
                    }
                } else if (showVpnDisclosure) {
                    VpnDisclosureScreen(
                        onAccept = {
                            VpnSettings.setVpnDisclosureAccepted(context, true)
                            showVpnDisclosure = false
                            requestConnect()
                        },
                        onDecline = { showVpnDisclosure = false },
                        onOpenPrivacy = {
                            if (!SupportLinks.openHttps(context, SupportLinks.PRIVACY)) {
                                statusMsg = "Could not open the privacy policy."
                            }
                        },
                    )
                } else if (showShieldSettings) {
                    ShieldSettingsScreen(
                        policy = shieldPolicy,
                        isPremium = PlanEntitlement.grantsPremium(billingStatus),
                        connected = connected,
                        error = shieldError,
                        onPolicyChange = { next ->
                            val previous = shieldPolicy
                            val generation = ++shieldWriteGeneration[0]
                            shieldPolicy = next
                            shieldError = null
                            VpnSettings.setShieldPolicy(context, next)
                            val peerId = currentPeerId
                            if (peerId.isNullOrBlank() || !connected) return@ShieldSettingsScreen
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val failure = AuthenticatedApi.execute(authRepo, { token ->
                                        ApiClient.patch("/api/v1/wg/peers/$peerId", next.wireBody(), token)
                                    }) { res ->
                                        if (res.isSuccessful) null
                                        else ApiClient.parse<PeerResponse>(res)?.error?.takeIf { it.isNotBlank() }
                                            ?: "Could not update Veritas Shield. Try again."
                                    }
                                    if (failure != null && generation == shieldWriteGeneration[0]) {
                                        withContext(Dispatchers.Main) {
                                            shieldPolicy = previous
                                            VpnSettings.setShieldPolicy(context, previous)
                                            shieldError = failure
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    if (generation != shieldWriteGeneration[0]) return@launch
                                    withContext(Dispatchers.Main) {
                                        shieldPolicy = previous
                                        VpnSettings.setShieldPolicy(context, previous)
                                        if (e is SessionExpiredException) {
                                            handleSessionExpired()
                                        } else {
                                            shieldError = cloud.veritasvpn.api.UserFacingError.toUserMessage(e, context)
                                        }
                                    }
                                }
                            }
                        },
                        onUpgrade = {
                            showShieldSettings = false
                            showPlans = true
                            if (billingStatus == null) refreshBilling()
                        },
                        onBack = { showShieldSettings = false }
                    )
                } else if (showStealthSettings) {
                    StealthSettingsScreen(
                        stealthMode = stealthMode,
                        showReconnectBanner = connected && stealthDirty,
                        onStealthModeChange = {
                            stealthMode = it
                            VpnSettings.setStealthMode(context, it)
                        },
                        onBack = { showStealthSettings = false }
                    )
                } else if (showTunnelSettings) {
                    TunnelSettingsScreen(
                        excludeLan = excludeLan,
                        bypassApps = bypassApps,
                        showReconnectBanner = connected && splitTunnelDirty,
                        onExcludeLanChange = {
                            excludeLan = it
                            VpnSettings.setExcludeLan(context, it)
                        },
                        onBypassAppsChange = {
                            bypassApps = it
                            VpnSettings.setBypassApps(context, it)
                        },
                        onBack = { showTunnelSettings = false }
                    )
                } else if (showDiagnostics) {
                    DiagnosticsScreen(
                        connected = connected,
                        connecting = connecting,
                        handshakeEpochMs = handshakeMs,
                        transport = transport,
                        lastError = lastError,
                        onBack = { showDiagnostics = false },
                    )
                } else if (showHelp) {
                    HelpScreen(
                        shareDiagnostics = shareDiagnostics,
                        onShareDiagnosticsChange = { enabled ->
                            shareDiagnostics = enabled
                            VpnSettings.setShareDiagnostics(context, enabled)
                        },
                        connected = connected,
                        connecting = connecting,
                        handshakeEpochMs = handshakeMs,
                        transport = transport,
                        lastError = lastError,
                        onOpenDiagnostics = { showDiagnostics = true },
                        onBack = { showHelp = false },
                    )
                } else if (showPlans) {
                    AccountScreen(
                        email = user?.email,
                        accountId = user?.accountId.orEmpty(),
                        billingStatus = billingStatus,
                        refreshing = billingRefreshing,
                        cancelling = cancellationInProgress,
                        checkoutMethod = checkoutMethod,
                        paymentState = billingStatus?.paymentState.orEmpty(),
                        paymentMessage = billingStatus?.paymentMessage,
                        error = billingError,
                        purchaseHistoryFailed = purchaseHistoryFailed,
                        playBilling = BuildConfig.PLAY_BILLING,
                        playPurchasePending = playPurchasePending,
                        deletingAccount = deletingAccount,
                        deleteError = deleteAccountError,
                        trialOffers = trialOffers,
                        onBack = { showPlans = false },
                        onRefresh = { refreshBilling() },
                        onPurchase = { plan -> startStorePurchase(plan) },
                        onCancel = { cancelSubscription() },
                        onManageSubscription = {
                            val activity = context as? Activity ?: return@AccountScreen
                            storeBilling.manageSubscription(activity, billingStatus?.planId)
                        },
                        onDeleteAccount = { password, turnstileToken ->
                            if (deletingAccount) return@AccountScreen
                            deletingAccount = true
                            deleteAccountError = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        authRepo.deleteAccount(password, turnstileToken)
                                    }
                                    userWantsConnected = false
                                    hadEstablishedSession = false
                                    cancelReconnect()
                                    disconnectVpnService()
                                    clearBillingCache(context)
                                    VpnSettings.setCurrentPeerId(context, null)
                                    VpnSettings.setLastError(context, "")
                                    lastError = ""
                                    deletingAccount = false
                                    deleteAccountError = null
                                    clearLocalSessionUi()
                                    noteIntentionalDisconnect()
                                } catch (e: Exception) {
                                    deletingAccount = false
                                    deleteAccountError = cloud.veritasvpn.api.UserFacingError.toUserMessage(e, context)
                                }
                            }
                        }
                    )
                } else {
                    DashboardScreen(
                        connected = connected,
                        connecting = connecting,
                        onConnect = { requestConnect() },
                        onDisconnect = {
                            CircleDisconnect.run(lockdownEnabled) {
                                userWantsConnected = false
                                hadEstablishedSession = false
                                cancelReconnect()
                                signInAgain = false
                                awaitingAuthClear = false
                                reauthenticate = false
                                statusMsg = null
                                val disconnectedPeerId = peerIdForDisconnect()
                                disconnectVpnService()
                                deletePeerBestEffort(disconnectedPeerId)
                                noteIntentionalDisconnect()
                            }
                        },
                        onSignIn = { reauthenticate = true },
                        onSignOut = { performLocalSignOut() },
                        onSignOutEverywhere = {
                            val accessToken = authRepo.getAccessToken()?.takeIf { it.isNotBlank() }
                            if (accessToken != null) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        ApiClient.post("/api/v1/auth/logout-all", emptyMap<String, Any>(), accessToken).close()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            userWantsConnected = false
                            hadEstablishedSession = false
                            cancelReconnect()
                            deletePeerBestEffort(peerIdForDisconnect())
                            disconnectVpnService()
                            // The local app exits immediately even if the remote session
                            // revocation is delayed by the network transition.
                            clearLocalSessionUi()
                            noteIntentionalDisconnect()
                        },
                        onPlans = {
                            showPlans = true
                            if (billingStatus == null) refreshBilling()
                        },
                        onStealthSettings = { showStealthSettings = true },
                        onShieldSettings = { showShieldSettings = true },
                        onTunnelSettings = { showTunnelSettings = true },
                        onHelp = { showHelp = true },
                        onOpenKillSwitchSettings = {
                            val opened = runCatching {
                                context.startActivity(VpnKillSwitch.systemVpnSettingsIntent())
                            }.isSuccess
                            if (!opened) {
                                statusMsg = "Could not open Android VPN settings. Enable Always-on VPN and Block connections without VPN for VeritasVPN in system settings."
                            }
                        },
                        showKillSwitchRequired = showKillSwitchRequired,
                        onDismissKillSwitchRequired = {
                            showKillSwitchRequired = false
                            pendingConnectAfterKillSwitch = false
                            userWantsConnected = false
                            connecting = false
                            reconnecting = false
                        },
                        isPremium = PlanEntitlement.grantsPremium(billingStatus),
                        billingReady = PlanEntitlement.homeBillingReady(billingStatus, billingSettled),
                        statusMsg = statusMsg,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        handshakeMs = handshakeMs,
                        dnsBlockedCount = dnsBlockedCount,
                        dnsBlockedBaseline = dnsBlockedBaseline,
                        dnsGateway = dnsGateway,
                        transport = transport,
                        reconnecting = reconnecting,
                        handshakeOk = handshakeOk,
                        lockdownEnabled = lockdownEnabled,
                        signInAgain = signInAgain,
                    )
                }
                if (showReleaseLockdown) {
                    ReleaseLockdownDialog(
                        onOpenSystemVpnSettings = {
                            val opened = runCatching {
                                context.startActivity(VpnKillSwitch.systemVpnSettingsIntent())
                            }.isSuccess
                            releaseLockdownError = if (opened) {
                                null
                            } else {
                                "Could not open Android VPN settings. Turn off Always-on VPN and Block connections without VPN for VeritasVPN in system settings."
                            }
                        },
                        onDismiss = {
                            showReleaseLockdown = false
                            awaitingLockdownRelease = false
                            releaseLockdownError = null
                        },
                        settingsError = releaseLockdownError,
                    )
                }
            }
        }
    }

    private var currentPeerId: String? = null
    private var connectJob: Job? = null

    private fun peerIdForDisconnect(): String? {
        val id = currentPeerId ?: VpnSettings.currentPeerId(this)
        currentPeerId = null
        VpnSettings.setCurrentPeerId(this, null)
        return id
    }

    /**
     * Peer create for a circle-tap reconnect. Lockdown keeps the interface up
     * after disconnect, and the normal client is blackholed. That path uses
     * the protected system-default client. With the interface closed the
     * normal client is used, including its token refresh.
     */
    private suspend fun createPeer(
        context: Context,
        keyPair: KeyPair,
        deviceId: String,
    ): PeerResponse {
        val body = mapOf<String, Any>(
            "public_key" to keyPair.publicKey.toBase64(),
            "device_id" to deviceId,
        ) + WireGuardSession.deviceMetadata(context) + VpnSettings.shieldPolicy(context).wireBody()
        val protected = VeritasVpnService.provisioningClient()
        val route = ProvisionRoute.choose(
            interfaceHeld = VeritasVpnService.interfaceHeld(),
            protectedAvailable = protected != null,
        )
        DiagnosticLog.record("provision route=${ProvisionRoute.logName(route)}")
        ConnectTiming.mark("client")
        return when (route) {
            ProvisionRoute.Route.NORMAL ->
                AuthenticatedApi.execute(authRepo, { token ->
                    ApiClient.post("/api/v1/wg/peers", body, token)
                }, ::parseCreatedPeer)
            ProvisionRoute.Route.PROTECTED ->
                postPeerOutsideTunnel(body, requireNotNull(protected))
            ProvisionRoute.Route.BLOCKED ->
                throw java.net.ConnectException("api")
        }
    }

    private suspend fun postPeerOutsideTunnel(
        body: Map<String, Any>,
        client: okhttp3.OkHttpClient,
    ): PeerResponse {
        val access = authRepo.accessForControl(client, tunnelHealthy = false)
        val token = when (access) {
            is ControlAccess.Ready -> access.token
            is ControlAccess.SignedOut -> throw SessionExpiredException()
            is ControlAccess.Unavailable -> throw java.net.ConnectException("api")
        }
        var response = ApiClient.post("/api/v1/wg/peers", body, token, httpClient = client)
        if (response.code == 401) {
            response.close()
            when (authRepo.refreshForControl(client, force = true)) {
                ControlRefresh.SignedOut -> throw SessionExpiredException()
                ControlRefresh.Unavailable -> throw java.net.ConnectException("api")
                ControlRefresh.Refreshed -> {
                    val refreshed = authRepo.getAccessToken()?.takeIf { it.isNotBlank() }
                        ?: throw SessionExpiredException()
                    response = ApiClient.post(
                        "/api/v1/wg/peers",
                        body,
                        refreshed,
                        httpClient = client,
                    )
                }
            }
        }
        return response.use(::parseCreatedPeer)
    }

    private fun parseCreatedPeer(res: okhttp3.Response): PeerResponse {
        if (!res.isSuccessful) {
            val err = ApiClient.parse<PeerResponse>(res)?.error
            throw PeerError(err ?: "Failed to create peer")
        }
        return ApiClient.parse<PeerResponse>(res)
            ?: throw PeerError("Invalid VPN server response")
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
        shouldContinue: () -> Boolean = { true },
    ) {
        if (currentPeerId != null) return
        if (isReconnect && ReprovisionFlight.blocksActivity(
                VpnSettings.reprovisionInProgressAt(context),
                System.currentTimeMillis(),
            )
        ) {
            Log.i("VeritasVPN", PeerCheckLog.reprovision("skipped", 0))
            VpnSettings.noteReprovision(context, "skipped", 0)
            return
        }
        setStatus(if (isReconnect) "Reconnecting…" else "Connecting...")
        // Key generation, the peer request, and config assembly stay off the
        // UI thread. Compose only hears the small state hop afterwards, so the
        // hero clock is not paused or restarted by this work.
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            val flight = if (isReconnect) {
                ReprovisionFlight.tryClaim(
                    persistedAtMs = VpnSettings.reprovisionInProgressAt(context),
                    nowMs = System.currentTimeMillis(),
                    replaceWaiting = false,
                ) { claimedAt ->
                    VpnSettings.setReprovisionInProgressAt(context, claimedAt)
                }
            } else {
                null
            }
            if (isReconnect && flight == null) {
                Log.i("VeritasVPN", PeerCheckLog.reprovision("skipped", 0))
                VpnSettings.noteReprovision(context, "skipped", 0)
                return@launch
            }
            try {
                ConnectTiming.arm(System.currentTimeMillis())
                // The server upserts by (account_id, device_id). While the
                // interface is held, the earlier delete is on a socket lockdown
                // will not answer, and waiting for it is the multi-second gap.
                // Cancelling it also stops a late delete of the peer we create.
                if (ConnectTiming.shouldWaitForCleanup(VeritasVpnService.interfaceHeld())) {
                    peerCleanupJob?.join()
                    ConnectTiming.mark("waitcleanup")
                } else {
                    peerCleanupJob?.cancel()
                    peerCleanupJob = null
                    // The delete may still be on the cached client. Drop it so
                    // the peer POST opens a new socket instead of inheriting
                    // that read.
                    VeritasVpnService.retireProvisioningClient()
                    ConnectTiming.mark("skipcleanup")
                }
                if (!shouldContinue()) return@launch
                val keyPair = KeyPair()
                val deviceId = VpnSettings.deviceId(context)
                val peer = createPeer(context, keyPair, deviceId)
                ConnectTiming.mark("provision")
                if (!shouldContinue()) return@launch
                val config = WireGuardSession.buildConfig(context, peer, keyPair)
                val intent = Intent(context, VeritasVpnService::class.java).apply {
                    action = VeritasVpnService.ACTION_CONNECT
                    putExtra(VeritasVpnService.EXTRA_CONFIG, config)
                    putExtra(
                        VeritasVpnService.EXTRA_ENDPOINT_LAN,
                        peer.serverEndpointLan?.trim().orEmpty()
                    )
                    putExtra(
                        VeritasVpnService.EXTRA_ENDPOINT_WAN,
                        peer.serverEndpointWan?.trim().orEmpty()
                    )
                    putExtra(
                        VeritasVpnService.EXTRA_STEALTH_ENDPOINT,
                        peer.stealthEndpoint?.trim().orEmpty()
                    )
                    putExtra(
                        VeritasVpnService.EXTRA_STEALTH_PREFIX,
                        peer.stealthPathPrefix?.trim().orEmpty()
                    )
                    putExtra(
                        VeritasVpnService.EXTRA_STEALTH_MODE,
                        VpnSettings.stealthMode(context).stored()
                    )
                    putExtra(
                        VeritasVpnService.EXTRA_STEALTH_AVAILABLE,
                        peer.stealthAvailable
                    )
                }
                withContext(Dispatchers.Main) {
                    if (!shouldContinue()) return@withContext
                    currentPeerId = peer.peerId
                    VpnSettings.setCurrentPeerId(context, peer.peerId)
                    peer.dnsServer?.trim()?.takeIf { it.isNotEmpty() }?.let { onDnsGateway?.invoke(it) }
                    context.startForegroundService(intent)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ConnectTiming.finish()
                withContext(Dispatchers.Main) {
                    if (e is SessionExpiredException) {
                        onSessionExpired?.invoke()
                        return@withContext
                    }
                    setConnecting(false)
                    setStatus(cloud.veritasvpn.api.UserFacingError.toUserMessage(e, context))
                    onFailure?.invoke()
                }
            } finally {
                if (flight != null) {
                    ReprovisionFlight.release(flight) {
                        VpnSettings.clearReprovisionInProgressAt(context)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBillingReturn(intent)
    }
}

class PeerError(override val userMessage: String) : Exception(userMessage), cloud.veritasvpn.api.UserVisibleError
