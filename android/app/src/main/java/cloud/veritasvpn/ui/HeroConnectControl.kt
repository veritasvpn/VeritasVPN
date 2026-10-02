package cloud.veritasvpn.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.veritasvpn.ui.theme.CardElevated
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.Ink
import cloud.veritasvpn.ui.theme.Ink2
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.PaperDim
import cloud.veritasvpn.ui.theme.Royal
import cloud.veritasvpn.ui.theme.RoyalHover
import cloud.veritasvpn.ui.theme.rememberMotionEnabled

enum class HeroPhase {
    Ready,
    Upsell,
    Checking,
    Connecting,
    Protected,
}

@Composable
fun HeroConnectControl(
    phase: HeroPhase,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.94f else 1f,
        animationSpec = if (motion) spring(dampingRatio = 0.68f, stiffness = 560f) else snap(),
        label = "hero-press",
    )
    val settle by animateFloatAsState(
        targetValue = if (phase == HeroPhase.Protected) 1f else 0.97f,
        animationSpec = if (motion) spring(dampingRatio = 0.62f, stiffness = 340f) else snap(),
        label = "hero-settle",
    )
    val burst = remember { Animatable(0f) }
    LaunchedEffect(phase, motion) {
        if (phase == HeroPhase.Protected && motion) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
        } else {
            burst.snapTo(0f)
        }
    }
    val glow = if (motion && (phase == HeroPhase.Protected || phase == HeroPhase.Connecting || phase == HeroPhase.Ready)) {
        val transition = rememberInfiniteTransition(label = "hero-glow")
        val animated by transition.animateFloat(
            initialValue = if (phase == HeroPhase.Protected) 0.28f else 0.12f,
            targetValue = if (phase == HeroPhase.Protected) 0.62f else 0.32f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (phase == HeroPhase.Protected) 1700 else 1200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "hero-glow-alpha",
        )
        animated
    } else if (phase == HeroPhase.Protected) {
        0.4f
    } else {
        0.16f
    }
    val spin = if (motion && phase == HeroPhase.Connecting) {
        val transition = rememberInfiniteTransition(label = "hero-spin")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2200, easing = LinearEasing)),
            label = "hero-spin-angle",
        )
        animated
    } else {
        0f
    }
    val ring = when (phase) {
        HeroPhase.Protected -> Cyan
        HeroPhase.Connecting, HeroPhase.Checking -> CyanHover
        HeroPhase.Upsell -> RoyalHover
        HeroPhase.Ready -> Cyan
    }
    val ringColor by animateColorAsState(ring, label = "hero-ring")
    val label = when (phase) {
        HeroPhase.Ready -> "Connect now"
        HeroPhase.Upsell -> "Get Premium"
        HeroPhase.Checking -> "Checking plan…"
        HeroPhase.Connecting -> "Connecting…"
        HeroPhase.Protected -> "Protected"
    }
    val labelColor = when (phase) {
        HeroPhase.Protected -> Cyan
        HeroPhase.Connecting, HeroPhase.Checking -> CyanHover
        else -> Paper
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                if (onClick != null) role = Role.Button
                if (phase == HeroPhase.Protected) stateDescription = "VPN connected"
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(196.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val center = this.center
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = glow), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
                val burstValue = burst.value
                if (burstValue in 0.01f..0.99f) {
                    drawCircle(
                        color = Cyan.copy(alpha = (1f - burstValue) * 0.55f),
                        radius = radius * (0.48f + burstValue * 0.48f),
                        center = center,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                if (phase == HeroPhase.Connecting) {
                    val arcRadius = radius * 0.72f
                    drawArc(
                        color = Cyan.copy(alpha = 0.9f),
                        startAngle = spin,
                        sweepAngle = 72f,
                        useCenter = false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(arcRadius * 2f, arcRadius * 2f),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Box(
                Modifier
                    .size(168.dp)
                    .border(1.dp, ringColor.copy(alpha = 0.16f), CircleShape)
            )
            Box(
                Modifier
                    .size(144.dp)
                    .border(1.dp, ringColor.copy(alpha = 0.28f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .graphicsLayer {
                        val scale = pressScale * settle
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(
                        elevation = if (phase == HeroPhase.Protected) 28.dp else 16.dp,
                        shape = CircleShape,
                        ambientColor = ringColor.copy(alpha = 0.35f),
                        spotColor = ringColor.copy(alpha = 0.2f),
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(CardElevated, Ink2, Ink)
                        )
                    )
                    .border(
                        width = if (phase == HeroPhase.Protected) 2.5.dp else 1.5.dp,
                        brush = Brush.sweepGradient(listOf(Cyan, RoyalHover, Royal, CyanHover, Cyan)),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = phase,
                    animationSpec = if (motion) tween(280) else snap(),
                    label = "hero-glyph",
                ) { current ->
                    when (current) {
                        HeroPhase.Checking, HeroPhase.Connecting -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = CyanHover,
                                strokeWidth = 2.5.dp,
                            )
                        }
                        HeroPhase.Protected -> {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = Cyan,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        HeroPhase.Upsell -> {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = CyanHover,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        HeroPhase.Ready -> {
                            Icon(
                                Icons.Rounded.LockOpen,
                                contentDescription = null,
                                tint = CyanHover,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = labelColor,
            textAlign = TextAlign.Center,
        )
        if (phase == HeroPhase.Protected) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Your connection is encrypted",
                style = MaterialTheme.typography.bodySmall,
                color = PaperDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}
