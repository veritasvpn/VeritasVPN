package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.*

@Composable
fun TunnelSettingsScreen(
    excludeLan: Boolean,
    bypassAppsText: String,
    onExcludeLanChange: (Boolean) -> Unit,
    onBypassAppsChange: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = CyanHover)
            }
            Column {
                Text("TUNNEL", color = CyanHover, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Split tunnel", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Spacer(Modifier.height(18.dp))

        Text("ROUTING", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))

        SettingToggleRow(
            title = "Full tunnel",
            subtitle = "Send all traffic through the VPN (AllowedIPs from server, typically 0.0.0.0/0).",
            checked = !excludeLan,
            onCheckedChange = { if (it) onExcludeLanChange(false) }
        )
        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            title = "Exclude private LAN",
            subtitle = "Replace 0.0.0.0/0 with public prefixes that omit RFC1918 (10/8, 172.16/12, 192.168/16) so local network traffic stays off the VPN. Reconnect to apply.",
            checked = excludeLan,
            onCheckedChange = { onExcludeLanChange(it) }
        )

        Spacer(Modifier.height(22.dp))
        Text("PER-APP BYPASS", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Package names listed below use VpnService.Builder.addDisallowedApplication (via WireGuard ExcludedApplications). One package per line. Reconnect to apply.",
            color = PaperMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = bypassAppsText,
            onValueChange = onBypassAppsChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = {
                Text("com.example.bank\ncom.example.localapp", color = PaperDim)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                focusedBorderColor = CyanHover,
                unfocusedBorderColor = LineStrong,
                cursorColor = CyanHover,
                focusedContainerColor = CardElevated,
                unfocusedContainerColor = CardElevated
            ),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (checked) CyanHover.copy(alpha = 0.4f) else LineStrong, RoundedCornerShape(14.dp))
            .background(CardElevated, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = PaperMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Cyan,
                uncheckedThumbColor = PaperDim,
                uncheckedTrackColor = Ink3
            )
        )
    }
}
