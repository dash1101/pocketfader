package app.pocketfader.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun QueueScreen(s: RemoteState, modifier: Modifier = Modifier) {
    val now = s.now
    val main = s.queue.filter { it.section != "autoplay" }
    val auto = s.queue.filter { it.section == "autoplay" }

    if (s.queue.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (s.bridge) "The queue is empty." else "The queue needs the YouTube Music extension on the computer.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val loop = now != null && now.repeat != "NONE"
    val autoWontPlay = loop || now?.autoplay == false
    val why = when {
        now?.repeat == "ONE" -> "Won't play — this track is on repeat"
        loop -> "Won't play — your queue is on loop"
        now?.autoplay == false -> "Won't play — autoplay is off"
        else -> "Plays when your queue ends"
    }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionHeader("Up next", "${main.size} in queue")
        }
        items(main, key = { "q-${it.index}-${it.videoId}" }) { t ->
            TrackRow(t, dimmed = false, canMove = true, lastIndex = main.size - 1)
        }
        if (auto.isNotEmpty()) {
            item { SectionHeader("Autoplay suggestions", why) }
            items(auto, key = { "a-${it.index}-${it.videoId}" }) { t ->
                TrackRow(t, dimmed = autoWontPlay, canMove = false, lastIndex = 0)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackRow(t: Track, dimmed: Boolean, canMove: Boolean, lastIndex: Int) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .clickable(enabled = t.videoId != null) { Remote.queuePlayAt(t) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Cover(t.art, Modifier.size(48.dp), corner = 10)
            if (t.selected) {
                Icon(
                    Icons.Filled.Equalizer, "Playing now",
                    Modifier.align(Alignment.Center).size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (t.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (t.selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(t.artist, t.duration).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play now") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    onClick = { menu = false; Remote.queuePlayAt(t) },
                )
                if (canMove && !t.selected) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
                        onClick = { menu = false; Remote.queueMoveNext(t) },
                    )
                }
                if (canMove && t.index > 0) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                        onClick = { menu = false; Remote.queueMove(t, t.index - 1) },
                    )
                }
                if (canMove && t.index < lastIndex) {
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                        onClick = { menu = false; Remote.queueMove(t, t.index + 1) },
                    )
                }
                if (canMove) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; Remote.queueRemove(t) },
                    )
                }
            }
        }
    }
}
