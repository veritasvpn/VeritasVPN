package cloud.veritasvpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
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
    // Circle-only pulse. Disconnected travels farther so the control reads as
    // tappable; protected keeps the same loop at a lower amplitude. Animator
    // scale 0 holds the circle at rest. No rings outside the button.
    val pulseTransition = rememberInfiniteTransition(label = "hero-pulse")
    val pulseUnit by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero-pulse-unit",
    )
    val pulseAmplitude = if (!motion) {
        0f
    } else if (phase == HeroPhase.Protected) {
        0.06f
    } else {
        0.16f
    }
    val pulseScale = 1f + pulseUnit * pulseAmplitude
    val secured = phase == HeroPhase.Protected
    val arrival = remember { Animatable(1f) }
    val arrivalSeen = remember { booleanArrayOf(false) }
    LaunchedEffect(secured) {
        if (!arrivalSeen[0]) {
            arrivalSeen[0] = true
            return@LaunchedEffect
        }
        if (!motion) {
            arrival.snapTo(1f)
            return@LaunchedEffect
        }
        arrival.snapTo(if (secured) 0.86f else 1.08f)
        arrival.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 380f))
    }
    val edge = when (phase) {
        HeroPhase.Protected -> Cyan
        HeroPhase.Upsell -> RoyalHover
        else -> CyanHover
    }
    val edgeColor by animateColorAsState(
        edge,
        animationSpec = if (motion) tween(420, easing = FastOutSlowInEasing) else snap(),
        label = "hero-edge",
    )
    val showNotConnected = phase == HeroPhase.Ready ||
        phase == HeroPhase.Upsell ||
        phase == HeroPhase.Checking
    val actionName = when (phase) {
        HeroPhase.Ready -> "Connect"
        HeroPhase.Upsell -> "Get Premium"
        HeroPhase.Protected -> "Disconnect"
        else -> null
    }
    val glyphSpec = if (motion) {
        (fadeIn(tween(420, easing = FastOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.68f,
                animationSpec = tween(520, easing = FastOutSlowInEasing),
            )) togetherWith
            (fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                scaleOut(
                    targetScale = 0.82f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ))
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = showNotConnected,
            enter = if (motion) {
                fadeIn(tween(360, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(420, easing = FastOutSlowInEasing)) { -it / 3 }
            } else {
                EnterTransition.None
            },
            exit = if (motion) {
                fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 4 }
            } else {
                ExitTransition.None
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Not connected",
                    color = Cyan,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val scale = pressScale * pulseScale * arrival.value
                    scaleX = scale
                    scaleY = scale
                }
                .size(118.dp)
                .shadow(
                    elevation = if (secured) 18.dp else 12.dp,
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
                                if (secured) stateDescription = "VPN connected"
                                if (phase == HeroPhase.Ready) stateDescription = "Not connected"
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
            AnimatedContent(
                targetState = phase,
                transitionSpec = { glyphSpec },
                label = "hero-glyph",
            ) { current ->
                when (current) {
                    HeroPhase.Checking, HeroPhase.Connecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = CyanHover,
                            strokeWidth = 2.5.dp,
                        )
                    }
                    HeroPhase.Protected -> {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = Cyan,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                    HeroPhase.Upsell -> {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = CyanHover,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                    HeroPhase.Ready -> {
                        Icon(
                            Icons.Rounded.LockOpen,
                            contentDescription = null,
                            tint = CyanHover,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                }
            }
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                if (motion) {
                    (fadeIn(tween(340, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 4 }) togetherWith
                        (fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                            slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 5 })
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "hero-caption",
        ) { current ->
            val caption = when (current) {
                HeroPhase.Checking -> "Checking plan…"
                HeroPhase.Connecting -> "Connecting…"
                HeroPhase.Protected -> "Protected"
                else -> null
            }
            val captionColor = when (current) {
                HeroPhase.Protected -> Cyan
                HeroPhase.Connecting, HeroPhase.Checking -> CyanHover
                else -> PaperDim
            }
            if (caption != null || current == HeroPhase.Upsell) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    if (caption != null) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.titleLarge,
                            color = captionColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (current == HeroPhase.Protected) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Your connection is encrypted",
                            style = MaterialTheme.typography.bodySmall,
                            color = PaperDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (current == HeroPhase.Upsell) {
                        Text(
                            text = "Premium required",
                            style = MaterialTheme.typography.bodySmall,
                            color = PaperDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
