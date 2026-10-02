package cloud.veritasvpn.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.*
import kotlinx.coroutines.delay

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
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .border(1.dp, LineStrong.copy(alpha = 0.7f), RoundedCornerShape(13.dp)),
                contentScale = ContentScale.Crop
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
        Spacer(Modifier.height(8.dp))

        val motion = rememberMotionEnabled()
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrivacyExposureScene(encrypted = connected)

            Spacer(Modifier.height(22.dp))

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
                if (!isConnected) {
                    DisconnectedActionContent(
                        connecting = connecting,
                        transport = transport,
                    )
                } else {
                    ProtectedStatusCopy(transport = transport)
                }
            }

            Spacer(Modifier.height(8.dp))

            HeroConnectControl(
                phase = when {
                    connected -> HeroPhase.Protected
                    connecting -> HeroPhase.Connecting
                    !billingReady -> HeroPhase.Checking
                    isPremium -> HeroPhase.Ready
                    else -> HeroPhase.Upsell
                },
                onClick = when {
                    connected || connecting || !billingReady -> null
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
                    Spacer(Modifier.height(16.dp))
                    LiveTransferStats(
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        handshakeMs = handshakeMs,
                        dnsBlockedThisSession = if (dnsBlockedCount != null && dnsBlockedBaseline != null) {
                            (dnsBlockedCount - dnsBlockedBaseline).coerceAtLeast(0)
                        } else null,
                        dnsGateway = dnsGateway
                    )
                    Spacer(Modifier.height(14.dp))
                    DisconnectButton(onClick = onDisconnect)
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
        isPremium = isPremium,
        onPlans = onPlans,
        onNetworkMap = { showNetworkMap = true },
        onStealthSettings = onStealthSettings,
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = "You're protected",
            style = MaterialTheme.typography.headlineLarge,
            color = Paper,
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
private fun DisconnectButton(onClick: () -> Unit) {
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = if (motion) spring(dampingRatio = 0.75f, stiffness = 600f) else snap(),
        label = "disconnect-press",
    )
    OutlinedButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(25.dp),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ErrorRed,
            containerColor = ErrorRed.copy(alpha = 0.08f),
        ),
    ) {
        Text("Disconnect", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun DisconnectedActionContent(
    connecting: Boolean,
    transport: String,
) {
    val motion = rememberMotionEnabled()
    val dotScale = if (motion) {
        val pulse = rememberInfiniteTransition(label = "disconnected badge")
        val animated by pulse.animateFloat(
            initialValue = .88f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "status dot pulse"
        )
        animated
    } else {
        1f
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background((if (connecting) CyanHover else WarningOrange).copy(alpha = .09f))
            .border(1.dp, (if (connecting) CyanHover else WarningOrange).copy(alpha = .28f), RoundedCornerShape(50.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                .clip(CircleShape)
                .background(if (connecting) CyanHover else WarningOrange)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                connecting && transport == "switching" -> "SWITCHING TO STEALTH"
                connecting && transport == "stealth" -> "CONNECTING OVER STEALTH"
                connecting -> "ESTABLISHING SECURE CONNECTION"
                else -> "VPN DISCONNECTED"
            },
            color = if (connecting) CyanHover else WarningOrange,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
        )
    }

    Spacer(Modifier.height(14.dp))
    Text(
        if (connecting) "Securing this device" else "Your online activity\nis visible",
        color = Paper,
        style = MaterialTheme.typography.displayMedium,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            connecting && transport == "switching" ->
                "UDP did not complete a handshake. Switching to Stealth. The VPN stays on."
            connecting && transport == "stealth" ->
                "Connecting over Stealth. WireGuard stays inside the VPN."
            connecting -> "Creating secure keys and validating encrypted internet access."
            else -> "Hide your IP address and encrypt your connection."
        },
        color = PaperMuted,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )
    }
}

@Composable
private fun PrivacyExposureScene(encrypted: Boolean) {
    val accent = if (encrypted) SuccessGreen else WarningOrange
    val motion = rememberMotionEnabled()
    val trafficPhase: Float
    val observerPulse: Float
    val warningAlpha: Float
    if (motion) {
        val transition = rememberInfiniteTransition(label = "visible traffic")
        trafficPhase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "traffic movement"
        ).value
        observerPulse = transition.animateFloat(
            initialValue = .98f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "observer pulse"
        ).value
        warningAlpha = transition.animateFloat(
            initialValue = .72f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "warning blink"
        ).value
    } else {
        trafficPhase = 0.35f
        observerPulse = 1f
        warningAlpha = 1f
    }
    var activityIndex by remember { mutableIntStateOf(0) }
    val visibleActivities = remember(encrypted) {
        if (encrypted) listOf("Your activity is private", "Your IP address is hidden", "Trackers cannot inspect traffic")
        else listOf("Sites you visit", "Your IP address", "Searches and activity")
    }
    LaunchedEffect(encrypted, motion) {
        if (!motion) {
            activityIndex = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(2400)
            activityIndex = (activityIndex + 1) % visibleActivities.size
        }
    }
    val deviceScale by animateFloatAsState(
        targetValue = if (encrypted) 1f else 0.96f,
        animationSpec = if (motion) tween(420, easing = FastOutSlowInEasing) else snap(),
        label = "device-scale",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(286.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = .14f), CardElevated, Ink),
                    radius = 780f
                )
            )
            .border(1.dp, accent.copy(alpha = .28f), RoundedCornerShape(28.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val device = Offset(size.width * .5f, size.height * .82f)
            val isp = Offset(size.width * .5f, size.height * .18f)
            val leftWebsite = Offset(size.width * .17f, size.height * .49f)
            val rightWebsite = Offset(size.width * .83f, size.height * .49f)
            val scanY = size.height * trafficPhase

            drawLine(
                color = accent.copy(alpha = .08f),
                start = Offset(0f, scanY),
                end = Offset(size.width, scanY),
                strokeWidth = 2f
            )

            val destinations = listOf(isp, leftWebsite, rightWebsite)
            destinations.forEachIndexed { index, destination ->
                drawLine(
                    color = accent.copy(alpha = .24f),
                    start = device,
                    end = destination,
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                repeat(3) { particleIndex ->
                    val progress = (trafficPhase + particleIndex / 3f + index * .13f) % 1f
                    val point = Offset(
                        device.x + (destination.x - device.x) * progress,
                        device.y + (destination.y - device.y) * progress
                    )
                    drawCircle(
                        color = if (encrypted) SuccessGreen else if (index == 0) WarningOrange else CyanHover,
                        radius = if (particleIndex == 0) 5f else 3.5f,
                        center = point
                    )
                    drawCircle(
                        color = accent.copy(alpha = .16f),
                        radius = 10f,
                        center = point
                    )
                }
            }
        }

        Text(
            if (encrypted) "ENCRYPTED TRAFFIC" else "UNENCRYPTED TRAFFIC",
            color = accent.copy(alpha = warningAlpha),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        )
        Text(
            if (encrypted) "IP HIDDEN" else "IP VISIBLE",
            color = Ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = warningAlpha))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )

        ExposureNode(
            label = "YOUR ISP",
            detail = if (encrypted) "Sees encrypted data" else "Can observe traffic",
            warning = !encrypted,
            scale = observerPulse,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp)
        )
        ExposureNode(
            label = "WEBSITE",
            detail = if (encrypted) "Sees the VPN IP" else "Sees your IP",
            warning = false,
            scale = observerPulse,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp)
        )
        ExposureNode(
            label = "TRACKERS",
            detail = if (encrypted) "Traffic is obscured" else "Build a profile",
            warning = false,
            scale = observerPulse,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
        )

        AnimatedContent(
            targetState = visibleActivities[activityIndex],
            transitionSpec = {
                if (motion) {
                    fadeIn(tween(350)) + slideInVertically { it / 2 } togetherWith fadeOut(tween(250))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "visible activity",
            modifier = Modifier.align(Alignment.Center).offset(y = 43.dp)
        ) { activity ->
            Text(
                activity,
                color = Paper,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink.copy(alpha = .9f))
                    .border(1.dp, accent.copy(alpha = .24f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .size(78.dp)
                .graphicsLayer {
                    scaleX = deviceScale
                    scaleY = deviceScale
                }
                .border(1.dp, accent.copy(alpha = .22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(CardElevated, Ink2)))
                    .border(1.dp, accent.copy(alpha = .5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Crossfade(
                        targetState = encrypted,
                        animationSpec = if (motion) tween(280) else snap(),
                        label = "device-lock",
                    ) { locked ->
                        Icon(
                            if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            if (locked) "Your protected device" else "Your unprotected device",
                            tint = accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text("YOU", color = Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ExposureNode(
    label: String,
    detail: String,
    warning: Boolean,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(if (warning) WarningOrange.copy(alpha = .16f) else Royal.copy(alpha = .2f))
                .border(1.dp, if (warning) WarningOrange.copy(alpha = .5f) else CyanHover.copy(alpha = .35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(if (label.contains("ISP")) "ISP" else "WEB", color = if (warning) WarningOrange else CyanHover, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = PaperDim, fontSize = 9.sp)
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
