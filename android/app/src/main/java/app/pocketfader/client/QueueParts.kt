package app.pocketfader.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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

/** What shuffle / loop / autoplay are doing right now, in plain words. */
fun modesCaption(now: Now?, count: Int): String {
    if (now == null) return ""
    val parts = ArrayList<String>(2)
    if (now.shuffle) parts.add("Shuffled: your $count tracks play in a random order.")
    parts.add(
        when (now.repeat) {
            "ONE" -> "Repeating this track over and over."
            "ALL" -> "Looping the whole queue (tracks already played too), so suggestions won't play."
            else -> when (now.autoplay) {
                false -> "Plays your $count tracks once, then stops."
                else -> "Plays your $count tracks once, then continues with suggestions."
            }
        }
    )
    return parts.joinToString(" ")
}

@Composable
private fun QueueModes(now: Now?, bridge: Boolean, count: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!bridge || now == null) {
            Text(
                "Shuffle, loop and autoplay need the YouTube Music extension connected on the computer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val tap = rememberTap()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = now.shuffle, onClick = { tap(); Remote.setShuffle(!now.shuffle) },
                    label = { Text(if (now.shuffle) "Shuffle on" else "Shuffle off") },
                    leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = now.repeat != "NONE",
                    onClick = { tap(); Remote.setRepeat(when (now.repeat) { "NONE" -> "ALL"; "ALL" -> "ONE"; else -> "NONE" }) },
                    label = { Text(when (now.repeat) { "ALL" -> "Loop: queue"; "ONE" -> "Loop: track"; else -> "Loop off" }) },
                    leadingIcon = { Icon(if (now.repeat == "ONE") Icons.Filled.RepeatOne else Icons.Filled.Repeat, null, Modifier.size(18.dp)) },
                )
                val auto = now.autoplay
                if (auto != null) {
                    FilterChip(
                        selected = auto, onClick = { tap(); Remote.setAutoplay(!auto) },
                        label = { Text(if (auto) "Autoplay on" else "Autoplay off") },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
                    )
                }
            }
            Text(modesCaption(now, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The queue as list items: modes, tracks already played (collapsed), the current track, what's up next,
 * and YouTube Music's autoplay suggestions, which can be added to the queue from here.
 */
fun LazyListScope.queueItems(s: RemoteState, playedOpen: Boolean, onTogglePlayed: () -> Unit) {
    val main = s.queue.filter { it.section != "autoplay" }
    val auto = s.queue.filter { it.section == "autoplay" }
    val now = s.now
    val cur = main.indexOfFirst { it.selected }
    val played = if (cur > 0) main.subList(0, cur) else emptyList()
    val current = if (cur >= 0) main[cur] else null
    val upcoming = if (cur >= 0) main.subList(cur + 1, main.size) else main

    item(key = "q-modes") { QueueModes(now, s.bridge, main.size) }

    if (s.queue.isEmpty()) {
        item(key = "q-empty") {
            Text(
                if (s.bridge) "The queue is empty." else "The queue needs the YouTube Music extension on the computer.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }
        return
    }

    if (played.isNotEmpty()) {
        item(key = "q-played-h") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onTogglePlayed).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Already played · ${played.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (now?.repeat == "ALL") "These come back around because the queue is looping." else "Tap to show the tracks before this one.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (playedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
        }
        if (playedOpen) {
            items2(played, "qp", dimmed = true, canMove = true, lastIndex = main.size - 1)
        }
    }
    if (current != null) {
        item(key = "q-now-h") { SectionHeader("Now playing", null) }
        item(key = "q-now") { TrackRow(current, dimmed = false, canMove = false, lastIndex = main.size - 1, suggestion = false) }
    }
    if (upcoming.isNotEmpty()) {
        item(key = "q-up-h") { SectionHeader("Up next · ${upcoming.size}", null) }
        items2(upcoming, "qu", dimmed = false, canMove = true, lastIndex = main.size - 1)
    }
    if (auto.isNotEmpty()) {
        item(key = "q-auto-h") {
            val wontPlay = (now?.repeat ?: "NONE") != "NONE" || now?.autoplay == false
            val why = when {
                now?.repeat == "ONE" -> "Won't play: this track is on repeat. You can still add any of them to your queue."
                now?.repeat == "ALL" -> "Won't play: your queue is on loop. You can still add any of them to your queue."
                now?.autoplay == false -> "Won't play: autoplay is off. You can still add any of them to your queue."
                else -> "Plays when your queue ends. Tap + to add one to your queue now."
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay suggestions · ${auto.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(why, style = MaterialTheme.typography.bodySmall, color = if (wontPlay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val a = now?.autoplay
                if (s.bridge && a != null) Switch(checked = a, onCheckedChange = { Remote.setAutoplay(it) })
            }
        }
        items2(auto, "qa", dimmed = (now?.repeat ?: "NONE") != "NONE" || now?.autoplay == false, canMove = false, lastIndex = 0, suggestion = true)
    }
}

private fun LazyListScope.items2(
    list: List<Track>, prefix: String, dimmed: Boolean, canMove: Boolean, lastIndex: Int, suggestion: Boolean = false,
) {
    for (t in list) {
        item(key = "$prefix-${t.index}-${t.videoId}") { TrackRow(t, dimmed, canMove, lastIndex, suggestion) }
    }
}

@Composable
private fun SectionHeader(title: String, note: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackRow(t: Track, dimmed: Boolean, canMove: Boolean, lastIndex: Int, suggestion: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
            .clickable(enabled = t.videoId != null) { Remote.queuePlayAt(t) }
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Cover(t.art, Modifier.size(46.dp), corner = 10)
            if (t.selected) {
                Icon(Icons.Filled.Equalizer, "Playing now", Modifier.align(Alignment.Center).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.title, style = MaterialTheme.typography.bodyLarge,
                color = if (t.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (t.selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(t.artist, t.duration).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (suggestion) {
            // one tap puts a suggestion on the end of your real queue
            IconButton(onClick = { Remote.queueAddEnd(t) }) { Icon(Icons.Filled.Add, "Add to your queue") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play now") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    onClick = { menu = false; Remote.queuePlayAt(t) })
                if (!t.selected) {
                    DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
                        onClick = { menu = false; Remote.queueMoveNext(t) })
                }
                if (suggestion) {
                    DropdownMenuItem(text = { Text("Add to end of queue") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menu = false; Remote.queueAddEnd(t) })
                }
                if (canMove && t.index > 0) {
                    DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                        onClick = { menu = false; Remote.queueMove(t, t.index - 1) })
                }
                if (canMove && t.index < lastIndex) {
                    DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                        onClick = { menu = false; Remote.queueMove(t, t.index + 1) })
                }
                if (canMove) {
                    DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; Remote.queueRemove(t) })
                }
            }
        }
    }
}
