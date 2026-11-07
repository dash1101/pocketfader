package app.pocketfader.client

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(cfg: ServerConfig, s: RemoteState, onEditConnection: () -> Unit, modifier: Modifier = Modifier) {
    val p by Settings.prefs.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Connection") {
            val status = when {
                !s.configured -> "Not set up"
                s.online -> "Connected" + if (s.volume.device.isNotBlank()) " · ${s.volume.device}" else ""
                else -> "Can't reach the computer right now"
            }
            Info("Computer", cfg.url.ifBlank { "Not set up" })
            Info("Status", status)
            Info("YouTube Music extension", if (s.bridge) "Connected" else "Not connected")
            FilledTonalButton(onClick = onEditConnection, modifier = Modifier.padding(top = 4.dp)) { Text("Edit connection") }
        }

        Section("Appearance") {
            Choice(
                "Theme", "Black turns pixels fully off on OLED screens.",
                listOf("system" to "System", "light" to "Light", "dark" to "Dark", "black" to "Black"), p.theme,
            ) { v -> Settings.update { it.copy(theme = v) } }
            if (Build.VERSION.SDK_INT >= 31) {
                Toggle("Material You colours", "Use colours from your wallpaper.", p.dynamicColor) { v -> Settings.update { it.copy(dynamicColor = v) } }
            }
            Choice(
                "Open on", "The screen shown when the app starts.",
                listOf("playing" to "Playing", "search" to "Search", "library" to "Library", "mixer" to "Mixer"), p.startTab,
            ) { v -> Settings.update { it.copy(startTab = v) } }
        }

        Section("Screen & always-on") {
            Choice(
                "Keep screen on", "Handy when the phone or tablet is a dedicated remote or now-playing display.",
                listOf("off" to "Off", "playing" to "While playing", "always" to "Always"), p.keepAwake,
            ) { v -> Settings.update { it.copy(keepAwake = v) } }
            Choice(
                "Dim when untouched", "Lowers the brightness after a while; touch to wake. Needs Keep screen on.",
                listOf("0" to "Never", "1" to "1 min", "2" to "2 min", "5" to "5 min", "10" to "10 min"), p.dimAfterMin.toString(),
                enabled = p.keepAwake != "off",
            ) { v -> Settings.update { it.copy(dimAfterMin = v.toInt()) } }
            Toggle("Full screen", "Hide the status and navigation bars. Swipe from the edge to bring them back.", p.immersive) { v ->
                Settings.update { it.copy(immersive = v) }
            }
            Toggle(
                "Pixel shift",
                "Moves the whole screen a few pixels every so often so static parts don't burn in on OLED displays. Works best with Always on and the Black theme.",
                p.pixelShift,
            ) { v -> Settings.update { it.copy(pixelShift = v) } }
            Choice(
                "Shift every", null,
                listOf("1" to "1 min", "2" to "2 min", "5" to "5 min", "10" to "10 min"), p.pixelShiftMin.toString(), enabled = p.pixelShift,
            ) { v -> Settings.update { it.copy(pixelShiftMin = v.toInt()) } }
            Choice(
                "Shift distance", null,
                listOf("2" to "Small", "4" to "Medium", "8" to "Large"), p.pixelShiftDp.toString(), enabled = p.pixelShift,
            ) { v -> Settings.update { it.copy(pixelShiftDp = v.toInt()) } }
        }

        Section("Controls") {
            Toggle(
                "Media notification", "Playback controls on the lock screen and in the notification shade. Also keeps the connection alive in the background.",
                p.notification,
            ) { v -> Settings.update { it.copy(notification = v) } }
            Toggle("Volume buttons control the computer", "While the app is open, or the notification is active and something is playing.", p.volumeKeys) { v ->
                Settings.update { it.copy(volumeKeys = v) }
            }
            Choice(
                "Volume step", "Percent per button press.",
                listOf("1" to "1%", "2" to "2%", "5" to "5%", "10" to "10%"), p.volumeStep.toString(), enabled = p.volumeKeys,
            ) { v -> Settings.update { it.copy(volumeStep = v.toInt()) } }
            Toggle("Haptic feedback", "A small buzz on the main buttons.", p.haptics) { v -> Settings.update { it.copy(haptics = v) } }
        }

        Section("Lyrics") {
            Toggle("Romanize", "Show lines in Latin letters (Korean, Japanese kana, Cyrillic, Greek, and anything a source provides).", p.lyricsRoman) { v ->
                Settings.update { it.copy(lyricsRoman = v) }
            }
            Toggle("Translations", "Show a translation under each line when a source has one.", p.lyricsTranslation) { v ->
                Settings.update { it.copy(lyricsTranslation = v) }
            }
            Choice(
                "Sync", "Shift the lyrics if they run early or late on this device. You can also nudge them while viewing lyrics.",
                listOf("-500" to "−0.5 s", "-200" to "−0.2 s", "0" to "On time", "200" to "+0.2 s", "500" to "+0.5 s"), p.lyricsOffsetMs.toString(),
            ) { v -> Settings.update { it.copy(lyricsOffsetMs = v.toInt()) } }
            LyricsLayoutControls()
            Choice(
                "Preferred source", "Used first when it has the song. You can also switch while viewing lyrics.",
                listOf("auto" to "Automatic", "kugou" to "KuGou", "lrclib" to "LRCLIB", "netease" to "NetEase", "ytm" to "YouTube Music"), p.lyricsSource,
            ) { v -> Settings.update { it.copy(lyricsSource = v) } }
        }

        Section("Performance") {
            Choice(
                "Update speed", "How often the app asks the computer what's playing. Battery saver is gentler on old phones.",
                listOf("fast" to "Fast", "normal" to "Normal", "battery" to "Battery saver"), p.pollSpeed,
            ) { v -> Settings.update { it.copy(pollSpeed = v) } }
            Toggle("Reduce animations", "Fewer moving parts; smoother on older devices.", p.reduceMotion) { v -> Settings.update { it.copy(reduceMotion = v) } }
        }

        Section("Updates") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val info = UpdateManager.info
            val newer = UpdateManager.available(ctx)
            Info("Installed", version.ifBlank { "unknown" })
            Info("Latest release", info?.latest?.ifBlank { null } ?: "Not checked yet")
            UpdateManager.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { scope.launch { UpdateManager.check(ctx, refresh = true) } }, enabled = !UpdateManager.checking && s.online) {
                    Text(if (UpdateManager.checking) "Checking…" else "Check now")
                }
                if (newer) {
                    androidx.compose.material3.Button(onClick = { scope.launch { UpdateManager.install(ctx) } }, enabled = !UpdateManager.downloading) {
                        Text(if (UpdateManager.downloading) "Downloading…" else "Download & install")
                    }
                }
            }
            Toggle("Check automatically", "Looks for a new version when the app opens (the computer checks GitHub, at most every few hours).", p.autoUpdate) { v ->
                Settings.update { it.copy(autoUpdate = v) }
            }
            if (info != null && newer && info.notes.isNotBlank()) {
                Text(info.notes.lines().take(14).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section("About") {
            Info("Pocketfader app", version.ifBlank { "unknown" })
            Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all settings") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) {
        ConfirmDialog("Reset settings?", "This restores every setting on this page to its default. Your connection is kept.", "Reset",
            onDismiss = { confirmReset = false }) {
            confirmReset = false
            Settings.reset()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val icon = when (title) {
        "Connection" -> Icons.Filled.Link; "Appearance" -> Icons.Filled.Palette; "Screen & always-on" -> Icons.Filled.Smartphone
        "Controls" -> Icons.Filled.TouchApp; "Lyrics" -> Icons.Filled.MusicNote; "Performance" -> Icons.Filled.Speed
        "Updates" -> Icons.Filled.SystemUpdate; else -> Icons.Filled.Info
    }
    Column {
        Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(
    title: String,
    subtitle: String?,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) })
            }
        }
    }
}
