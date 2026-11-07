package app.pocketfader.client

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
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
            Icon(Icons.Filled.MusicNote, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PlayingScreen(s: RemoteState, modifier: Modifier = Modifier) {
    val now = s.now
    val hasTrack = now != null && now.title.isNotBlank()
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(now?.art, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(24.dp))

        val videoId = now?.videoId
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasTrack) now!!.title else "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasTrack) {
                    Text(
                        now!!.artist,
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

        SeekBar(now, s.stamp)
        Transport(now?.playing == true, hasTrack)
        if (s.bridge && now != null) Modes(now)
        Spacer(Modifier.height(8.dp))
        VolumeRow(s.volume)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SeekBar(now: Now?, stamp: Long) {
    val duration = now?.duration ?: 0.0
    val playing = now?.playing == true
    var drag by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    // Advance the bar locally between polls so it moves smoothly.
    LaunchedEffect(playing) {
        while (playing) {
            delay(250)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val clock = tick   // read so the bar recomposes on every tick
    val elapsed = if (playing && clock >= stamp) (clock - stamp) / 1000.0 else 0.0
    val pos = ((now?.position ?: 0.0) + elapsed).coerceIn(0.0, if (duration > 0) duration else 0.0)
    val fraction = if (duration > 0) (pos / duration).toFloat() else 0f
    val shown = drag ?: fraction

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
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

@Composable
private fun Transport(playing: Boolean, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { Remote.transport("prev") }, Modifier.size(64.dp), enabled = enabled) {
            Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
        }
        FilledIconButton(onClick = { Remote.transport("playpause") }, Modifier.size(80.dp), enabled = enabled) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(44.dp))
        }
        IconButton(onClick = { Remote.transport("next") }, Modifier.size(64.dp), enabled = enabled) {
            Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
        }
    }
}

@Composable
private fun Modes(now: Now) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        FilterChip(
            selected = now.shuffle,
            onClick = { Remote.setShuffle(!now.shuffle) },
            label = { Text("Shuffle") },
            leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = now.repeat != "NONE",
            onClick = {
                Remote.setRepeat(
                    when (now.repeat) { "NONE" -> "ALL"; "ALL" -> "ONE"; else -> "NONE" }
                )
            },
            label = {
                Text(
                    when (now.repeat) { "ALL" -> "Loop: queue"; "ONE" -> "Loop: track"; else -> "Loop" }
                )
            },
            leadingIcon = {
                Icon(if (now.repeat == "ONE") Icons.Filled.RepeatOne else Icons.Filled.Repeat, null, Modifier.size(18.dp))
            },
        )
        val auto = now.autoplay
        if (auto != null) {
            FilterChip(
                selected = auto,
                onClick = { Remote.setAutoplay(!auto) },
                label = { Text("Autoplay") },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
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
