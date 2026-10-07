package cloud.veritasvpn.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import cloud.veritasvpn.api.ApiClient
import cloud.veritasvpn.auth.AuthenticatedApi
import cloud.veritasvpn.auth.SessionExpiredException
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

fun createStoreBilling(context: Context, billing: BillingRepository): StoreBilling =
    PlayStoreBilling(context, billing)

/**
 * Google Play Billing for the Play flavor. Purchases are verified by
 * billing-svc before they are acknowledged. Nothing in this type opens an
 * external payment page.
 */
class PlayStoreBilling(
    context: Context,
    private val billing: BillingRepository,
) : StoreBilling {
    override val usesPlayBilling: Boolean = true

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veritas-play-billing").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private var setupError: String? = null
    private var purchaseCallbacks: StoreBillingCallbacks? = null

    private val purchasesUpdatedListener =
        com.android.billingclient.api.PurchasesUpdatedListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val callbacks = purchaseCallbacks
                    if (purchases.isNullOrEmpty()) {
                        callbacks?.onError("Google Play didn't return a purchase.")
                    } else {
                        purchases.forEach { purchase -> handlePurchase(purchase, callbacks) }
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    purchaseCallbacks?.onError("Purchase canceled.")
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                    purchaseCallbacks?.let { restoreOwned(it) }
                else -> purchaseCallbacks?.onError(billingMessage(result))
            }
        }

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    override fun connect() {
        if (closed.get() || client.isReady) return
        client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    setupError = null
                } else {
                    setupError = billingMessage(result)
                }
            }

            override fun onBillingServiceDisconnected() {
                setupError = "Google Play Billing isn't available right now."
            }
        })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        purchaseCallbacks = null
        io.shutdownNow()
        if (client.isReady) client.endConnection()
    }

    override fun purchase(activity: Activity, planId: String, accountId: String, callbacks: StoreBillingCallbacks) {
        if (closed.get()) {
            callbacks.onError("Google Play Billing isn't available.")
            return
        }
        val entry = PlayCatalog.byPlanId(planId)
        if (entry == null) {
            callbacks.onError("That plan isn't available.")
            return
        }
        if (accountId.isBlank()) {
            callbacks.onError("Sign in before subscribing.")
            return
        }
        if (!client.isReady) {
            connect()
            callbacks.onError(setupError ?: "Google Play Billing isn't available on this device.")
            return
        }
        purchaseCallbacks = callbacks
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(entry.productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                main.post { callbacks.onError(billingMessage(result)) }
                return@queryProductDetailsAsync
            }
            val details = detailsResult.productDetailsList.firstOrNull { it.productId == entry.productId }
            val offerToken = details?.let { offerToken(it, entry.basePlanId) }
            if (details == null || offerToken.isNullOrBlank()) {
                main.post {
                    callbacks.onError("This subscription isn't available in Google Play yet.")
                }
                return@queryProductDetailsAsync
            }
            main.post {
                if (activity.isFinishing) {
                    callbacks.onError("Couldn't open Google Play. Try again.")
                    return@post
                }
                val flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .setOfferToken(offerToken)
                                .build(),
                        ),
                    )
                    .setObfuscatedAccountId(accountId)
                    .build()
                val launched = client.launchBillingFlow(activity, flow)
                if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
                    callbacks.onError(billingMessage(launched))
                }
            }
        }
    }

    override fun restore(accountId: String, callbacks: StoreBillingCallbacks) {
        if (closed.get() || accountId.isBlank()) return
        if (!client.isReady) {
            connect()
            main.postDelayed({
                if (!closed.get() && client.isReady) queryExisting(callbacks)
            }, 600)
            return
        }
        queryExisting(callbacks)
    }

    override fun manageSubscription(activity: Activity, planId: String?) {
        val productId = planId?.let { PlayCatalog.byPlanId(it)?.productId }
        val uri = if (productId.isNullOrBlank()) {
            Uri.parse("https://play.google.com/store/account/subscriptions?package=${appContext.packageName}")
        } else {
            Uri.parse(
                "https://play.google.com/store/account/subscriptions?sku=$productId&package=${appContext.packageName}",
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { appContext.startActivity(intent) }
    }

    private fun restoreOwned(callbacks: StoreBillingCallbacks) {
        queryExisting(callbacks)
    }

    private fun queryExisting(callbacks: StoreBillingCallbacks) {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                if (result.responseCode != BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
                    main.post { callbacks.onError(billingMessage(result)) }
                }
                return@queryPurchasesAsync
            }
            purchases.forEach { purchase -> handlePurchase(purchase, callbacks) }
        }
    }

    private fun handlePurchase(purchase: Purchase, callbacks: StoreBillingCallbacks?) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> main.post {
                callbacks?.onPending("Google Play is still processing this subscription.")
            }
            Purchase.PurchaseState.PURCHASED -> verifyThenAcknowledge(purchase, callbacks)
            else -> Unit
        }
    }

    private fun verifyThenAcknowledge(purchase: Purchase, callbacks: StoreBillingCallbacks?) {
        val productId = purchase.products.firstOrNull()
        if (productId.isNullOrBlank() || PlayCatalog.byProductId(productId) == null) {
            main.post { callbacks?.onError("Google Play returned an unexpected subscription.") }
            return
        }
        io.execute {
            if (closed.get()) return@execute
            try {
                val verified = verifyWithServer(productId, purchase.purchaseToken)
                if (verified.pending) {
                    main.post { callbacks?.onPending("Google Play is still processing this subscription.") }
                    return@execute
                }
                if (verified.acknowledge && !purchase.isAcknowledged) {
                    acknowledge(purchase.purchaseToken)
                }
                main.post { callbacks?.onVerified() }
            } catch (e: SessionExpiredException) {
                main.post { callbacks?.onSessionExpired() }
            } catch (e: Exception) {
                main.post { callbacks?.onError(e.message ?: "Couldn't verify this Google Play purchase.") }
            }
        }
    }

    private fun verifyWithServer(productId: String, purchaseToken: String): PlayVerifyResponse =
        AuthenticatedApi.execute(
            billing.auth,
            { token ->
                ApiClient.post(
                    "/api/v1/billing/google-play/verify",
                    mapOf(
                        "product_id" to productId,
                        "purchase_token" to purchaseToken,
                    ),
                    token,
                )
            },
        ) { response ->
            val data = ApiClient.parse<PlayVerifyResponse>(response)
            if (!response.isSuccessful) {
                throw BillingRepository.Error(
                    data?.error?.takeIf { it.isNotBlank() }
                        ?: "Couldn't verify this Google Play purchase.",
                )
            }
            data ?: throw BillingRepository.Error("The server returned an invalid purchase response.")
        }

    private fun acknowledge(purchaseToken: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        val done = java.util.concurrent.CountDownLatch(1)
        var failed: String? = null
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                failed = billingMessage(result)
            }
            done.countDown()
        }
        done.await(20, java.util.concurrent.TimeUnit.SECONDS)
        if (failed != null) throw BillingRepository.Error(failed!!)
    }

    private fun offerToken(details: ProductDetails, basePlanId: String): String? {
        val offers = details.subscriptionOfferDetails ?: return null
        val matches = offers.filter { it.basePlanId == basePlanId }
        val base = matches.firstOrNull { it.offerId.isNullOrEmpty() } ?: matches.firstOrNull()
        return base?.offerToken
    }

    private fun billingMessage(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play Billing isn't available on this device."
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "Couldn't reach Google Play. Try again."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "This subscription isn't available in Google Play yet."
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "Google Play Billing isn't set up for this build."
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "This version of Google Play doesn't support subscriptions."
        BillingClient.BillingResponseCode.USER_CANCELED ->
            "Purchase canceled."
        else -> result.debugMessage?.takeIf { it.isNotBlank() }
            ?: "Google Play couldn't complete the purchase."
    }
}

private data class PlayVerifyResponse(
    @SerializedName("is_premium") val isPremium: Boolean = false,
    @SerializedName("acknowledge") val acknowledge: Boolean = false,
    @SerializedName("pending") val pending: Boolean = false,
    val error: String? = null,
)
