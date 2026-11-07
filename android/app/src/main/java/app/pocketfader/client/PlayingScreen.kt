package app.pocketfader.client

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, corner: Int = 28) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.MusicNote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A small cover for lists. It is decoded at its own size with the rounded corners baked into the bitmap, so
 * scrolling a long list doesn't make the GPU clip every picture on every frame (the main cause of jank on
 * older tablets).
 */
@Composable
fun Thumb(url: String?, sizeDp: Int, cornerDp: Int = 10, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val px = (sizeDp * density).toInt().coerceAtLeast(32)
    val shape = RoundedCornerShape(cornerDp.dp)
    Box(modifier.size(sizeDp.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape), contentAlignment = Alignment.Center) {
        if (url != null) {
            val request = remember(url, px) {
                ImageRequest.Builder(ctx).data(url).size(px).crossfade(false).allowRgb565(true)
                    .transformations(RoundedCornersTransformation(cornerDp * density)).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A tap handler that gives a small buzz when haptics are on in the settings. */
@Composable
fun rememberTap(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val on = Settings.prefs.collectAsStateWithLifecycle().value.haptics
    return remember(haptics, on) { { if (on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

// ---------------------------------------------------------------------------------------------
// The player itself: cover, title, seek bar, transport, volume. Used on its own in the wide layout
// and as the top of the Playing page on phones.

@Composable
fun PlayerPanel(s: RemoteState, modifier: Modifier = Modifier) {
    val now = s.now
    val hasTrack = now != null && now.title.isNotBlank()
    Column(modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(now?.art, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(20.dp))

        val videoId = now?.videoId
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    now?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasTrack) {
                    Text(
                        now?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (s.bridge && videoId != null) {
                val liked = now?.liked == true
                IconButton(onClick = { Remote.like(videoId, !liked) }) {
                    Icon(
                        if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        if (liked) "Unlike" else "Like",
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!hasTrack) {
            Text(
                if (s.online) "Start something on the computer. Open YouTube Music with the Pocketfader extension, or any player."
                else "Waiting for the computer…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }

        SeekBar(now?.duration ?: 0.0, now?.playing == true)
        Transport(now, s.bridge, hasTrack)
        Spacer(Modifier.height(4.dp))
        VolumeRow(s.volume)
    }
}

@Composable
private fun SeekBar(duration: Double, playing: Boolean) {
    val reduce = LocalReduceMotion.current
    val progress by Remote.progress.collectAsStateWithLifecycle()
    var drag by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    // Advance the bar locally between polls so it moves smoothly.
    LaunchedEffect(playing, reduce) {
        while (playing) {
            delay(if (reduce) 1000 else 250)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val clock = maxOf(tick, progress.at)
    val elapsed = if (playing) (clock - progress.at) / 1000.0 else 0.0
    val pos = (progress.position + elapsed).coerceIn(0.0, if (duration > 0) duration else 0.0)
    val fraction = if (duration > 0) (pos / duration).toFloat() else 0f
    val shown = drag ?: fraction

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Slider(
            value = shown.coerceIn(0f, 1f),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { Remote.transport("seek", it * duration) }
                drag = null
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown * duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shuffle · previous · play/pause · next · loop. The two outer buttons light up when they're on. */
@Composable
private fun Transport(now: Now?, bridge: Boolean, enabled: Boolean) {
    val tap = rememberTap()
    val playing = now?.playing == true
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffle = now?.shuffle == true
        IconButton(onClick = { tap(); Remote.setShuffle(!shuffle) }, enabled = bridge && enabled) {
            Icon(Icons.Filled.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", tint = if (shuffle) on else off)
        }
        IconButton(onClick = { tap(); Remote.transport("prev") }, Modifier.size(60.dp), enabled = enabled) {
            Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(34.dp))
        }
        FilledIconButton(onClick = { tap(); Remote.transport("playpause") }, Modifier.size(76.dp), enabled = enabled) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(42.dp))
        }
        IconButton(onClick = { tap(); Remote.transport("next") }, Modifier.size(60.dp), enabled = enabled) {
            Icon(Icons.Filled.SkipNext, "Next", Modifier.size(34.dp))
        }
        val repeat = now?.repeat ?: "NONE"
        IconButton(
            onClick = { tap(); Remote.setRepeat(when (repeat) { "NONE" -> "ALL"; "ALL" -> "ONE"; else -> "NONE" }) },
            enabled = bridge && enabled,
        ) {
            Icon(
                if (repeat == "ONE") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                when (repeat) { "ALL" -> "Loop queue"; "ONE" -> "Loop track"; else -> "Loop off" },
                tint = if (repeat != "NONE") on else off,
            )
        }
    }
}

@Composable
fun VolumeRow(v: VolumeState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Remote.setMuted(!v.muted) }, enabled = v.available) {
            Icon(if (v.muted || v.percent == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (v.muted) "Unmute" else "Mute")
        }
        Slider(
            value = v.percent / 100f,
            onValueChange = { Remote.setVolume((it * 100).roundToInt(), false) },
            onValueChangeFinished = { Remote.setVolume(Remote.state.value.volume.percent, true) },
            enabled = v.available,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${v.percent}%",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp).widthIn(min = 40.dp),
            textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Phone: one scrolling page. The player is at the top; "Up next" and "Lyrics" are below it, with the
// tab bar pinned while you scroll. Once the player has scrolled away a slim control bar appears.

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayingPage(s: RemoteState, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }          // 0 = up next, 1 = lyrics
    var playedOpen by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ui = rememberLyrics(s.now, active = tab == 1)
    val lines = ui.result?.lines.orEmpty()
    val active = rememberActiveLine(lines, ui.result?.synced == true, s.now?.playing == true)
    // list items before the first lyric line: player, tabs, lyrics controls
    FollowLyrics(listState, firstLineIndex = 3, active = active, enabled = tab == 1)

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "player") { PlayerPanel(s) }
            stickyHeader(key = "tabs") {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0; scope.launch { listState.animateScrollToItem(1) } }, text = { Text("Up next") })
                        Tab(selected = tab == 1, onClick = { tab = 1; scope.launch { listState.animateScrollToItem(1) } }, text = { Text("Lyrics") })
                    }
                }
            }
            if (tab == 0) {
                queueItems(s, playedOpen, { playedOpen = !playedOpen }) { menuTrack = it }
            } else {
                item(key = "ly-controls") { LyricsControls(ui) }
                lyricsItems(ui, active, prefs, s.canLyrics, s.now?.title?.isNotBlank() == true, s.now?.playing == true)
            }
            item(key = "pad") { Spacer(Modifier.height(88.dp)) }
        }
        menuTrack?.let { t -> TrackActionsDialog(t, s.queue.count { it.section != "autoplay" }) { menuTrack = null } }
        val showMini by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        if (showMini) {
            MiniControls(s, onTap = { scope.launch { listState.animateScrollToItem(0) } }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Slim now-playing bar shown over the lists once the full player has scrolled out of view. */
@Composable
private fun MiniControls(s: RemoteState, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val now = s.now ?: return
    val tap = rememberTap()
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.clickable(onClick = onTap).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(now.art, 44)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(now.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { tap(); Remote.transport("prev") }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
            FilledIconButton(onClick = { tap(); Remote.transport("playpause") }) {
                Icon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (now.playing) "Pause" else "Play")
            }
            IconButton(onClick = { tap(); Remote.transport("next") }) { Icon(Icons.Filled.SkipNext, "Next") }
        }
    }
}

/** Wide layout, left pane: just the player. */
@Composable
fun PlayerPane(s: RemoteState, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) { PlayerPanel(s) }
}

/** Wide layout, right pane: the queue on its own. */
@Composable
fun QueuePane(s: RemoteState, modifier: Modifier = Modifier) {
    var playedOpen by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    menuTrack?.let { t -> TrackActionsDialog(t, s.queue.count { it.section != "autoplay" }) { menuTrack = null } }
    LazyColumn(modifier.fillMaxSize()) {
        queueItems(s, playedOpen, { playedOpen = !playedOpen }) { menuTrack = it }
        item(key = "pad") { Spacer(Modifier.height(24.dp)) }
    }
}
