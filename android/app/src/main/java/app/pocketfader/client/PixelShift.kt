package app.pocketfader.client

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// A walk around a 3×3 grid, so every pixel of the screen spends time lit and time dark.
private val WALK = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

/**
 * Pixel shift for OLED screens: every few minutes the whole UI is moved a few pixels in a slow glide,
 * so nothing stays on exactly the same pixels for hours (static bars and buttons are what burn in).
 */
@Composable
fun PixelShift(enabled: Boolean, everyMinutes: Int, distanceDp: Int, content: @Composable () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled, everyMinutes) {
        if (!enabled) { step = 0; return@LaunchedEffect }
        while (true) {
            delay(everyMinutes.coerceAtLeast(1) * 60_000L)
            step++
        }
    }
    val (gx, gy) = if (enabled) WALK[step % WALK.size] else (0 to 0)
    val ox by animateDpAsState((gx * distanceDp).dp, tween(if (LocalReduceMotion.current) 0 else 6000), label = "shiftX")
    val oy by animateDpAsState((gy * distanceDp).dp, tween(if (LocalReduceMotion.current) 0 else 6000), label = "shiftY")
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().offset(ox, oy)) { content() }
    }
}
