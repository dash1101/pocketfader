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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.onFocusChanged
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

/** What the search screen had, kept while you look at other screens. */
private object SearchMemory {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchItem>>(emptyList())
    var searched by mutableStateOf(false)
}

/**
 * Search and your library in one place: type to search; otherwise the tabs show what's recommended for you, your
 * playlists, and what's on this phone (downloads and music files). Without the computer's YouTube Music the
 * recommendations are made on the phone and the playlists come from the last sync.
 */
@Composable
fun SearchScreen(s: RemoteState, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(SearchMemory.query)) }
    val results = SearchMemory.results
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val searched = SearchMemory.searched
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val recent by Library.recentSearches.collectAsStateWithLifecycle()
    val saved by Downloads.items.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.videoId } }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val resultsState = rememberLazyListState()

    fun clear() {
        query = androidx.compose.ui.text.input.TextFieldValue("")
        SearchMemory.query = ""; SearchMemory.results = emptyList(); SearchMemory.searched = false; error = null; suggestions = emptyList()
    }

    fun doSearch(text: String = query.text) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        if (q != query.text) query = androidx.compose.ui.text.input.TextFieldValue(q, androidx.compose.ui.text.TextRange(q.length))
        SearchMemory.query = q
        busy = true
        error = null
        suggestions = emptyList()
        focus.clearFocus()
        Library.rememberSearch(q)
        scope.launch {
            try {
                // with the computer: your account's search; without it (or if that fails): the phone's own
                SearchMemory.results = withContext(Dispatchers.IO) {
                    if (s.ytm) runCatching { Remote.search(q) }.getOrElse { Streams.search(q) } else Streams.search(q)
                }
                SearchMemory.searched = true
            } catch (e: Exception) {
                error = e.friendly()
            }
            busy = false
        }
    }

    // suggestions while typing (a short pause first, so not every letter asks)
    LaunchedEffect(query.text, focused) {
        val q = query.text.trim()
        if (!focused || q.isEmpty() || (searched && q == SearchMemory.query)) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        suggestions = withContext(Dispatchers.IO) { runCatching { Remote.suggest(q) }.getOrDefault(emptyList()) }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; if (it.text.isEmpty() && searched) clear() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.isFocused },
            placeholder = { Text("Search YouTube Music") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.text.isNotEmpty() || searched) IconButton(onClick = { clear() }) { Icon(Icons.Filled.Close, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        val typing = focused && query.text.isNotBlank() && !(searched && query.text.trim() == SearchMemory.query)
        when {
            busy -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            typing && suggestions.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions, key = { "s-$it" }) { sug -> TextRow(sug, Icons.Filled.Search, onClick = { doSearch(sug) }) }
            }
            focused && query.text.isBlank() && recent.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "recent-h") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        androidx.compose.material3.TextButton(onClick = { Library.forgetSearches() }) { Text("Clear") }
                    }
                }
                items(recent, key = { "r-$it" }) { q -> TextRow(q, Icons.Filled.History, onClick = { doSearch(q) }) }
            }
            error != null -> Note(error ?: "")
            searched && results.isEmpty() -> Note("No results.")
            searched -> LazyColumn(Modifier.fillMaxSize().edgeFade(resultsState), state = resultsState) {
                items(results, key = { (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind }) { item -> ResultRow(item, results, item.videoId in savedIds) }
            }
            else -> {
                TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("For you") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("On this phone") })
                }
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        FilledTonalButton(
                            onClick = { Remote.lucky() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        ) { Text("🎲  I'm feeling lucky") }
                        // your account's home page through the computer; otherwise the phone's own picks
                        if (s.ytm) HomeTab(onOpen = { Detail.open(it) }) else ForYouTab(s, onOpen = { Detail.open(it) })
                    }
                    1 -> if (s.ytm) PlaylistsTab(onOpen = { Detail.open(it) }) else SyncedPlaylistsTab(s, onOpen = { Detail.open(it) })
                    else -> DeviceTab()
                }
            }
        }
    }
}

@Composable
private fun TextRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Recommendations made on this phone (see [Recommend]), with a note about where they come from. */
@Composable
private fun ForYouTab(s: RemoteState, onOpen: (SearchItem) -> Unit) {
    val feed by Recommend.feed.collectAsStateWithLifecycle()
    val busy by Recommend.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Recommend.refreshIfStale() }
    val shelves = feed
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    Recommend.offline -> "No connection: showing what's on this phone and your synced library."
                    Library.hasLibrary -> "Picked on this phone from what you play, like and keep in your playlists."
                    else -> "Picked on this phone from what you play. Sync your library (Settings → Library & offline) for better picks."
                },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { Recommend.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh recommendations") }
        }
        when {
            shelves == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shelves.isEmpty() -> Note("Nothing to recommend yet. Search for a song you like and play it: the picks here grow from what you listen to.")
            else -> ShelvesList(shelves, onOpen)
        }
    }
}

/** Your playlists as last synced from your account, for when YouTube Music on the computer isn't there. */
@Composable
private fun SyncedPlaylistsTab(s: RemoteState, onOpen: (SearchItem) -> Unit) {
    val lists by Library.playlists.collectAsStateWithLifecycle()
    val liked by Library.liked.collectAsStateWithLifecycle()
    val at by Library.syncedAt.collectAsStateWithLifecycle()
    val keep by Library.keepOffline.collectAsStateWithLifecycle()
    if (lists.isEmpty() && liked.isEmpty()) {
        NeedsComputer(s, "Your playlists come from your YouTube Music account. Connect once with YouTube Music open on the computer and they're synced here, to use anywhere.")
        return
    }
    val cards = remember(lists, liked) {
        val l = ArrayList<SearchItem>()
        if (liked.isNotEmpty()) l.add(SearchItem("Liked music", "${liked.size} songs", liked.first().art, null, "LM", "playlist"))
        l.addAll(lists.map { it.toSearchItem() })
        l
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Synced " + ago(at) + if (s.online && !s.ytm) ". Open YouTube Music on the computer to sync again." else ".",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(cards.size, key = { cards[it].playlistId ?: cards[it].title }) { i ->
                val p = cards[i]
                Column(Modifier.clickable { onOpen(p) }) {
                    Thumb(p.thumb, 150, 18)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (p.playlistId in keep) Icon(Icons.Filled.DownloadDone, "Kept on this phone", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "3 hours ago", "yesterday"... for when something last happened. */
fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "yesterday"
        else -> "${m / (24 * 60)} days ago"
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
                    FilledTonalButton(onClick = { playOnPhone(savedItems, savedItems.indices.random(), shuffle = true) }) { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text(" Shuffle") }
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
private fun ResultRow(item: SearchItem, all: List<SearchItem>, downloaded: Boolean) {
    val playable = item.videoId != null || item.playlistId != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = playable || item.kind == "artist") {
                if (item.kind == "artist") Detail.open(item)
                else if (item.videoId != null) Remote.playItem(item)      // on the phone, its radio follows (as in YouTube Music)
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
        if (downloaded) {
            Icon(Icons.Filled.DownloadDone, "Downloaded", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (item.videoId != null && item.kind == "song") {
            SongMenuButton(item)
        } else if (item.playlistId != null) {
            IconButton(onClick = { Remote.startPlaylist(item.playlistId, true) }) { Icon(Icons.Filled.Shuffle, "Shuffle play") }
        }
    }
}
