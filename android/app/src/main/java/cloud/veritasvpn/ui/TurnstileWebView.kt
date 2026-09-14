package cloud.veritasvpn.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val TURNSTILE_PAGE = "https://veritasvpn.cloud/turnstile-mobile-v2"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileWebView(
    resetKey: Int,
    executeVersion: Int,
    showInteractive: Boolean,
    onToken: (String) -> Unit,
    onReady: () -> Unit,
    onInteractiveRequired: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // A Turnstile token is single-use. Recreate its WebView only when the caller
    // explicitly resets it. Reloading from AndroidView.update would run again on
    // every Compose redraw and could interrupt the challenge with a blank/error page.
    key(resetKey) {
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        var lastExecuteVersion by remember { mutableStateOf(0) }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }

        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.apply {
                    stopLoading()
                    destroy()
                }
                webViewRef = null
            }
        }

        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                // Keep a prewarmed, non-interactive Turnstile page effectively
                // out of layout. The caller expands it only while verification
                // is actually running or Cloudflare asks for interaction.
                .height(if (showInteractive) 96.dp else 1.dp)
                .clip(RoundedCornerShape(12.dp)),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.parseColor("#06101c"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postMessage(raw: String) {
                                runCatching {
                                    val json = JSONObject(raw)
                                    when (json.optString("type")) {
                                        "ready" -> mainHandler.post { onReady() }
                                        "interactive-required" -> mainHandler.post { onInteractiveRequired() }
                                        "token" -> {
                                            val token = json.optString("token")
                                            if (token.isNotBlank()) mainHandler.post { onToken(token) }
                                        }
                                        "expired" -> mainHandler.post { onToken("") }
                                        "error" -> mainHandler.post {
                                            onError(json.optString("message", "Verification failed"))
                                        }
                                    }
                                }
                            }
                        },
                        "VeritasTurnstile"
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = false
                    }
                    webViewRef = this
                    loadUrl(TURNSTILE_PAGE)
                }
            },
            update = { view ->
                // Use a direct JavaScript entry point rather than a cross-frame
                // message. Some Android WebView versions can defer a posted
                // message while the warm iframe is only one pixel tall.
                if (executeVersion > lastExecuteVersion) {
                    lastExecuteVersion = executeVersion
                    view.evaluateJavascript(
                        "window.veritasTurnstileExecute && window.veritasTurnstileExecute()",
                        null
                    )
                }
            }
        )
    }
}
