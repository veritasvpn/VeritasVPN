package cloud.veritasvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PlansScreen(
    billingStatus: BillingStatus?,
    refreshing: Boolean,
    cancelling: Boolean,
    checkoutMethod: String?,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCheckout: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf("premium_monthly") }
    val periodEnd = remember(billingStatus?.currentPeriodEnd) {
        formatBillingDate(billingStatus?.currentPeriodEnd)
    }
    LaunchedEffect(billingStatus?.cancelAtPeriodEnd) {
        if (billingStatus?.cancelAtPeriodEnd == true) showCancelConfirmation = false
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!cancelling) showCancelConfirmation = false },
            title = { Text("Schedule cancellation?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your VPN will stay active until $periodEnd. After that date, Premium will end and you will not be charged again.",
                    color = PaperMuted,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { onCancel() },
                    enabled = !cancelling,
                    colors = ButtonDefaults.buttonColors(containerColor = Royal)
                ) { Text(if (cancelling) "Scheduling…" else "Confirm cancellation", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }, enabled = !cancelling) {
                    Text("Keep Premium", color = CyanHover)
                }
            },
            containerColor = CardElevated
        )
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Paper)
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Plans & billing", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Choose the privacy plan that fits you", color = PaperDim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(22.dp))

        val premium = billingStatus?.isPremium == true
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardElevated).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("CURRENT PLAN", color = PaperDim, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Text(
                    when {
                        refreshing && billingStatus == null -> "Checking subscription…"
                        premium -> "Premium"
                        else -> "No active subscription"
                    },
                    color = if (premium) CyanHover else Paper,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cyan)
            else TextButton(onClick = onRefresh) { Text("Refresh", color = CyanHover) }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = WarningOrange, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))

        PlanCard(
            name = "Premium", price = if (selectedPlan == "premium_annual") "$30" else "$3",
            suffix = if (selectedPlan == "premium_annual") "/year" else "/month", current = premium,
            features = listOf("Paraguay WireGuard egress", "Up to 5 VPN devices", "Private Bitcoin checkout", "Chrome, Android, and Linux access"),
            emphasized = true
        )
        if (!premium) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanChoice("Monthly", "$3 / 30 days", selectedPlan == "premium_monthly", { selectedPlan = "premium_monthly" }, Modifier.weight(1f))
                PlanChoice("Annual", "$30 / 365 days", selectedPlan == "premium_annual", { selectedPlan = "premium_annual" }, Modifier.weight(1f))
            }
        }

        if (!premium) {
            Spacer(Modifier.height(18.dp))
            Text("Pay privately", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Complete checkout securely inside VeritasVPN. Premium activates automatically after confirmation.", color = PaperMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onCheckout("btcpay", selectedPlan) },
                enabled = checkoutMethod == null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Royal)
            ) { Text(if (checkoutMethod == "btcpay") "Opening Bitcoin checkout…" else "Pay with Bitcoin", color = Color.White, fontWeight = FontWeight.Bold) }
        } else {
            Spacer(Modifier.height(18.dp))
            Text("Premium is active", color = SuccessGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            if (billingStatus?.cancelAtPeriodEnd == true) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Cyan.copy(alpha = .08f)),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = .3f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cancellation scheduled", color = CyanHover, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Your VPN remains active until $periodEnd. After that, Premium ends automatically. You can purchase another period whenever you want.",
                            color = PaperMuted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            } else {
                OutlinedButton(onClick = { showCancelConfirmation = true }, enabled = !cancelling, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, LineStrong)) {
                    Text(if (cancelling) "Scheduling cancellation…" else "Cancel at period end", color = PaperMuted)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatBillingDate(value: String?): String {
    if (value.isNullOrBlank()) return "the end of your current billing period"
    return runCatching {
        LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }.getOrDefault(value.take(10))
}

@Composable
private fun PlanChoice(name: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier, border = BorderStroke(1.dp, if (selected) Cyan else Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Cyan.copy(alpha = .12f) else Color.Transparent),
        shape = RoundedCornerShape(12.dp)) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(detail, color = PaperDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlanCard(name: String, price: String, suffix: String, current: Boolean, features: List<String>, emphasized: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (emphasized) CardElevated else CardBg),
        border = BorderStroke(1.dp, if (emphasized) RoyalHover else Line)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (current) Text("CURRENT", color = SuccessGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(SuccessGreen.copy(alpha = .12f)).padding(horizontal = 9.dp, vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(price, color = if (emphasized) CyanHover else Paper, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(suffix, color = PaperDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            features.forEach { feature ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(feature, color = PaperMuted, fontSize = 14.sp)
                }
            }
        }
    }
}
