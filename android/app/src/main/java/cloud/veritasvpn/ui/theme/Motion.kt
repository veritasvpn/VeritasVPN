package cloud.veritasvpn.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Decorative motion follows the system animator and transition scales.
 * Those are what Android's "Remove animations" setting (and Compose's own
 * MotionDurationScale) already use. When either scale is zero, screens hold
 * a calm end state instead of running infinite pulses.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    val enabled = remember(context) { mutableStateOf(readMotionEnabled(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled.value = readMotionEnabled(context)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
                false,
                observer,
            )
        }
        enabled.value = readMotionEnabled(context)
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return enabled.value
}

internal fun readMotionEnabled(context: Context): Boolean {
    val resolver = context.contentResolver
    val animator = readScale(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    val transition = readScale(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE)
    return animator > 0.01f && transition > 0.01f
}

private fun readScale(resolver: android.content.ContentResolver, name: String): Float {
    return runCatching { Settings.Global.getFloat(resolver, name, 1f) }.getOrDefault(1f)
}
