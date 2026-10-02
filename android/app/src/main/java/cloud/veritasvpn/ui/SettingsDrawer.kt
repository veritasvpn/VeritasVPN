package cloud.veritasvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cloud.veritasvpn.ui.theme.CardElevated
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.Ink
import cloud.veritasvpn.ui.theme.Ink2
import cloud.veritasvpn.ui.theme.Line
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.ErrorRed
import cloud.veritasvpn.ui.theme.glassSurface
import kotlin.math.roundToInt

private val DrawerOpenEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val DrawerCloseEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

@Composable
fun SettingsDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    isPremium: Boolean,
    onPlans: () -> Unit,
    onNetworkMap: () -> Unit,
    onStealthSettings: () -> Unit,
    onTunnelSettings: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutEverywhere: () -> Unit,
) {
    var mounted by remember { mutableStateOf(open) }

    LaunchedEffect(open) {
        if (open) mounted = true
    }

    if (!mounted) return

    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val drawerWidthDp = minOf(300, (screenWidthDp * 0.85f).roundToInt()).dp
    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }

    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = if (open) {
            tween(durationMillis = 280, easing = DrawerOpenEasing)
        } else {
            tween(durationMillis = 220, easing = DrawerCloseEasing)
        },
        finishedListener = { value ->
            if (value == 0f && !open) mounted = false
        },
        label = "settingsDrawerProgress",
    )

    val scrimAlpha = 0.55f * progress
    val panelOffsetPx = drawerWidthPx * (1f - progress)

    BackHandler(enabled = open || progress > 0.01f) {
        if (open) onDismiss()
    }

    fun navigate(action: () -> Unit) {
        onDismiss()
        action()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(60f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = progress > 0.01f,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = "Close settings" },
        )

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .offset { IntOffset(panelOffsetPx.roundToInt(), 0) }
                .shadow(18.dp)
                .semantics { paneTitle = "Settings" },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Cyan.copy(alpha = 0.25f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidthDp)
                    .background(Brush.verticalGradient(listOf(CardElevated, Ink2, Ink)))
                    .safeDrawingPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Settings",
                        color = Paper,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { contentDescription = "Close settings" },
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = PaperMuted)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Line),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    SettingsDrawerSection(title = "Account & tools") {
                        SettingsDrawerNavItem(
                            label = if (isPremium) "Premium" else "Plans",
                            icon = Icons.Rounded.WorkspacePremium,
                            onClick = { navigate(onPlans) },
                        )
                        SettingsDrawerNavItem(
                            label = "Network map",
                            icon = Icons.Rounded.Public,
                            onClick = { navigate(onNetworkMap) },
                        )
                    }

                    SettingsDrawerSection(title = "Connection") {
                        SettingsDrawerNavItem(
                            label = "Stealth",
                            note = "Auto · UDP only · Stealth always",
                            icon = Icons.Rounded.VisibilityOff,
                            onClick = { navigate(onStealthSettings) },
                        )
                        SettingsDrawerNavItem(
                            label = "Split tunnel",
                            note = "Exclude LAN · per-app bypass",
                            icon = Icons.AutoMirrored.Rounded.CallSplit,
                            onClick = { navigate(onTunnelSettings) },
                        )
                    }

                    SettingsDrawerSection(title = "Session") {
                        SettingsDrawerNavItem(
                            label = "Sign out from all devices",
                            icon = Icons.Rounded.Devices,
                            danger = true,
                            onClick = { navigate(onSignOutEverywhere) },
                        )
                        SettingsDrawerNavItem(
                            label = "Sign out from this device",
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            danger = true,
                            onClick = { navigate(onSignOut) },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsDrawerSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Text(
            text = title.uppercase(),
            color = PaperDim,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .glassSurface(RoundedCornerShape(18.dp), borderColor = Line.copy(alpha = 0.9f))
                .padding(vertical = 4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsDrawerNavItem(
    label: String,
    onClick: () -> Unit,
    note: String? = null,
    muted: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) ErrorRed.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (danger) ErrorRed.copy(alpha = 0.14f) else Cyan.copy(alpha = 0.12f))
                    .border(
                        1.dp,
                        if (danger) ErrorRed.copy(alpha = 0.28f) else Cyan.copy(alpha = 0.22f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (danger) ErrorRed else CyanHover,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = when {
                    danger -> ErrorRed
                    muted -> PaperMuted
                    else -> Paper
                },
                fontWeight = if (muted) FontWeight.Medium else FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            if (note != null) {
                Text(
                    text = note,
                    color = PaperDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!danger) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = PaperDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
