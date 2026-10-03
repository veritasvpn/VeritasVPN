package cloud.veritasvpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.LineStrong
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.PremiumBackdrop
import cloud.veritasvpn.ui.theme.PremiumEnter
import cloud.veritasvpn.ui.theme.glassSurface
import cloud.veritasvpn.ui.theme.rememberMotionEnabled
import cloud.veritasvpn.vpn.StealthMode

@Composable
fun StealthSettingsScreen(
    stealthMode: StealthMode,
    showReconnectBanner: Boolean,
    onStealthModeChange: (StealthMode) -> Unit,
    onBack: () -> Unit
) {
    PremiumBackdrop {
    PremiumEnter {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        PremiumTopBar(eyebrow = "CONNECTION", title = "Stealth", onBack = onBack)

        if (showReconnectBanner) {
            Spacer(Modifier.height(16.dp))
            SettingsReconnectBanner()
        }

        Spacer(Modifier.height(22.dp))
        Text("TRANSPORT", style = MaterialTheme.typography.labelSmall, color = PaperDim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose how VeritasVPN carries WireGuard. Auto is the default. Reconnect from Home to apply a change.",
            color = PaperMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        TransportChoice(
            title = "Auto",
            subtitle = "Try UDP first. If that handshake does not complete, switch to Stealth without dropping Always-on VPN. Recommended.",
            selected = stealthMode == StealthMode.AUTO,
            onClick = { onStealthModeChange(StealthMode.AUTO) }
        )
        Spacer(Modifier.height(8.dp))
        TransportChoice(
            title = "UDP only",
            subtitle = "Plain WireGuard over UDP. Never fall back to Stealth.",
            selected = stealthMode == StealthMode.UDP,
            onClick = { onStealthModeChange(StealthMode.UDP) }
        )
        Spacer(Modifier.height(8.dp))
        TransportChoice(
            title = "Stealth always",
            subtitle = "Start with WireGuard inside TLS. Use this on networks that block UDP.",
            selected = stealthMode == StealthMode.STEALTH,
            onClick = { onStealthModeChange(StealthMode.STEALTH) }
        )
        Spacer(Modifier.height(28.dp))
    }
    }
    }
}

@Composable
private fun TransportChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val motion = rememberMotionEnabled()
    val borderColor by animateColorAsState(
        targetValue = if (selected) Cyan.copy(alpha = 0.72f) else LineStrong.copy(alpha = 0.8f),
        animationSpec = if (motion) tween(220) else tween(0),
        label = "transport-border",
    )
    val wash by animateColorAsState(
        targetValue = if (selected) Cyan.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = if (motion) tween(220) else tween(0),
        label = "transport-wash",
    )
    Row(
        Modifier.fillMaxWidth()
            .glassSurface(RoundedCornerShape(18.dp), borderColor = borderColor)
            .background(wash)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Cyan, unselectedColor = PaperDim)
        )
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = PaperMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
