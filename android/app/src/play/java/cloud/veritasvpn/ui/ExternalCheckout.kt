package cloud.veritasvpn.ui

import androidx.compose.runtime.Composable

/**
 * The Play build has no external checkout screen. MainActivity only calls this
 * when [cloud.veritasvpn.BuildConfig.PLAY_BILLING] is false, so this body is
 * unused in the Play variant.
 */
@Composable
fun ExternalCheckout(
    @Suppress("UNUSED_PARAMETER") checkoutUrl: String,
    @Suppress("UNUSED_PARAMETER") onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onRefreshPlan: () -> Unit,
) {
}
