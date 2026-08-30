package cloud.veritasvpn.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.*

private fun openCheckoutInBrowser(context: android.content.Context, checkoutUrl: String) {
    runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(checkoutUrl))
    }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl)))
    }
}

@Composable
fun PaymentCheckoutScreen(checkoutUrl: String, onClose: () -> Unit, onRefreshPlan: () -> Unit) {
    val context = LocalContext.current
    var opened by remember(checkoutUrl) { mutableStateOf(false) }

    LaunchedEffect(checkoutUrl) {
        if (!opened) {
            openCheckoutInBrowser(context, checkoutUrl)
            opened = true
        }
    }

    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close checkout", tint = Paper)
            }
            Column(Modifier.weight(1f)) {
                Text("Bitcoin checkout", color = Paper, style = MaterialTheme.typography.titleMedium)
                Text("Opened in your browser", color = PaperDim, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(28.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Complete payment in the browser tab that just opened.",
                    color = Paper,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "When Bitcoin confirms, come back here and tap Check payment. Premium activates automatically.",
                    color = PaperMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Button(
                    onClick = { openCheckoutInBrowser(context, checkoutUrl) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Royal)
                ) {
                    Text("Open checkout again", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onRefreshPlan,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Check payment", color = CyanHover, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Checkout is processed by BTCPay Server. You can close this screen and return anytime.",
            color = PaperDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
