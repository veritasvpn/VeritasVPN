package cloud.veritasvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.R
import cloud.veritasvpn.support.DiagnosticSnapshot
import cloud.veritasvpn.support.SupportLinks
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.CyanSoft
import cloud.veritasvpn.ui.theme.Ink3
import cloud.veritasvpn.ui.theme.LineStrong
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.PremiumBackdrop
import cloud.veritasvpn.ui.theme.PremiumEnter
import cloud.veritasvpn.ui.theme.WarningOrange
import cloud.veritasvpn.ui.theme.glassSurface

private data class OssNotice(val name: String, val license: String)

private val OSS_NOTICES = listOf(
    OssNotice("AndroidX and Jetpack Compose", "Apache License 2.0"),
    OssNotice("OkHttp", "Apache License 2.0"),
    OssNotice("Gson", "Apache License 2.0"),
    OssNotice("WireGuard Android tunnel", "Apache License 2.0"),
    OssNotice("AndroidSVG", "Apache License 2.0"),
)

@Composable
fun HelpScreen(
    shareDiagnostics: Boolean,
    onShareDiagnosticsChange: (Boolean) -> Unit,
    connected: Boolean,
    connecting: Boolean,
    handshakeEpochMs: Long,
    transport: String,
    lastError: String?,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val versionLabel = remember {
        val report = DiagnosticSnapshot.assemble(
            context = context,
            connected = false,
            connecting = false,
            handshakeEpochMs = 0L,
            nowMs = 0L,
            transport = "",
            lastError = null,
        )
        report.versionLabel()
    }

    fun currentReportText(): String = DiagnosticSnapshot.assemble(
        context = context,
        connected = connected,
        connecting = connecting,
        handshakeEpochMs = handshakeEpochMs,
        nowMs = System.currentTimeMillis(),
        transport = transport,
        lastError = lastError,
    ).text()

    BackHandler {
        if (showLicenses) showLicenses = false else onBack()
    }

    PremiumBackdrop {
        PremiumEnter {
            if (showLicenses) {
                AcknowledgementsPage(onBack = { showLicenses = false })
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    PremiumTopBar(
                        eyebrow = "HELP",
                        title = "Help and Support",
                        onBack = onBack,
                        backContentDescription = "Back",
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Diagnostic details stay on this device unless you choose to share them.",
                        color = PaperMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    HelpSection(title = "Contact") {
                        HelpNavRow(
                            label = "Contact us",
                            note = SupportLinks.CONTACT_EMAIL,
                            icon = Icons.Rounded.Email,
                            onClick = {
                                actionError = null
                                val opened = SupportLinks.launchContact(
                                    context,
                                    includeDiagnostics = shareDiagnostics,
                                    reportText = currentReportText(),
                                )
                                if (!opened) {
                                    actionError = "No email app found. Write to ${SupportLinks.CONTACT_EMAIL}."
                                }
                            },
                        )
                    }
                    Text(
                        "Contact opens your email app. A redacted diagnostic report is included only when sharing is on.",
                        color = PaperDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp),
                    )
                    if (!actionError.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(actionError!!, color = WarningOrange, fontSize = 12.sp, lineHeight = 17.sp)
                    }

                    HelpSection(title = "Guides") {
                        HelpNavRow(
                            label = "How to use the VPN",
                            note = "Open the support guides",
                            icon = Icons.AutoMirrored.Rounded.MenuBook,
                            onClick = {
                                actionError = null
                                if (!SupportLinks.openHttps(context, SupportLinks.SUPPORT)) {
                                    actionError = "Could not open the support page."
                                }
                            },
                        )
                        HelpNavRow(
                            label = "I can't get connected",
                            note = "Connection and Stealth",
                            icon = Icons.Rounded.PowerSettingsNew,
                            onClick = {
                                actionError = null
                                if (!SupportLinks.openHttps(context, SupportLinks.SUPPORT_CONNECT)) {
                                    actionError = "Could not open the support page."
                                }
                            },
                        )
                        HelpNavRow(
                            label = "I can connect but there is a problem",
                            note = "Still protected, something else is wrong",
                            icon = Icons.Rounded.ErrorOutline,
                            onClick = {
                                actionError = null
                                if (!SupportLinks.openHttps(context, SupportLinks.SUPPORT)) {
                                    actionError = "Could not open the support page."
                                }
                            },
                        )
                    }

                    HelpSection(title = "Diagnostics") {
                        ShareDiagnosticsToggle(
                            checked = shareDiagnostics,
                            onCheckedChange = onShareDiagnosticsChange,
                        )
                        Spacer(Modifier.height(8.dp))
                        HelpNavRow(
                            label = "Diagnostic information",
                            note = "Connection, version, and endpoint",
                            icon = Icons.Rounded.Build,
                            onClick = onOpenDiagnostics,
                        )
                    }

                    HelpSection(title = "Legal") {
                        HelpNavRow(
                            label = "Privacy Policy",
                            icon = Icons.Rounded.Policy,
                            onClick = {
                                actionError = null
                                if (!SupportLinks.openHttps(context, SupportLinks.PRIVACY)) {
                                    actionError = "Could not open the privacy policy."
                                }
                            },
                        )
                        HelpNavRow(
                            label = "Terms of Service",
                            icon = Icons.Rounded.Description,
                            onClick = {
                                actionError = null
                                if (!SupportLinks.openHttps(context, SupportLinks.TERMS)) {
                                    actionError = "Could not open the terms of service."
                                }
                            },
                        )
                        HelpNavRow(
                            label = "Acknowledgements",
                            note = "Open-source licenses",
                            icon = Icons.AutoMirrored.Rounded.Article,
                            onClick = { showLicenses = true },
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.veritas_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "App details",
                            color = Paper,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(versionLabel, color = PaperMuted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AcknowledgementsPage(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        PremiumTopBar(
            eyebrow = "LEGAL",
            title = "Acknowledgements",
            onBack = onBack,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "VeritasVPN is licensed under the Business Source License 1.1. This build also includes the open-source components below.",
            color = PaperMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        OSS_NOTICES.forEach { notice ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .glassSurface(RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                Text(notice.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(notice.license, color = PaperMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        Text(
            text = title.uppercase(),
            color = PaperDim,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Paper.copy(alpha = 0.055f),
                            Paper.copy(alpha = 0.028f),
                        ),
                    ),
                )
                .padding(vertical = 6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun HelpNavRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    note: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(CyanSoft),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = CyanHover,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = Paper,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (note != null) {
                Text(
                    note,
                    color = PaperDim,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = PaperDim,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ShareDiagnosticsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val border = if (checked) CyanHover.copy(alpha = 0.55f) else LineStrong.copy(alpha = 0.85f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .glassSurface(RoundedCornerShape(18.dp), borderColor = border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text("Share diagnostics", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Off by default. When on, Contact us includes a redacted report. Keys and tokens stay out.",
                color = PaperMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Cyan,
                uncheckedThumbColor = PaperDim,
                uncheckedTrackColor = Ink3,
            ),
        )
    }
}
