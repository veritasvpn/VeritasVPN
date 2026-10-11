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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.R
import cloud.veritasvpn.api.BillingStatus
import cloud.veritasvpn.api.PurchaseHistoryItem
import cloud.veritasvpn.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AccountScreen(
    email: String?,
    accountId: String,
    billingStatus: BillingStatus?,
    refreshing: Boolean,
    cancelling: Boolean,
    checkoutMethod: String?,
    paymentState: String,
    paymentMessage: String?,
    error: String?,
    purchaseHistoryFailed: Boolean = false,
    playBilling: Boolean = false,
    playPurchasePending: Boolean = false,
    deletingAccount: Boolean = false,
    deleteError: String? = null,
    trialOffers: Map<String, cloud.veritasvpn.billing.TrialOffer> = emptyMap(),
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPurchase: (String) -> Unit,
    onCancel: () -> Unit,
    onManageSubscription: () -> Unit = {},
    onDeleteAccount: (String, String) -> Unit = { _, _ -> },
) {
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var turnstileToken by remember { mutableStateOf("") }
    var turnstileResetKey by remember { mutableIntStateOf(0) }
    var turnstileReady by remember { mutableStateOf(false) }
    var turnstileExecuteVersion by remember { mutableIntStateOf(0) }
    var selectedPlan by remember { mutableStateOf("premium_monthly") }
    val requiresPassword = !email.isNullOrBlank()
    val periodEnd = remember(billingStatus?.currentPeriodEnd) {
        formatBillingDate(billingStatus?.currentPeriodEnd)
    }
    val hasPeriodEnd = !billingStatus?.currentPeriodEnd.isNullOrBlank()
    val paymentPending = playPurchasePending || paymentState == "awaiting_payment" ||
        paymentState == "awaiting_confirmation" || paymentState == "checking"
    val checkoutFeature = stringResource(R.string.plan_feature_checkout)
    val pendingFallback = stringResource(R.string.pending_payment_body)
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
            containerColor = CardElevated,
            shape = RoundedCornerShape(28.dp),
        )
    }

    PremiumBackdrop {
    PremiumEnter {
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        PremiumTopBar(eyebrow = "SIGNED IN", title = "Account", onBack = onBack)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.account_intro), color = PaperDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.account_privacy_note),
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(16.dp),
            color = Paper,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(14.dp))
        AccountIdentity(email = email, accountId = accountId)
        Spacer(Modifier.height(18.dp))

        val premium = billingStatus?.isPremium == true
        Row(
            Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(20.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("CURRENT PLAN", color = PaperDim, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Text(
                    when {
                        refreshing && billingStatus == null -> "Checking subscription…"
                        premium -> "Premium"
                        paymentPending -> "Payment pending"
                        else -> "No active subscription"
                    },
                    color = if (premium) CyanHover else Paper,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (premium && hasPeriodEnd) {
                    Spacer(Modifier.height(4.dp))
                    Text("PREMIUM ACCESS EXPIRES", color = PaperDim, fontSize = 10.sp, letterSpacing = 1.1.sp)
                    Text("Expires on $periodEnd", color = PaperMuted, fontSize = 13.sp)
                }
            }
            if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cyan)
            else TextButton(onClick = onRefresh) { Text("Refresh", color = CyanHover) }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = WarningOrange, fontSize = 13.sp)
        }
        if (paymentPending) {
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Cyan.copy(alpha = .08f)),
                border = BorderStroke(1.dp, Cyan.copy(alpha = .32f))
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cyan)
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (paymentState) {
                                "awaiting_confirmation" -> "Payment received"
                                "awaiting_payment" -> "Waiting for payment"
                                else -> "Checking payment"
                            },
                            color = CyanHover,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            paymentMessage ?: pendingFallback,
                            color = PaperMuted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        PlanCard(
            name = "Premium", price = if (selectedPlan == "premium_annual") "$30" else "$3",
            suffix = if (selectedPlan == "premium_annual") "/year" else "/month", current = premium,
            features = listOf("Paraguay WireGuard egress", "Up to 5 VPN devices", checkoutFeature, "Chrome, Android, and Linux access"),
            emphasized = true
        )
        
        // Show trial messaging if eligible (only for Play billing)
        val trialOffer = if (playBilling) trialOffers[selectedPlan] else null
        if (!premium && !paymentPending && billingStatus?.trialEligible == true && trialOffer != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Cyan.copy(alpha = .08f)),
                border = BorderStroke(1.dp, Cyan.copy(alpha = .3f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${trialOffer.trialDays} days free",
                        color = CyanHover,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Try Premium free for ${trialOffer.trialDays} days, then ${trialOffer.priceAfterTrial}/${trialOffer.period}. Cancel anytime in Google Play.",
                        color = PaperMuted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
        
        if (!premium && !paymentPending) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanChoice("Monthly", "$3 / 30 days", selectedPlan == "premium_monthly", { selectedPlan = "premium_monthly" }, Modifier.weight(1f))
                PlanChoice("Annual", "$30 / 365 days", selectedPlan == "premium_annual", { selectedPlan = "premium_annual" }, Modifier.weight(1f))
            }
        }

        if (!premium && !paymentPending) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.pay_section_title), color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.pay_section_body), color = PaperMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onPurchase(selectedPlan) },
                enabled = checkoutMethod == null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Royal)
            ) {
                Text(
                    when {
                        checkoutMethod != null -> stringResource(R.string.pay_button_busy)
                        playBilling && billingStatus?.trialEligible == true && trialOffers[selectedPlan] != null -> "Start free trial"
                        else -> stringResource(R.string.pay_button_idle)
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else if (premium && playBilling && billingStatus?.paymentMethod == "google_play") {
            Spacer(Modifier.height(18.dp))
            Text("Premium is active", color = SuccessGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onManageSubscription,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Royal),
            ) { Text("Manage subscription", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Change or cancel in Google Play. Premium stays active until the period Google Play reports has ended.",
                color = PaperMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (premium) {
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
        Spacer(Modifier.height(22.dp))
        PurchaseHistorySection(
            payments = billingStatus?.payments,
            refreshing = refreshing,
            loadFailed = purchaseHistoryFailed || (error != null && billingStatus?.payments == null)
        )
        Spacer(Modifier.height(28.dp))
        Text("Delete account", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Permanently deletes this account, signs you out, and removes the data we store for it. This cannot be undone.",
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        if (!showDeleteConfirmation) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showDeleteConfirmation = true },
                enabled = !deletingAccount,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ErrorRed.copy(alpha = .6f))
            ) {
                Text("Delete account", color = ErrorRed, fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassSurface(RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Text("Delete this account?", color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (requiresPassword) {
                        "Enter your password to confirm. You will be signed out of this device."
                    } else {
                        "Complete the security check to confirm. You will be signed out of this device."
                    },
                    color = PaperMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                if (requiresPassword) {
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !deletingAccount
                    )
                } else {
                    TurnstileWebView(
                        resetKey = turnstileResetKey,
                        executeVersion = turnstileExecuteVersion,
                        isReady = turnstileReady,
                        showInteractive = true,
                        onToken = { turnstileToken = it },
                        onReady = {
                            turnstileReady = true
                            if (turnstileToken.isBlank()) turnstileExecuteVersion += 1
                        },
                        onInteractiveRequired = {},
                        onError = {
                            turnstileToken = ""
                            turnstileReady = false
                            turnstileResetKey += 1
                        },
                    )
                }
                deleteError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = WarningOrange, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onDeleteAccount(deletePassword, turnstileToken) },
                    enabled = !deletingAccount && (
                        if (requiresPassword) deletePassword.isNotBlank() else turnstileToken.isNotBlank()
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text(
                        if (deletingAccount) "Deleting…" else "Delete account permanently",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(
                    onClick = {
                        if (!deletingAccount) {
                            showDeleteConfirmation = false
                            deletePassword = ""
                            turnstileToken = ""
                        }
                    },
                    enabled = !deletingAccount,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Keep my account", color = CyanHover) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
    }
}

@Composable
private fun AccountIdentity(email: String?, accountId: String) {
    val shownEmail = email?.trim()?.takeIf { it.isNotEmpty() }
    val shownAccountId = accountId.trim().takeIf { it.isNotEmpty() }
    if (shownEmail == null && shownAccountId == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        if (shownEmail != null) {
            Text("EMAIL", color = PaperDim, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(4.dp))
            Text(shownEmail, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (shownAccountId != null) {
            if (shownEmail != null) Spacer(Modifier.height(14.dp))
            Text("ACCOUNT ID", color = PaperDim, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(4.dp))
            Text(shownAccountId, color = Paper, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun PurchaseHistorySection(
    payments: List<PurchaseHistoryItem>?,
    refreshing: Boolean,
    loadFailed: Boolean,
) {
    Text("Purchase history", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.purchase_history_subtitle), color = PaperMuted, fontSize = 13.sp, lineHeight = 19.sp)
    Spacer(Modifier.height(12.dp))
    when {
        payments == null && refreshing && !loadFailed -> {
            Text("Loading purchase history…", color = PaperMuted, fontSize = 14.sp)
        }
        payments == null && loadFailed -> {
            Text("Purchase history could not be loaded.", color = PaperMuted, fontSize = 14.sp)
        }
        payments == null -> {
            Text("This billing service has not sent purchase history yet.", color = PaperMuted, fontSize = 14.sp)
        }
        payments.isEmpty() -> {
            Text("No past payments yet.", color = PaperMuted, fontSize = 14.sp)
        }
        else -> {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardElevated),
                border = BorderStroke(1.dp, Line)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    payments.forEachIndexed { index, payment ->
                        if (index > 0) HorizontalDivider(color = Line)
                        PurchaseHistoryRow(payment)
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseHistoryRow(payment: PurchaseHistoryItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatBillingDate(payment.createdAt).let { if (payment.createdAt.isNullOrBlank()) "—" else it }, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                purchaseHistoryDetail(payment),
                color = PaperMuted,
                fontSize = 13.sp
            )
        }
        Text(
            purchaseStatusLabel(payment.status),
            color = purchaseStatusColor(payment.status),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatPurchaseAmount(cents: Long, currency: String?): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    val dollars = abs / 100
    val remainder = abs % 100
    val value = if (remainder == 0L) dollars.toString() else "%d.%02d".format(dollars, remainder)
    return if (currency.isNullOrBlank() || currency.equals("usd", ignoreCase = true)) {
        "$sign${'$'}$value"
    } else {
        "$sign$value ${currency.uppercase()}"
    }
}

private fun purchaseHistoryDetail(payment: PurchaseHistoryItem): String {
    val base = "${purchasePlanLabel(payment.plan)} · ${formatPurchaseAmount(payment.amountCents, payment.currency)}"
    return if (payment.provider == "google_play") "$base · Google Play" else base
}

private fun purchasePlanLabel(plan: String?): String = when (plan) {
    "annual" -> "Annual"
    "monthly" -> "Monthly"
    else -> "—"
}

private fun purchaseStatusLabel(status: String?): String = when (status) {
    "completed" -> "Confirmed"
    "failed" -> "Failed"
    "refunded" -> "Refunded"
    else -> "Pending"
}

private fun purchaseStatusColor(status: String?): Color = when (status) {
    "completed" -> SuccessGreen
    "failed", "refunded" -> WarningOrange
    else -> CyanHover
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
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (emphasized) CardElevated else CardBg),
        border = BorderStroke(
            1.dp,
            if (emphasized) Brush.linearGradient(listOf(Cyan.copy(alpha = 0.75f), Royal)) else Brush.linearGradient(listOf(Line, Line))
        )
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
