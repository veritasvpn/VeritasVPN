package cloud.veritasvpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
 * Keeps the ramp clock across checking → connecting → protected. Clears it
 * only when the control leaves that sequence, so a status update cannot
 * restart the glow or jump the mark brightness.
 * Returns -1 at the disconnected dim end, and when protected was reached
 * with no ramp in progress (the mark is already fully bright).
 */
internal fun nextHeroMarkLiveStartMs(previousStartMs: Long, phase: HeroPhase, nowMs: Long): Long {
    return when (phase) {
        HeroPhase.Checking, HeroPhase.Connecting ->
            if (previousStartMs < 0L) nowMs else previousStartMs
        HeroPhase.Protected -> previousStartMs
        else -> -1L
    }
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
        // A negative elapsed means there is no ramp in progress. Protected
        // reached that way (session already up) is fully bright. A ramp that
        // started while connecting keeps running so the handoff does not jump.
        phase == HeroPhase.Protected && (!motion || elapsedLiveMs < 0L) -> 1f
        phase == HeroPhase.Protected -> heroMarkGlow(elapsedLiveMs)
        live && !motion -> 1f
        live -> heroMarkGlow(elapsedLiveMs.coerceAtLeast(0L))
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
 * Veritas mark inside the connect circle. The idle pulse and the brightness
 * ramp are sampled from elapsed time, not from connection progress, so a
 * stalled backend cannot freeze them. The same clock keeps running from
 * connecting into protected; nothing about that handoff remounts this layer.
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
    // and checking use that same uninterrupted loop. The phase of the loop is
    // wall-clock time, and the amplitude eases toward the new strength, so
    // connecting → protected never snaps, restarts, or holds still.
    val pulseTarget = heroPulseAmplitude(phase, motion)
    val pulseFrom = remember { floatArrayOf(pulseTarget) }
    val pulseTo = remember { floatArrayOf(pulseTarget) }
    val pulseEnvelopeStartMs = remember { longArrayOf(nowMs) }
    val pulseAmplitude = if (!motion) {
        0f
    } else {
        pulseEnvelope(pulseFrom[0], pulseTo[0], nowMs - pulseEnvelopeStartMs[0])
    }
    SideEffect {
        if (!motion) {
            pulseFrom[0] = 0f
            pulseTo[0] = 0f
            pulseEnvelopeStartMs[0] = nowMs
        } else if (pulseTo[0] != pulseTarget) {
            pulseFrom[0] = pulseAmplitude
            pulseTo[0] = pulseTarget
            pulseEnvelopeStartMs[0] = nowMs
        }
    }
    val pulseScale = 1f + pulseUnitFromElapsed(nowMs) * pulseAmplitude
    val secured = phase == HeroPhase.Protected
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
    // Remembered across recomposition, including checking → connecting →
    // protected, so a stalled handshake cannot restart the ramp or drop the
    // mark back to dim. Not Compose state: the frame clock above already
    // recomposes while motion is on.
    val liveStartMs = remember { longArrayOf(-1L) }
    val startedAt = nextHeroMarkLiveStartMs(
        previousStartMs = liveStartMs[0],
        phase = phase,
        nowMs = SystemClock.elapsedRealtime(),
    )
    SideEffect { liveStartMs[0] = startedAt }
    val elapsedLiveMs = if (startedAt < 0L) -1L else nowMs - startedAt
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
                    val scale = pressScale * pulseScale
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

internal fun pulseUnitFromElapsed(nowMs: Long): Float {
    val half = 1280L
    val t = nowMs % (half * 2)
    return if (t < half) {
        FastOutSlowInEasing.transform(t / half.toFloat())
    } else {
        FastOutSlowInEasing.transform(1f - (t - half) / half.toFloat())
    }
}

/** Stronger while disconnected or connecting; softer once protected. Zero when motion is off. */
internal fun heroPulseAmplitude(phase: HeroPhase, motion: Boolean): Float {
    if (!motion) return 0f
    return if (phase == HeroPhase.Protected) 0.06f else 0.16f
}

/**
 * Ease idle-pulse strength from [from] toward [to]. The pulse phase is a
 * separate wall clock, so this envelope never restarts that loop.
 */
internal fun pulseEnvelope(from: Float, to: Float, elapsedMs: Long, durationMs: Long = 280L): Float {
    if (from == to || elapsedMs <= 0L) return from
    if (elapsedMs >= durationMs) return to
    val t = FastOutSlowInEasing.transform(elapsedMs.toFloat() / durationMs.toFloat())
    return from + (to - from) * t
}
