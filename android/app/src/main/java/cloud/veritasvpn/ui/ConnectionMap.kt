package cloud.veritasvpn.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.veritasvpn.ui.theme.*
import com.caverock.androidsvg.SVG

private const val PARAGUAY_LAT = -25.2867
private const val PARAGUAY_LON = -57.3333

@Composable
fun ConnectionMap(
    modifier: Modifier = Modifier,
    connected: Boolean,
    connecting: Boolean,
) {
    val context = LocalContext.current
    val worldMap = remember(context) {
        SVG.getFromResource(context, cloud.veritasvpn.R.raw.world_map).apply {
            documentWidth = 1200f
            documentHeight = 600f
        }
    }
    val motion = rememberMotionEnabled()
    val pulse = if (motion) {
        val animation = rememberInfiniteTransition(label = "vpn-map-pulse")
        animation.animateFloat(
            .92f, 1.18f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "location-pulse"
        ).value
    } else {
        1f
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Ink3, Ink2)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())
        )

        val mapZoom = 1.10f
        val mapWidth = size.width * mapZoom
        val mapHeight = size.height * mapZoom
        val horizontalPadding = (size.width - mapWidth) / 2f
        val verticalPadding = (size.height - mapHeight) / 2f

        fun project(latitude: Double, longitude: Double): Offset {
            val x = horizontalPadding + (((longitude + 180.0) / 360.0).toFloat() * mapWidth)
            val y = verticalPadding + (((90.0 - latitude) / 180.0).toFloat() * mapHeight)
            return Offset(x, y)
        }

        val grid = Line.copy(alpha = .25f)
        listOf(-120.0, -60.0, 0.0, 60.0, 120.0).forEach { lon ->
            drawLine(grid, project(75.0, lon), project(-60.0, lon))
        }
        listOf(-45.0, 0.0, 45.0).forEach { lat ->
            drawLine(grid, project(lat, -175.0), project(lat, 175.0))
        }

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(horizontalPadding, verticalPadding)
            native.scale(mapWidth / 1200f, mapHeight / 600f)
            worldMap.renderToCanvas(native)
            native.restore()
        }

        val server = project(PARAGUAY_LAT, PARAGUAY_LON)
        val pinScale = if (connecting || connected) pulse else 1f
        drawCircle(RoyalHover.copy(alpha = .22f), 12.dp.toPx() * pinScale, server)
        drawCircle(RoyalHover, 6.dp.toPx(), server)
        drawCircle(Paper, 2.dp.toPx(), server)
    }
}
