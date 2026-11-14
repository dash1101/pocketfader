package app.pocketfader.client

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DownloadForOffline
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
import androidx.compose.material.icons.filled.LibraryMusic
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Extension
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
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
    val link = SettingsNav.deepLink?.let { if (it == "library") "downloads" else it }    // "library" is part of downloads now
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
    if (current == "sound" && Remote.local) {
        // music plays on this phone: its own volume and outputs
        PhoneSoundScreen(Modifier.weight(1f).fillMaxWidth())
    } else if (current == "sound" && !s.audioOn) {
        // computer audio is switched off on the computer: say so, with a way to turn it back on
        Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { AudioOffNote(s.online) }
    } else if (current == "sound") {
        MixerScreen(s, Modifier.weight(1f).fillMaxWidth())
    } else {
    // the page's rows glide in one after another as it opens (not when screen transitions are switched off)
    val arrival = rememberArrival(current)
    CompositionLocalProvider(
        LocalPageTitle provides (allCats.firstOrNull { it.key == current }?.title ?: ""),
        LocalSettingsArrival provides (if (p.transitions) arrival else null),
    ) {
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
            Info(
                "YouTube Music extension",
                if (!s.remoteOn) "Not used: the YouTube Music remote is switched off" else if (s.ytm) "Connected" else "Not connected",
            )
            FilledTonalButton(onClick = onEditConnection, modifier = Modifier.pageGlide().padding(top = 4.dp)) { Text("Edit connection") }
            if (s.online && !s.ytm) OutlinedButton(onClick = { page = "extension" }, modifier = Modifier.pageGlide()) { Text("Set up the browser extension") }
        }

        if (page == "extension") Section("Browser extension") { ExtensionSetup(s, LocalSettingsArrival.current) }

        if (page == "parts") Section("Parts") {
            // fresh from the computer each time the page opens (another phone or the browser may have changed them)
            LaunchedEffect(Unit) { Remote.refreshParts() }
            val parts = s.parts
            if (parts == null) {
                Text(
                    "This computer's YTM Starcast has no parts to switch on or off yet. Update YTM Starcast on the computer.",
                    modifier = Modifier.pageGlide(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val scope = rememberCoroutineScope()
                Text(
                    "Switch off the parts you don't use. They are switched on the computer, so the change counts for every phone " +
                        "and browser that uses it.",
                    modifier = Modifier.pageGlide(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Toggle(
                    "YouTube Music remote", "See and control what plays in YouTube Music on the computer: the queue, search and your library.",
                    parts.remote, enabled = s.online,
                ) { v -> scope.launch { Remote.setPart("remote", v) } }
                Toggle(
                    "Discord status", "Show the song you're playing on your Discord profile.",
                    parts.discord, enabled = s.online,
                ) { v -> scope.launch { Remote.setPart("discord", v); if (s.canBeacon) BeaconModel.refresh() } }
                Toggle(
                    "Refine in YouTube Music", "Improvements inside YouTube Music in the computer's browser. Its own options are in its ✦ panel there.",
                    parts.refine, enabled = s.online,
                ) { v -> scope.launch { Remote.setPart("refine", v) } }
                Toggle(
                    "Computer audio", "The computer's volume, output devices and equalizer.",
                    parts.audio, enabled = s.online,
                ) { v -> scope.launch { Remote.setPart("audio", v) } }
            }
        }

        if (page == "appearance") Section("Appearance") {
            Choice(
                "Theme", "Black turns pixels fully off on OLED screens.",
                listOf("system" to "System", "light" to "Light", "dark" to "Dark", "black" to "Black"), p.theme,
            ) { v -> Settings.update { it.copy(theme = v) } }
            Toggle(
                "Album colours",
                "The whole app takes the colours of the cover that plays, as Refine does in YouTube Music." +
                    (if (p.accent != 0) " The accent colour chosen below replaces them while it's set." else ""),
                p.albumColors,
            ) { v -> Settings.update { it.copy(albumColors = v) } }
            if (Build.VERSION.SDK_INT >= 31) {
                // the theme takes Album colours first, so the wallpaper's colours would change nothing while it is on
                val albumOn = p.albumColors && p.accent == 0
                Toggle(
                    "Material You colours",
                    if (albumOn) "Not used while Album colours is on." else "Use colours from your wallpaper (when the accent colour below is on Auto).",
                    p.dynamicColor, enabled = !albumOn,
                ) { v -> Settings.update { it.copy(dynamicColor = v) } }
            }
            AccentPicker(p, Modifier.pageGlide())
            Toggle("Cover colours in the background", "Tint the Now playing screen with the cover's colour.", p.coverWash) { v -> Settings.update { it.copy(coverWash = v) } }
            Toggle("Lyric highlight follows the cover", "Off: the line being sung uses your accent colour.", p.coverHighlight) { v -> Settings.update { it.copy(coverHighlight = v) } }
            Choice(
                "Navigation bar", "Classic keeps the bar along the bottom; Pill floats above the content.",
                listOf("bar" to "Classic bar", "pill" to "Floating pill"), p.navStyle,
            ) { v -> Settings.update { it.copy(navStyle = v) } }
            Choice(
                "Open on", "The screen shown when the app starts.",
                listOf("playing" to "Playing", "search" to "Search", "settings" to "Settings"), p.startTab,
            ) { v -> Settings.update { it.copy(startTab = v) } }
        }

        if (page == "effects") Section("Effects") {
            Choice(
                "Cover glow", "How bright the cover's colours shine around the album art. Extreme is the brightest.",
                listOf("off" to "Off", "soft" to "Soft", "strong" to "Strong", "extreme" to "Extreme"), p.coverGlow,
            ) { v -> Settings.update { it.copy(coverGlow = v) } }
            Choice(
                "Cover visualizer",
                "Glow: the cover's colours shine softly around it. Halo: the blurred cover turns slowly around it. " +
                    "Orbit: a ring of the album's colours runs round its edge." +
                    (if (p.coverGlow == "off") " Turn on Cover glow to use one." else ""),
                listOf("glow" to "Glow", "halo" to "Halo", "orbit" to "Orbit"), p.coverViz, enabled = p.coverGlow != "off",
            ) { v -> Settings.update { it.copy(coverViz = v) } }
            Choice(
                "Cover shape", "Circle spins slowly like a record while music plays.",
                listOf("rounded" to "Rounded", "soft" to "Soft", "circle" to "Circle", "cut" to "Cut corners"), p.coverShape,
            ) { v -> Settings.update { it.copy(coverShape = v) } }
            Toggle("Glow on text, icons and buttons", "A soft glow around the song title, the lyric being sung and the buttons that are on.", p.glowUi) { v ->
                Settings.update { it.copy(glowUi = v) }
            }
            Toggle("Wavy seek bar", "The played part of the bar ripples while music plays.", p.wavyBar) { v -> Settings.update { it.copy(wavyBar = v) } }
            Toggle(
                "Screen transitions", "A short fade and slide between screens, and the rows of lists, pages and settings gliding in as they open.",
                p.transitions,
            ) { v -> Settings.update { it.copy(transitions = v) } }
            Text(
                "These are light drawing effects. “Reduce animations” (under Performance) stops all motion and keeps the rest.",
                modifier = Modifier.pageGlide(),
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
            Toggle("Show the time", "In full screen, the time in the top corner.", p.barClock, enabled = p.immersive) { v ->
                Settings.update { it.copy(barClock = v) }
            }
            Toggle("Show the battery", "In full screen, the battery level beside it.", p.barBattery, enabled = p.immersive) { v ->
                Settings.update { it.copy(barBattery = v) }
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
            // the computer's volume is part of "Computer audio"; with that switched off there is nothing for the buttons to change
            if (s.audioOn) {
                Toggle("Volume buttons control the computer", "While the app is open, or the notification is active and something is playing.", p.volumeKeys) { v ->
                    Settings.update { it.copy(volumeKeys = v) }
                }
                Choice(
                    "Volume step", "Percent per button press.",
                    listOf("1" to "1%", "2" to "2%", "5" to "5%", "10" to "10%"), p.volumeStep.toString(), enabled = p.volumeKeys,
                ) { v -> Settings.update { it.copy(volumeStep = v.toInt()) } }
            }
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
            OutlinedButton(onClick = { Remote.pauseOthers() }, enabled = s.online, modifier = Modifier.pageGlide()) { Text("Pause other players now") }
        }

        // what only matters when the phone itself plays the music: how a changed speed sounds, and when it stops
        if (page == "controls") Section("Music on this phone") {
            Toggle(
                "Keep the pitch when the speed changes",
                "Off: a slower song also sounds lower and a faster one higher, as on a record.",
                p.keepPitch,
            ) { v -> Settings.update { it.copy(keepPitch = v) } }
            Text(
                "The speed is set from the player while music plays on this phone" +
                    (if (p.phoneSpeed != 100) " (now ${speedShort(p.phoneSpeed)})." else "."),
                modifier = Modifier.pageGlide(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // the phone's player follows this setting as it changes, so this works whether or not music is playing
            if (p.phoneSpeed != 100) {
                OutlinedButton(onClick = { Settings.update { it.copy(phoneSpeed = 100) } }, modifier = Modifier.pageGlide()) {
                    Text("Back to normal speed")
                }
            }
            Toggle(
                "Keep playing when the app is swiped away",
                "When off, swiping the app away stops the music; the queue and your place in the song are kept.",
                p.keepPlayingClosed,
            ) { v -> Settings.update { it.copy(keepPlayingClosed = v) } }
            Toggle(
                "Pause when headphones disconnect",
                "Pauses when the sound would move to the phone's speaker, as when headphones or an AUX cable are unplugged, " +
                    "or a Bluetooth or USB speaker disconnects.",
                p.pauseOnUnplug,
            ) { v -> Settings.update { it.copy(pauseOnUnplug = v) }; LocalPlayer.setPauseOnUnplug(v) }
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
            // one block, so its titles stay close to their chips and it glides in as one row
            Column(Modifier.pageGlide(), verticalArrangement = Arrangement.spacedBy(6.dp)) { LyricsLayoutControls() }
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
                BeaconCard(s.online, Modifier.pageGlide())
            } else {
                Text(
                    "This computer's YTM Starcast doesn't support Discord status yet. Update YTM Starcast on the computer.",
                    modifier = Modifier.pageGlide(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Discord status shows the song playing in YouTube Music on your Discord profile. Here you can see whether it's " +
                    "working, turn it on or off, hide it for a while and change what it shows. Discord has to be open on the computer.",
                modifier = Modifier.pageGlide().padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // only the song playing goes to the computer this app is connected to; with no computer there is nothing to show it
            if (cfg.isSet) Section("Music on this phone") {
                Toggle(
                    "Show what this phone plays",
                    "When music plays on this phone, your computer shows it as your Discord status. Needs YTM Starcast and the " +
                        "Discord app running on your computer." +
                        if (s.online && !s.canPhoneDiscord) " This computer's YTM Starcast can't do this yet: update YTM Starcast on the computer." else "",
                    p.discordFromPhone,
                ) { v -> Settings.update { it.copy(discordFromPhone = v) }; PhonePresence.refresh() }
            }
        }

        if (page == "downloads") Section("Downloads") {
            val saved by Downloads.items.collectAsStateWithLifecycle()
            var confirm by remember { mutableStateOf(false) }
            Choice(
                "Play music on", "On this phone, songs from YouTube Music play straight from the internet (or from your downloads); " +
                    "nothing has to be running on a computer.",
                listOf("computer" to "The computer", "phone" to "This phone"), p.playOn,
            ) { v -> Remote.setPlayOn(v, carryOver = false) }
            Choice(
                "Download quality",
                "For songs saved from now on: about 1.2 MB a minute at Best, 1 MB at Standard, 0.4 MB at Data saver. Best is " +
                    "usually Opus, Standard AAC (plays everywhere).",
                listOf("best" to "Best · 160 kbps", "normal" to "Standard · 128 kbps", "saver" to "Data saver · 50 kbps"), p.dlQuality,
            ) { v -> Settings.update { it.copy(dlQuality = v) }; AutoDownload.soon() }
            Info("Saved on this phone", "${saved.size} songs · ${formatBytes(saved.sumOf { it.size })}")
            Text(
                "Downloads keep the song, its cover and its lyrics, and play with no connection. Download from a song's ⋮ menu, " +
                    "a playlist, the queue or the player. They're stored in the app's own folder and removed if you uninstall it.",
                modifier = Modifier.pageGlide(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirm = true }, enabled = saved.isNotEmpty(), modifier = Modifier.pageGlide()) { Text("Delete all downloads") }
            if (confirm) ConfirmDialog("Delete all downloads?", "${saved.size} songs will be removed from this phone.", "Delete",
                onDismiss = { confirm = false }) { confirm = false; Downloads.deleteAll() }
        }

        // the library and what's kept offline are on the downloads page too: one place for music on this phone
        if (page == "downloads") {
            val lists by Library.playlists.collectAsStateWithLifecycle()
            val liked by Library.liked.collectAsStateWithLifecycle()
            val at by Library.syncedAt.collectAsStateWithLifecycle()
            val status by Library.status.collectAsStateWithLifecycle()
            val problem by Library.problem.collectAsStateWithLifecycle()
            val keep by Library.keepOffline.collectAsStateWithLifecycle()
            val history by Library.history.collectAsStateWithLifecycle()
            val saved by Downloads.items.collectAsStateWithLifecycle()
            var confirmHistory by remember { mutableStateOf(false) }
            Section("Your library") {
                Info("Synced from your account", if (at == 0L) "Not yet" else "${ago(at).replaceFirstChar { it.uppercase() }} · ${lists.size} playlists · ${liked.size} liked songs")
                val waiting = Library.pendingCount
                if (waiting > 0) Info("Waiting to be sent", "$waiting change" + (if (waiting == 1) "" else "s") + " made without the account (likes, songs added to playlists)")
                status?.let { Text(it, Modifier.pageGlide(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (status == null) problem?.let { Info("The last automatic sync stopped", it) }
                FilledTonalButton(onClick = { Library.sync() }, enabled = s.ytm && status == null, modifier = Modifier.pageGlide()) {
                    Text(if (status != null) "Syncing…" else "Sync now")
                }
                Text(
                    "Your playlists, liked songs and YouTube Music home page are copied to this phone while YouTube Music is open on " +
                        "the computer, so they're here when it isn't: on the go, with no computer at all, or with no connection " +
                        "(for downloaded songs). Likes and playlist additions made in the meantime are sent at the next sync." +
                        (if (!s.ytm) " Open YouTube Music on the computer to sync." else ""),
                    modifier = Modifier.pageGlide(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Toggle("Sync automatically", "Each time this phone pairs with or connects to a computer with YouTube Music open, and every few hours while it stays connected.", p.autoSync) { v ->
                    Settings.update { it.copy(autoSync = v) }
                }
            }
            Section("Automatic downloads") {
                Toggle("Liked songs", "Keep the songs you like downloaded, new likes included.", p.autoDlLiked) { v ->
                    Settings.update { it.copy(autoDlLiked = v) }; AutoDownload.soon()
                }
                val song = remember(p.dlQuality, saved) { AutoDownload.typicalSong() }
                val limitBytes = p.autoDlLimitMb * 1_048_576L
                val songsNeed = p.smartDownloads.coerceAtLeast(0) * song
                // room for automatic downloads: what's free, plus what they take now (they make way for each other)
                val freeNow = remember(saved) { Downloads.freeBytes() }
                val room = if (freeNow < 0) Long.MAX_VALUE else freeNow + saved.filter { it.auto }.sumOf { it.size }
                NumberChoice(
                    "Smart downloads",
                    when {
                        p.smartDownloads < 0 ->
                            "As many as fit in the space below: your most played songs and quick picks first, then liked songs, " +
                                "what you play most and your playlists." +
                                if (p.autoDlLimitMb > 0) " About ${limitBytes / song} songs at this quality." else
                                    " With no limit, it stops 1 GB short of filling the phone."
                        else ->
                            "Keep up to this many of your most played songs and today's quick picks on the phone, refreshed as your " +
                                "taste changes (fewer while your listening history is short)." +
                                if (p.autoDlLimitMb > 0 && songsNeed > limitBytes)
                                    " That many need about ${gb(songsNeed)}: about ${limitBytes / song} fit in the space below." else ""
                    },
                    presets = listOf(0 to "Off", 25 to "25", 50 to "50", 100 to "100", 250 to "250", 500 to "500", 1000 to "1000", -1 to "Fill the space"),
                    value = p.smartDownloads, range = 1..1000, unit = "songs", custom = { "$it songs" },
                    warning = if (p.smartDownloads > 0 && songsNeed > room)
                        "That many need about ${gb(songsNeed)}, more than the phone has room for (${gb(room)})." else null,
                    warnAbove = if (room == Long.MAX_VALUE) null else (room / song).toInt().coerceAtLeast(1),
                ) { v -> Settings.update { it.copy(smartDownloads = v) }; Recommend.refreshIfStale(); AutoDownload.soon() }
                Info("Playlists kept on this phone", if (keep.isEmpty()) "None. Open a playlist and turn on “Keep on this phone”." else "${keep.size}: " +
                    keep.mapNotNull { k -> if (k == "LM") "Liked music" else lists.firstOrNull { it.id == k }?.title }.joinToString(", "))
                val free = remember { Downloads.freeBytes() }
                NumberChoice(
                    "Space for automatic downloads",
                    "Songs you download yourself don't count, and are never removed automatically." +
                        (if (p.autoDlLimitMb > 0) " Room for about ${limitBytes / song} songs at this quality." else "") +
                        if (free >= 0) " ${gb(free)} free on this phone." else "",
                    presets = listOf(1 to "1 GB", 2 to "2 GB", 5 to "5 GB", 10 to "10 GB", 25 to "25 GB", 50 to "50 GB", 100 to "100 GB", 0 to "No limit"),
                    value = if (p.autoDlLimitMb <= 0) 0 else (p.autoDlLimitMb + 1023) / 1024,
                    range = 1..2000, unit = "GB", custom = { "$it GB" },
                    warning = when {
                        p.autoDlLimitMb > 0 && limitBytes > room ->
                            "More than the phone has room for (${gb(room)}): downloads stop a little before it's full."
                        p.autoDlLimitMb <= 0 && room != Long.MAX_VALUE ->
                            "Downloads stop a little before the phone is full (${gb(room)} free for them now)."
                        else -> null
                    },
                    warnAbove = if (room == Long.MAX_VALUE) null else (room / 1_073_741_824L).toInt(),
                ) { v -> Settings.update { it.copy(autoDlLimitMb = v * 1024) }; AutoDownload.soon() }
                Toggle("Only on Wi-Fi", "Automatic downloads wait for Wi-Fi (or another connection that isn't metered).", p.dlWifiOnly) { v ->
                    Settings.update { it.copy(dlWifiOnly = v) }; AutoDownload.soon()
                }
                val plan = remember(p, keep, liked, lists, saved) { AutoDownload.plan() }
                val autoNow = saved.filter { it.auto }
                Info(
                    "Downloaded automatically",
                    "${autoNow.size} songs · ${formatBytes(autoNow.sumOf { it.size })}" + if (plan.first > 0) " (the rules want ${plan.first}, about ${formatBytes(plan.second)})" else "",
                )
                OutlinedButton(onClick = { AutoDownload.soon(); Remote.notify("Checking for songs to download…") }, modifier = Modifier.pageGlide()) { Text("Download now") }
            }
            Section("Recommendations") {
                Info("Listening history", "${history.size} plays remembered on this phone")
                Text(
                    "When YouTube Music on the computer isn't connected, “For you”, autoplay and “I'm feeling lucky” are worked out on " +
                        "this phone from what you play (here and on the computer), skip and like, using YouTube Music's own song radios. " +
                        "Your listening history is kept only on this phone. Songs from it are used to ask YouTube for similar music.",
                    modifier = Modifier.pageGlide(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmHistory = true }, enabled = history.isNotEmpty(), modifier = Modifier.pageGlide()) { Text("Clear listening history") }
            }
            if (confirmHistory) ConfirmDialog("Clear listening history?", "The recommendations start over from your likes and playlists.", "Clear",
                onDismiss = { confirmHistory = false }) { confirmHistory = false; Library.clearHistory() }
        }

        if (page == "updates") Section("Updates") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val info = UpdateManager.info
            val newer = UpdateManager.available(ctx)
            Info("Installed", version.ifBlank { "unknown" })
            Info("Latest release", info?.latest?.ifBlank { null } ?: "Not checked yet")
            UpdateManager.message?.let { Text(it, Modifier.pageGlide(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (UpdateManager.needsReinstall) {
                OutlinedButton(onClick = { UpdateManager.openReleasePage(ctx) }, modifier = Modifier.pageGlide()) { Text("Open the download page") }
            }
            Row(Modifier.pageGlide(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { scope.launch { UpdateManager.check(ctx, refresh = true) } }, enabled = !UpdateManager.checking) {
                    Text(if (UpdateManager.checking) "Checking…" else "Check now")
                }
                if (newer) {
                    androidx.compose.material3.Button(onClick = { scope.launch { UpdateManager.install(ctx) } }, enabled = !UpdateManager.downloading) {
                        Text(if (UpdateManager.downloading) "Downloading…" else "Download & install")
                    }
                }
            }
            Toggle("Check automatically", "Looks for a new version on GitHub when the app opens (or asks the computer, when GitHub can't be reached from the phone).", p.autoUpdate) { v ->
                Settings.update { it.copy(autoUpdate = v) }
            }
            val notes = remember(info?.notes) { info?.notes?.let { parseReleaseNotes(it) } }
            if (notes != null) {
                // just the "What's new" part of the release, as a list, not the whole release page
                Column(Modifier.pageGlide(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(Modifier.pageGlide(), verticalAlignment = Alignment.CenterVertically) {
                StarcastTile(52.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("YTM Starcast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android app · " + version.ifBlank { "version unknown" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.pageGlide()) { Text("Reset all settings") }
        }
        if (page == "about") Section("If the app closed by itself") {
            // what Android says about the last times the app stopped (Android 11 and later), and errors the app wrote down
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val exits = remember { CrashLog.exits(ctx) }
            var errors by remember { mutableStateOf(CrashLog.read()) }
            if (exits.isEmpty() && errors == null) {
                Text(
                    if (Build.VERSION.SDK_INT >= 30) "Nothing has gone wrong that the app knows of."
                    else "No errors written down. (Android only tells apps why they were closed from Android 11 on.)",
                    modifier = Modifier.pageGlide(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (exits.isNotEmpty()) {
                Column(Modifier.pageGlide(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("The last times it stopped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (e in exits.take(4)) Text(e, style = MaterialTheme.typography.bodySmall)
                }
            }
            errors?.let { text ->
                Column(Modifier.pageGlide(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Errors written down", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text.lineSequence().filter { it.isNotBlank() }.take(5).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall, maxLines = 8,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "When Android closes the app to free memory, that isn't an error in the app, but it is why the music's place can be " +
                    "lost; switching off battery optimisation for YTM Starcast in Android's settings helps on devices that do it often. " +
                    "This report stays on this device unless you copy it somewhere.",
                modifier = Modifier.pageGlide(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.pageGlide(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(CrashLog.report(ctx)))
                    Remote.notify("Report copied")
                }) { Text("Copy report") }
                if (errors != null) OutlinedButton(onClick = { CrashLog.clear(); errors = null }) { Text("Clear") }
            }
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
            PhonePresence.refresh()      // "Show what this phone plays" may have been switched back on
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    // the page's header already names its first card; the cards after it get their own small heading
    val pageTitle = LocalPageTitle.current
    Column {
        if (pageTitle == null) {
            Row(Modifier.pageGlide().padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(allCats.firstOrNull { it.title == title }?.icon ?: Icons.Filled.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        } else if (title in SUBHEADS) {
            // small and in the accent colour, as the overview's group names
            Text(
                title, Modifier.pageGlide().padding(start = 8.dp, top = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            )
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = PfShape.Lg,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column(Modifier.pageGlide()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // the whole row flips the switch (its words too, not only the switch), and reads as one switch to TalkBack
    Row(
        Modifier.pageGlide().fillMaxWidth().heightIn(min = 48.dp).clip(PfShape.Sm)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
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
    Column(Modifier.pageGlide(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(colors = refineChipColors(), border = refineChipBorder(selected == value, enabled), selected = selected == value, shape = chipShape(selected == value), onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) })
            }
        }
    }
}

/** "1.4 GB" / "350 MB" */
private fun gb(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0).replace(".0 GB", " GB") else "${bytes / 1_048_576L} MB"

/** A number picked from [presets] (0 means off / no limit when it is one of them), or typed in with "Custom…" (within
 *  [range], in [unit]). */
@Composable
private fun NumberChoice(
    title: String,
    subtitle: String?,
    presets: List<Pair<Int, String>>,
    value: Int,
    range: IntRange,
    unit: String,
    custom: (Int) -> String,
    warning: String? = null,
    warnAbove: Int? = null,
    onSet: (Int) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    val isPreset = presets.any { it.first == value }
    Column(Modifier.pageGlide(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (warning != null) Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (v, label) ->
                FilterChip(colors = refineChipColors(), border = refineChipBorder(value == v), selected = value == v, shape = chipShape(value == v), onClick = { onSet(v) }, label = { Text(label) })
            }
            FilterChip(colors = refineChipColors(), border = refineChipBorder(!isPreset), 
                selected = !isPreset, shape = chipShape(!isPreset), onClick = { asking = true },
                label = { Text(if (isPreset) "Custom…" else custom(value)) },
            )
        }
    }
    if (asking) {
        var text by remember { mutableStateOf(if (isPreset || value == 0) "" else value.toString()) }
        val n = text.trim().toIntOrNull()
        val ok = n != null && n in range
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text(unit.replaceFirstChar { it.uppercase() }) },
                    supportingText = {
                        if (n != null && warnAbove != null && n > warnAbove) {
                            Text("More than fits on the phone now (about $warnAbove $unit).", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("From ${range.first} to ${range.last} $unit.")
                        }
                    },
                    isError = text.isNotEmpty() && !ok,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { if (ok) { onSet(n!!); asking = false } }, enabled = ok) { Text("Set") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The overview: categories in groups. Tapping one opens its page.

/** Lets other screens (the "new version" banner) open a particular settings page. */
object SettingsNav {
    var deepLink by mutableStateOf<String?>(null)
}

private val LocalPageTitle = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** The open page's arrival: its rows glide in one after another (null: they just appear). */
private val LocalSettingsArrival = androidx.compose.runtime.compositionLocalOf<Arrival?> { null }

/** This row takes its turn in the open page's arrival, after the rows above it. */
@Composable
private fun Modifier.pageGlide(): Modifier = arrive(LocalSettingsArrival.current)

/** Cards that come after a page's first one, and so need a heading of their own. */
private val SUBHEADS = setOf("Your library", "Automatic downloads", "Recommendations", "If the app closed by itself", "Music on this phone")

private class Cat(val key: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val groups = listOf(
    "Listening" to listOf(
        Cat("sound", "Sound & outputs", Icons.Filled.Tune), Cat("downloads", "Downloads & offline", Icons.Filled.DownloadForOffline),
        Cat("lyrics", "Lyrics", Icons.Filled.MusicNote), Cat("controls", "Controls", Icons.Filled.TouchApp),
    ),
    "Look & feel" to listOf(
        Cat("appearance", "Appearance", Icons.Filled.Palette), Cat("effects", "Effects", Icons.Filled.AutoAwesome),
        Cat("screen", "Screen & always-on", Icons.Filled.Smartphone),
    ),
    "Connections" to listOf(
        Cat("connection", "Connection", Icons.Filled.Link), Cat("parts", "Parts", Icons.Filled.PowerSettingsNew),
        Cat("extension", "Browser extension", Icons.Filled.Extension),
        Cat("discord", "Discord status", Icons.Filled.Chat), Cat("updates", "Updates", Icons.Filled.SystemUpdate),
    ),
    "More" to listOf(Cat("performance", "Performance", Icons.Filled.Speed), Cat("about", "About", Icons.Filled.Info)),
)
private val allCats = groups.flatMap { it.second }

private fun themeLabel(t: String) = when (t) { "light" -> "Light"; "dark" -> "Dark"; "black" -> "Black"; else -> "System" }

private fun vizName(v: String) = when (v) { "halo" -> "Halo"; "orbit" -> "Orbit"; else -> "Glow" }

private fun partsSummary(p: Parts): String {
    val off = listOfNotNull(
        "Remote".takeIf { !p.remote }, "Discord status".takeIf { !p.discord },
        "Refine".takeIf { !p.refine }, "Computer audio".takeIf { !p.audio },
    )
    return if (off.isEmpty()) "Everything is on" else "Off: " + off.joinToString(", ")
}

/** Pages left out of the overview: Parts on a computer without part switches, the computer's sound while that part is off. */
private fun catShown(key: String, s: RemoteState): Boolean = when (key) {
    "parts" -> s.parts != null
    "sound" -> s.audioOn || Remote.local
    else -> true
}

private fun summaryOf(key: String, p: Prefs, s: RemoteState, cfg: ServerConfig, version: String): String = when (key) {
    "sound" -> if (s.volume.available) "${s.volume.percent}%" + (if (s.volume.muted) " (muted)" else "") + s.volume.device.let { if (it.isBlank()) "" else " · $it" } else "No audio output found"
    "downloads" -> (if (p.playOn == "phone") "Playing on this phone" else "Playing on the computer") + " · " + Downloads.items.value.size + " downloaded" +
        Library.syncedAt.value.let { at -> if (at == 0L) "" else " · library synced " + ago(at) } +
        (if (p.autoDlLiked || p.smartDownloads != 0 || Library.keepOffline.value.isNotEmpty()) " · automatic downloads on" else "")
    "lyrics" -> (if (p.lyricsRoman) "Romanized" else "Original text") + " · " + (if (p.lyricsSource == "auto") "automatic source" else p.lyricsSource)
    "controls" -> "Notification " + (if (p.notification) "on" else "off") + (if (s.audioOn) " · volume keys " + (if (p.volumeKeys) "on" else "off") else "")
    "appearance" -> themeLabel(p.theme) + " theme" + (if (p.accent != 0) " · custom accent" else if (p.albumColors) " · album colours" else "") +
        " · " + (if (p.navStyle == "pill") "pill navigation" else "classic navigation")
    "effects" -> (if (p.coverGlow == "off") "No cover glow" else vizName(p.coverViz) + " " + p.coverGlow) + " · " + (if (p.wavyBar) "wavy bar" else "plain bar")
    "screen" -> when (p.keepAwake) { "always" -> "Always on"; "playing" -> "On while playing"; else -> "Screen sleeps normally" } + (if (p.pixelShift) " · pixel shift" else "")
    "connection" -> if (!s.configured) "Not set up" else if (s.online) "Connected · " + cfg.url.removePrefix("http://").removePrefix("https://").trimEnd('/') else "Can't reach the computer"
    "parts" -> s.parts?.let { partsSummary(it) } ?: "Switch parts of YTM Starcast on or off"
    "extension" -> if (s.ytm) "Connected" else if (s.extNeverSet) "Not added to a browser yet" else "How to add it to the computer's browser"
    "discord" -> BeaconModel.status?.let { beaconSummary(it) } ?: "Show your music on Discord"
    "updates" -> "Installed " + version.ifBlank { "unknown" }
    "performance" -> (when (p.pollSpeed) { "fast" -> "Fast updates"; "battery" -> "Battery saver"; else -> "Normal updates" }) + (if (p.reduceMotion) " · reduced animations" else "")
    else -> "Version " + version.ifBlank { "unknown" }
}

@Composable
private fun SettingsOverview(p: Prefs, s: RemoteState, cfg: ServerConfig, version: String, modifier: Modifier, open: (String) -> Unit) {
    val scroll = rememberScrollState()
    // the categories glide in one after another each time the overview appears
    val arrival = rememberArrival("overview").takeIf { p.transitions }
    Column(
        modifier.edgeFade(scroll).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.online && s.remoteOn && s.extNeverSet) ExtensionPrompt(Modifier.arrive(arrival)) { open("extension") }
        for ((label, all) in groups) {
            val cats = all.filter { catShown(it.key, s) }
            if (cats.isNotEmpty()) {
                // group names: small, in the accent colour, as in the web app and Refine
                Text(
                    label, Modifier.arrive(arrival).padding(start = 8.dp, top = 10.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                )
                Card(
                    Modifier.fillMaxWidth(), shape = PfShape.Lg,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column {
                        cats.forEachIndexed { i, c ->
                            CategoryRow(c, summaryText(c.key, p, s, cfg, version), divider = i > 0, arrival = arrival) { open(c.key) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun summaryText(key: String, p: Prefs, s: RemoteState, cfg: ServerConfig, version: String): String {
    if (key == "updates") return if (UpdateManager.available(LocalContext.current)) "A new version is ready" else "Installed " + version.ifBlank { "unknown" }
    // music on this phone: the page is about the phone's own sound
    if (key == "sound" && Remote.local) return "This phone · ${LocalPlayer.phoneVolume()}% · ${phoneOutputName(LocalContext.current)}"
    return summaryOf(key, p, s, cfg, version)
}

/** One category: its icon on a tonal circle, its name and a line saying how it is set. [divider]: a hairline above it. */
@Composable
private fun CategoryRow(c: Cat, summary: String, divider: Boolean = false, arrival: Arrival? = null, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Row(
        Modifier.arrive(arrival).fillMaxWidth()
            .drawBehind {
                // from where the words start to the card's edge; drawn in the row so it glides in with it
                if (divider) {
                    val inset = 70.dp.toPx()
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    drawLine(
                        line, Offset(if (rtl) 0f else inset, 0f), Offset(if (rtl) size.width - inset else size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
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
        ShapeIconButton(Icons.Filled.ArrowBack, "Back to settings", onBack)
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
