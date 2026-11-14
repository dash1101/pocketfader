package app.pocketfader.client

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the Discord status card. The computer (never the phone) talks to Discord; this just asks the computer.
 * What you tap is shown at once and kept for a few seconds so a poll that lands before the change has been applied
 * can't flip the control back; if the change fails, the old value returns with a short message.
 */
object BeaconModel {
    var status by mutableStateOf<BeaconStatus?>(null)
        private set
    /** The computer's answer to the last "Move over": whether it worked, and its message (shown in the card). */
    var migrateResult by mutableStateOf<Pair<Boolean, String>?>(null)
        private set
    var migrating by mutableStateOf(false)
        private set
    private var failures = 0
    private var heldVisible: Triple<Boolean, Int, Long>? = null          // visible, snoozeSec, until
    private val heldSettings = HashMap<String, Pair<Any, Long>>()

    private fun applyHeld(s: BeaconStatus): BeaconStatus {
        val now = SystemClock.elapsedRealtime()
        var out = s
        val hv = heldVisible
        if (hv != null) {
            if (now < hv.third) {
                out = out.copy(visible = hv.first, snoozeSec = hv.second)
            } else {
                heldVisible = null
            }
        }
        for ((k, v) in heldSettings.toMap()) {
            if (now < v.second) {
                out = out.copy(settings = out.settings.withValue(k, v.first))
            } else {
                heldSettings.remove(k)
            }
        }
        return out
    }

    suspend fun refresh() {
        try {
            val s = withContext(Dispatchers.IO) { Remote.beaconStatus() }
            if (s.headline == "unreachable") failures++ else failures = 0
            // A restart on the computer makes it vanish for a moment; only call it unreachable after two misses in a row.
            if (s.headline == "unreachable" && failures < 2 && status?.reachable == true) return
            status = applyHeld(s)
        } catch (e: Exception) {
            // The computer itself can't be reached: the app's own banner says so. Keep showing the last status.
        }
    }

    suspend fun setVisibility(show: Boolean, minutes: Int? = null) {
        val prev = status ?: return
        val snooze = if (!show && minutes != null) minutes * 60 else 0
        heldVisible = Triple(show, snooze, SystemClock.elapsedRealtime() + 4000)
        status = prev.copy(visible = show, snoozeSec = snooze, receivedAt = SystemClock.elapsedRealtime())
        try {
            withContext(Dispatchers.IO) { Remote.beaconVisibility(show, minutes) }
            refresh()
            // "Show" also switches the Discord status part back on (one switch), so the Parts page follows
            if (show && Remote.state.value.parts?.discord == false) Remote.refreshParts()
        } catch (e: Exception) {
            heldVisible = null
            status = prev
            Remote.notify("Couldn't change your Discord status: ${e.friendly()}")
        }
    }

    /** Only older computers could install anything; nothing in the app calls this any more. */
    suspend fun install(action: String) {
        val prev = status ?: return
        status = prev.copy(install = prev.install.copy(jobState = "running", jobMessage = "Working…"))
        try {
            withContext(Dispatchers.IO) { Remote.beaconInstall(action) }
            refresh()
        } catch (e: Exception) {
            status = prev
            Remote.notify("Couldn't start that: ${e.friendly()}")
        }
    }

    val installing: Boolean get() = status?.install?.jobState == "running"

    // Outlives the screens: the computer finishes a move even when the page that started it is left, and the answer
    // shown afterwards has to be the computer's, not "cancelled".
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.quiet)

    /** Moves over from the old YTM Beacon app (the computer does the work) and keeps the computer's answer to show. */
    fun migrate() {
        if (migrating) return
        migrating = true
        migrateResult = null
        scope.launch {
            migrateResult = try {
                withContext(Dispatchers.IO) { Remote.beaconMigrate() }
            } catch (e: Exception) {
                false to "Couldn't move over: ${e.friendly()}"
            }
            migrating = false
            refresh()
        }
    }

    suspend fun setSetting(key: String, value: Any) {
        val prev = status ?: return
        heldSettings[key] = value to (SystemClock.elapsedRealtime() + 4000)
        status = prev.copy(settings = prev.settings.withValue(key, value))
        try {
            withContext(Dispatchers.IO) { Remote.beaconSetting(key, value) }
            refresh()
        } catch (e: Exception) {
            heldSettings.remove(key)
            status = prev
            Remote.notify("Couldn't save that: ${e.friendly()}")
        }
    }
}

private fun BeaconSettings.withValue(key: String, value: Any): BeaconSettings = when (key) {
    "privateMode" -> copy(privateMode = value as Boolean)
    "showPaused" -> copy(showPaused = value as Boolean)
    "showProgress" -> copy(showProgress = value as Boolean)
    "showStateIcon" -> copy(showStateIcon = value as Boolean)
    "showButtons" -> copy(showButtons = value as Boolean)
    "showAlbumArt" -> copy(showAlbumArt = value as Boolean)
    "pauseTimeoutMinutes" -> copy(pauseTimeoutMinutes = value as Int)
    "statusDisplay" -> copy(statusDisplay = value as String)
    "hideMatching" -> copy(hideMatching = (value as? List<*>)?.filterIsInstance<String>() ?: hideMatching)
    else -> this
}

/** One short line for the settings overview. */
fun beaconSummary(s: BeaconStatus): String = headlineText(s).first.substringBefore(".").trim()

private enum class Tone { Good, Warn, Bad, Off }

/**
 * What the computer's headline means, in words. Older computers relayed the separate YTM Beacon app, so a few states read
 * differently there ([BeaconStatus.builtIn] is false): the fix is then to update YTM Starcast on the computer.
 */
private fun headlineText(s: BeaconStatus): Pair<String, Tone> = when (s.headline) {
    "showing" -> "Showing on Discord." to Tone.Good
    "waiting" -> "Getting ready to show…" to Tone.Good
    "idle" -> "Ready. Nothing is playing." to Tone.Good
    "off" -> "Switched off. Nothing is shown on Discord." to Tone.Off
    "snoozed" -> "Hidden for a while. It comes back by itself." to Tone.Warn
    "paused-hidden" -> "Not shown while paused. Turn on “Show when paused” to keep it." to Tone.Warn
    "paused-timeout" -> "Hidden because the song was paused a while. It returns when you press play." to Tone.Warn
    "filtered" -> "Not shown: this song matches one of your never-show words." to Tone.Warn
    "ad" -> "An advert is playing. Nothing is shown until the music is back." to Tone.Warn
    "beacon-running" -> "The old YTM Beacon app is still running on the computer, so YTM Starcast isn't showing your status." to Tone.Warn
    "extension-quiet" -> "The browser extension went quiet. Check that YouTube Music is open in your browser." to Tone.Warn
    "extension-missing" ->
        (if (s.builtIn) "The YTM Starcast browser extension hasn't been heard from yet. Add it to your browser on the computer and open YouTube Music."
        else "The YTM Beacon browser extension isn't set up yet. Install it on the computer.") to Tone.Bad
    "discord-down" -> "Can't reach Discord. Open the Discord app on the computer." to Tone.Bad
    "unsupported" -> "The YTM Beacon app on the computer is out of date. Update YTM Starcast on the computer: it shows your Discord status itself." to Tone.Bad
    else ->
        (if (s.builtIn) "Discord status isn't answering on the computer right now."
        else "YTM Beacon isn't running on the computer. Update YTM Starcast on the computer: it shows your Discord status itself.") to Tone.Bad
}

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return c as? LifecycleOwner
}

@Composable
private fun rememberClock(): State<Long> {
    val now = remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now.value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
fun BeaconCard(online: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(ctx) {
        val owner = ctx.lifecycleOwner()
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) resumed = true else if (e == Lifecycle.Event.ON_STOP) resumed = false
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    // Ask every few seconds while the screen is on and this card is showing; again right away on return.
    LaunchedEffect(resumed, online) {
        if (resumed && online) while (true) {
            BeaconModel.refresh()
            delay(if (BeaconModel.installing || BeaconModel.migrating) 1500 else 5000)
        }
    }

    val st = BeaconModel.status
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val clock = rememberClock()
    // while the old YTM Beacon app still runs, Discord is its job: the switches would change nothing, so they wait
    val usable = online && st != null && st.reachable && st.headline != "unsupported" && !st.beaconApp.running

    Card(
        modifier.fillMaxWidth(),
        shape = PfShape.Lg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Chat, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Discord status", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                if (st != null) Dot(headlineText(st).second)
            }

            if (st == null) {
                Text(
                    if (online) "Checking…" else "Can't reach the computer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val (text, _) = headlineText(st)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                st.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                st.song?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${if (it.playing) "▶" else "⏸"}  ${it.title}${if (it.artist.isNotBlank()) " · ${it.artist}" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        // a song from a phone: this one when it plays the same song now, else another phone on the same computer
                        if (it.from == "phone") Text(
                            if (Remote.local && Remote.state.value.now?.title == it.title) "from this phone" else "from a phone",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                MoveOver(st, online)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Show on Discord", style = MaterialTheme.typography.bodyLarge)
                        st.discordUser?.let { Text("as $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Switch(checked = st.visible, enabled = usable, onCheckedChange = { on -> scope.launch { BeaconModel.setVisibility(on) } })
                }

                val left = if (st.snoozeSec > 0) st.snoozeSec - ((clock.value - st.receivedAt) / 1000).toInt() else 0
                if (st.snoozeSec > 0 && !st.visible) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Hidden for ${leftText(left)} more",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(enabled = usable, onClick = { scope.launch { BeaconModel.setVisibility(true) } }) { Text("Show now") }
                    }
                } else if (st.visible) {
                    Text("Hide for…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, mins) in listOf("30 min" to 30, "1 hour" to 60, "3 hours" to 180)) {
                            FilterChip(
                                selected = false, shape = chipShape(false), enabled = usable,
                                onClick = { scope.launch { BeaconModel.setVisibility(false, mins) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                if (st.reachable && st.headline != "unsupported") {
                    TextButton(onClick = { showSettings = !showSettings }, enabled = usable) {
                        Text("What it shows")
                        Icon(if (showSettings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp))
                    }
                    AnimatedVisibility(showSettings) { BeaconSettingsList(st.settings, usable) }
                }

                val ago = ((clock.value - st.receivedAt) / 1000).coerceAtLeast(0)
                Text(
                    if (!online) "Can't reach the computer. Last updated ${ago} s ago." else "Updated ${ago} s ago",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The old YTM Beacon app is still on the computer: a short word about it and "Move over", which asks the computer to take
 * its settings, remove it and show the status itself. The computer's answer stays in view afterwards.
 */
@Composable
private fun MoveOver(st: BeaconStatus, online: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val app = st.beaconApp
    val result = BeaconModel.migrateResult
    val busy = BeaconModel.migrating
    if (!app.canMigrate && !busy && result == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (app.canMigrate) {
            Text(
                "The old YTM Beacon app" + (app.version?.let { " ($it)" } ?: "") + " is still on the computer. YTM Starcast shows your " +
                    "Discord status itself now. Move over to bring your YTM Beacon settings across and remove the old app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { (ok, message) ->
            Text(
                message, style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (app.canMigrate || busy) {
            Button(enabled = online && !busy && app.canMigrate, onClick = { confirm = true }) {
                Text(if (busy) "Moving over…" else "Move over")
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            "Move over from YTM Beacon?",
            "YTM Starcast takes your YTM Beacon settings, then removes the YTM Beacon app from the computer (its settings file is " +
                "kept). From then on YTM Starcast shows your music on Discord itself.",
            "Move over",
            onDismiss = { confirm = false },
        ) {
            confirm = false
            BeaconModel.migrate()
        }
    }
}

private fun leftText(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
        s >= 60 -> "${(s + 59) / 60} min"
        else -> "$s s"
    }
}

@Composable
private fun Dot(tone: Tone) {
    val bg = MaterialTheme.colorScheme.surfaceContainer
    val base = when (tone) {
        Tone.Good -> Color(0xFF2EB872)
        Tone.Warn -> Color(0xFFE0A100)
        Tone.Bad -> Color(0xFFE5484D)
        Tone.Off -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(12.dp).background(readableOn(base, bg, 3f), CircleShape))
}

@Composable
private fun BeaconSettingsList(s: BeaconSettings, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    fun flip(key: String, v: Boolean) { scope.launch { BeaconModel.setSetting(key, v) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BRow("Private mode", "Only say “Listening to music”: no song, artist, picture or button.", s.privateMode, enabled) { flip("privateMode", it) }
        BRow("Show when paused", "Keep the song on your profile while paused.", s.showPaused, enabled) { flip("showPaused", it) }
        if (s.showPaused) {
            Text("Stop showing a paused song after", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(0, 1, 5, 15, 60) + s.pauseTimeoutMinutes).distinct().sorted().forEach { m ->
                    FilterChip(
                        selected = s.pauseTimeoutMinutes == m, shape = chipShape(s.pauseTimeoutMinutes == m), enabled = enabled,
                        onClick = { scope.launch { BeaconModel.setSetting("pauseTimeoutMinutes", m) } },
                        label = { Text(if (m == 0) "Never" else "$m min") },
                    )
                }
            }
        }
        BRow("Progress bar", null, s.showProgress, enabled) { flip("showProgress", it) }
        BRow("Play / pause icon", null, s.showStateIcon, enabled) { flip("showStateIcon", it) }
        BRow("Listen button", "A button on the card that opens the song.", s.showButtons, enabled) { flip("showButtons", it) }
        BRow("Album art", null, s.showAlbumArt, enabled) { flip("showAlbumArt", it) }
        Text("Name next to your username", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, label) in listOf("name" to "YouTube Music", "state" to "Artist", "details" to "Song title")) {
                FilterChip(
                    selected = s.statusDisplay == v, shape = chipShape(s.statusDisplay == v), enabled = enabled,
                    onClick = { scope.launch { BeaconModel.setSetting("statusDisplay", v) } },
                    label = { Text(label) },
                )
            }
        }
        // only when the computer sends the list: older ones neither show nor accept it
        if (s.hideMatchingKnown) {
            NeverShowWords(s.hideMatching, enabled) { words -> scope.launch { BeaconModel.setSetting("hideMatching", words) } }
        }
    }
}

/** Words that keep a song off Discord: tap a word to remove it, "Add a word" for a new one. The whole list is sent each time. */
@Composable
private fun NeverShowWords(words: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Never show songs matching", style = MaterialTheme.typography.bodyMedium)
        Text(
            "A song whose title, artist or album contains one of these words isn't shown on Discord.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (words.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (w in words) {
                    FilterChip(
                        selected = true, shape = chipShape(true), enabled = enabled,
                        onClick = { onChange(words.filter { it != w }) },
                        label = { Text(w) },
                        trailingIcon = { Icon(Icons.Filled.Close, "Remove $w", Modifier.size(16.dp)) },
                    )
                }
            }
        }
        TextButton(onClick = { adding = true }, enabled = enabled && words.size < 50) { Text("Add a word") }
    }
    if (adding) {
        TextPrompt(
            "Never show songs matching", "", "Word or phrase", "Add",
            onDismiss = { adding = false },
        ) { text ->
            adding = false
            // the same clean-up the computer does: trimmed, lower case, no repeats
            val w = text.trim().lowercase().take(80)
            if (w.isNotEmpty() && w !in words) onChange(words + w)
        }
    }
}

@Composable
private fun BRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
