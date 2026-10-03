package cloud.veritasvpn.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    borderColor: Color = LineStrong.copy(alpha = 0.72f),
): Modifier = this
    .shadow(
        elevation = 14.dp,
        shape = shape,
        ambientColor = Royal.copy(alpha = 0.18f),
        spotColor = Cyan.copy(alpha = 0.08f),
    )
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                CardElevated,
                Ink2,
            )
        )
    )
    .border(BorderStroke(1.dp, borderColor), shape)

@Composable
inline fun PremiumBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Royal.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.02f),
                    radius = size.minDimension * 0.92f,
                ),
                radius = size.minDimension * 0.92f,
                center = Offset(size.width * 0.82f, size.height * 0.02f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Cyan.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.08f, size.height * 0.34f),
                    radius = size.minDimension * 0.72f,
                ),
                radius = size.minDimension * 0.72f,
                center = Offset(size.width * 0.08f, size.height * 0.34f),
            )
        }
        content()
    }
}

@Composable
fun PremiumEnter(
    delayMillis: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = rememberMotionEnabled()
    var visible by remember { mutableStateOf(!motion) }
    LaunchedEffect(motion) { visible = true }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) {
            fadeIn(tween(durationMillis = 420, delayMillis = delayMillis, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 460, delayMillis = delayMillis, easing = FastOutSlowInEasing)
                ) { it / 10 }
        } else {
            fadeIn(tween(0))
        },
    ) {
        content()
    }
}
