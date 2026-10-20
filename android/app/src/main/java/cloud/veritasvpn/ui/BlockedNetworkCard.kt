package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.Ink
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.WarningOrange
import cloud.veritasvpn.vpn.BlockedNetworkNotice

@Composable
fun BlockedNetworkCard(
    offerStealthSwitch: Boolean,
    suggestOtherNetwork: Boolean,
    onSwitchToStealth: () -> Unit,
    onUseMobileData: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, WarningOrange.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
            .background(WarningOrange.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            BlockedNetworkNotice.MESSAGE,
            color = Paper,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
        if (suggestOtherNetwork) {
            Text(
                BlockedNetworkNotice.OTHER_NETWORK,
                color = PaperMuted,
                fontSize = 13.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (offerStealthSwitch) {
                Button(
                    onClick = onSwitchToStealth,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                ) {
                    Text("Switch to Stealth", fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = onUseMobileData,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarningOrange.copy(alpha = 0.18f),
                    contentColor = Paper,
                ),
            ) {
                Text("Use mobile data", fontWeight = FontWeight.Bold)
            }
        }
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = PaperMuted)
        }
    }
}
