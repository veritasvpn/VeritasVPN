package cloud.veritasvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.veritasvpn.ui.theme.CardElevated
import cloud.veritasvpn.ui.theme.CyanHover
import cloud.veritasvpn.ui.theme.Line
import cloud.veritasvpn.ui.theme.Paper
import cloud.veritasvpn.ui.theme.rememberMotionEnabled

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (motion) spring(dampingRatio = 0.72f, stiffness = 640f) else snap(),
        label = "glass-icon-press",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(48.dp)
            .clip(CircleShape)
            .background(CardElevated.copy(alpha = 0.92f))
            .border(1.dp, Line.copy(alpha = 0.9f), CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun PremiumTopBar(
    eyebrow: String,
    title: String,
    onBack: () -> Unit,
    backContentDescription: String = "Back",
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(onClick = onBack, contentDescription = backContentDescription) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = CyanHover)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = CyanHover)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Paper)
        }
    }
}
