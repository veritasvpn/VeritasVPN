package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import cloud.veritasvpn.api.PeerInfo
import cloud.veritasvpn.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DevicesScreen(
    peers: List<PeerInfo>,
    loading: Boolean,
    error: String?,
    currentPeerId: String?,
    revokingId: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRevoke: (PeerInfo) -> Unit
) {
    var confirmPeer by remember { mutableStateOf<PeerInfo?>(null) }

    confirmPeer?.let { peer ->
        val isCurrent = peer.id == currentPeerId
        AlertDialog(
            onDismissRequest = { if (revokingId == null) confirmPeer = null },
            title = { Text("Revoke device?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isCurrent) {
                        "This is the device connected right now. The VPN will disconnect, then the peer will be revoked."
                    } else {
                        "Remove peer ${shortId(peer.id)} (${peer.assignedIp}) from your account?"
                    },
                    color = PaperMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = confirmPeer
                        confirmPeer = null
                        if (target != null) onRevoke(target)
                    },
                    enabled = revokingId == null,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Revoke", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmPeer = null },
                    enabled = revokingId == null
                ) { Text("Cancel", color = CyanHover) }
            },
            containerColor = CardElevated
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = CyanHover)
            }
            Column(Modifier.weight(1f)) {
                Text("DEVICES", color = CyanHover, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Active peers", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            TextButton(onClick = onRefresh, enabled = !loading && revokingId == null) {
                Text("Refresh", color = CyanHover, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading && peers.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanHover)
                }
            }
            error != null && peers.isEmpty() -> {
                Text(error, color = WarningOrange, fontSize = 14.sp)
            }
            peers.isEmpty() -> {
                Text("No devices registered.", color = PaperMuted, fontSize = 14.sp)
            }
            else -> {
                if (error != null) {
                    Text(error, color = WarningOrange, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(peers, key = { it.id }) { peer ->
                        DeviceCard(
                            peer = peer,
                            isCurrent = peer.id == currentPeerId,
                            revoking = revokingId == peer.id,
                            onRevoke = { confirmPeer = peer }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    peer: PeerInfo,
    isCurrent: Boolean,
    revoking: Boolean,
    onRevoke: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (isCurrent) CyanHover.copy(alpha = 0.45f) else LineStrong, RoundedCornerShape(14.dp))
            .background(CardElevated, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                shortId(peer.id),
                color = Paper,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                peer.status.ifBlank { "unknown" }.uppercase(Locale.US),
                color = when (peer.status.lowercase(Locale.US)) {
                    "active" -> SuccessGreen
                    "pending" -> CyanHover
                    else -> PaperDim
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("IP  ${peer.assignedIp.ifBlank { "—" }}", color = PaperMuted, fontSize = 13.sp)
        Text("Created  ${formatEpoch(peer.createdAt)}", color = PaperDim, fontSize = 12.sp)
        Text("DNS blocked  ${peer.dnsBlockedCount}", color = PaperDim, fontSize = 12.sp)
        if (isCurrent) {
            Spacer(Modifier.height(4.dp))
            Text("This device", color = CyanHover, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onRevoke,
            enabled = !revoking,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.9f))
        ) {
            if (revoking) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("Revoke", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun shortId(id: String): String =
    if (id.length <= 10) id else id.take(8) + "…"

private fun formatEpoch(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "—"
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochSeconds * 1000))
    } catch (_: Exception) {
        epochSeconds.toString()
    }
}
