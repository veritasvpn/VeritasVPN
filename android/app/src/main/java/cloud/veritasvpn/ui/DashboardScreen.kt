package cloud.veritasvpn.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.*

@Composable
fun DashboardScreen(
    connected: Boolean,
    connecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutEverywhere: () -> Unit,
    onPlans: () -> Unit,
    onStealthSettings: () -> Unit,
    onShieldSettings: () -> Unit,
    onTunnelSettings: () -> Unit,
    onOpenKillSwitchSettings: () -> Unit,
    showKillSwitchRequired: Boolean,
    onDismissKillSwitchRequired: () -> Unit,
    isPremium: Boolean,
    billingReady: Boolean,
    statusMsg: String?,
    deviceLatitude: Double?,
    deviceLongitude: Double?,
    rxBytes: Long = 0,
    txBytes: Long = 0,
    handshakeMs: Long = 0,
    dnsBlockedCount: Long? = null,
    dnsBlockedBaseline: Long? = null,
    dnsGateway: String? = null,
    transport: String = "",
) {
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var showSignOutEverywhereConfirmation by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showNetworkMap by remember { mutableStateOf(false) }

    if (showKillSwitchRequired) {
        KillSwitchRequiredDialog(
            onOpenSystemVpnSettings = onOpenKillSwitchSettings,
            onCancel = onDismissKillSwitchRequired
        )
    }

    if (showSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmation = false },
            title = { Text("Sign out from this device?", color = Paper, fontWeight = FontWeight.Bold) },
            text = { Text("Signing out will disconnect your VPN. Continue?", color = PaperMuted) },
            confirmButton = {
                Button(
                    onClick = { showSignOutConfirmation = false; onSignOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Sign out from this device", color = Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showSignOutConfirmation = false }) { Text("Cancel", color = CyanHover) } },
            containerColor = CardElevated,
            shape = RoundedCornerShape(28.dp),
        )
    }
    if (showSignOutEverywhereConfirmation) {
        AlertDialog(
            onDismissRequest = { showSignOutEverywhereConfirmation = false },
            title = { Text("Sign out from all devices?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This revokes all sessions on every device, disconnects VPN on this device, and signs you out locally.",
                    color = PaperMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = { showSignOutEverywhereConfirmation = false; onSignOutEverywhere() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Sign out from all devices", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutEverywhereConfirmation = false }) {
                    Text("Cancel", color = CyanHover)
                }
            },
            containerColor = CardElevated,
            shape = RoundedCornerShape(28.dp),
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
    PremiumBackdrop {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(cloud.veritasvpn.R.drawable.veritas_logo),
                contentDescription = "VeritasVPN shield",
                modifier = Modifier.size(44.dp),
                contentScale = ContentScale.Fit,
            )
            GlassIconButton(onClick = { showSettingsMenu = true }, contentDescription = "Open settings") {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = CyanHover, modifier = Modifier.size(21.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        if (showNetworkMap) {
            NetworkMapView(
                connected = connected,
                connecting = connecting,
                deviceLatitude = deviceLatitude,
                deviceLongitude = deviceLongitude,
                onBack = { showNetworkMap = false }
            )
        } else {
        Spacer(Modifier.height(28.dp))

        val motion = rememberMotionEnabled()
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (connected || connecting) {
                AnimatedContent(
                    targetState = connected,
                    transitionSpec = {
                        if (motion) {
                            (fadeIn(tween(320)) + slideInVertically { it / 8 }) togetherWith
                                (fadeOut(tween(180)) + slideOutVertically { -it / 10 })
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "home-status",
                ) { isConnected ->
                    if (isConnected) {
                        ProtectedStatusCopy(transport = transport)
                    } else {
                        ConnectingStatusCopy(transport = transport)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            HeroConnectControl(
                phase = when {
                    connected -> HeroPhase.Protected
                    connecting -> HeroPhase.Connecting
                    !billingReady -> HeroPhase.Checking
                    isPremium -> HeroPhase.Ready
                    else -> HeroPhase.Upsell
                },
                onClick = when {
                    connecting || !billingReady -> null
                    connected -> onDisconnect
                    isPremium -> onConnect
                    else -> onPlans
                },
            )

            AnimatedVisibility(
                visible = connected,
                enter = if (motion) fadeIn(tween(360)) + expandVertically(tween(380)) else EnterTransition.None,
                exit = if (motion) fadeOut(tween(180)) + shrinkVertically(tween(200)) else ExitTransition.None,
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(20.dp))
                    LiveTransferStats(
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        handshakeMs = handshakeMs,
                        dnsBlockedThisSession = if (dnsBlockedCount != null && dnsBlockedBaseline != null) {
                            (dnsBlockedCount - dnsBlockedBaseline).coerceAtLeast(0)
                        } else null,
                        dnsGateway = dnsGateway
                    )
                }
            }

            // Status message
            statusMsg?.takeUnless {
                connecting && (
                    it.startsWith("Connecting", ignoreCase = true) ||
                        it.startsWith("Reconnecting", ignoreCase = true)
                    )
            }?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = when { connected -> SuccessGreen; connecting -> CyanHover; else -> WarningOrange },
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        }
    }
    }

    SettingsDrawer(
        open = showSettingsMenu,
        onDismiss = { showSettingsMenu = false },
        onPlans = onPlans,
        onNetworkMap = { showNetworkMap = true },
        onStealthSettings = onStealthSettings,
        onShieldSettings = onShieldSettings,
        onTunnelSettings = onTunnelSettings,
        onSignOut = {
            if (connected || connecting) showSignOutConfirmation = true else onSignOut()
        },
        onSignOutEverywhere = { showSignOutEverywhereConfirmation = true },
    )
    }
}

@Composable
private fun LiveTransferStats(
    rxBytes: Long,
    txBytes: Long,
    handshakeMs: Long,
    dnsBlockedThisSession: Long?,
    dnsGateway: String?
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text("LIVE STATS", style = MaterialTheme.typography.labelSmall, color = PaperDim)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell(label = "Download", value = formatBytes(rxBytes))
            StatCell(label = "Upload", value = formatBytes(txBytes))
            StatCell(label = "Handshake", value = formatHandshakeAge(handshakeMs))
        }
        if (dnsBlockedThisSession != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Shield blocked this session", color = PaperMuted, fontSize = 13.sp)
                Text(
                    dnsBlockedThisSession.toString(),
                    color = Paper,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(10.dp))
        Text("Veritas Shield on", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(if (!dnsGateway.isNullOrBlank()) "Gateway $dnsGateway" else "Tunnel gateway")
                append(" · malware/phishing blocks via DoH upstreams. Well-known public DoH resolvers are blocked.")
            },
            color = PaperDim,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = PaperDim, fontSize = 11.sp)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

private fun formatHandshakeAge(handshakeMs: Long): String {
    if (handshakeMs <= 0L) return "—"
    val ageSec = ((System.currentTimeMillis() - handshakeMs) / 1000L).coerceAtLeast(0)
    return when {
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        else -> "${ageSec / 3600}h ago"
    }
}

@Composable
private fun NetworkMapView(
    connected: Boolean,
    connecting: Boolean,
    deviceLatitude: Double?,
    deviceLongitude: Double?,
    onBack: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("NETWORK MAP", style = MaterialTheme.typography.labelSmall, color = CyanHover)
            Text("Your secure route", style = MaterialTheme.typography.headlineMedium, color = Paper)
        }
        TextButton(onClick = onBack) { Text("Back", color = CyanHover, fontWeight = FontWeight.SemiBold) }
    }
    Spacer(Modifier.height(18.dp))
    ConnectionMap(
        connected = connected,
        connecting = connecting,
        deviceLatitude = deviceLatitude,
        deviceLongitude = deviceLongitude
    )
    Spacer(Modifier.height(18.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(20.dp))
            .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CONNECTION", color = PaperDim, fontSize = 10.sp, letterSpacing = 1.2.sp)
                Text(
                    when { connected -> "Encrypted route active"; connecting -> "Establishing route…"; else -> "No secure route" },
                    color = Paper,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                when { connected -> "SECURED"; connecting -> "CONNECTING"; else -> "OFFLINE" },
                color = when { connected -> SuccessGreen; connecting -> CyanHover; else -> WarningOrange },
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
    }
}

@Composable
private fun transportLabel(transport: String): String? = when (transport) {
    "udp" -> "Direct UDP"
    "stealth" -> "Stealth"
    "switching" -> "Switching to Stealth…"
    else -> null
}

@Composable
private fun ProtectedStatusCopy(transport: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "CONNECTION SECURED",
            style = MaterialTheme.typography.labelSmall,
            color = Cyan,
        )
        transportLabel(transport)?.let { label ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                color = if (transport == "switching") WarningOrange else PaperMuted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background((if (transport == "switching") WarningOrange else Cyan).copy(alpha = 0.1f))
                    .border(
                        1.dp,
                        (if (transport == "switching") WarningOrange else Cyan).copy(alpha = 0.28f),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ConnectingStatusCopy(transport: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            when {
                transport == "switching" -> "SWITCHING TO STEALTH"
                transport == "stealth" -> "CONNECTING OVER STEALTH"
                else -> "ESTABLISHING SECURE CONNECTION"
            },
            color = CyanHover,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                transport == "switching" ->
                    "UDP did not complete a handshake. Switching to Stealth. The VPN stays on."
                transport == "stealth" ->
                    "Connecting over Stealth. WireGuard stays inside the VPN."
                else -> "Creating secure keys and validating encrypted internet access."
            },
            color = PaperMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun KillSwitchRequiredDialog(
    onOpenSystemVpnSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Kill switch required", color = Paper, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Connect stays blocked until Android’s fail-closed VPN settings are on for VeritasVPN. The app cannot turn these on for you:",
                    color = PaperMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Text(
                    "1. Always-on VPN",
                    color = Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "2. Block connections without VPN",
                    color = Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Open system VPN settings and select VeritasVPN. If another VPN app is already listed, Always-on must be VeritasVPN, not that app. Enable both switches, then return here. Connect continues only after both are detected. There is no in-app off switch and no way to connect without them.",
                    color = PaperDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSystemVpnSettings,
                colors = ButtonDefaults.buttonColors(containerColor = CyanHover)
            ) {
                Text("Open VPN settings", color = Ink, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = CyanHover) }
        },
        containerColor = CardElevated,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
fun ReleaseLockdownDialog(
    onOpenSystemVpnSettings: () -> Unit,
    onDismiss: () -> Unit,
    settingsError: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Internet is still blocked", color = Paper, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "VeritasVPN is disconnected. Android is still blocking traffic because Always-on VPN and Block connections without VPN stay on. This app cannot turn those switches off.",
                    color = PaperMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    "1. Open VPN settings and select VeritasVPN",
                    color = Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "2. Turn off Always-on VPN",
                    color = Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "3. Turn off Block connections without VPN",
                    color = Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "While the tunnel is connected, those switches stay required and traffic stays fail-closed. After you disconnect, turn them off to use your normal connection.",
                    color = PaperDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                settingsError?.let {
                    Text(it, color = WarningOrange, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSystemVpnSettings,
                colors = ButtonDefaults.buttonColors(containerColor = CyanHover),
            ) {
                Text("Open VPN settings", color = Ink, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now", color = CyanHover) }
        },
        containerColor = CardElevated,
        shape = RoundedCornerShape(28.dp),
    )
}
