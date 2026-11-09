package app.pocketfader.client

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search and your library in one place: type to search; otherwise the tabs show what YouTube Music picks for you, your
 * playlists, and what's on this phone (downloads and music files).
 */
@Composable
fun SearchScreen(s: RemoteState, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val resultsState = rememberLazyListState()

    fun clear() { query = ""; results = emptyList(); searched = false; error = null }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        busy = true
        error = null
        focus.clearFocus()
        scope.launch {
            try {
                // with the computer: your account's search; without it (or if that fails): the phone's own
                results = withContext(Dispatchers.IO) {
                    if (s.ytm) runCatching { Remote.search(q) }.getOrElse { Streams.search(q) } else Streams.search(q)
                }
                searched = true
            } catch (e: Exception) {
                error = e.friendly()
            }
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search YouTube Music") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty() || searched) IconButton(onClick = { clear() }) { Icon(Icons.Filled.Close, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        when {
            busy -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Note(error ?: "")
            searched && results.isEmpty() -> Note("No results.")
            searched -> LazyColumn(Modifier.fillMaxSize().edgeFade(resultsState), state = resultsState) {
                items(results, key = { (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind }) { item -> ResultRow(item) }
            }
            else -> {
                TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("For you") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("On this phone") })
                }
                when (tab) {
                    0 -> if (s.ytm) {
                        Column(Modifier.fillMaxSize()) {
                            FilledTonalButton(
                                onClick = { Remote.command(org.json.JSONObject().put("type", "lucky")) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            ) { Text("🎲  I'm feeling lucky") }
                            HomeTab(onOpen = { Detail.open(it) })
                        }
                    } else NeedsComputer(s, "Your recommendations come from your YouTube Music account, through the computer. You can still search.")
                    1 -> if (s.ytm) PlaylistsTab(onOpen = { Detail.open(it) })
                    else NeedsComputer(s, "Your playlists come from your YouTube Music account, through the computer.")
                    else -> DeviceTab()
                }
            }
        }
    }
}

@Composable
private fun NeedsComputer(s: RemoteState, text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Note(text)
        if (s.online && !s.ytm) OpenYtmButton()
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

/** Play songs that only exist on this phone (downloads, files): switches playback to the phone first. */
fun playOnPhone(list: List<LocalItem>, start: Int, shuffle: Boolean = false) {
    if (!Remote.local) Remote.setPlayOn("phone", carryOver = false)
    LocalPlayer.playItems(list, start, shuffle)
}

/** Downloads and the music files on this phone. */
@Composable
private fun DeviceTab() {
    val ctx = LocalContext.current
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    val active by Downloads.active.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf<List<LocalItem>?>(null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, DeviceMusic.permission) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (granted) files = withContext(Dispatchers.IO) { runCatching { DeviceMusic.load(ctx) }.getOrDefault(emptyList()) }
    }
    val saved = remember(downloads) { downloads.sortedByDescending { it.addedAt } }
    val savedItems = remember(saved) { saved.map { it.toLocal() } }
    val state = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().edgeFade(state), state = state) {
        item(key = "dl-h") {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                Text("Downloaded · ${saved.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (saved.isEmpty()) "Save songs from search, a playlist or the queue (⋮ → Download) to play them with no connection."
                    else "Plays with no connection, lyrics included. " + formatBytes(saved.sumOf { it.size }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (saved.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { playOnPhone(savedItems, 0) }) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text(" Play") }
                    FilledTonalButton(onClick = { playOnPhone(savedItems, 0, shuffle = true) }) { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text(" Shuffle") }
                }
            }
        }
        if (active.isNotEmpty()) item(key = "dl-active") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Downloading ${active.size}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { active.values.average().toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        itemsIndexed(saved, key = { _, d -> "dl-" + d.videoId }) { i, d ->
            DeviceRow(d.art, d.title, d.artist, onClick = { playOnPhone(savedItems, i) }) {
                IconButton(onClick = { Downloads.delete(d.videoId) }) { Icon(Icons.Filled.Delete, "Remove download") }
            }
        }
        item(key = "files-h") {
            Text(
                "Music on this phone", Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            )
        }
        val f = files
        when {
            !granted -> item(key = "files-ask") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Pocketfader needs your permission to see the music files on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { ask.launch(DeviceMusic.permission) }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow access to music") }
                }
            }
            f == null -> item(key = "files-load") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            f.isEmpty() -> item(key = "files-none") { Note("No music files found on this phone.") }
            else -> itemsIndexed(f, key = { _, it -> "f-" + it.key }) { i, it ->
                DeviceRow(it.art, it.title, listOf(it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString(" · "), onClick = { playOnPhone(f, i) }) {}
            }
        }
        item(key = "end") { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun DeviceRow(art: String?, title: String, sub: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumb(art, 48, 10)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

@Composable
private fun ResultRow(item: SearchItem) {
    val playable = item.videoId != null || item.playlistId != null
    val saved by Downloads.items.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = playable || item.kind == "artist") {
                if (item.kind == "artist") Detail.open(item)
                else if (item.videoId != null) Remote.playItem(item)
                else if (item.playlistId != null) Remote.startPlaylist(item.playlistId, false)
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumb(item.thumb, 52, 10)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle.ifBlank { item.kind.replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.videoId != null && saved.any { it.videoId == item.videoId }) {
            Icon(Icons.Filled.DownloadDone, "Downloaded", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (item.videoId != null && item.kind == "song") {
            SongMenuButton(item)
        } else if (item.playlistId != null) {
            IconButton(onClick = { Remote.startPlaylist(item.playlistId, true) }) { Icon(Icons.Filled.Shuffle, "Shuffle play") }
        }
    }
}
