package app.pocketfader.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MixerScreen(s: RemoteState, modifier: Modifier = Modifier) {
    // While this screen is open, keep the device list fresh.
    DisposableEffect(Unit) {
        Remote.setWantOutputs(true)
        onDispose { Remote.setWantOutputs(false) }
    }
    val scroll = rememberScrollState()
    Column(
        modifier.edgeFade(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel("Volume") {
            Text(
                if (s.volume.available) s.volume.device.ifBlank { "Computer output" } else "No audio output found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VolumeRow(s.volume)
        }

        if (s.canOutputs && s.outputs.isNotEmpty()) {
            Panel("Output devices") {
                Text(
                    "Set each device's level before you switch to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.outputs.forEach { OutputRow(it) }
            }
        }

        if (s.eq.available) EqPanel(s.eq)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = PfShape.Lg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    "bluetooth" -> Icons.Filled.Bluetooth
    "usb" -> Icons.Filled.Usb
    "hdmi" -> Icons.Filled.Tv
    "headphones" -> Icons.Filled.Headphones
    else -> Icons.Filled.Speaker
}

@Composable
private fun OutputRow(o: Output) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = o.isDefault, onClick = { Remote.setOutput(o.name) })
            Icon(iconFor(o.kind), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                o.description,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                maxLines = 1,
            )
            IconButton(onClick = { Remote.setOutputMuted(o.name, !o.muted) }) {
                Icon(if (o.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (o.muted) "Unmute" else "Mute")
            }
        }
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = o.percent / 100f,
                onValueChange = { Remote.setOutputVolume(o.name, (it * 100).roundToInt(), false) },
                onValueChangeFinished = {
                    val p = Remote.state.value.outputs.firstOrNull { it.name == o.name }?.percent ?: o.percent
                    Remote.setOutputVolume(o.name, p, true)
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${o.percent}%",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

private val BAND_LABELS = listOf("bass" to "Bass", "mid" to "Mid", "high" to "High")

@Composable
private fun EqPanel(eq: EqState) {
    Panel(if (eq.label.isNotBlank()) "Equalizer · ${eq.label}" else "Equalizer") {
        if (eq.toggle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (eq.enabled) "On" else "Off",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = eq.enabled, onCheckedChange = { Remote.eqEnable(it) })
            }
        }
        if (eq.status.isNotBlank()) {
            Text(eq.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (eq.enabled) {
            if (eq.presets.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    eq.presets.forEach { p ->
                        AssistChip(onClick = { Remote.eqPreset(p) }, label = { Text(p.replace('-', ' ')) })
                    }
                }
            }
            BAND_LABELS.forEach { (key, label) ->
                val db = eq.bands[key] ?: 0.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = db.toFloat().coerceIn(eq.min.toFloat(), eq.max.toFloat()),
                        onValueChange = { Remote.eqBand(key, ((it * 2).roundToInt() / 2.0), false) },
                        onValueChangeFinished = {
                            Remote.eqBand(key, Remote.state.value.eq.bands[key] ?: 0.0, true)
                        },
                        valueRange = eq.min.toFloat()..eq.max.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%+.1f dB".format(db),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sound & outputs while music plays on the phone itself: the phone's volume and where its sound goes.

/** One way out for the phone's sound: its name, an icon, whether the music goes there now. */
private class PhoneOutput(val name: String, val icon: ImageVector, val active: Boolean)

private fun outputKind(type: Int): Pair<String, ImageVector>? = when (type) {
    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "This phone's speaker" to Icons.Filled.Speaker
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones" to Icons.Filled.Headphones
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth" to Icons.Filled.Bluetooth
    android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio" to Icons.Filled.Usb
    android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI" to Icons.Filled.Tv
    26, 27 -> "Bluetooth" to Icons.Filled.Bluetooth          // TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER (Android 12)
    else -> null
}

/** The outputs the phone has now, the one the music goes to marked (Android 13 and later say which; before that the
 *  usual order is assumed: Bluetooth, then USB or wired headphones, else the speaker). */
private fun phoneOutputs(ctx: android.content.Context): List<PhoneOutput> {
    val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return emptyList()
    val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { d -> outputKind(d.type)?.let { (kind, icon) -> Triple(d, kind, icon) } }
        .distinctBy { (d, kind, _) -> kind + "|" + d.productName }
    val routed: Set<Int> = if (android.os.Build.VERSION.SDK_INT >= 33) {
        runCatching {
            val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(media).map { it.id }.toSet()
        }.getOrDefault(emptySet())
    } else emptySet()
    val guess = devices.firstOrNull { it.first.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.first.type == 26 || it.first.type == 27 }
        ?: devices.firstOrNull { it.first.type in setOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET) }
        ?: devices.firstOrNull { it.first.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    return devices.map { (d, kind, icon) ->
        val label = d.productName?.toString()?.takeIf { it.isNotBlank() && d.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it != android.os.Build.MODEL } ?: kind
        PhoneOutput(label, icon, if (routed.isNotEmpty()) d.id in routed else d.id == guess?.first?.id)
    }
}

/** Where the phone's sound goes now, for summaries ("Galaxy Buds", "This phone's speaker"). */
fun phoneOutputName(ctx: android.content.Context): String = phoneOutputs(ctx).firstOrNull { it.active }?.name ?: "This phone"

/** Android's own output picker (Android 14 and later), its older form, or the Bluetooth settings. */
private fun openOutputPicker(ctx: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        val shown = runCatching { android.media.MediaRouter2.getInstance(ctx).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    val sent = runCatching {
        ctx.sendBroadcast(
            android.content.Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui").putExtra("package_name", ctx.packageName)
        )
    }.isSuccess
    if (!sent || android.os.Build.VERSION.SDK_INT < 30) {
        runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun PhoneSoundScreen(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var outputs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(phoneOutputs(ctx)) }
    // the list follows devices connecting and disconnecting
    DisposableEffect(ctx) {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { outputs = phoneOutputs(ctx) }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { outputs = phoneOutputs(ctx) }
        }
        am.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    val scroll = rememberScrollState()
    Column(
        modifier.edgeFade(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel("Volume") {
            Text(
                "Music is playing on this phone: " + (outputs.firstOrNull { it.active }?.name ?: "this phone") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PhoneVolumeRow()
        }
        Panel("Output") {
            outputs.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(o.icon, null, Modifier.size(22.dp), tint = if (o.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(o.name, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
                    if (o.active) Text("Playing here", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            androidx.compose.material3.FilledTonalButton(onClick = { openOutputPicker(ctx) }) { Text("Switch output") }
            Text(
                "Android chooses where the phone's sound goes; this opens its own picker. The computer's outputs are here when " +
                    "music plays on the computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
