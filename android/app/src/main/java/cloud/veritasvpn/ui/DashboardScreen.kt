package cloud.veritasvpn.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    onDevices: () -> Unit,
    onPortForwards: () -> Unit,
    onTunnelSettings: () -> Unit,
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
    dnsGateway: String? = null
) {
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var showSignOutEverywhereConfirmation by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showNetworkMap by remember { mutableStateOf(false) }

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
            containerColor = CardElevated
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
            containerColor = CardElevated
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
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
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            IconButton(
                onClick = { showSettingsMenu = true },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(CardElevated)
                    .border(1.dp, LineStrong, CircleShape)
            ) {
                Icon(Icons.Rounded.Settings, "Open settings", tint = CyanHover, modifier = Modifier.size(21.dp))
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
        Spacer(Modifier.height(16.dp))

        // Status Orb
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrivacyExposureScene(encrypted = connected)

            Spacer(Modifier.height(22.dp))

            if (!connected) {
                DisconnectedActionContent(
                    isPremium = isPremium,
                    billingReady = billingReady,
                    connecting = connecting,
                    onPlans = onPlans,
                    onConnect = onConnect
                )
            } else {
                Text(
                    text = "CONNECTION SECURED",
                    color = PaperDim,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "You're protected",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }

            Spacer(Modifier.height(24.dp))

            // Never expose a connect action until billing confirms Premium.
            if (connected) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed,
                    disabledContainerColor = Royal.copy(alpha = 0.45f)
                )
            ) {
                Text(
                    text = "Disconnect",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
            }

            if (connected) {
                Spacer(Modifier.height(14.dp))
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

    SettingsDrawer(
        open = showSettingsMenu,
        onDismiss = { showSettingsMenu = false },
        isPremium = isPremium,
        onPlans = onPlans,
        onNetworkMap = { showNetworkMap = true },
        onDevices = onDevices,
        onPortForwards = onPortForwards,
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
            .clip(RoundedCornerShape(16.dp))
            .background(CardElevated)
            .border(1.dp, LineStrong, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("LIVE STATS", color = PaperDim, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
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
                Text("DNS blocked this session", color = PaperMuted, fontSize = 13.sp)
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
        Text("Protected DNS on", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(if (!dnsGateway.isNullOrBlank()) "Gateway $dnsGateway" else "Tunnel gateway")
                append(" · malware/phishing blocks via DoH upstreams. Apps with their own DoH may bypass.")
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
            Text("NETWORK MAP", color = CyanHover, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Your secure route", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
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
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, LineStrong)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
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
}

@Composable
private fun DisconnectedActionContent(
    isPremium: Boolean,
    billingReady: Boolean,
    connecting: Boolean,
    onPlans: () -> Unit,
    onConnect: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "disconnected badge")
    val dotScale by pulse.animateFloat(
        initialValue = .75f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "status dot pulse"
    )
    val glowAlpha by pulse.animateFloat(
        initialValue = .16f,
        targetValue = .34f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "premium button glow"
    )

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
            if (connecting) "ESTABLISHING SECURE CONNECTION" else "VPN DISCONNECTED",
            color = if (connecting) CyanHover else WarningOrange,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp
        )
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "Your online activity\nis visible",
        color = Paper,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 29.sp,
        lineHeight = 34.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (connecting) "Creating secure keys and validating encrypted internet access." else "Hide your IP address and encrypt your connection.",
        color = PaperMuted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = Royal.copy(alpha = glowAlpha), spotColor = Cyan.copy(alpha = glowAlpha))
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Cyan, RoyalHover, Royal)))
            .clickable(enabled = billingReady && !connecting, onClick = if (isPremium) onConnect else onPlans),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connecting || !billingReady) {
                CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (isPremium) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                if (connecting) "Connecting…"
                else if (!billingReady) "Checking plan…"
                else if (isPremium) "Connect now"
                else "Get Premium",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (!connecting && billingReady) {
                Spacer(Modifier.width(10.dp))
                Text("→", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PrivacyExposureScene(encrypted: Boolean) {
    val accent = if (encrypted) SuccessGreen else WarningOrange
    val transition = rememberInfiniteTransition(label = "visible traffic")
    val trafficPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "traffic movement"
    )
    val observerPulse by transition.animateFloat(
        initialValue = .96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "observer pulse"
    )
    val warningAlpha by transition.animateFloat(
        initialValue = .55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "warning blink"
    )
    var activityIndex by remember { mutableIntStateOf(0) }
    val visibleActivities = remember(encrypted) {
        if (encrypted) listOf("Your activity is private", "Your IP address is hidden", "Trackers cannot inspect traffic")
        else listOf("Sites you visit", "Your IP address", "Searches and activity")
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1900)
            activityIndex = (activityIndex + 1) % visibleActivities.size
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(310.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = .12f), CardElevated, Ink),
                    radius = 780f
                )
            )
            .border(1.dp, accent.copy(alpha = .2f), RoundedCornerShape(24.dp))
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
            transitionSpec = { fadeIn(tween(350)) + slideInVertically { it / 2 } togetherWith fadeOut(tween(250)) },
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
                .size(72.dp)
                .clip(CircleShape)
                .background(CardElevated)
                .border(1.dp, accent.copy(alpha = .45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (encrypted) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    if (encrypted) "Your protected device" else "Your unprotected device",
                    tint = accent,
                    modifier = Modifier.size(25.dp)
                )
                Text("YOU", color = Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
