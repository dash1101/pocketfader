package app.pocketfader.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Immutable
data class Battery(val percent: Int, val charging: Boolean)

private fun readBattery(i: Intent?): Battery? {
    if (i == null) return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return Battery((level * 100 / scale).coerceIn(0, 100), charging)
}

/** A battery as phones draw it now: a rounded outline on its side, filled to the charge (red when low), with a bolt
 *  while charging. The bolt is cut right through, so whatever lies behind (the cover's wash under the top bar, say)
 *  shows in it rather than a fixed colour. */
@Composable
fun BatteryGlyph(b: Battery, color: Color, modifier: Modifier = Modifier) {
    val fill = when {
        b.charging -> Color(0xFF3DDC84)
        b.percent <= 15 -> MaterialTheme.colorScheme.error
        else -> color
    }
    // drawn on its own layer, so clearing the bolt's pixels leaves a real hole instead of a black one
    Canvas(modifier.size(width = 24.dp, height = 12.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val stroke = 1.2.dp.toPx()
        val nub = 1.8.dp.toPx()
        val bodyW = size.width - nub - 1.dp.toPx()
        val radius = 3.2.dp.toPx()
        drawRoundRect(
            color.copy(alpha = 0.55f), topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyW - stroke, size.height - stroke), cornerRadius = CornerRadius(radius), style = Stroke(stroke),
        )
        drawRoundRect(
            color.copy(alpha = 0.55f), topLeft = Offset(bodyW + 0.6.dp.toPx(), size.height * 0.33f),
            size = Size(nub, size.height * 0.34f), cornerRadius = CornerRadius(nub / 2),
        )
        val inset = stroke + 1.3.dp.toPx()
        val inner = bodyW - inset * 2
        val level = (b.percent / 100f).coerceIn(0.06f, 1f)
        drawRoundRect(
            fill, topLeft = Offset(inset, inset), size = Size(inner * level, size.height - inset * 2),
            cornerRadius = CornerRadius((radius - inset / 2).coerceAtLeast(1f)),
        )
        if (b.charging) {
            // a bolt cut out of the middle
            val w = bodyW
            val h = size.height
            val bolt = Path().apply {
                moveTo(w * 0.56f, h * 0.12f)
                lineTo(w * 0.36f, h * 0.56f)
                lineTo(w * 0.50f, h * 0.56f)
                lineTo(w * 0.44f, h * 0.90f)
                lineTo(w * 0.66f, h * 0.42f)
                lineTo(w * 0.52f, h * 0.42f)
                close()
            }
            drawPath(bolt, Color.Black, blendMode = BlendMode.Clear)
            // past the end of the charge there is nothing to cut through: the bolt is drawn there in the outline's
            // colour instead, so it still shows on a nearly empty battery
            clipRect(left = inset + inner * level) { drawPath(bolt, color) }
        }
    }
}

/** The battery, kept up to date while shown (Android sends the current level as soon as a receiver is registered). */
@Composable
private fun rememberBattery(ctx: Context): Battery? {
    var state by remember { mutableStateOf<Battery?>(null) }
    DisposableEffect(ctx) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                readBattery(i)?.let { state = it }
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return state
}

/** The time in the phone's own 12 or 24 hour style, moving on at each minute. */
@Composable
private fun rememberClock(ctx: Context): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
            now = System.currentTimeMillis()
        }
    }
    val format = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    return format.format(java.util.Date(now))
}

/**
 * Full screen hides the status bar, and with it the phone's clock and battery: this shows them in the app's top bar
 * instead (Settings → Screen & always-on).
 */
@Composable
fun StatusCorner(clock: Boolean, battery: Boolean, modifier: Modifier = Modifier) {
    if (!clock && !battery) return
    val ctx = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (clock) {
            Text(rememberClock(ctx), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
        }
        if (battery) {
            val b = rememberBattery(ctx)
            if (b != null) {
                BatteryGlyph(
                    b, color,
                    Modifier.padding(start = if (clock) 6.dp else 0.dp)
                        .semantics { contentDescription = if (b.charging) "Charging, ${b.percent}%" else "Battery ${b.percent}%" },
                )
                Text("${b.percent}%", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}
