package app.pocketfader.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// YTM Starcast's parts (YouTube Music remote, Discord status, Refine, computer audio) each have a switch on the computer.
// When one is off, the screens that belong to it give way to a short note with a button that turns it back on.

/** "This part is switched off", with a button that switches it on again on the computer. */
@Composable
fun PartOffNote(title: String, text: String, part: String, online: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Card(
        modifier.fillMaxWidth(),
        shape = PfShape.Lg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { scope.launch { Remote.setPart(part, true) } }, enabled = online) { Text("Turn it on") }
        }
    }
}

/** In place of the player, the queue and search while the YouTube Music remote is switched off on the computer. */
@Composable
fun RemoteOffPage(s: RemoteState, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier.edgeFade(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PartOffNote(
            "The YouTube Music remote is off",
            "It's switched off on the computer, so this app doesn't show or control what plays there. " +
                "You can still play music on this phone with the button at the top.",
            "remote", s.online, Modifier.widthIn(max = 560.dp),
        )
        // the computer's volume is a part of its own, and can stay on
        if (s.audioOn && s.volume.available) {
            Card(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = PfShape.Lg,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                    Text(
                        s.volume.device.ifBlank { "Computer volume" }, Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VolumeRow(s.volume)
                }
            }
        }
    }
}

/** In place of the computer's volume, outputs and equalizer while computer audio is switched off. */
@Composable
fun AudioOffNote(online: Boolean, modifier: Modifier = Modifier) {
    PartOffNote(
        "Computer audio is off",
        "The computer's volume, output devices and equalizer are switched off in YTM Starcast, so they aren't shown here.",
        "audio", online, modifier,
    )
}
