package cloud.veritasvpn.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.*

private data class LaunchableApp(val label: String, val packageName: String)

@Composable
fun TunnelSettingsScreen(
    excludeLan: Boolean,
    bypassApps: Set<String>,
    showReconnectBanner: Boolean,
    onExcludeLanChange: (Boolean) -> Unit,
    onBypassAppsChange: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val installedApps = remember(context) { launchableApps(context) }
    var showAppPicker by remember { mutableStateOf(false) }
    val selectedApps = installedApps.filter { it.packageName in bypassApps }
    val missingApps = bypassApps - installedApps.mapTo(mutableSetOf()) { it.packageName }

    if (showAppPicker) {
        AppBypassPicker(
            apps = installedApps,
            selectedPackages = bypassApps,
            onDismiss = { showAppPicker = false },
            onApply = {
                onBypassAppsChange(it)
                showAppPicker = false
            }
        )
    }

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
                Text("Split tunnel", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        if (showReconnectBanner) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().border(1.dp, WarningOrange.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .background(WarningOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reconnect from Home to apply these changes", color = WarningOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("ROUTING", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().border(1.dp, CyanHover.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .background(CardElevated, RoundedCornerShape(16.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Cyan, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Protected connection", color = CyanHover, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("Route your internet through VeritasVPN", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                "Your browser, apps, and DNS use the encrypted WireGuard tunnel unless you choose a local-network or per-app exception below.",
                color = PaperMuted, fontSize = 13.sp, lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingToggleRow(
            title = "Allow local network access",
            subtitle = "Keep devices on your home, office, or hotel network reachable. Internet traffic still uses VeritasVPN.",
            checked = excludeLan,
            onCheckedChange = onExcludeLanChange
        )

        Spacer(Modifier.height(24.dp))
        Text("PER-APP BYPASS", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(7.dp))
        Text(
            "Choose apps that should use your regular internet connection instead of the VPN. Those apps expose your normal network IP address.",
            color = PaperMuted, fontSize = 13.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanHover),
            border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(CyanHover, RoyalHover)))
        ) {
            Text(if (bypassApps.isEmpty()) "Choose apps" else "Manage ${bypassApps.size} selected app${if (bypassApps.size == 1) "" else "s"}")
        }

        if (bypassApps.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().border(1.dp, LineStrong, RoundedCornerShape(14.dp))
                    .background(CardElevated, RoundedCornerShape(14.dp))
            ) {
                selectedApps.forEachIndexed { index, app ->
                    BypassAppRow(app.label, "Uses your regular connection") { onBypassAppsChange(bypassApps - app.packageName) }
                    if (index != selectedApps.lastIndex || missingApps.isNotEmpty()) HorizontalDivider(color = LineStrong)
                }
                missingApps.sorted().forEachIndexed { index, packageName ->
                    BypassAppRow("Unavailable app", packageName) { onBypassAppsChange(bypassApps - packageName) }
                    if (index != missingApps.sorted().lastIndex) HorizontalDivider(color = LineStrong)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun BypassAppRow(label: String, detail: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Cyan.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), color = CyanHover, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = PaperDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRemove) { Text("Remove", fontSize = 12.sp) }
    }
}

@Composable
private fun AppBypassPicker(
    apps: List<LaunchableApp>, selectedPackages: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(selectedPackages) { mutableStateOf(selectedPackages) }
    val visibleApps = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) apps else apps.filter { it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle) }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardElevated, titleContentColor = Paper, textContentColor = PaperMuted,
        title = { Text("Choose apps to bypass") },
        text = {
            Column {
                Text("Selected apps will use your regular connection instead of VeritasVPN.", fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search installed apps") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Paper, unfocusedTextColor = Paper, focusedBorderColor = CyanHover,
                        unfocusedBorderColor = LineStrong, cursorColor = CyanHover,
                        focusedContainerColor = CardElevated, unfocusedContainerColor = CardElevated
                    )
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (visibleApps.isEmpty()) Text("No matching apps found", color = PaperDim, modifier = Modifier.padding(vertical = 18.dp))
                    visibleApps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = { checked -> selected = if (checked) selected + app.packageName else selected - app.packageName },
                                colors = CheckboxDefaults.colors(checkedColor = Cyan, checkmarkColor = Ink)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(app.label, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, color = PaperDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(selected) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PaperMuted) } }
    )
}

private fun launchableApps(context: Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            LaunchableApp(resolveInfo.loadLabel(context.packageManager).toString(), packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun SettingToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, if (checked) CyanHover.copy(alpha = 0.4f) else LineStrong, RoundedCornerShape(14.dp))
            .background(CardElevated, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = PaperMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Cyan, uncheckedThumbColor = PaperDim, uncheckedTrackColor = Ink3)
        )
    }
}
