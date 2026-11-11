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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
fun Cover(url: String?, modifier: Modifier = Modifier, corner: Int = 28, shape: androidx.compose.ui.graphics.Shape? = null) {
    Box(
        modifier
            .clip(shape ?: RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // the next track's picture fades in over the last one, instead of replacing it in one frame
        val calm = LocalReduceMotion.current
        androidx.compose.animation.Crossfade(
            targetState = url, modifier = Modifier.fillMaxSize(),
            animationSpec = androidx.compose.animation.core.tween(if (calm) 0 else Motion.Medium, easing = Motion.Ease), label = "cover-art",
        ) { u ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (u != null) {
                    AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.MusicNote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A small cover for lists. It is decoded at its own size with the rounded corners baked into the bitmap, so
 * scrolling a long list doesn't make the GPU clip every picture on every frame (the main cause of jank on
 * older tablets).
 */
@Composable
fun Thumb(url: String?, sizeDp: Int, cornerDp: Int = 8, modifier: Modifier = Modifier) {
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
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val hasTrack = now != null && now.title.isNotBlank()
    val playing = now?.playing == true
    Column(modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val calm = LocalReduceMotion.current
        val scale by androidx.compose.animation.core.animateFloatAsState(
            if (playing || calm) 1f else 0.9f, androidx.compose.animation.core.tween(if (calm) 0 else Motion.Long, easing = Motion.Ease), label = "cover",
        )
        val shape = coverShape(prefs.coverShape)
        // "Vinyl": the round cover turns slowly while music plays
        val spin: State<Float> = if (prefs.coverShape == "circle" && playing && !calm) {
            androidx.compose.animation.core.rememberInfiniteTransition(label = "spin").animateFloat(
                0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(26000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin",
            )
        } else {
            remember { mutableStateOf(0f) }
        }
        // (the glow behind the cover is drawn by PlayerGlow, outside the scrolling list, so it isn't cut off at the top)
        Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)) {
            Cover(
                now?.art,
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = spin.value }
                    .then(if (calm || prefs.coverGlow != "off") Modifier else Modifier.shadow(20.dp, shape, clip = false)),
                shape = shape,
            )
        }
        Spacer(Modifier.height(if (prefs.coverGlow == "extreme") 34.dp else 22.dp))

        val videoId = now?.videoId
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // the title and artist fade into each other when the track changes
                androidx.compose.animation.Crossfade(
                    targetState = (now?.title.orEmpty()) to (now?.artist.orEmpty()),
                    animationSpec = androidx.compose.animation.core.tween(if (calm) 0 else Motion.Medium, easing = Motion.Ease), label = "title",
                ) { (title, artist) ->
                    Column {
                        Text(
                            title.takeIf { it.isNotBlank() } ?: "Nothing playing",
                            style = MaterialTheme.typography.headlineMedium.glow(),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (title.isNotBlank()) {
                            Text(
                                artist,
                                // tap the artist to see their page: about, top songs, albums
                                modifier = Modifier.then(if (artist.isNotBlank()) Modifier.clickable { Detail.openArtist(videoId, artist) } else Modifier),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (s.canSleep) SleepButton(s)
            // save the song that's playing to this phone
            if (isYtId(videoId)) {
                val saved by Downloads.items.collectAsStateWithLifecycle()
                val busy by Downloads.active.collectAsStateWithLifecycle()
                val vid = videoId
                if (vid != null && saved.none { it.videoId == vid }) {
                    PressIconButton(
                        Icons.Filled.Download, if (vid in busy) "Downloading" else "Download to this phone", 48, 24,
                        MaterialTheme.colorScheme.onSurfaceVariant, false, vid !in busy,
                    ) { now?.let { n -> Downloads.enqueue(LocalItem(vid, null, n.title, n.artist, n.art, n.duration)) } }
                }
            }
            // likes count on the phone right away, and reach YouTube Music now (computer connected) or at the next sync
            if (isYtId(videoId) && videoId != null) {
                val liked = now?.liked == true
                val primary = MaterialTheme.colorScheme.primary
                PressIconButton(
                    if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, if (liked) "Unlike" else "Like", 48, 24,
                    tint = if (liked) primary else MaterialTheme.colorScheme.onSurfaceVariant, glow = liked, enabled = true,
                ) { Remote.like(videoId, !liked) }
            }
        }

        if (!hasTrack) {
            Text(
                when {
                    s.source == "phone" -> "Nothing is playing on this phone. Search for a song, or play something you downloaded (Search → On this phone)."
                    s.online -> "Start something on the computer. Open YouTube Music with the YTM Starcast extension, or any player."
                    else -> "Waiting for the computer…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            // nothing to control because there's no YouTube Music tab: open one on the computer
            if (s.online && !s.bridge) OpenYtmButton(Modifier.padding(bottom = 8.dp))
        }

        SeekBar(now?.duration ?: 0.0, playing, prefs.wavyBar)
        Transport(now, s.bridge, hasTrack)
        Spacer(Modifier.height(4.dp))
        // the computer's volume only while "Computer audio" is switched on there
        if (s.source == "phone") PhoneVolumeRow() else if (s.audioOn) VolumeRow(s.volume)
    }
}

/**
 * The soft light around the cover. It lives behind the scrolling list (not inside it) and follows the cover's position, so
 * it can spread under the top bar instead of being cut off at the top edge of the list.
 */
@Composable
fun PlayerGlow(s: RemoteState, visible: Boolean, scrollPx: () -> Int, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    if (!visible || prefs.coverGlow == "off") return
    val calm = LocalReduceMotion.current
    val playing = s.now?.playing == true
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (playing || calm) 1f else 0.9f, androidx.compose.animation.core.tween(if (calm) 0 else Motion.Long, easing = Motion.Ease), label = "glow-scale",
    )
    val pulse = rememberPulse(playing && !calm)
    val thumb = LocalArtThumb.current
    val tint = LocalTint.current ?: MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val coverW = minOf(420.dp, maxWidth - 48.dp)
        val coverPx = with(androidx.compose.ui.platform.LocalDensity.current) { coverW.toPx() }
        // the light dims as the cover scrolls away, instead of vanishing all at once
        val fade = { (1f - scrollPx() / (coverPx * 0.9f)).coerceIn(0f, 1f) }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(((maxWidth - coverW) / 2).roundToPx(), 8.dp.roundToPx() - scrollPx()) }
                .size(coverW),
        ) {
            if (thumb != null) {
                AmbientGlow(thumb, prefs.coverGlow, prefs.coverShape, coverW.value, scale, pulse, Modifier.matchParentSize(), fade)
            } else {
                Box(Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale; alpha = fade() }.coverGlow(tint, prefs.coverGlow, pulse))
            }
        }
    }
}

/** An icon button that dips a little when pressed and can glow when it's "on". */
@Composable
fun PressIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, sizeDp: Int, iconDp: Int,
    tint: androidx.compose.ui.graphics.Color, glow: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    IconButton(
        onClick = onClick, enabled = enabled, interactionSource = src,
        modifier = Modifier.size(sizeDp.dp).pressScale(src).iconGlow(tint, glow, sizeDp / 2 + 10),
    ) {
        Icon(icon, description, Modifier.size(iconDp.dp), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

@Composable
private fun SeekBar(duration: Double, playing: Boolean, wavy: Boolean) {
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
        WavySeekBar(
            value = shown.coerceIn(0f, 1f),
            enabled = duration > 0,
            playing = playing,
            wavy = wavy,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { Remote.transport("seek", it * duration) }
                drag = null
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // figures of one width, so the time doesn't shiver as the seconds change
            val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Text(formatTime(shown * duration), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shuffle · previous · play/pause · next · loop. The two outer buttons light up (and glow, if enabled) when they're on. */
@Composable
private fun Transport(now: Now?, bridge: Boolean, enabled: Boolean) {
    val tap = rememberTap()
    val playing = now?.playing == true
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    val plain = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffle = now?.shuffle == true
        PressIconButton(Icons.Filled.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", 48, 24, if (shuffle) on else off, shuffle, bridge && enabled) {
            tap(); Remote.setShuffle(!shuffle)
        }
        PressIconButton(Icons.Filled.SkipPrevious, "Previous", 60, 34, plain, false, enabled) { tap(); Remote.transport("prev") }
        PlayButton(playing, enabled) { tap(); Remote.transport("playpause") }
        PressIconButton(Icons.Filled.SkipNext, "Next", 60, 34, plain, false, enabled) { tap(); Remote.transport("next") }
        val repeat = now?.repeat ?: "NONE"
        PressIconButton(
            if (repeat == "ONE") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            when (repeat) { "ALL" -> "Loop queue"; "ONE" -> "Loop track"; else -> "Loop off" }, 48, 24,
            if (repeat != "NONE") on else off, repeat != "NONE", bridge && enabled,
        ) { tap(); Remote.setRepeat(when (repeat) { "NONE" -> "ALL"; "ALL" -> "ONE"; else -> "NONE" }) }
    }
}

/** Play / pause: a round button while playing that eases into a softer square when paused, and dips when pressed. */
@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val percent by androidx.compose.animation.core.animateFloatAsState(
        if (playing) 50f else 30f, androidx.compose.animation.core.tween(if (reduce) 0 else Motion.Medium, easing = Motion.Ease), label = "shape",
    )
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick, enabled = enabled, interactionSource = src,
        shape = RoundedCornerShape(percent = percent.toInt()),
        color = if (enabled) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(76.dp).pressScale(src).iconGlow(primary, playing && enabled, 58),
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.animation.Crossfade(playing, animationSpec = androidx.compose.animation.core.tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "pp") { p ->
                Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p) "Pause" else "Play", Modifier.size(42.dp))
            }
        }
    }
}

/** The phone's own volume (while music plays on the phone). */
@Composable
fun PhoneVolumeRow(modifier: Modifier = Modifier) {
    var percent by remember { mutableIntStateOf(LocalPlayer.phoneVolume()) }
    // follow the phone's volume buttons too
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            percent = LocalPlayer.phoneVolume()
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (percent == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, null, Modifier.padding(12.dp))
        Slider(
            value = percent / 100f,
            onValueChange = { percent = (it * 100).roundToInt(); LocalPlayer.setPhoneVolume(percent) },
            modifier = Modifier.weight(1f),
        )
        Text("$percent%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).widthIn(min = 40.dp), textAlign = TextAlign.End)
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
    var jump by remember { mutableIntStateOf(0) }
    FollowLyrics(listState, firstLineIndex = 3, active = active, enabled = tab == 1, jump = jump)

    val glowVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    Box(modifier.fillMaxSize()) {
        PlayerGlow(s, glowVisible, { listState.firstVisibleItemScrollOffset })
        LazyColumn(Modifier.fillMaxSize().edgeFade(listState), state = listState) {
            item(key = "player") { PlayerPanel(s) }
            stickyHeader(key = "tabs") {
                Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)) {
                    TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                        Tab(selected = tab == 0, onClick = { tab = 0; scope.launch { listState.animateScrollToItem(1) } }, text = { Text("Up next") })
                        Tab(selected = tab == 1, onClick = { tab = 1; scope.launch { listState.animateScrollToItem(1) } }, text = { Text("Lyrics") })
                    }
                }
            }
            if (tab == 0) {
                queueItems(s, playedOpen, { playedOpen = !playedOpen }, prefs.queueArt) { menuTrack = it }
            } else {
                item(key = "ly-controls") { LyricsControls(ui) }
                lyricsItems(ui, active, prefs, s.canLyrics, s.now?.title?.isNotBlank() == true, s.now?.playing == true)
            }
            item(key = "pad") { Spacer(Modifier.height(88.dp)) }
        }
        menuTrack?.let { t -> TrackActionsDialog(t, s.queue.count { it.section != "autoplay" }) { menuTrack = null } }
        if (tab == 1) JumpToLine(listState, 3, active, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp)) { jump++ }
        val showMini by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        val still = !prefs.transitions || prefs.reduceMotion
        // the slim bar glides up from the bottom when the full controls scroll out of view, and back down when they return
        androidx.compose.animation.AnimatedVisibility(
            visible = showMini,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (still) androidx.compose.animation.EnterTransition.None
            else androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(Motion.Medium, easing = Motion.Ease)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Short, easing = Motion.Ease)),
            exit = if (still) androidx.compose.animation.ExitTransition.None
            else androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(Motion.Short, easing = Motion.Ease)) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Short, easing = Motion.Ease)),
        ) {
            MiniControls(s, onTap = { scope.launch { listState.animateScrollToItem(0) } }, Modifier)
        }
    }
}

/** Slim now-playing bar shown over the lists once the full player has scrolled out of view. */
@Composable
private fun MiniControls(s: RemoteState, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val now = s.now ?: return
    val tap = rememberTap()
    val reduce = LocalReduceMotion.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = PfShape.Lg,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
        Row(Modifier.clickable(onClick = onTap).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(now.art, 44)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(now.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { tap(); Remote.transport("prev") }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
            FilledIconButton(onClick = { tap(); Remote.transport("playpause") }, modifier = Modifier.pressScale(src), interactionSource = src) {
                androidx.compose.animation.Crossfade(now.playing, animationSpec = androidx.compose.animation.core.tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "mini-pp") { p ->
                    Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p) "Pause" else "Play")
                }
            }
            IconButton(onClick = { tap(); Remote.transport("next") }) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        // a hairline along the bottom shows how far the track is (once a second is plenty at this size)
        val fraction = rememberTrackFraction(now.duration, now.playing, 1000L)
        if (now.duration > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
            )
        }
        }   // Column
    }
}

/** How far through the track playback is (0..1), moving on by itself between the updates from the player. */
@Composable
private fun rememberTrackFraction(duration: Double, playing: Boolean, everyMs: Long): Float {
    val progress by Remote.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, everyMs) {
        while (playing) {
            delay(everyMs)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val clock = maxOf(tick, progress.at)
    val elapsed = if (playing) (clock - progress.at) / 1000.0 else 0.0
    val pos = (progress.position + elapsed).coerceIn(0.0, if (duration > 0) duration else 0.0)
    return if (duration > 0) (pos / duration).toFloat() else 0f
}

/** Wide layout, left pane: just the player. */
@Composable
fun PlayerPane(s: RemoteState, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier) {
        PlayerGlow(s, true, { scroll.value })
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) { PlayerPanel(s) }
    }
}

/** Wide layout, right pane: the queue on its own. */
@Composable
fun QueuePane(s: RemoteState, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    var playedOpen by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    menuTrack?.let { t -> TrackActionsDialog(t, s.queue.count { it.section != "autoplay" }) { menuTrack = null } }
    val queueState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(modifier.fillMaxSize().edgeFade(queueState), state = queueState) {
        queueItems(s, playedOpen, { playedOpen = !playedOpen }, prefs.queueArt) { menuTrack = it }
        item(key = "pad") { Spacer(Modifier.height(24.dp)) }
    }
}
