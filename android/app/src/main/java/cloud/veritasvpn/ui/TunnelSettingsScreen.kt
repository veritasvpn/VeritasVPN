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
    showReconnectBanner: Boolean,
    connected: Boolean = false,
    dnsGateway: String? = null,
    dnsBlockedThisSession: Long? = null,
    shieldPreset: String? = null,
    onShieldPresetChange: ((String) -> Unit)? = null,
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

        if (showReconnectBanner) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, WarningOrange.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .background(WarningOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Reconnect to apply",
                    color = WarningOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text("VERITAS SHIELD", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, LineStrong, RoundedCornerShape(14.dp))
                .background(CardElevated, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(
                if (connected) "Veritas Shield on" else "Veritas Shield",
                color = Paper,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (connected) {
                    buildString {
                        append(if (!dnsGateway.isNullOrBlank()) "Gateway $dnsGateway" else "Tunnel gateway")
                        if (dnsBlockedThisSession != null) {
                            append(" · $dnsBlockedThisSession blocked this session")
                        }
                    }
                } else {
                    "Always on while connected — DNS threat filtering through the tunnel gateway."
                },
                color = PaperMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Lookups use DNS-over-HTTPS upstreams. Well-known public DoH resolvers are blocked; uncommon DoH endpoints may still bypass.",
                color = PaperDim,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            if (connected && onShieldPresetChange != null) {
                Spacer(Modifier.height(12.dp))
                Text("Preset", color = PaperDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "security" to "Security — malware, phishing, scam, crypto",
                    "standard" to "Standard — + trackers (default)",
                    "aggressive" to "Aggressive — + ads"
                ).forEach { (value, label) ->
                    val selected = (shieldPreset ?: "standard") == value
                    TextButton(
                        onClick = { onShieldPresetChange(value) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (selected) Cyan else PaperMuted
                        )
                    ) {
                        Text(
                            if (selected) "● $label" else "○ $label",
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
            subtitle = "Replace 0.0.0.0/0 with public prefixes that omit RFC1918 (10/8, 172.16/12, 192.168/16) so local network traffic stays off the VPN.",
            checked = excludeLan,
            onCheckedChange = { onExcludeLanChange(it) }
        )

        Spacer(Modifier.height(22.dp))
        Text("PER-APP BYPASS", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Apps listed below skip the VPN (WireGuard ExcludedApplications → VpnService addDisallowedApplication). One Android package name per line.",
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Find package names: Settings → Apps → [app] → App info (or “Advanced”), or connect the device and run adb shell pm list packages | grep name.",
            color = PaperDim,
            fontSize = 12.sp,
            lineHeight = 16.sp
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

        Spacer(Modifier.height(22.dp))
        Text("STEALTH", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Stealth mode (WireGuard over TLS/WebSocket) is available on the Linux desktop app — not in this Android build yet.",
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp
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
