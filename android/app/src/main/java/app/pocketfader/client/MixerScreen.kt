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
        shape = RoundedCornerShape(24.dp),
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
