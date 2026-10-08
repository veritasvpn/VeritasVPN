package cloud.veritasvpn.billing

import android.app.Activity

/**
 * Shared purchase callbacks. The play and direct flavors each provide
 * [StoreBilling]; the website APK never links the Play implementation.
 */
interface StoreBillingCallbacks {
    fun onCheckoutUrl(url: String) {}
    fun onPending(message: String) {}
    fun onVerified() {}
    fun onError(message: String) {}
    fun onSessionExpired() {}
}

/**
 * Flavor-specific store client. [usesPlayBilling] is a compile-time constant
 * in each variant so release shrinking can drop the other payment path.
 */
interface StoreBilling {
    val usesPlayBilling: Boolean
    fun connect()
    fun close()
    fun purchase(activity: Activity, planId: String, accountId: String, callbacks: StoreBillingCallbacks)
    fun restore(accountId: String, callbacks: StoreBillingCallbacks)
    fun manageSubscription(activity: Activity, planId: String?)
}
