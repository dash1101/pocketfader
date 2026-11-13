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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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

fun batteryIcon(b: Battery): ImageVector = when {
    b.charging -> Icons.Filled.BatteryChargingFull
    b.percent >= 95 -> Icons.Filled.BatteryFull
    b.percent >= 80 -> Icons.Filled.Battery6Bar
    b.percent >= 65 -> Icons.Filled.Battery5Bar
    b.percent >= 50 -> Icons.Filled.Battery4Bar
    b.percent >= 35 -> Icons.Filled.Battery3Bar
    b.percent >= 20 -> Icons.Filled.Battery2Bar
    b.percent >= 8 -> Icons.Filled.Battery1Bar
    else -> Icons.Filled.Battery0Bar
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
                Icon(
                    batteryIcon(b), if (b.charging) "Charging, ${b.percent}%" else "Battery ${b.percent}%",
                    Modifier.padding(start = if (clock) 6.dp else 0.dp).size(18.dp), tint = color,
                )
                Text("${b.percent}%", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}
