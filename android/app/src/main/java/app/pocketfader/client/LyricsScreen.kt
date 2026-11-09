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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.TextButton
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
    val key = now?.takeIf { it.title.isNotBlank() }?.let { it.title + "|" + it.artist }

    LaunchedEffect(key, prefs.lyricsSource, refreshTick) {
        val n = now
        if (n == null || key == null) { result = null; error = null; return@LaunchedEffect }
        val refresh = force
        force = false
        delay(if (refresh) 0 else if (n.duration <= 0) 1500 else 500)   // skipping tracks shouldn't start a lookup for each; the length helps pick the right version
        loading = result == null
        error = null
        try {
            var r = withContext(Dispatchers.IO) { Remote.lyrics(n, prefs.lyricsSource, refresh) }
            result = r
            if (r.pending) {                      // a slower source (often the word-by-word one) was still working
                delay(2500)
                r = withContext(Dispatchers.IO) { Remote.lyrics(n, prefs.lyricsSource, false) }
                result = r
            }
        } catch (e: Exception) {
            if (result == null) error = e.friendly()
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
    val offsetMs = Settings.prefs.collectAsStateWithLifecycle().value.lyricsOffsetMs
    val progress = Remote.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, synced, reduce) {
        while (playing && synced) {
            delay(if (reduce) 700 else 300)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val index by remember(lines, synced, playing, offsetMs) {
        derivedStateOf {
            if (!synced) return@derivedStateOf -1
            val p = progress.value
            val clock = maxOf(tick, p.at)
            val pos = p.position + (if (playing) (clock - p.at) / 1000.0 else 0.0) + offsetMs / 1000.0 + 0.1
            var lo = 0
            var hi = lines.size - 1
            var found = -1
            while (lo <= hi) {                         // last line whose time has passed
                val mid = (lo + hi) ushr 1
                val t = lines[mid].t ?: Double.MAX_VALUE
                if (t <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            found
        }
    }
    return index
}

/** Keeps the line being sung about a third of the way down, unless the user is scrolling. */
@Composable
fun FollowLyrics(listState: LazyListState, firstLineIndex: Int, active: Int, enabled: Boolean, jump: Int = 0) {
    var pausedUntil by remember { mutableLongStateOf(0L) }
    var ours by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (moving && !ours) pausedUntil = SystemClock.elapsedRealtime() + 6000
        }
    }
    LaunchedEffect(jump) { if (jump > 0) pausedUntil = 0L }     // "current line" button: follow again right away
    LaunchedEffect(active, enabled, jump) {
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
    var layoutOpen by remember { mutableStateOf(false) }
    if (layoutOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { layoutOpen = false },
            title = { Text("Lyrics layout") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { LyricsLayoutControls() } },
            confirmButton = { TextButton(onClick = { layoutOpen = false }) { Text("Done") } },
        )
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            r?.sources?.forEach { src ->
                FilterChip(
                    selected = if (prefs.lyricsSource == "auto") r.source == src.id else prefs.lyricsSource == src.id,
                    onClick = { Settings.update { it.copy(lyricsSource = src.id) } },
                    enabled = src.found,
                    label = { Text(src.label + if (src.found && src.words) " · word-by-word" else if (src.found && src.synced) " · synced" else "") },
                )
            }
            IconButton(onClick = ui.refresh, enabled = !ui.loading) { Icon(Icons.Filled.Refresh, "Look again") }
            FilterChip(selected = false, onClick = { layoutOpen = true }, label = { Text("Aa  Layout") })
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
            if (r.synced) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                    TextButton(onClick = { Settings.update { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs - 100).coerceAtLeast(-2000)) } }) { Text("−0.1 s") }
                    TextButton(onClick = { Settings.update { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs + 100).coerceAtMost(2000)) } }) { Text("+0.1 s") }
                    if (prefs.lyricsOffsetMs != 0) {
                        TextButton(onClick = { Settings.update { it.copy(lyricsOffsetMs = 0) } }) {
                            Text("%+.1f s · reset".format(prefs.lyricsOffsetMs / 1000.0))
                        }
                    }
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
fun LazyListScope.lyricsItems(ui: LyricsUi, active: Int, prefs: Prefs, canLyrics: Boolean, hasTrack: Boolean, playing: Boolean) {
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
            val base = prefs.lyricsSp
            itemsLines(r, active, prefs, base, playing)
        }
    }
}

private fun LazyListScope.itemsLines(r: LyricsResult, active: Int, prefs: Prefs, base: Int, playing: Boolean) {
    val lines = r.lines
    val align = when (prefs.lyricsAlign) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start }
    for (i in lines.indices) {
        item(key = "ly-$i") {
            val ln = lines[i]
            val isActive = r.synced && i == active
            if (ln.text.isBlank()) {
                // an instrumental break: a big ♪ that lights up while it lasts
                Text(
                    "♪",
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    fontSize = (if (isActive) 38 else 30).sp,
                    color = if (isActive) (LocalAccent.current ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = r.synced && ln.t != null) { ln.t?.let { Remote.transport("seek", it) } }
                        .padding(horizontal = 20.dp, vertical = prefs.lyricsSpacing.dp)
                        .alpha(if (!r.synced || isActive) 1f else prefs.lyricsDim / 100f),
                    horizontalAlignment = when (prefs.lyricsAlign) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start },
                ) {
                    if (isActive && ln.words != null) {
                        WordLine(ln, base + 2, playing, prefs.lyricsOffsetMs, align)
                    } else {
                        Text(
                            ln.text,
                            fontSize = (if (isActive) base + 2 else base).sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isActive) (LocalAccent.current ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.onSurface,
                            style = if (isActive) LocalTextStyle.current.glow(LocalAccent.current ?: MaterialTheme.colorScheme.primary) else LocalTextStyle.current,
                            lineHeight = ((base + 6)).sp,
                            textAlign = align,
                        )
                    }
                    if (prefs.lyricsRoman && ln.rom != null) {
                        Text(ln.rom, fontSize = (base - 5).sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
                    }
                    if (prefs.lyricsTranslation && ln.tr != null) {
                        Text(ln.tr, fontSize = (base - 5).sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
                    }
                }
            }
        }
    }
    item(key = "ly-end") { Spacer(Modifier.height(120.dp)) }
}

/** The line being sung, filling in word by word (and letter by letter inside the current word). */
@Composable
private fun WordLine(ln: LyricLine, size: Int, playing: Boolean, offsetMs: Int, align: TextAlign) {
    val reduce = LocalReduceMotion.current
    val progress = Remote.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, reduce) {
        while (playing) {
            delay(if (reduce) 140 else 70)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val on = LocalAccent.current ?: MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val p = progress.value
    val clock = maxOf(tick, p.at)
    val pos = p.position + (if (playing) (clock - p.at) / 1000.0 else 0.0) + offsetMs / 1000.0 + 0.1
    val text = buildAnnotatedString {
        for (w in ln.words.orEmpty()) {
            val f = ((pos - w.start) / (w.end - w.start).coerceAtLeast(0.05)).coerceIn(0.0, 1.0)
            when {
                f >= 1.0 -> withStyle(SpanStyle(color = on)) { append(w.text) }
                f <= 0.0 -> withStyle(SpanStyle(color = off)) { append(w.text) }
                else -> {
                    val filled = f * w.text.length
                    for ((i, c) in w.text.withIndex()) {
                        val k = (filled - i).coerceIn(0.0, 1.0).toFloat()
                        withStyle(SpanStyle(color = lerp(off, on, k))) { append(c) }
                    }
                }
            }
        }
    }
    Text(text, style = LocalTextStyle.current.glow(on), fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = (size + 6).sp, textAlign = align)
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
    var jump by remember { mutableIntStateOf(0) }
    FollowLyrics(state, firstLineIndex = 1, active = active, enabled = true, jump = jump)
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().edgeFade(state), state = state) {
            item(key = "ly-controls") { LyricsControls(ui) }
            lyricsItems(ui, active, prefs, s.canLyrics, s.now?.title?.isNotBlank() == true, s.now?.playing == true)
        }
        JumpToLine(state, 1, active, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { jump++ }
    }
}

/** A button that appears when the line being sung is off screen and takes you back to it. */
@Composable
fun JumpToLine(state: LazyListState, firstLineIndex: Int, active: Int, modifier: Modifier = Modifier, onJump: () -> Unit) {
    val offscreen by remember(active) {
        derivedStateOf { active >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == firstLineIndex + active } }
    }
    if (offscreen) {
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = onJump,
            modifier = modifier,
            icon = { Icon(Icons.Filled.MyLocation, null) },
            text = { Text("Current line") },
        )
    }
}

/** Alignment, size, spacing and how faint the other lines are. Shared by Settings and the lyrics view. */
@Composable
fun LyricsLayoutControls() {
    val p by Settings.prefs.collectAsStateWithLifecycle()
    @Composable
    fun chips(title: String, options: List<Pair<String, String>>, selected: String, pick: (String) -> Unit) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> FilterChip(selected = selected == v, onClick = { pick(v) }, label = { Text(l) }) }
        }
    }
    chips("Alignment", listOf("start" to "Left", "center" to "Centered", "end" to "Right"), p.lyricsAlign) { v -> Settings.update { it.copy(lyricsAlign = v) } }
    Text("Text size · ${p.lyricsSp} sp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
    androidx.compose.material3.Slider(
        value = p.lyricsSp.toFloat(), onValueChange = { v -> Settings.update { it.copy(lyricsSp = v.toInt()) } }, valueRange = 14f..40f,
    )
    chips("Line spacing", listOf("3" to "Tight", "7" to "Normal", "12" to "Relaxed", "18" to "Airy"), p.lyricsSpacing.toString()) { v -> Settings.update { it.copy(lyricsSpacing = v.toInt()) } }
    chips("Other lines", listOf("65" to "Bright", "45" to "Normal", "28" to "Faint"), p.lyricsDim.toString()) { v -> Settings.update { it.copy(lyricsDim = v.toInt()) } }
    Text("Sample", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    val align = when (p.lyricsAlign) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start }
    Column(Modifier.fillMaxWidth().padding(vertical = p.lyricsSpacing.dp)) {
        Text("This is the line being sung", fontSize = (p.lyricsSp + 2).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = align, modifier = Modifier.fillMaxWidth())
        Text("And this one comes next", fontSize = p.lyricsSp.sp, fontWeight = FontWeight.SemiBold, textAlign = align,
            modifier = Modifier.fillMaxWidth().padding(top = p.lyricsSpacing.dp).alpha(p.lyricsDim / 100f))
    }
}
