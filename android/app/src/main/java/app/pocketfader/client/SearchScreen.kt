package app.pocketfader.client

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the search screen had, kept while you look at other screens. */
private object SearchMemory {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchItem>>(emptyList())
    var searched by mutableStateOf(false)
    var filter by mutableStateOf("")        // "" (everything), "songs", "artists", "albums" or "playlists"
    var tab by mutableIntStateOf(0)
    var index = 0                           // where the results were scrolled to
    var offset = 0
}

private val FILTERS: List<Pair<String, String>> = listOf(
    "" to "All", "songs" to "Songs", "artists" to "Artists", "albums" to "Albums", "playlists" to "Playlists",
)

/** The library tabs under the search box. */
private val TABS = listOf("For you", "Playlists", "This phone")

private fun resultKey(it: SearchItem): String = (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind

/** Rows that can be played or opened, each once (the list is keyed by what a row is, so a repeat can't be shown). */
private fun usable(list: List<SearchItem>): List<SearchItem> {
    val seen = HashSet<String>()
    return list.filter { (it.videoId != null || it.playlistId != null || it.kind == "artist" || it.kind == "album") && seen.add(resultKey(it)) }
}

/** The phone's own search. Everything: the artist you named, then songs with a few albums and playlists among them. */
private suspend fun phoneSearch(q: String, filter: String): List<SearchItem> {
    if (filter.isNotEmpty()) return Streams.search(q, filter)
    return coroutineScope {
        val songsJob = async { Streams.search(q, "songs") }
        val artistsJob = async { runCatching { Streams.search(q, "artists") }.getOrDefault(emptyList()) }
        val albumsJob = async { runCatching { Streams.search(q, "albums") }.getOrDefault(emptyList()) }
        val listsJob = async { runCatching { Streams.search(q, "playlists") }.getOrDefault(emptyList()) }
        val songs: List<SearchItem> = songsJob.await()
        val out = ArrayList<SearchItem>()
        // an artist comes first only when the search names them
        out.addAll(
            artistsJob.await().filter { a ->
                (a.title.length >= 2 && q.contains(a.title, ignoreCase = true)) || (q.length >= 3 && a.title.startsWith(q, ignoreCase = true))
            }.take(1)
        )
        out.addAll(songs.take(6))
        out.addAll(albumsJob.await().take(3))
        out.addAll(listsJob.await().take(3))
        out.addAll(songs.drop(6))
        out
    }
}

/** With the computer: your account's search. Without it, or if that fails or has nothing of the kind asked for: the phone's own. */
private suspend fun searchFor(q: String, filter: String, ytm: Boolean): List<SearchItem> {
    if (ytm) {
        val found: List<SearchItem>? = runCatching { Remote.search(q, filter.ifEmpty { null }) }.getOrNull()
        if (found != null) {
            if (filter.isEmpty()) return usable(found)
            // an older extension ignores the filter and answers with everything: keep the kind that was asked for
            val want = when (filter) { "artists" -> "artist"; "albums" -> "album"; "playlists" -> "playlist"; else -> "song" }
            val only = usable(found.filter { it.kind == want })
            if (only.isNotEmpty()) return only
        }
    }
    return usable(phoneSearch(q, filter))
}

/**
 * Search and your library in one place: type to search; otherwise the tabs show what's recommended for you, your
 * playlists, and what's on this phone (downloads and music files). Without the computer's YouTube Music the
 * recommendations are made on the phone and the playlists come from the last sync.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)   // the chip and text field colour defaults
@Composable
fun SearchScreen(s: RemoteState, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(SearchMemory.query)) }
    val results = SearchMemory.results
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val searched = SearchMemory.searched
    val tab = SearchMemory.tab
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val recent by Library.recentSearches.collectAsStateWithLifecycle()
    val saved by Downloads.items.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.videoId } }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    // opening an album or artist replaces this screen; coming back, the results are where you left them
    val resultsState = rememberLazyListState(SearchMemory.index, SearchMemory.offset)
    DisposableEffect(resultsState) {
        onDispose {
            SearchMemory.index = resultsState.firstVisibleItemIndex
            SearchMemory.offset = resultsState.firstVisibleItemScrollOffset
        }
    }

    fun clear() {
        query = androidx.compose.ui.text.input.TextFieldValue("")
        SearchMemory.query = ""; SearchMemory.results = emptyList(); SearchMemory.searched = false; SearchMemory.filter = ""
        SearchMemory.index = 0; SearchMemory.offset = 0
        error = null; suggestions = emptyList()
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
            var fresh = false
            try {
                // with the computer: your account's search; without it (or if that fails): the phone's own
                val filter = SearchMemory.filter
                val found = withContext(Dispatchers.IO) { searchFor(q, filter, s.ytm) }
                SearchMemory.results = found
                SearchMemory.searched = true
                SearchMemory.index = 0; SearchMemory.offset = 0
                fresh = found.isNotEmpty()
            } catch (e: Exception) {
                error = e.friendly()
            }
            busy = false
            // a new answer starts at its top (asked only once the list is on screen again: before that it would wait forever)
            if (fresh) kotlinx.coroutines.withTimeoutOrNull(1500) { resultsState.scrollToItem(0) }
        }
    }

    // Back leaves the results (or what was typed) for the library tabs before it leaves the app
    androidx.activity.compose.BackHandler(enabled = searched || query.text.isNotEmpty()) { clear(); focus.clearFocus() }

    // suggestions while typing (a short pause first, so not every letter asks)
    LaunchedEffect(query.text, focused) {
        val q = query.text.trim()
        if (!focused || q.isEmpty() || (searched && q == SearchMemory.query)) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        suggestions = withContext(Dispatchers.IO) { runCatching { Remote.suggest(q) }.getOrDefault(emptyList()) }
    }

    Column(modifier.fillMaxSize()) {
        // Refine's search box: a pill on a tonal surface, with no outline until it is in use
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; if (it.text.isEmpty() && searched) clear() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.isFocused },
            placeholder = { Text("Search YouTube Music") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.text.isNotEmpty() || searched) {
                    ShapeIconButton(Icons.Filled.Close, "Clear", onClick = { clear() }, sizeDp = 44, iconDp = 22, tint = scheme.onSurfaceVariant)
                }
            },
            singleLine = true,
            shape = PfShape.Full,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = scheme.surfaceContainerHigh,
                unfocusedContainerColor = scheme.surfaceContainerHigh,
                focusedBorderColor = scheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        val typing = focused && query.text.isNotBlank() && !(searched && query.text.trim() == SearchMemory.query)
        val filter = SearchMemory.filter
        // what to look for: shown once something was searched, and not over the suggestions while typing. Five kinds
        // don't fit a pill bar, so they stay a row of chips that scrolls, the chosen one a tonal pill
        if (SearchMemory.query.isNotEmpty() && !typing && !(focused && query.text.isBlank())) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((id, label) in FILTERS) {
                    val chosen = filter == id
                    FilterChip(
                        selected = chosen, shape = chipShape(chosen),
                        onClick = { if (filter != id && !busy) { SearchMemory.filter = id; doSearch(SearchMemory.query) } },
                        label = { Text(label) },
                        colors = refineChipColors(),
                        border = refineChipBorder(chosen),
                    )
                }
            }
        }
        // looking again (another kind, another search): what's there stays in place under a thin bar, instead of
        // the whole list giving way to a spinner and coming back
        val refreshing = busy && searched && results.isNotEmpty()
        if (searched && results.isNotEmpty() && !typing) {
            // (its room is always there, so the list doesn't hop when the bar comes and goes)
            Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
            }
        }
        when {
            busy && !refreshing -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            typing && suggestions.isNotEmpty() -> {
                // the first suggestions glide in; later ones, as you go on typing, just appear
                val arrival = rememberPageArrival()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(suggestions, key = { "s-$it" }) { sug -> TextRow(sug, Icons.Filled.Search, onClick = { doSearch(sug) }, arrival = arrival) }
                }
            }
            focused && query.text.isBlank() && recent.isNotEmpty() -> {
                val arrival = rememberPageArrival()
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "recent-h") {
                        Row(
                            Modifier.fillMaxWidth().arrive(arrival).padding(start = 16.dp, end = 8.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PageSmallLabel("Recent searches", Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { Library.forgetSearches() }) { Text("Clear") }
                        }
                    }
                    items(recent, key = { "r-$it" }) { q -> TextRow(q, Icons.Filled.History, onClick = { doSearch(q) }, arrival = arrival) }
                }
            }
            error != null -> Note(error ?: "")
            searched && results.isEmpty() -> Note(
                when (filter) {
                    "songs" -> "No songs found for that."
                    "artists" -> "No artists found for that."
                    "albums" -> "No albums found for that."
                    "playlists" -> "No playlists found for that."
                    else -> "No results."
                }
            )
            searched -> {
                // the rows glide in one after another each time an answer arrives (another search, another kind)
                val arrival = rememberPageArrival(results)
                val nowId = s.now?.videoId
                val nowPlaying = s.now?.playing == true
                LazyColumn(Modifier.fillMaxSize().edgeFade(resultsState), state = resultsState) {
                    items(results, key = { resultKey(it) }) { item ->
                        ResultRow(
                            item, item.videoId in savedIds, arrival,
                            current = item.videoId != null && item.videoId == nowId, playing = nowPlaying,
                        )
                    }
                }
            }
            else -> {
                PillTabs(
                    TABS, tab, onSelect = { SearchMemory.tab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        PageHeaderTonal(
                            Icons.Filled.Casino, "I'm feeling lucky", onClick = { Remote.lucky() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
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

/** A suggestion or a recent search: a line with an icon, on Refine's rounded highlight. */
@Composable
private fun TextRow(text: String, icon: ImageVector, onClick: () -> Unit, arrival: Arrival? = null) {
    Row(
        Modifier.fillMaxWidth().arrive(arrival)
            .padding(horizontal = 8.dp).clip(PfShape.Sm).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
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
            else ShapeIconButton(Icons.Filled.Refresh, "Refresh recommendations", onClick = { Recommend.refresh() }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // the covers glide in when the tab opens (and again after a sync). Keyed on the sync, not on the cards: a like
    // changes the "Liked music" card's count, and that card shouldn't glide in again each time
    val arrival = rememberPageArrival(at)
    Column(Modifier.fillMaxSize()) {
        Text(
            "Synced " + ago(at) + if (s.online && !s.ytm) ". Open YouTube Music on the computer to sync again." else ".",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().edgeFade({ gridState.canScrollBackward }, { gridState.canScrollForward }),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cards.size, key = { cards[it].playlistId ?: cards[it].title }) { i ->
                val p = cards[i]
                PageCoverTile(
                    p.thumb, p.title, p.subtitle, onClick = { onOpen(p) }, arrival = arrival, sizeDp = 170,
                    mark = if (p.playlistId in keep) Icons.Filled.DownloadDone else null, markDescription = "Kept on this phone",
                )
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

/** Downloads and the music files on this phone, with the offline playlists at the top. */
@Composable
private fun DeviceTab() {
    var openList by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val open = openList
    if (open != null) {
        OfflineListView(open, onBack = { openList = null })
        return
    }
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf<LocalItem?>(null) }
    adding?.let { song -> AddToOfflineDialog(song) { adding = null } }
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
    // what is here glides in when the tab opens; the music files, read a moment later, glide in when they are there
    val arrival = rememberPageArrival()
    val filesArrival = rememberPageArrival(files != null)
    val playingSong by rememberPlayingSong()
    LazyColumn(Modifier.fillMaxSize().edgeFade(state), state = state) {
        item(key = "offline-lists") { OfflineListsRow(onOpen = { openList = it }, arrival = arrival) }
        item(key = "dl-h") {
            Column(Modifier.fillMaxWidth().arrive(arrival).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) {
                PageSectionTitle("Downloaded · ${saved.size}")
                Text(
                    if (saved.isEmpty()) "Save songs from search, a playlist or the queue (⋮ → Download) to play them with no connection."
                    else "Plays with no connection, lyrics included. " + formatBytes(saved.sumOf { it.size }) +
                        Downloads.freeBytes().let { f -> if (f >= 0) " · " + formatBytes(f) + " free on the phone" else "" },
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val free = Downloads.freeBytes()
                if (free in 0 until 1_073_741_824L) {
                    Text(
                        "The phone is almost full (${formatBytes(free)} free): downloads stop when it is.",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (saved.isNotEmpty()) Row(
                    Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageHeaderPlay(onClick = { playOnPhone(savedItems, 0); AppNav.showPlayer() }, description = "Play downloads")
                    PageHeaderTonal(
                        Icons.Filled.Shuffle, "Shuffle",
                        onClick = { playOnPhone(savedItems, savedItems.indices.random(), shuffle = true); AppNav.showPlayer() },
                    )
                }
            }
        }
        if (active.isNotEmpty()) item(key = "dl-active") {
            DownloadsInProgress(Modifier.arrive(arrival).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        itemsIndexed(saved, key = { _, d -> "dl-" + d.videoId }) { i, d ->
            PageSongRow(
                d.art, d.title, d.artist, onClick = { playOnPhone(savedItems, i) }, arrival = arrival,
                current = d.videoId == playingSong.first, playing = playingSong.second,
            ) {
                val tint = LocalContentColor.current
                ShapeIconButton(Icons.Filled.PlaylistAdd, "Add to an offline playlist", onClick = { adding = d.toLocal().copy(uri = null) }, tint = tint)
                ShapeIconButton(Icons.Filled.Delete, "Remove download", onClick = { Downloads.delete(d.videoId) }, tint = tint)
            }
        }
        item(key = "files-h") {
            PageSectionTitle(
                "Music on this phone", Modifier.arrive(arrival).padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
            )
        }
        val f = files
        when {
            !granted -> item(key = "files-ask") {
                Column(Modifier.fillMaxWidth().arrive(arrival).padding(16.dp)) {
                    Text("YTM Starcast needs your permission to see the music files on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PageHeaderTonal(null, "Allow access to music", onClick = { ask.launch(DeviceMusic.permission) }, modifier = Modifier.padding(top = 10.dp))
                }
            }
            f == null -> item(key = "files-load") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            f.isEmpty() -> item(key = "files-none") { Note("No music files found on this phone.") }
            else -> itemsIndexed(f, key = { _, it -> "f-" + it.key }) { i, it ->
                // a file playing on this phone is known by its address (the key), as it has no YouTube id
                PageSongRow(
                    it.art, it.title, listOf(it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString(" · "),
                    onClick = { playOnPhone(f, i) }, arrival = filesArrival,
                    current = it.key == playingSong.first, playing = playingSong.second,
                )
            }
        }
        item(key = "end") { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

/** A search result: a song plays (on the phone its radio follows, as in YouTube Music); an artist, album or playlist opens its page. */
@Composable
private fun ResultRow(item: SearchItem, downloaded: Boolean, arrival: Arrival?, current: Boolean, playing: Boolean) {
    val opens = item.videoId != null || item.playlistId != null || item.browseId != null
    PageSongRow(
        item.thumb, item.title, item.subtitle.ifBlank { item.kind.replaceFirstChar { it.uppercase() } },
        onClick = { activate(item) { Detail.open(it) } }, enabled = opens, arrival = arrival,
        round = item.kind == "artist", thumbDp = 52, current = current, playing = playing,
    ) {
        if (downloaded) {
            Icon(Icons.Filled.DownloadDone, "Downloaded", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        val pl = item.playlistId
        if (item.videoId != null && item.kind == "song") {
            SongMenuButton(item)
        } else if (pl != null) {
            ShapeIconButton(Icons.Filled.PlayArrow, "Play", onClick = { Remote.startPlaylist(pl, false) }, tint = LocalContentColor.current)
        }
    }
}
