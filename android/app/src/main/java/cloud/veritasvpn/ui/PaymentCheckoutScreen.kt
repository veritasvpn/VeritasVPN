package cloud.veritasvpn.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cloud.veritasvpn.ui.theme.*

@Composable
fun PaymentCheckoutScreen(checkoutUrl: String, onClose: () -> Unit, onRefreshPlan: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(GradientSurface)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close checkout", tint = Paper) }
            Column(Modifier.weight(1f)) {
                Text("Secure crypto checkout", color = Paper, style = MaterialTheme.typography.titleMedium)
                Text("Payment is processed by BTCPay Server", color = PaperDim, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRefreshPlan) { Text("Check payment", color = CyanHover) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan, trackColor = CardBg)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.host == "veritasvpn.cloud") { onRefreshPlan(); onClose(); return true }
                            if (uri.scheme == "https" && uri.host == "btcpay.veritasvpn.cloud") return false
                            if (uri.scheme in setOf("bitcoin", "monero")) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                return true
                            }
                            return true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                    }
                    loadUrl(checkoutUrl)
                }
            },
            update = { }
        )
    }
}
