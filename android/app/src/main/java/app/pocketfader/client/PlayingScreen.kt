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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        // a new song's cover settles into place (a little small and faint, then full), as in Refine
        val settle = rememberSettle(now?.videoId ?: now?.art, calm)
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
                    .graphicsLayer {
                        val k = settle.value
                        val sc = scale * (0.94f + 0.06f * k)
                        scaleX = sc
                        scaleY = sc
                        rotationZ = spin.value
                        alpha = 0.35f + 0.65f * k
                    }
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
                    s.source == "phone" -> "Nothing is playing on this phone. Search for a song, or play something you downloaded (Search → This phone)."
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
 *
 * It moves as Refine's cover visualizers do (the coverViz setting): "glow" breathes, "halo" also turns slowly, and
 * "orbit" is a ring of the album's colours running round the cover's outline. The strength setting (coverGlow) still
 * applies to all three, and "off" means no light at all.
 */
@Composable
fun PlayerGlow(s: RemoteState, visible: Boolean, scrollPx: () -> Int, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    if (!visible || prefs.coverGlow == "off") return
    val calm = LocalReduceMotion.current
    val playing = s.now?.playing == true
    val viz = prefs.coverViz
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (playing || calm) 1f else 0.9f, androidx.compose.animation.core.tween(if (calm) 0 else Motion.Long, easing = Motion.Ease), label = "glow-scale",
    )
    val pulse = rememberPulse(playing && !calm && viz != "orbit")
    // halo turns once in 38 s, orbit once in 7 s; both only while music plays, and a pause leaves them where they are
    val spin = rememberSpin(playing && !calm && (viz == "halo" || viz == "orbit"), if (viz == "orbit") 7000 else 38000)
    val spinNow = remember(spin) { { spin.value } }
    // the orbit ring hugs the cover's outline, so it settles with a new song's cover too
    val settle = rememberSettle(s.now?.videoId ?: s.now?.art, calm)
    val thumb = LocalArtThumb.current
    val tint = LocalTint.current ?: MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val coverW = minOf(420.dp, maxWidth - 48.dp)
        val coverPx = with(androidx.compose.ui.platform.LocalDensity.current) { coverW.toPx() }
        // the light dims as the cover scrolls away, instead of vanishing all at once
        val away = { (1f - scrollPx() / (coverPx * 0.9f)).coerceIn(0f, 1f) }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(((maxWidth - coverW) / 2).roundToPx(), 8.dp.roundToPx() - scrollPx()) }
                .size(coverW),
        ) {
            when {
                viz == "orbit" -> {
                    val strength = orbitStrength(prefs.coverGlow)
                    OrbitRing(
                        prefs.coverShape, tint, spin,
                        Modifier.matchParentSize().graphicsLayer {
                            val k = scale * (0.94f + 0.06f * settle.value)
                            scaleX = k
                            scaleY = k
                        },
                        fade = { away() * strength },
                    )
                }
                thumb != null -> AmbientGlow(
                    thumb, prefs.coverGlow, prefs.coverShape, coverW.value, scale, pulse, Modifier.matchParentSize(), away,
                    turn = if (viz == "halo") spinNow else null,
                )
                else -> Box(Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale; alpha = away() }.coverGlow(tint, prefs.coverGlow, pulse))
            }
        }
    }
}

/** How bright the orbit ring is at each strength of the light. */
private fun orbitStrength(level: String): Float = when (level) {
    "soft" -> 0.6f
    "extreme" -> 1f
    else -> 0.85f
}

/**
 * An angle in degrees that turns once every [periodMs] while [on], and stays where it is when stopped, so a pause
 * doesn't snap the light back to its start and play carries on from there.
 */
@Composable
private fun rememberSpin(on: Boolean, periodMs: Int): androidx.compose.runtime.State<Float> {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(on, periodMs) {
        if (!on) return@LaunchedEffect
        while (true) {
            val from = angle.value % 360f
            angle.snapTo(from)
            angle.animateTo(from + 360f, androidx.compose.animation.core.tween(periodMs, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    return angle.asState()
}

/**
 * 0 to 1 over three quarters of a second each time the song ([key]) changes, for a cover settling into place. Not on
 * the first showing, and at once with "Reduce animations".
 */
@Composable
private fun rememberSettle(key: Any?, calm: Boolean): Animatable<Float, AnimationVector1D> {
    val settle = remember { Animatable(1f) }
    val seen = remember { arrayOfNulls<Any>(1).also { it[0] = key } }
    LaunchedEffect(key) {
        // the same song again (or none for a moment, between two): just be whole
        if (key == null || key == seen[0]) {
            settle.snapTo(1f)
            return@LaunchedEffect
        }
        seen[0] = key
        if (calm) {
            settle.snapTo(1f)
            return@LaunchedEffect
        }
        settle.snapTo(0f)
        settle.animateTo(1f, androidx.compose.animation.core.tween(750, easing = Motion.Emphasized))
    }
    return settle
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
    val pos = progress.positionAt(clock, playing).coerceIn(0.0, if (duration > 0) duration else 0.0)
    val fraction = if (duration > 0) (pos / duration).toFloat() else 0f
    val shown = drag ?: fraction
    // at another speed the times are how long it takes to hear (0.8x: a 3:00 song shows 3:45), as in YouTube Music
    val rate = progress.rate

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
            Text(formatTime(shown * duration / rate), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration / rate), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shuffle · previous · play/pause · next · loop, as Refine's player: round buttons that soften into a squircle when
 * pressed; shuffle and loop sit on a tonal squircle while they're on (and glow, if enabled).
 */
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
        ShapeIconButton(
            Icons.Filled.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", onClick = { tap(); Remote.setShuffle(!shuffle) },
            modifier = Modifier.iconGlow(on, shuffle && bridge && enabled, 34), tint = off, active = shuffle, enabled = bridge && enabled, activeTint = on,
        )
        ShapeIconButton(
            Icons.Filled.SkipPrevious, "Previous", onClick = { tap(); Remote.transport("prev") },
            sizeDp = 60, iconDp = 34, tint = plain, enabled = enabled,
        )
        PlayButton(playing, enabled) { tap(); Remote.transport("playpause") }
        ShapeIconButton(
            Icons.Filled.SkipNext, "Next", onClick = { tap(); Remote.transport("next") },
            sizeDp = 60, iconDp = 34, tint = plain, enabled = enabled,
        )
        val repeat = now?.repeat ?: "NONE"
        val looping = repeat != "NONE"
        ShapeIconButton(
            if (repeat == "ONE") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            when (repeat) { "ALL" -> "Loop queue"; "ONE" -> "Loop track"; else -> "Loop off" },
            onClick = { tap(); Remote.setRepeat(when (repeat) { "NONE" -> "ALL"; "ALL" -> "ONE"; else -> "NONE" }) },
            modifier = Modifier.iconGlow(on, looping && bridge && enabled, 34), tint = off, active = looping, enabled = bridge && enabled, activeTint = on,
        )
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
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier.size(76.dp).pressScale(src).iconGlow(primary, playing && enabled, 58),
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.animation.Crossfade(playing, animationSpec = androidx.compose.animation.core.tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "pp") { p ->
                Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p) "Pause" else "Play", Modifier.size(42.dp))
            }
        }
    }
}

/** A slider's position: it glides to changes made elsewhere (the volume buttons, another device) and follows the finger
 *  exactly while dragged. */
@Composable
private fun rememberGlide(target: Float, dragging: Boolean): Float {
    val still = LocalReduceMotion.current
    val shown by androidx.compose.animation.core.animateFloatAsState(
        target,
        if (dragging || still) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(180, easing = Motion.Ease),
        label = "slider-glide",
    )
    return shown
}

/** The phone's own volume (while music plays on the phone). */
@Composable
fun PhoneVolumeRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var percent by remember { mutableIntStateOf(LocalPlayer.phoneVolume()) }
    var dragging by remember { mutableStateOf(false) }
    // follow the phone's volume buttons at once: Android records every volume change in its settings, so watching them
    // reports a press straight away
    androidx.compose.runtime.DisposableEffect(ctx) {
        val watch = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!dragging) percent = LocalPlayer.phoneVolume()
            }
        }
        ctx.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, watch)
        onDispose { ctx.contentResolver.unregisterContentObserver(watch) }
    }
    // and now and then anyway, for a phone that doesn't record it
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            if (!dragging) percent = LocalPlayer.phoneVolume()
        }
    }
    val shown = rememberGlide(percent / 100f, dragging)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (percent == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, null, Modifier.padding(12.dp))
        Slider(
            value = shown,
            onValueChange = { dragging = true; percent = (it * 100).roundToInt(); LocalPlayer.setPhoneVolume(percent) },
            // the phone has fewer volume steps than a hundred: settle on the step it chose
            onValueChangeFinished = { dragging = false; percent = LocalPlayer.phoneVolume() },
            modifier = Modifier.weight(1f),
        )
        Text("$percent%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).widthIn(min = 40.dp), textAlign = TextAlign.End)
        // the speed of music playing on this phone (the computer's player keeps its own): beside the phone's volume, which
        // is shown only then, so the song's title keeps its room
        SpeedButton()
    }
}

@Composable
fun VolumeRow(v: VolumeState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Remote.setMuted(!v.muted) }, enabled = v.available) {
            Icon(if (v.muted || v.percent == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (v.muted) "Unmute" else "Mute")
        }
        var dragging by remember { mutableStateOf(false) }
        Slider(
            value = rememberGlide(v.percent / 100f, dragging),
            onValueChange = { dragging = true; Remote.setVolume((it * 100).roundToInt(), false) },
            onValueChangeFinished = { dragging = false; Remote.setVolume(Remote.state.value.volume.percent, true) },
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
// Phone: one scrolling page. The player is at the top; "Up next", "Lyrics" and "Comments" are below it, with the
// tab bar pinned while you scroll. Once the player has scrolled away a slim control bar appears.

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayingPage(s: RemoteState, modifier: Modifier = Modifier) {
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }          // 0 = up next, 1 = lyrics, 2 = comments
    var playedOpen by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ui = rememberLyrics(s.now, active = tab == 1)
    val comments = rememberComments(s.now?.videoId)
    // The Comments tab: shown once a song's comments have been looked up, crossed out (and not openable) for a song that
    // has them turned off, as in Refine. After the first look it stays for the songs that follow, while theirs are looked
    // up, so the tab bar doesn't change shape at every song; it goes for what can't have comments (files on this phone).
    LaunchedEffect(comments.loaded) { if (comments.loaded) CommentsMemory.seen = true }
    val showComments = comments.usable && (comments.loaded || CommentsMemory.seen)
    val commentsOff = comments.turnedOff
    // the tab shown: Up next at once when Comments can't be opened any more (the saved choice follows just after)
    val shownTab = if (tab == 2 && (!showComments || commentsOff)) 0 else tab
    // leaving the Comments tab when the song playing now can't show any
    LaunchedEffect(showComments, commentsOff) { if ((!showComments || commentsOff) && tab == 2) tab = 0 }
    // Opening the tab looks the comments up at once. A new song while the tab is already open waits a moment first, so
    // songs skipped past aren't looked up. Keyed on the song only, so switching tabs doesn't cut a lookup short.
    LaunchedEffect(comments) {
        if (tab == 2) delay(800)
        androidx.compose.runtime.snapshotFlow { tab == 2 }.first { it }
        if (comments.usable && !comments.loaded && !comments.loading && comments.error == null) comments.loadFirst()
    }
    val tabLabels = remember(showComments) { if (showComments) listOf("Up next", "Lyrics", "Comments") else listOf("Up next", "Lyrics") }
    val crossed = remember(commentsOff) { if (commentsOff) setOf(2) else emptySet<Int>() }
    val still = !prefs.transitions || prefs.reduceMotion
    // the rows glide in one after another when a tab opens (and the comments' when they arrive), as in Refine
    val arrival = rememberArrival(shownTab).takeIf { prefs.transitions }
    val commentArrival = rememberArrival(shownTab, comments, comments.loaded).takeIf { prefs.transitions }
    val lines = ui.result?.lines.orEmpty()
    val active = rememberActiveLine(lines, ui.result?.synced == true, s.now?.playing == true)
    // list items before the first lyric line: player, tabs, lyrics controls
    var jump by remember { mutableIntStateOf(0) }
    FollowLyrics(listState, firstLineIndex = 3, active = active, enabled = tab == 1, jump = jump)

    val glowVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    Box(modifier.fillMaxSize()) {
        PlayerGlow(s, glowVisible, { listState.firstVisibleItemScrollOffset })
        // no fade at the top once the tabs are pinned there: it would fade the tabs themselves
        LazyColumn(Modifier.fillMaxSize().edgeFade({ listState.firstVisibleItemIndex == 0 && listState.canScrollBackward }, { listState.canScrollForward }), state = listState) {
            item(key = "player") { PlayerPanel(s) }
            stickyHeader(key = "tabs") {
                // see-through while it sits under the player (the cover's light carries on behind it); a backdrop once it
                // is pinned to the top, so the list doesn't show through
                // in the page's own wash colour at the top, read while drawing (the wash glides when the song changes)
                val pinned by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
                val backdrop = androidx.compose.animation.core.animateFloatAsState(
                    if (pinned) 0.94f else 0f,
                    androidx.compose.animation.core.tween(if (still) 0 else Motion.Short, easing = Motion.Ease), label = "tabs-backdrop",
                )
                val edge = LocalEdgeFade.current
                val page = MaterialTheme.colorScheme.background
                // it also catches the taps that land beside the tabs, so they never reach the row hidden under it
                Box(Modifier.fillMaxWidth().drawBehind { drawRect((edge?.top ?: page).copy(alpha = backdrop.value)) }.pointerInput(Unit) {}) {
                    PillTabs(
                        tabLabels, shownTab,
                        onSelect = { i -> tab = i; scope.launch { listState.animateScrollToItem(1) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        crossed = crossed,
                    )
                }
            }
            if (shownTab == 0) {
                queueItems(s, playedOpen, { playedOpen = !playedOpen }, prefs.queueArt, arrival) { menuTrack = it }
            } else if (shownTab == 1) {
                item(key = "ly-controls") { LyricsControls(ui) }
                lyricsItems(ui, active, prefs, s.canLyrics, s.now?.title?.isNotBlank() == true, s.now?.playing == true)
            } else {
                commentItems(comments, scope, commentArrival)
            }
            item(key = "pad") { Spacer(Modifier.height(88.dp)) }
        }
        menuTrack?.let { t -> TrackActionsDialog(t, s.queue.count { it.section != "autoplay" }) { menuTrack = null } }
        if (tab == 1) JumpToLine(listState, 3, active, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp)) { jump++ }
        val showMini by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
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

/** Whether any song's comments have shown since the app started: the Comments tab then stays while the next song's load. */
private object CommentsMemory {
    var seen by mutableStateOf(false)
}

/** Slim now-playing bar: over the lists once the full player has scrolled out of view, and above the navigation on every
 *  other page. */
@Composable
fun MiniControls(s: RemoteState, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val now = s.now ?: return
    val tap = rememberTap()
    val reduce = LocalReduceMotion.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    // the play button morphs as the big one does: a soft square while paused, round while playing
    val round by androidx.compose.animation.core.animateFloatAsState(
        if (now.playing) 50f else 30f, androidx.compose.animation.core.tween(if (reduce) 0 else Motion.Medium, easing = Motion.Ease), label = "mini-shape",
    )
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
            ShapeIconButton(Icons.Filled.SkipPrevious, "Previous", onClick = { tap(); Remote.transport("prev") }, sizeDp = 44)
            Surface(
                onClick = { tap(); Remote.transport("playpause") },
                shape = RoundedCornerShape(percent = round.roundToInt()),
                color = scheme.primary,
                contentColor = scheme.onPrimary,
                interactionSource = src,
                modifier = Modifier.padding(horizontal = 2.dp).size(44.dp).pressScale(src),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.animation.Crossfade(now.playing, animationSpec = androidx.compose.animation.core.tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "mini-pp") { p ->
                        Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p) "Pause" else "Play")
                    }
                }
            }
            ShapeIconButton(Icons.Filled.SkipNext, "Next", onClick = { tap(); Remote.transport("next") }, sizeDp = 44)
        }
        // a hairline along the bottom shows how far the track is (once a second is plenty at this size)
        val fraction = rememberTrackFraction(now.duration, now.playing, 1000L)
        if (now.duration > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp),
                color = scheme.primary, trackColor = androidx.compose.ui.graphics.Color.Transparent,
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
    val pos = progress.positionAt(clock, playing).coerceIn(0.0, if (duration > 0) duration else 0.0)
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
    // the rows glide in one after another when the pane opens
    val arrival = rememberArrival(Unit).takeIf { prefs.transitions }
    LazyColumn(modifier.fillMaxSize().edgeFade(queueState), state = queueState) {
        queueItems(s, playedOpen, { playedOpen = !playedOpen }, prefs.queueArt, arrival) { menuTrack = it }
        item(key = "pad") { Spacer(Modifier.height(24.dp)) }
    }
}
