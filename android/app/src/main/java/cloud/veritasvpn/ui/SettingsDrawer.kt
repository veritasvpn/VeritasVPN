package cloud.veritasvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
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
import cloud.veritasvpn.ui.theme.Line
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.PaperMuted
import cloud.veritasvpn.ui.theme.ErrorRed
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
    onDevices: () -> Unit,
    onPortForwards: () -> Unit,
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
                .background(Color(0xFF010814).copy(alpha = scrimAlpha))
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
                    .background(CardElevated)
                    .statusBarsPadding(),
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
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
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
                            onClick = { navigate(onPlans) },
                        )
                        SettingsDrawerNavItem(
                            label = "Network map",
                            onClick = { navigate(onNetworkMap) },
                        )
                        SettingsDrawerNavItem(
                            label = "Devices",
                            onClick = { navigate(onDevices) },
                        )
                        SettingsDrawerNavItem(
                            label = "Port forwarding",
                            onClick = { navigate(onPortForwards) },
                        )
                    }

                    SettingsDrawerSection(title = "Connection") {
                        SettingsDrawerNavItem(
                            label = "Split tunnel",
                            note = "Exclude LAN · per-app bypass",
                            onClick = { navigate(onTunnelSettings) },
                        )
                    }

                    SettingsDrawerSection(title = "Session") {
                        SettingsDrawerNavItem(
                            label = "Sign out from all devices",
                            danger = true,
                            onClick = { navigate(onSignOutEverywhere) },
                        )
                        SettingsDrawerNavItem(
                            label = "Sign out from this device",
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
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) {
        Text(
            text = title.uppercase(),
            color = PaperDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        content()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Line),
    )
}

@Composable
private fun SettingsDrawerNavItem(
    label: String,
    onClick: () -> Unit,
    note: String? = null,
    muted: Boolean = false,
    danger: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (danger) ErrorRed.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
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
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
