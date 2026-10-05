package cloud.veritasvpn.ui

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.Ink
import cloud.veritasvpn.ui.theme.Ink3
import cloud.veritasvpn.ui.theme.LineStrong
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.PremiumBackdrop
import cloud.veritasvpn.ui.theme.PremiumEnter
import cloud.veritasvpn.ui.theme.glassSurface
import cloud.veritasvpn.vpn.ShieldPolicy

@Composable
fun ShieldSettingsScreen(
    policy: ShieldPolicy,
    isPremium: Boolean,
    connected: Boolean,
    error: String?,
    onPolicyChange: (ShieldPolicy) -> Unit,
    onUpgrade: () -> Unit,
    onBack: () -> Unit,
) {
    PremiumBackdrop {
    PremiumEnter {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        PremiumTopBar(eyebrow = "CONNECTION", title = "Veritas Shield", onBack = onBack)

        Spacer(Modifier.height(22.dp))
        Text("ADVANCED VPN PROTECTION", style = MaterialTheme.typography.labelSmall, color = PaperDim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Blocks known domains in DNS while you are connected. Ads or trackers loaded from the site itself can still run.",
            color = PaperMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (!isPremium) {
                "These filters are Premium. Upgrade to turn them on."
            } else if (connected) {
                "Changes apply to this connection immediately."
            } else {
                "Saved on this device. Applied the next time you connect."
            },
            color = PaperDim,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = CyanHover, fontSize = 12.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(14.dp))
        ShieldToggleRow(
            title = "Block ads",
            subtitle = "Stop known ad domains from resolving. A page can still show an ad that is not a separate domain.",
            checked = policy.blockAds,
            locked = !isPremium,
            onCheckedChange = { onPolicyChange(policy.copy(blockAds = it)) },
            onUpgrade = onUpgrade,
        )
        Spacer(Modifier.height(8.dp))
        ShieldToggleRow(
            title = "Block malicious sites",
            subtitle = "Malware, phishing, scam, and cryptomining domains.",
            checked = policy.blockMalicious,
            locked = !isPremium,
            onCheckedChange = { onPolicyChange(policy.copy(blockMalicious = it)) },
            onUpgrade = onUpgrade,
        )
        Spacer(Modifier.height(8.dp))
        ShieldToggleRow(
            title = "Block trackers",
            subtitle = "Stop known tracker domains from resolving. A tracker served from the site itself can still run.",
            checked = policy.blockTrackers,
            locked = !isPremium,
            onCheckedChange = { onPolicyChange(policy.copy(blockTrackers = it)) },
            onUpgrade = onUpgrade,
        )
        Spacer(Modifier.height(8.dp))
        ShieldToggleRow(
            title = "Block adult sites",
            subtitle = "Known adult domains. Shield does not scan page content.",
            checked = policy.blockAdult,
            locked = !isPremium,
            onCheckedChange = { onPolicyChange(policy.copy(blockAdult = it)) },
            onUpgrade = onUpgrade,
        )
        Spacer(Modifier.height(28.dp))
    }
    }
    }
}

@Composable
private fun ShieldToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    locked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onUpgrade: () -> Unit,
) {
    val border = if (!locked && checked) CyanHover.copy(alpha = 0.55f) else LineStrong.copy(alpha = 0.85f)
    Row(
        Modifier.fillMaxWidth()
            .glassSurface(RoundedCornerShape(18.dp), borderColor = border)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = PaperMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (locked) {
            Text(
                "Upgrade",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Cyan)
                    .clickable(onClick = onUpgrade)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Cyan,
                    uncheckedThumbColor = PaperDim,
                    uncheckedTrackColor = Ink3,
                )
            )
        }
    }
}
