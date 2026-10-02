package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.CardElevated
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.GradientSurface
import cloud.veritasvpn.ui.theme.LineStrong
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.vpn.StealthMode

@Composable
fun StealthSettingsScreen(
    stealthMode: StealthMode,
    showReconnectBanner: Boolean,
    onStealthModeChange: (StealthMode) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(GradientSurface)).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = CyanHover)
            }
            Column {
                Text("CONNECTION", color = CyanHover, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Stealth", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        if (showReconnectBanner) {
            Spacer(Modifier.height(14.dp))
            SettingsReconnectBanner()
        }

        Spacer(Modifier.height(20.dp))
        Text("TRANSPORT", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose how VeritasVPN carries WireGuard. Auto is the default. Reconnect from Home to apply a change.",
            color = PaperMuted, fontSize = 13.sp, lineHeight = 18.sp
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

@Composable
private fun TransportChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, if (selected) CyanHover.copy(alpha = 0.4f) else LineStrong, RoundedCornerShape(14.dp))
            .background(CardElevated, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
