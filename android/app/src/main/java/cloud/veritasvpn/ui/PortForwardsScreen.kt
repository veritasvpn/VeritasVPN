package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.api.PeerInfo
import cloud.veritasvpn.api.PortForwardInfo
import cloud.veritasvpn.ui.theme.*
import java.util.Locale

@Composable
fun PortForwardsScreen(
    forwards: List<PortForwardInfo>,
    peers: List<PeerInfo>,
    loading: Boolean,
    creating: Boolean,
    deletingId: String?,
    error: String?,
    currentPeerId: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: (peerId: String, protocol: String, externalPort: Int, internalPort: Int?) -> Unit,
    onDelete: (PortForwardInfo) -> Unit
) {
    var confirmForward by remember { mutableStateOf<PortForwardInfo?>(null) }
    var peerId by remember { mutableStateOf(currentPeerId.orEmpty()) }
    var protocol by remember { mutableStateOf("tcp") }
    var externalPort by remember { mutableStateOf("") }
    var internalPort by remember { mutableStateOf("") }
    var peerMenuOpen by remember { mutableStateOf(false) }
    var protocolMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(currentPeerId) {
        if (!currentPeerId.isNullOrBlank()) peerId = currentPeerId
    }
    LaunchedEffect(peers, peerId) {
        if (peerId.isBlank() && peers.size == 1) peerId = peers.first().id
    }

    confirmForward?.let { pf ->
        AlertDialog(
            onDismissRequest = { if (deletingId == null) confirmForward = null },
            title = { Text("Delete port forward?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Remove ${(pf.egressEndpoint.ifBlank { "—" })}:${pf.externalPort} (${pf.protocol.uppercase(Locale.US)})?",
                    color = PaperMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = confirmForward
                        confirmForward = null
                        if (target != null) onDelete(target)
                    },
                    enabled = deletingId == null,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmForward = null },
                    enabled = deletingId == null
                ) { Text("Cancel", color = CyanHover) }
            },
            containerColor = CardElevated
        )
    }

    val busy = loading || creating || deletingId != null
    val atLimit = forwards.size >= 2
    val selectedPeerLabel = peers.find { it.id == peerId }?.let {
        "${shortPeerId(it.id)}${if (it.id == currentPeerId) " (this device)" else ""} · ${it.assignedIp.ifBlank { "—" }}"
    } ?: if (peers.isEmpty()) "No devices — connect first" else "Select a device…"

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
                Text("PORT FORWARDS", color = CyanHover, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Inbound DNAT", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            TextButton(onClick = onRefresh, enabled = !busy) {
                Text("Refresh", color = CyanHover, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Premium only (max 2). Traffic hits the node public IP (not Cloudflare). Open matching ports on your router toward your Dell. Recommended external ports: 40000–49999.",
            color = PaperMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))

        when {
            loading && forwards.isEmpty() -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanHover)
                }
            }
            else -> {
                if (error != null) {
                    Text(error, color = WarningOrange, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (forwards.isEmpty() && !loading) {
                        Text("No port forwards yet.", color = PaperMuted, fontSize = 14.sp)
                    }
                    forwards.forEach { pf ->
                        PortForwardCard(
                            forward = pf,
                            deleting = deletingId == pf.id,
                            onDelete = { confirmForward = pf }
                        )
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, LineStrong, RoundedCornerShape(14.dp))
                            .background(CardElevated, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Create forward", color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                        Text("Device", color = PaperDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Box {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, LineStrong, RoundedCornerShape(10.dp))
                                    .background(CardElevated, RoundedCornerShape(10.dp))
                                    .clickable(enabled = !busy && peers.isNotEmpty()) { peerMenuOpen = true }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedPeerLabel, color = Paper, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("▾", color = CyanHover)
                            }
                            DropdownMenu(
                                expanded = peerMenuOpen,
                                onDismissRequest = { peerMenuOpen = false },
                                containerColor = CardElevated
                            ) {
                                peers.forEach { peer ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${shortPeerId(peer.id)}${if (peer.id == currentPeerId) " (this device)" else ""} · ${peer.assignedIp.ifBlank { "—" }}",
                                                color = Paper
                                            )
                                        },
                                        onClick = {
                                            peerId = peer.id
                                            peerMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        Text("Protocol", color = PaperDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Box {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, LineStrong, RoundedCornerShape(10.dp))
                                    .background(CardElevated, RoundedCornerShape(10.dp))
                                    .clickable(enabled = !busy) { protocolMenuOpen = true }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(protocol.uppercase(Locale.US), color = Paper, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("▾", color = CyanHover)
                            }
                            DropdownMenu(
                                expanded = protocolMenuOpen,
                                onDismissRequest = { protocolMenuOpen = false },
                                containerColor = CardElevated
                            ) {
                                listOf("tcp", "udp").forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value.uppercase(Locale.US), color = Paper) },
                                        onClick = {
                                            protocol = value
                                            protocolMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = externalPort,
                            onValueChange = { externalPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("External port") },
                            placeholder = { Text("40000–49999") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            singleLine = true,
                            colors = pfFieldColors()
                        )
                        OutlinedTextField(
                            value = internalPort,
                            onValueChange = { internalPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Internal port (optional)") },
                            placeholder = { Text("Same as external") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            singleLine = true,
                            colors = pfFieldColors()
                        )

                        Button(
                            onClick = {
                                val ext = externalPort.toIntOrNull() ?: return@Button
                                val internal = internalPort.toIntOrNull()
                                if (peerId.isBlank()) return@Button
                                onCreate(peerId, protocol, ext, internal)
                                externalPort = ""
                                internalPort = ""
                            },
                            enabled = !busy && !atLimit && peers.isNotEmpty() && peerId.isNotBlank() && externalPort.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanHover)
                        ) {
                            if (creating) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                            } else {
                                Text(
                                    if (atLimit) "Limit reached (2)" else "Create",
                                    color = Ink,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PortForwardCard(
    forward: PortForwardInfo,
    deleting: Boolean,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, LineStrong, RoundedCornerShape(14.dp))
            .background(CardElevated, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            "${forward.egressEndpoint.ifBlank { "—" }}:${forward.externalPort}",
            color = Paper,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "→ ${shortPeerId(forward.peerId)} · ${forward.protocol.uppercase(Locale.US)} · internal ${if (forward.internalPort > 0) forward.internalPort else "—"}",
            color = PaperMuted,
            fontSize = 13.sp
        )
        Text(
            forward.status.ifBlank { "unknown" }.uppercase(Locale.US),
            color = when (forward.status.lowercase(Locale.US)) {
                "active" -> SuccessGreen
                "pending" -> CyanHover
                else -> PaperDim
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onDelete,
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.9f))
        ) {
            if (deleting) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun pfFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Paper,
    unfocusedTextColor = Paper,
    disabledTextColor = Paper,
    focusedBorderColor = CyanHover,
    unfocusedBorderColor = LineStrong,
    disabledBorderColor = LineStrong,
    focusedLabelColor = CyanHover,
    unfocusedLabelColor = PaperDim,
    disabledLabelColor = PaperDim,
    cursorColor = CyanHover,
    focusedContainerColor = CardElevated,
    unfocusedContainerColor = CardElevated,
    disabledContainerColor = CardElevated
)

private fun shortPeerId(id: String): String =
    if (id.length <= 10) id else id.take(8) + "…"
