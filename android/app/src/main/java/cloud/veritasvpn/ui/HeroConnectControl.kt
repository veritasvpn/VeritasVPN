package cloud.veritasvpn.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.veritasvpn.ui.theme.CardElevated
import cloud.veritasvpn.ui.theme.Cyan
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.ErrorRed
import cloud.veritasvpn.ui.theme.Ink
import cloud.veritasvpn.ui.theme.Ink2
import cloud.veritasvpn.ui.theme.PaperDim
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
    // One scale pulse on the filled circle. Disconnected travels farther so the
    // control reads as tappable; protected uses the same loop at a lower amplitude.
    // Animator scale 0 holds the circle at rest.
    val pulseTransition = rememberInfiniteTransition(label = "hero-pulse")
    val pulseUnit by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero-pulse-unit",
    )
    val pulseAmplitude = if (!motion) {
        0f
    } else if (phase == HeroPhase.Protected) {
        0.035f
    } else {
        0.10f
    }
    val pulseScale = 1f + pulseUnit * pulseAmplitude
    val edge = when (phase) {
        HeroPhase.Protected -> Cyan
        HeroPhase.Upsell -> RoyalHover
        else -> CyanHover
    }
    val edgeColor by animateColorAsState(edge, label = "hero-edge")
    val label = when (phase) {
        HeroPhase.Ready, HeroPhase.Upsell -> "Not protected"
        HeroPhase.Checking -> "Checking plan…"
        HeroPhase.Connecting -> "Connecting…"
        HeroPhase.Protected -> "Protected"
    }
    val actionName = when (phase) {
        HeroPhase.Ready -> "Connect"
        HeroPhase.Upsell -> "Get Premium"
        HeroPhase.Protected -> "Disconnect"
        else -> null
    }
    val labelColor = when (phase) {
        HeroPhase.Protected -> Cyan
        HeroPhase.Connecting, HeroPhase.Checking -> CyanHover
        HeroPhase.Ready, HeroPhase.Upsell -> ErrorRed
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val scale = pressScale * pulseScale
                    scaleX = scale
                    scaleY = scale
                }
                .size(118.dp)
                .shadow(
                    elevation = if (phase == HeroPhase.Protected) 18.dp else 12.dp,
                    shape = CircleShape,
                    ambientColor = edgeColor.copy(alpha = 0.28f),
                    spotColor = edgeColor.copy(alpha = 0.16f),
                )
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(CardElevated, Ink2, Ink)))
                .border(1.5.dp, edgeColor.copy(alpha = 0.85f), CircleShape)
                .then(
                    if (onClick != null && actionName != null) {
                        Modifier
                            .semantics {
                                role = Role.Button
                                contentDescription = actionName
                                if (phase == HeroPhase.Protected) stateDescription = "VPN connected"
                                if (phase == HeroPhase.Ready) stateDescription = "Not protected"
                            }
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = onClick,
                            )
                    } else {
                        Modifier
                    }
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
        if (phase == HeroPhase.Upsell) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Premium required",
                style = MaterialTheme.typography.bodySmall,
                color = PaperDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}
