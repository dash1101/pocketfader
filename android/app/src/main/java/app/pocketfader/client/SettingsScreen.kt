package app.pocketfader.client

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
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

    // The settings are split into pages: an overview of categories, and one page per category.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val link = SettingsNav.deepLink
    LaunchedEffect(link) {
        if (link != null) {
            page = link
            SettingsNav.deepLink = null
        }
    }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val current = page
    if (current == null) {
        SettingsOverview(p, s, cfg, version, modifier) { page = it }
        return
    }
    Column(modifier.fillMaxSize()) {
    PageHeader(allCats.firstOrNull { it.key == current }?.title ?: "Settings") { page = null }
    if (current == "sound") {
        MixerScreen(s, Modifier.weight(1f).fillMaxWidth())
    } else {
    CompositionLocalProvider(LocalInPage provides true) {
    val pageScroll = rememberScrollState()
    Column(
        Modifier.weight(1f).edgeFade(pageScroll).verticalScroll(pageScroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (page == "connection") Section("Connection") {
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

        if (page == "appearance") Section("Appearance") {
            Choice(
                "Theme", "Black turns pixels fully off on OLED screens.",
                listOf("system" to "System", "light" to "Light", "dark" to "Dark", "black" to "Black"), p.theme,
            ) { v -> Settings.update { it.copy(theme = v) } }
            if (Build.VERSION.SDK_INT >= 31) {
                Toggle("Material You colours", "Use colours from your wallpaper (when the accent colour below is on Auto).", p.dynamicColor) { v -> Settings.update { it.copy(dynamicColor = v) } }
            }
            AccentPicker(p)
            Toggle("Cover colours in the background", "Tint the Now playing screen with the cover's colour.", p.coverWash) { v -> Settings.update { it.copy(coverWash = v) } }
            Toggle("Lyric highlight follows the cover", "Off: the line being sung uses your accent colour.", p.coverHighlight) { v -> Settings.update { it.copy(coverHighlight = v) } }
            Choice(
                "Navigation bar", "Classic keeps the bar along the bottom; Pill floats above the content.",
                listOf("bar" to "Classic bar", "pill" to "Floating pill"), p.navStyle,
            ) { v -> Settings.update { it.copy(navStyle = v) } }
            Choice(
                "Open on", "The screen shown when the app starts.",
                listOf("playing" to "Playing", "search" to "Search", "library" to "Library"), p.startTab,
            ) { v -> Settings.update { it.copy(startTab = v) } }
        }

        if (page == "effects") Section("Effects") {
            Choice(
                "Cover glow", "A halo of the cover's colour around the album art. Extreme is the brightest.",
                listOf("off" to "Off", "soft" to "Soft", "strong" to "Strong", "extreme" to "Extreme"), p.coverGlow,
            ) { v -> Settings.update { it.copy(coverGlow = v) } }
            Choice(
                "Cover shape", "Circle spins slowly like a record while music plays.",
                listOf("rounded" to "Rounded", "soft" to "Soft", "circle" to "Circle", "cut" to "Cut corners"), p.coverShape,
            ) { v -> Settings.update { it.copy(coverShape = v) } }
            Toggle("Glow on text, icons and buttons", "A soft glow around the song title, the lyric being sung and the buttons that are on.", p.glowUi) { v ->
                Settings.update { it.copy(glowUi = v) }
            }
            Toggle("Wavy seek bar", "The played part of the bar ripples while music plays.", p.wavyBar) { v -> Settings.update { it.copy(wavyBar = v) } }
            Toggle("Screen transitions", "A short fade and slide between Playing, Search, Library and Settings.", p.transitions) { v -> Settings.update { it.copy(transitions = v) } }
            Text(
                "These are light drawing effects. “Reduce animations” (under Performance) stops all motion and keeps the rest.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (page == "screen") Section("Screen & always-on") {
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

        if (page == "controls") Section("Controls") {
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
            // other players on the computer (Spotify, a video in another tab, VLC...)
            var pauseOthers by remember { mutableStateOf<Boolean?>(null) }
            androidx.compose.runtime.LaunchedEffect(s.online) {
                if (s.online) pauseOthers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { Remote.serverPrefs().optBoolean("pauseOthers", false) }.getOrNull()
                }
            }
            pauseOthers?.let { on ->
                Toggle(
                    "Pause other players when YouTube Music starts",
                    "Other music or video playing on the computer is paused when a YouTube Music song starts, so they don't play over each other.",
                    on,
                ) { v -> pauseOthers = v; Remote.setServerPref("pauseOthers", v) }
            }
            OutlinedButton(onClick = { Remote.pauseOthers() }, enabled = s.online) { Text("Pause other players now") }
        }

        if (page == "lyrics") Section("Lyrics") {
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

        if (page == "performance") Section("Performance") {
            Choice(
                "Update speed", "How often the app asks the computer what's playing. Battery saver is gentler on old phones.",
                listOf("fast" to "Fast", "normal" to "Normal", "battery" to "Battery saver"), p.pollSpeed,
            ) { v -> Settings.update { it.copy(pollSpeed = v) } }
            Toggle("Reduce animations", "Fewer moving parts; smoother on older devices.", p.reduceMotion) { v -> Settings.update { it.copy(reduceMotion = v) } }
            Toggle("Pictures in the queue", "Little cover pictures next to each queued song. Turn off if scrolling the queue is slow on an old device.", p.queueArt) { v ->
                Settings.update { it.copy(queueArt = v) }
            }
        }

        if (page == "discord") {
            if (s.canBeacon) {
                BeaconCard(s.online)
            } else {
                Text(
                    "This computer's Pocketfader doesn't support Discord presence yet. Update Pocketfader on the computer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "YTM Beacon shows the song playing in YouTube Music on your Discord profile. Here you can see whether it's working, " +
                    "turn it on or off, hide it for a while, change what it shows, and install or update it on the computer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (page == "updates") Section("Updates") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val info = UpdateManager.info
            val newer = UpdateManager.available(ctx)
            Info("Installed", version.ifBlank { "unknown" })
            Info("Latest release", info?.latest?.ifBlank { null } ?: "Not checked yet")
            UpdateManager.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (UpdateManager.needsReinstall) {
                OutlinedButton(onClick = { UpdateManager.openReleasePage(ctx) }) { Text("Open the download page") }
            }
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
            val notes = remember(info?.notes) { info?.notes?.let { parseReleaseNotes(it) } }
            if (notes != null) {
                // just the "What's new" part of the release, as a list, not the whole release page
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        notes.heading.replace("What's new in", "What's new in").ifBlank { "What's new" },
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    )
                    for (item in notes.items.take(12)) {
                        Row {
                            Text("•", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(inlineMarkdown(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (page == "about") Section("About") {
            Info("Pocketfader app", version.ifBlank { "unknown" })
            Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all settings") }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
    }
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
    val inPage = LocalInPage.current
    Column {
        if (!inPage) {
            Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(allCats.firstOrNull { it.title == title }?.icon ?: Icons.Filled.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
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

// ---------------------------------------------------------------------------------------------
// The overview: categories in groups. Tapping one opens its page.

/** Lets other screens (the "new version" banner) open a particular settings page. */
object SettingsNav {
    var deepLink by mutableStateOf<String?>(null)
}

private val LocalInPage = androidx.compose.runtime.compositionLocalOf { false }

private class Cat(val key: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val groups = listOf(
    "Listening" to listOf(
        Cat("sound", "Sound & outputs", Icons.Filled.Tune), Cat("lyrics", "Lyrics", Icons.Filled.MusicNote), Cat("controls", "Controls", Icons.Filled.TouchApp),
    ),
    "Look & feel" to listOf(
        Cat("appearance", "Appearance", Icons.Filled.Palette), Cat("effects", "Effects", Icons.Filled.AutoAwesome),
        Cat("screen", "Screen & always-on", Icons.Filled.Smartphone),
    ),
    "Connections" to listOf(
        Cat("connection", "Connection", Icons.Filled.Link), Cat("discord", "Discord presence", Icons.Filled.Chat), Cat("updates", "Updates", Icons.Filled.SystemUpdate),
    ),
    "More" to listOf(Cat("performance", "Performance", Icons.Filled.Speed), Cat("about", "About", Icons.Filled.Info)),
)
private val allCats = groups.flatMap { it.second }

private fun themeLabel(t: String) = when (t) { "light" -> "Light"; "dark" -> "Dark"; "black" -> "Black"; else -> "System" }

private fun summaryOf(key: String, p: Prefs, s: RemoteState, cfg: ServerConfig, version: String): String = when (key) {
    "sound" -> if (s.volume.available) "${s.volume.percent}%" + (if (s.volume.muted) " (muted)" else "") + s.volume.device.let { if (it.isBlank()) "" else " · $it" } else "No audio output found"
    "lyrics" -> (if (p.lyricsRoman) "Romanized" else "Original text") + " · " + (if (p.lyricsSource == "auto") "automatic source" else p.lyricsSource)
    "controls" -> "Notification " + (if (p.notification) "on" else "off") + " · volume keys " + (if (p.volumeKeys) "on" else "off")
    "appearance" -> themeLabel(p.theme) + " theme" + (if (p.accent != 0) " · custom accent" else "") + " · " + (if (p.navStyle == "pill") "pill navigation" else "classic navigation")
    "effects" -> "Glow " + p.coverGlow + " · " + (if (p.wavyBar) "wavy bar" else "plain bar")
    "screen" -> when (p.keepAwake) { "always" -> "Always on"; "playing" -> "On while playing"; else -> "Screen sleeps normally" } + (if (p.pixelShift) " · pixel shift" else "")
    "connection" -> if (!s.configured) "Not set up" else if (s.online) "Connected · " + cfg.url.removePrefix("http://").removePrefix("https://").trimEnd('/') else "Can't reach the computer"
    "discord" -> BeaconModel.status?.let { beaconSummary(it) } ?: "Show your music on Discord"
    "updates" -> "Installed " + version.ifBlank { "unknown" }
    "performance" -> (when (p.pollSpeed) { "fast" -> "Fast updates"; "battery" -> "Battery saver"; else -> "Normal updates" }) + (if (p.reduceMotion) " · reduced animations" else "")
    else -> "Version " + version.ifBlank { "unknown" }
}

@Composable
private fun SettingsOverview(p: Prefs, s: RemoteState, cfg: ServerConfig, version: String, modifier: Modifier, open: (String) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier.edgeFade(scroll).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, cats) in groups) {
            Text(
                label.uppercase(), Modifier.padding(start = 8.dp, top = 10.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            )
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column {
                    for (c in cats) CategoryRow(c, summaryText(c.key, p, s, cfg, version)) { open(c.key) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun summaryText(key: String, p: Prefs, s: RemoteState, cfg: ServerConfig, version: String): String {
    if (key == "updates") return if (UpdateManager.available(LocalContext.current)) "A new version is ready" else "Installed " + version.ifBlank { "unknown" }
    return summaryOf(key, p, s, cfg, version)
}

@Composable
private fun CategoryRow(c: Cat, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(c.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back to settings") }
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
