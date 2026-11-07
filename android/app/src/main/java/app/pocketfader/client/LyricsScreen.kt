package app.pocketfader.client

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the lyrics view needs: the answer from the server, whether it's loading, and how to ask again. */
class LyricsUi(
    val result: LyricsResult?,
    val loading: Boolean,
    val error: String?,
    val refresh: () -> Unit,
)

/** Looks the lyrics up when [active] (the lyrics view is showing) and the track changes. */
@Composable
fun rememberLyrics(now: Now?, active: Boolean): LyricsUi {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<LyricsResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    val key = now?.takeIf { it.title.isNotBlank() }?.let { it.title + "|" + it.artist + "|" + it.duration.toInt() }

    LaunchedEffect(key, prefs.lyricsSource, refreshTick, active) {
        if (!active) return@LaunchedEffect
        val n = now
        if (n == null || key == null) { result = null; error = null; return@LaunchedEffect }
        val refresh = force
        force = false
        loading = true
        error = null
        try {
            result = withContext(Dispatchers.IO) { Remote.lyrics(n, prefs.lyricsSource, refresh) }
        } catch (e: Exception) {
            result = null
            error = e.friendly()
        }
        loading = false
    }
    // A new track must never show the old track's words.
    LaunchedEffect(key) { result = null }
    return LyricsUi(result, loading, error) { force = true; refreshTick++ }
}

/** Index of the line being sung, or -1 before the first line. Recomposes only when it changes. */
@Composable
fun rememberActiveLine(lines: List<LyricLine>, synced: Boolean, playing: Boolean): Int {
    val reduce = LocalReduceMotion.current
    val progress = Remote.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, synced, reduce) {
        while (playing && synced) {
            delay(if (reduce) 700 else 300)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val index by remember(lines, synced, playing) {
        derivedStateOf {
            if (!synced) return@derivedStateOf -1
            val p = progress.value
            val clock = maxOf(tick, p.at)
            val pos = p.position + if (playing) (clock - p.at) / 1000.0 else 0.0
            var lo = 0
            var hi = lines.size - 1
            var found = -1
            while (lo <= hi) {                         // last line whose time has passed
                val mid = (lo + hi) ushr 1
                val t = lines[mid].t ?: Double.MAX_VALUE
                if (t <= pos + 0.25) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            found
        }
    }
    return index
}

/** Keeps the line being sung about a third of the way down, unless the user is scrolling. */
@Composable
fun FollowLyrics(listState: LazyListState, firstLineIndex: Int, active: Int, enabled: Boolean) {
    var pausedUntil by remember { mutableLongStateOf(0L) }
    var ours by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (moving && !ours) pausedUntil = SystemClock.elapsedRealtime() + 6000
        }
    }
    LaunchedEffect(active, enabled) {
        if (!enabled || active < 0 || SystemClock.elapsedRealtime() < pausedUntil) return@LaunchedEffect
        ours = true
        try {
            listState.animateScrollToItem(firstLineIndex + active, -listState.layoutInfo.viewportSize.height / 3)
        } finally {
            ours = false
        }
    }
}

/** Source picker, romanize / translation switches and refresh. */
@Composable
fun LyricsControls(ui: LyricsUi, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val r = ui.result
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            r?.sources?.forEach { src ->
                FilterChip(
                    selected = r.source == src.id,
                    onClick = { Settings.update { it.copy(lyricsSource = src.id) } },
                    enabled = src.found,
                    label = { Text(src.label + if (src.found && src.synced) " · synced" else "") },
                )
            }
            IconButton(onClick = ui.refresh, enabled = !ui.loading) { Icon(Icons.Filled.Refresh, "Look again") }
        }
        if (r != null && r.found) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.lines.any { it.rom != null }) {
                    FilterChip(
                        selected = prefs.lyricsRoman,
                        onClick = { Settings.update { it.copy(lyricsRoman = !it.lyricsRoman) } },
                        label = { Text("Romanize") },
                    )
                }
                if (r.lines.any { it.tr != null }) {
                    FilterChip(
                        selected = prefs.lyricsTranslation,
                        onClick = { Settings.update { it.copy(lyricsTranslation = !it.lyricsTranslation) } },
                        label = { Text("Translation") },
                    )
                }
            }
            val note = buildString {
                append(if (r.synced) "Live lyrics follow the song. Tap a line to jump to it." else "These lyrics aren't time-synced.")
                r.credit?.let { append("  ").append(it) }
            }
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The lyric lines themselves, as list items (so they can live inside a bigger scrolling page). */
fun LazyListScope.lyricsItems(ui: LyricsUi, active: Int, prefs: Prefs, canLyrics: Boolean, hasTrack: Boolean) {
    val r = ui.result
    when {
        !hasTrack -> item(key = "ly-none") { LyricsMessage("Nothing is playing.") }
        !canLyrics -> item(key = "ly-old") { LyricsMessage("This server is too old for lyrics. Update Pocketfader on the computer.") }
        ui.loading && r == null -> item(key = "ly-load") {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        ui.error != null && r == null -> item(key = "ly-err") { LyricsMessage(ui.error) }
        r == null || !r.found -> item(key = "ly-nf") {
            LyricsMessage("No lyrics found for this song. Try another source above, or look again.")
        }
        else -> {
            val base = when (prefs.lyricsSize) { 0 -> 18 else -> if (prefs.lyricsSize == 2) 26 else 22 }
            itemsLines(r, active, prefs, base)
        }
    }
}

private fun LazyListScope.itemsLines(r: LyricsResult, active: Int, prefs: Prefs, base: Int) {
    val lines = r.lines
    for (i in lines.indices) {
        item(key = "ly-$i") {
            val ln = lines[i]
            val isActive = r.synced && i == active
            if (ln.text.isBlank()) {
                Text("♪", Modifier.fillMaxWidth().padding(vertical = 6.dp).alpha(0.4f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = r.synced && ln.t != null) { ln.t?.let { Remote.transport("seek", it) } }
                        .padding(horizontal = 20.dp, vertical = 7.dp)
                        .alpha(if (!r.synced || isActive) 1f else 0.45f),
                ) {
                    Text(
                        ln.text,
                        fontSize = (if (isActive) base + 2 else base).sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        lineHeight = ((base + 6)).sp,
                    )
                    if (prefs.lyricsRoman && ln.rom != null) {
                        Text(ln.rom, fontSize = (base - 5).sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (prefs.lyricsTranslation && ln.tr != null) {
                        Text(ln.tr, fontSize = (base - 5).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    item(key = "ly-end") { Spacer(Modifier.height(120.dp)) }
}

@Composable
private fun LyricsMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

/** Lyrics as a screen of their own (the wide layout's right pane). */
@Composable
fun LyricsPane(s: RemoteState, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val ui = rememberLyrics(s.now, active = true)
    val active = rememberActiveLine(ui.result?.lines.orEmpty(), ui.result?.synced == true, s.now?.playing == true)
    val state = rememberLazyListState()
    FollowLyrics(state, firstLineIndex = 1, active = active, enabled = true)
    LazyColumn(modifier.fillMaxSize(), state = state) {
        item(key = "ly-controls") { LyricsControls(ui) }
        lyricsItems(ui, active, prefs, s.canLyrics, s.now?.title?.isNotBlank() == true)
    }
}
