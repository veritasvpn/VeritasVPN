package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.WarningOrange

@Composable
internal fun SettingsReconnectBanner() {
    Row(
        Modifier.fillMaxWidth().border(1.dp, WarningOrange.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .background(WarningOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Reconnect from Home to apply these changes", color = WarningOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
