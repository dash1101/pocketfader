package app.pocketfader.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

// The speed of music playing on this phone (Refine's speed menu, for the phone's own player): a big figure, steps of
// 0.05, a slider, a few common speeds, and whether the pitch stays. Music playing on the computer isn't affected.

/** The slowest and fastest music on this phone can play, in percent of its own speed (the same limits as the settings). */
private const val SPEED_MIN = 25
private const val SPEED_MAX = 300
private val SpeedPresets = listOf(50, 75, 100, 125, 150, 200)

/** "1.25×", "1.5×", "2×": a speed written as short as it can be (in the phone's own number style). */
fun speedShort(percent: Int): String = java.text.DecimalFormat("0.##").format(percent / 100.0) + "×"

/** "1.00×": a speed with both decimals, for the big figure (its width doesn't change as it moves). */
private fun speedLong(percent: Int): String = java.text.DecimalFormat("0.00").format(percent / 100.0) + "×"

private fun saveSpeed(percent: Int) {
    val p = percent.coerceIn(SPEED_MIN, SPEED_MAX)
    Settings.update { if (it.phoneSpeed == p) it else it.copy(phoneSpeed = p) }
}

/** The next step of 0.05 up or down from [percent]. */
private fun stepUp(percent: Int) = (percent / 5) * 5 + 5
private fun stepDown(percent: Int) = ((percent + 4) / 5) * 5 - 5

/**
 * The speed button beside the song's title, for music playing on this phone: the speedometer at the normal speed, and
 * the speed itself on a tonal pill ("1.25×") at any other, so a changed speed is never forgotten.
 */
@Composable
fun SpeedButton(modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val percent = prefs.phoneSpeed
    val scheme = MaterialTheme.colorScheme
    if (percent == 100) {
        ShapeIconButton(Icons.Filled.Speed, "Speed: normal", onClick = { open = true }, modifier = modifier, tint = scheme.onSurfaceVariant)
    } else {
        val label = speedShort(percent)
        Surface(
            onClick = { open = true },
            shape = PfShape.Full,
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            modifier = modifier.padding(horizontal = 4.dp).height(32.dp).semantics { contentDescription = "Speed: $label" },
        ) {
            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
    if (open) SpeedDialog { open = false }
}

/** The speed menu itself. Changes are heard at once; the slider's are saved when it is let go. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    // While the slider is dragged its value goes straight to the player (heard at once) and is saved only when the slider
    // is let go: saving on every step would redraw the whole app many times a second.
    val drag = remember { mutableStateOf<Int?>(null) }
    val percent = drag.value ?: prefs.phoneSpeed
    // a saved speed (from the slider, a step or a preset) is the one shown from then on
    LaunchedEffect(prefs.phoneSpeed) { drag.value = null }
    // closed in the middle of a drag: keep what was being heard
    DisposableEffect(Unit) { onDispose { drag.value?.let { saveSpeed(it) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Speed, null) },
        title = { Text("Speed") },
        text = {
            // scrolls when the screen is short (a phone on its side), so the pitch switch is never cut off
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "For music playing on this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeIconButton(Icons.Filled.Remove, "Slower", onClick = { saveSpeed(stepDown(percent)) }, enabled = percent > SPEED_MIN)
                    Text(
                        speedLong(percent),
                        Modifier.widthIn(min = 132.dp),
                        // figures of one width, so the number doesn't shiver as it changes
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
                    )
                    ShapeIconButton(Icons.Filled.Add, "Faster", onClick = { saveSpeed(stepUp(percent)) }, enabled = percent < SPEED_MAX)
                }
                Slider(
                    value = percent / 100f,
                    onValueChange = { v ->
                        // in steps of 0.05
                        val p = ((v * 20f).roundToInt() * 5).coerceIn(SPEED_MIN, SPEED_MAX)
                        if (p != drag.value) {
                            drag.value = p
                            LocalPlayer.applySpeed(p, prefs.keepPitch)
                        }
                    },
                    onValueChangeFinished = { drag.value?.let { saveSpeed(it) } },
                    valueRange = SPEED_MIN / 100f..SPEED_MAX / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                for (row in SpeedPresets.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in row) {
                            val chosen = percent == p
                            FilterChip(
                                selected = chosen, onClick = { saveSpeed(p) }, shape = chipShape(chosen),
                                label = { Text(speedShort(p)) },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(PfShape.Sm)
                        .toggleable(value = prefs.keepPitch, role = Role.Switch, onValueChange = { on -> Settings.update { it.copy(keepPitch = on) } })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Keep the pitch", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (prefs.keepPitch) "Voices sound the same at any speed."
                            else "Slower sounds lower and faster sounds higher, as a record would.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = prefs.keepPitch, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { if (percent != 100) TextButton(onClick = { saveSpeed(100) }) { Text("Normal speed") } },
    )
}
