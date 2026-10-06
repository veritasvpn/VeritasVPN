package cloud.veritasvpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import android.os.SystemClock
import kotlinx.coroutines.isActive

enum class HeroPhase {
    Ready,
    Upsell,
    Checking,
    Connecting,
    Protected,
}

/** Desktop MARK_DIM / MARK_FULL. Dim is disconnected; full is protected. */
private const val MARK_DIM_BRIGHTNESS = 0.58f
private const val MARK_DIM_SATURATE = 0.48f
private const val MARK_DIM_OPACITY = 0.66f
private const val MARK_FULL_BRIGHTNESS = 1.08f
private const val MARK_FULL_SATURATE = 1.12f
private const val MARK_FULL_OPACITY = 1f

/** One ramp from when checking/connecting starts, then hold. Same 1.4s as desktop. */
private const val MARK_GLOW_DURATION_MS = 1400L

internal data class HeroMarkLook(
    val brightness: Float,
    val saturate: Float,
    val opacity: Float,
)

internal fun heroMarkLive(phase: HeroPhase): Boolean {
    return phase == HeroPhase.Checking || phase == HeroPhase.Connecting
}

/**
 * Keeps the ramp clock across checking → connecting. Clears it only when the
 * control leaves that pair, so a status update cannot restart the glow.
 * Returns -1 when the mark is not in the live ramp.
 */
internal fun nextHeroMarkLiveStartMs(previousStartMs: Long, live: Boolean, nowMs: Long): Long {
    if (!live) return -1L
    if (previousStartMs < 0L) return nowMs
    return previousStartMs
}

/** 0 at the dim end, 1 at full. Absolute elapsed time, so a dropped frame catches up. */
internal fun heroMarkGlow(elapsedMs: Long): Float {
    if (elapsedMs <= 0L) return 0f
    if (elapsedMs >= MARK_GLOW_DURATION_MS) return 1f
    return FastOutSlowInEasing.transform(elapsedMs.toFloat() / MARK_GLOW_DURATION_MS.toFloat())
}

/**
 * Reduced motion skips the ramp and shows the end state: full while checking,
 * connecting, or protected, and dim while disconnected.
 */
internal fun heroMarkLook(phase: HeroPhase, motion: Boolean, elapsedLiveMs: Long): HeroMarkLook {
    val live = heroMarkLive(phase)
    val glow = when {
        phase == HeroPhase.Protected -> 1f
        live && !motion -> 1f
        live -> heroMarkGlow(elapsedLiveMs)
        else -> 0f
    }
    return HeroMarkLook(
        brightness = lerp(MARK_DIM_BRIGHTNESS, MARK_FULL_BRIGHTNESS, glow),
        saturate = lerp(MARK_DIM_SATURATE, MARK_FULL_SATURATE, glow),
        opacity = lerp(MARK_DIM_OPACITY, MARK_FULL_OPACITY, glow),
    )
}

/**
 * CSS `brightness()` then `saturate()` from the desktop hero mark.
 * Brightness is a uniform RGB scale, so it commutes with saturation; the
 * matrix still applies brightness first to match that filter list.
 */
internal fun heroMarkColorMatrix(brightness: Float, saturate: Float): ColorMatrix {
    val invSat = 1f - saturate
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat
    return ColorMatrix(
        floatArrayOf(
            brightness * (r + saturate), brightness * g, brightness * b, 0f, 0f,
            brightness * r, brightness * (g + saturate), brightness * b, 0f, 0f,
            brightness * r, brightness * g, brightness * (b + saturate), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

/**
 * Veritas mark inside the connect circle. The idle pulse, connecting arc, and
 * the checking/connecting brightness ramp are sampled from elapsed time, not
 * from connection progress, so a stalled backend cannot freeze them.
 */
@Composable
fun HeroConnectControl(
    phase: HeroPhase,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val motion = rememberMotionEnabled()
    // Wall clock, not connection progress. Recomposition while the tunnel waits
    // cannot restart this, and a dropped frame catches up instead of freezing.
    val nowMs = rememberElapsedRealtime(enabled = motion)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.94f else 1f,
        animationSpec = if (motion) spring(dampingRatio = 0.68f, stiffness = 560f) else snap(),
        label = "hero-press",
    )
    // Circle-only pulse. Disconnected travels farther so the control reads as
    // tappable; protected keeps the same loop at a lower amplitude. Connecting
    // and checking use that same uninterrupted loop. Animator scale 0 holds
    // the circle at rest. No rings outside the button.
    val pulseAmplitude = if (!motion) {
        0f
    } else if (phase == HeroPhase.Protected) {
        0.06f
    } else {
        0.16f
    }
    val pulseScale = 1f + pulseUnitFromElapsed(nowMs) * pulseAmplitude
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
    val live = heroMarkLive(phase)
    // Remembered across recomposition, including checking → connecting, so a
    // stalled handshake cannot restart the ramp. Not Compose state: the frame
    // clock above already recomposes while motion is on.
    val liveStartMs = remember { longArrayOf(-1L) }
    val startedAt = nextHeroMarkLiveStartMs(
        previousStartMs = liveStartMs[0],
        live = live,
        nowMs = SystemClock.elapsedRealtime(),
    )
    SideEffect { liveStartMs[0] = startedAt }
    val elapsedLiveMs = if (startedAt < 0L) 0L else nowMs - startedAt
    val mark = heroMarkLook(phase = phase, motion = motion, elapsedLiveMs = elapsedLiveMs)

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
                Spacer(Modifier.height(36.dp))
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
            Image(
                painter = painterResource(cloud.veritasvpn.R.drawable.veritas_mark),
                contentDescription = null,
                modifier = Modifier.size(68.dp),
                contentScale = ContentScale.Fit,
                alpha = mark.opacity,
                colorFilter = ColorFilter.colorMatrix(
                    heroMarkColorMatrix(mark.brightness, mark.saturate),
                ),
            )
            if (live) {
                ContinuousBusyGlyph(nowMs = nowMs, spinning = motion)
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
                    Spacer(Modifier.height(if (current == HeroPhase.Upsell) 20.dp else 8.dp))
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

/**
 * Elapsed realtime sampled once per frame. The loop is not keyed on connection
 * phase or status text, so those updates do not restart it. The value is
 * absolute, so a restarted frame callback does not snap the pulse to zero.
 */
@Composable
private fun rememberElapsedRealtime(enabled: Boolean): Long {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            withFrameNanos {
                now.longValue = SystemClock.elapsedRealtime()
            }
        }
    }
    return now.longValue
}

private fun pulseUnitFromElapsed(nowMs: Long): Float {
    val half = 1280L
    val t = nowMs % (half * 2)
    return if (t < half) {
        FastOutSlowInEasing.transform(t / half.toFloat())
    } else {
        FastOutSlowInEasing.transform(1f - (t - half) / half.toFloat())
    }
}

/**
 * Orbit drawn over the mark. Matches the desktop hero-arc: viewBox 36, r 14,
 * stroke 2.5, laid out at 100dp so it sits outside the 68dp mark. Rotation is
 * elapsed time, and reduced motion holds the arc still.
 */
@Composable
private fun ContinuousBusyGlyph(nowMs: Long, spinning: Boolean) {
    val rotation = if (spinning) ((nowMs % 1100L).toFloat() / 1100f) * 360f else 0f
    Canvas(Modifier.size(100.dp)) {
        val scale = size.minDimension / 36f
        val strokePx = 2.5f * scale
        val diameter = 28f * scale
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f,
        )
        drawArc(
            color = CyanHover,
            startAngle = rotation - 90f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}
