package cloud.veritasvpn.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
    deviceLatitude: Double?,
    deviceLongitude: Double?
) {
    val context = LocalContext.current
    val worldMap = remember(context) {
        SVG.getFromResource(context, cloud.veritasvpn.R.raw.world_map).apply {
            documentWidth = 1200f
            documentHeight = 600f
        }
    }
    val animation = rememberInfiniteTransition(label = "vpn-map")
    val progress by animation.animateFloat(
        0f, 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "route-progress"
    )
    val pulse by animation.animateFloat(
        .8f, 1.5f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "location-pulse"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(190.dp)) {
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

        val hasDeviceLocation = deviceLatitude != null &&
            deviceLongitude != null &&
            deviceLatitude in -90.0..90.0 &&
            deviceLongitude in -180.0..180.0
        val server = project(PARAGUAY_LAT, PARAGUAY_LON)
        val device = if (hasDeviceLocation) {
            project(deviceLatitude!!, deviceLongitude!!)
        } else {
            server
        }
        val route = if (hasDeviceLocation) routePath(device, server) else null

        if ((connecting || connected) && route != null) {
            if (connected) {
                drawPath(route, Royal.copy(alpha = .30f), style = Stroke(8.dp.toPx()))
                drawPath(
                    route,
                    Brush.linearGradient(listOf(Cyan, RoyalHover), device, server),
                    style = Stroke(2.5.dp.toPx())
                )
            } else {
                drawPath(route, LineStrong, style = Stroke(2.dp.toPx()))
                val packet = routePoint(progress, device, server)
                drawCircle(Cyan.copy(alpha = .20f), 11.dp.toPx() * pulse, packet)
                drawCircle(CyanHover, 4.dp.toPx(), packet)
            }
        }

        if (hasDeviceLocation) {
            drawCircle(Cyan.copy(alpha = .20f), 11.dp.toPx() * pulse, device)
            drawCircle(CyanHover, 5.dp.toPx(), device)
            drawCircle(Paper, 2.dp.toPx(), device)
        }
        if (connecting || connected) {
            drawCircle(RoyalHover.copy(alpha = .22f), 12.dp.toPx() * pulse, server)
            drawCircle(RoyalHover, 6.dp.toPx(), server)
            drawCircle(Paper, 2.dp.toPx(), server)
        }
    }
}

private fun routePath(start: Offset, end: Offset) = Path().apply {
    val lift = kotlin.math.max(28f, kotlin.math.abs(end.x - start.x) * .22f)
    moveTo(start.x, start.y)
    cubicTo(start.x, start.y - lift, end.x, end.y - lift, end.x, end.y)
}

private fun routePoint(t: Float, start: Offset, end: Offset): Offset {
    val lift = kotlin.math.max(28f, kotlin.math.abs(end.x - start.x) * .22f)
    val c1 = Offset(start.x, start.y - lift)
    val c2 = Offset(end.x, end.y - lift)
    val u = 1f - t
    return Offset(
        u*u*u*start.x + 3*u*u*t*c1.x + 3*u*t*t*c2.x + t*t*t*end.x,
        u*u*u*start.y + 3*u*u*t*c1.y + 3*u*t*t*c2.y + t*t*t*end.y
    )
}
