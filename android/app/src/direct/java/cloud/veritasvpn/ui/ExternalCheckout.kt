package cloud.veritasvpn.ui

import androidx.compose.runtime.Composable

@Composable
fun ExternalCheckout(checkoutUrl: String, onClose: () -> Unit, onRefreshPlan: () -> Unit) {
    PaymentCheckoutScreen(checkoutUrl = checkoutUrl, onClose = onClose, onRefreshPlan = onRefreshPlan)
}
