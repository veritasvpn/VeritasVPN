package cloud.veritasvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.support.DiagnosticSnapshot
import cloud.veritasvpn.support.SupportLinks
import cloud.veritasvpn.support.buildDiagnosticReport
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.PremiumBackdrop
import cloud.veritasvpn.ui.theme.PremiumEnter
import cloud.veritasvpn.ui.theme.Royal
import cloud.veritasvpn.ui.theme.WarningOrange
import cloud.veritasvpn.ui.theme.glassSurface
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsScreen(
    connected: Boolean,
    connecting: Boolean,
    handshakeEpochMs: Long,
    transport: String,
    lastError: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val identity = remember { DiagnosticSnapshot.identity(context) }
    val endpoints = remember(connected, transport) { DiagnosticSnapshot.endpoints(context) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val reportText = remember(
        identity,
        endpoints,
        connected,
        connecting,
        handshakeEpochMs,
        transport,
        lastError,
        nowMs,
    ) {
        buildDiagnosticReport(
            versionName = identity.versionName,
            versionCode = identity.versionCode,
            osVersion = identity.osVersion,
            device = identity.device,
            connected = connected,
            connecting = connecting,
            handshakeEpochMs = handshakeEpochMs,
            nowMs = nowMs,
            transport = transport,
            lastError = lastError,
            activeEndpoint = endpoints.active,
            wanEndpoint = endpoints.wan,
            stealthEndpoint = endpoints.stealth,
        ).text()
    }

    BackHandler(onBack = onBack)

    PremiumBackdrop {
        PremiumEnter {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                PremiumTopBar(
                    eyebrow = "DIAGNOSTICS",
                    title = "Diagnostic information",
                    onBack = onBack,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Connection state, app version, and the public endpoint. Private keys, preshared keys, and auth tokens are not included.",
                    color = PaperMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    reportText,
                    color = Paper,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(RoundedCornerShape(20.dp))
                        .padding(16.dp),
                )
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            actionError = null
                            notice = if (SupportLinks.copyReport(context, reportText)) {
                                "Copied"
                            } else {
                                actionError = "Could not copy the report."
                                null
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.45f)),
                    ) {
                        Text("Copy", color = CyanHover, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            actionError = null
                            notice = null
                            if (!SupportLinks.launchShare(context, reportText)) {
                                actionError = "Could not open the share sheet."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.45f)),
                    ) {
                        Text("Share", color = CyanHover, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            actionError = null
                            notice = null
                            if (!SupportLinks.launchEmailReport(context, reportText)) {
                                actionError = "No email app found. Write to ${SupportLinks.CONTACT_EMAIL}."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Royal),
                    ) {
                        Text("Email to support", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                if (!notice.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(notice!!, color = CyanHover, fontSize = 13.sp)
                }
                if (!actionError.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(actionError!!, color = WarningOrange, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Email from this screen always includes the report, even when sharing is off.",
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}
