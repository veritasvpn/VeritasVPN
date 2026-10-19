package cloud.veritasvpn.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.veritasvpn.R
import cloud.veritasvpn.ui.theme.BlueDeep
import cloud.veritasvpn.ui.theme.CardElevated
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.ErrorRed
import cloud.veritasvpn.ui.theme.LineStrong
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.PremiumBackdrop
import cloud.veritasvpn.ui.theme.PremiumEnter
import cloud.veritasvpn.ui.theme.Royal
import cloud.veritasvpn.ui.theme.VeritasVPNTheme
import cloud.veritasvpn.ui.theme.glassSurface

internal object VerifyEmailResend {
    const val COOLDOWN_SECONDS = 30

    fun canResend(loading: Boolean, cooldownSeconds: Int): Boolean =
        !loading && cooldownSeconds <= 0

    fun label(loading: Boolean, cooldownSeconds: Int): Label = when {
        loading -> Label.Sending
        cooldownSeconds > 0 -> Label.Countdown(cooldownSeconds)
        else -> Label.Ready
    }

    sealed class Label {
        data object Sending : Label()
        data class Countdown(val seconds: Int) : Label()
        data object Ready : Label()
    }
}

/** Keeps the address readable while letting long values wrap at @ and dots. */
internal fun displayEmail(email: String): String =
    email.replace("@", "\u200B@").replace(".", ".\u200B")

internal object EmailInbox {
    fun open(context: Context): Boolean {
        if (start(context, inboxIntent())) return true
        return start(context, mailtoIntent())
    }

    internal fun inboxIntent(): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    internal fun mailtoIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun start(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
}

@Composable
fun VerifyEmailScreen(
    email: String,
    notice: String?,
    error: String?,
    retryStatus: String?,
    resendLoading: Boolean,
    resendCooldownSeconds: Int,
    showOpenVpnSettings: Boolean,
    onBackToSignIn: () -> Unit,
    onResend: () -> Unit,
    onOpenEmailApp: () -> Unit,
    onOpenVpnSettings: () -> Unit,
) {
    BackHandler(onBack = onBackToSignIn)
    val canResend = VerifyEmailResend.canResend(resendLoading, resendCooldownSeconds)
    val resendLabel = when (val label = VerifyEmailResend.label(resendLoading, resendCooldownSeconds)) {
        VerifyEmailResend.Label.Sending -> stringResource(R.string.verify_email_sending)
        is VerifyEmailResend.Label.Countdown -> stringResource(R.string.verify_email_resend_in, label.seconds)
        VerifyEmailResend.Label.Ready -> stringResource(R.string.verify_email_resend)
    }
    val bannerError = error?.takeIf { it.isNotBlank() }
    val bannerNotice = notice?.takeIf { it.isNotBlank() }

    PremiumBackdrop {
        PremiumEnter {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PremiumTopBar(
                    eyebrow = stringResource(R.string.verify_email_eyebrow),
                    title = stringResource(R.string.verify_email_title),
                    onBack = onBackToSignIn,
                    backContentDescription = stringResource(R.string.verify_email_back),
                )
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(RoundedCornerShape(28.dp))
                        .padding(horizontal = 20.dp, vertical = 28.dp)
                        .semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MailBadge()
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(R.string.verify_email_sent_to),
                        color = PaperMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        displayEmail(email),
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanHover,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.verify_email_body),
                        color = PaperMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.verify_email_spam),
                        color = PaperDim,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                if (bannerError != null || bannerNotice != null) {
                    Spacer(Modifier.height(16.dp))
                    VerifyBanner(
                        message = bannerError ?: bannerNotice.orEmpty(),
                        isError = bannerError != null,
                    )
                }
                if (showOpenVpnSettings) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenVpnSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(26.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Royal),
                    ) {
                        Text(
                            stringResource(R.string.action_open_vpn_settings),
                            modifier = Modifier.weight(1f, fill = false),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (resendLoading && !retryStatus.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        retryStatus,
                        color = CyanHover,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onOpenEmailApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .shadow(
                            18.dp,
                            RoundedCornerShape(26.dp),
                            ambientColor = Royal.copy(alpha = 0.38f),
                            spotColor = Cyan.copy(alpha = 0.18f),
                        ),
                    shape = RoundedCornerShape(26.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Royal),
                ) {
                    Icon(
                        Icons.Rounded.Email,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.verify_email_open_app),
                        modifier = Modifier.weight(1f, fill = false),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onResend,
                    enabled = canResend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(26.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    border = BorderStroke(1.dp, if (canResend) CyanHover else LineStrong),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CyanHover,
                        disabledContentColor = PaperMuted,
                    ),
                ) {
                    when {
                        resendLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CyanHover,
                        )
                        resendCooldownSeconds > 0 -> Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (resendLoading || resendCooldownSeconds > 0) Spacer(Modifier.width(8.dp))
                    Text(
                        resendLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = onBackToSignIn,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.verify_email_back),
                        modifier = Modifier.weight(1f, fill = false),
                        color = CyanHover,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MailBadge() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(108.dp)) {
        Box(
            Modifier
                .size(108.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Cyan.copy(alpha = 0.34f),
                            Cyan.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .size(72.dp)
                .shadow(
                    16.dp,
                    CircleShape,
                    ambientColor = Cyan.copy(alpha = 0.30f),
                    spotColor = Royal.copy(alpha = 0.22f),
                )
                .background(
                    Brush.linearGradient(listOf(CardElevated, BlueDeep)),
                    CircleShape,
                )
                .border(BorderStroke(1.dp, Cyan.copy(alpha = 0.55f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Mail,
                contentDescription = null,
                tint = CyanHover,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun VerifyBanner(message: String, isError: Boolean) {
    val tint = if (isError) ErrorRed else CyanHover
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.38f)), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Check,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            color = if (isError) ErrorRed else Paper,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Verify email", widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF010814)
@Preview(name = "Verify email small", widthDp = 320, heightDp = 640, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF010814)
@Composable
private fun VerifyEmailCooldownPreview() {
    VeritasVPNTheme {
        VerifyEmailScreen(
            email = "jgv9710@gmail.com",
            notice = null,
            error = null,
            retryStatus = null,
            resendLoading = false,
            resendCooldownSeconds = 30,
            showOpenVpnSettings = false,
            onBackToSignIn = {},
            onResend = {},
            onOpenEmailApp = {},
            onOpenVpnSettings = {},
        )
    }
}

@Preview(name = "Verify email sent", widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF010814)
@Composable
private fun VerifyEmailSentPreview() {
    VeritasVPNTheme {
        VerifyEmailScreen(
            email = "jgv9710@gmail.com",
            notice = "A new verification link was sent.",
            error = null,
            retryStatus = null,
            resendLoading = false,
            resendCooldownSeconds = 30,
            showOpenVpnSettings = false,
            onBackToSignIn = {},
            onResend = {},
            onOpenEmailApp = {},
            onOpenVpnSettings = {},
        )
    }
}
