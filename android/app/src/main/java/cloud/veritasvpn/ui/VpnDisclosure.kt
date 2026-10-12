package cloud.veritasvpn.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.ErrorRed
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.PremiumBackdrop
import cloud.veritasvpn.ui.theme.PremiumEnter
import cloud.veritasvpn.ui.theme.Royal
import cloud.veritasvpn.ui.theme.glassSurface

/**
 * Prominent disclosure shown before Android's VPN consent dialog, until the
 * user accepts. Decline does not call VpnService.prepare().
 *
 * @param title The header label (e.g., "BEFORE YOU CONNECT" or "Before you use VeritasVPN").
 */
@Composable
fun VpnDisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacy: () -> Unit,
    title: String = "BEFORE YOU CONNECT",
) {
    PremiumBackdrop {
        PremiumEnter {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(title, color = CyanHover, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "VeritasVPN will route your traffic",
                    color = Paper,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "This app creates a VPN tunnel. After you accept, Android will ask you to allow a VPN connection. If you allow it, VeritasVPN routes your device's network traffic through VeritasVPN servers in Paraguay.",
                    color = Paper,
                    fontSize = 16.sp,
                    lineHeight = 23.sp
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Text("What we collect", color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If you use an email account, we store that email address, a password hash, and session tokens. We also store device details needed to run the tunnel, including manufacturer and model on Android. We do not collect your browsing activity, the contents of your DNS queries, the contents of your traffic, or a log of your real public IP when you connect. The map shows only our server in Paraguay, not your position.",
                        color = PaperMuted,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth()) {
                    Text("Read the Privacy Policy", color = CyanHover, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Royal)
                ) {
                    Text("Accept and continue", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Decline", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Decline keeps the VPN off. You can read the policy again the next time you tap Connect.",
                    color = PaperMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}
