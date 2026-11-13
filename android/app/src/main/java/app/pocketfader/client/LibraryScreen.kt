package app.pocketfader.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** In-memory copy of what the Library shows, so switching tabs doesn't reload it. */
object LibraryStore {
    var playlists by mutableStateOf<List<SearchItem>?>(null)
    var home by mutableStateOf<List<Shelf>?>(null)
    var playlistsError by mutableStateOf<String?>(null)
    var homeError by mutableStateOf<String?>(null)
    private var loadingPlaylists = false
    private var loadingHome = false

    fun clear() { playlists = null; home = null; playlistsError = null; homeError = null }

    suspend fun ensurePlaylists() { if (playlists == null) refreshPlaylists() }
    suspend fun ensureHome() { if (home == null) refreshHome() }

    suspend fun refreshPlaylists() {
        if (loadingPlaylists) return
        loadingPlaylists = true
        playlistsError = null
        try {
            playlists = withContext(Dispatchers.IO) { Remote.playlists() }
        } catch (e: Exception) {
            playlistsError = e.friendly()
        }
        loadingPlaylists = false
    }

    suspend fun refreshHome() {
        if (loadingHome) return
        loadingHome = true
        homeError = null
        try {
            home = withContext(Dispatchers.IO) { Remote.home() }
        } catch (e: Exception) {
            homeError = e.friendly()
        }
        loadingHome = false
    }

    /** Runs a change on the IO thread; false (and a message) when it failed. */
    suspend fun attempt(block: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: Exception) {
            Remote.notify(e.friendly())
            false
        }
    }
}

internal fun activate(item: SearchItem, onOpen: (SearchItem) -> Unit) {
    val vid = item.videoId
    when {
        item.kind == "artist" -> Detail.open(item)
        item.kind == "radio" && vid != null -> Remote.startMix(vid)
        item.kind == "song" && vid != null -> Remote.playItem(item)
        item.playlistId != null || item.browseId != null -> onOpen(item)
        vid != null -> Remote.playItem(item)
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun HomeTab(onOpen: (SearchItem) -> Unit) {
    LaunchedEffect(Unit) { LibraryStore.ensureHome() }
    val shelves = LibraryStore.home
    val err = LibraryStore.homeError
    when {
        shelves == null && err != null -> Message(err)
        shelves == null -> Loading()
        shelves.isEmpty() -> Message("Nothing on your home page yet.")
        else -> ShelvesList(shelves, onOpen)
    }
}

/** Rows of cards, one row per shelf (a home page). */
@Composable
internal fun ShelvesList(shelves: List<Shelf>, onOpen: (SearchItem) -> Unit) {
    val homeState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().edgeFade(homeState), state = homeState) {
            itemsIndexed(shelves, key = { i, it -> "$i-${it.title}" }) { _, shelf ->
                Text(
                    shelf.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    itemsIndexed(shelf.items, key = { i, it -> (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind + i }) { _, card ->
                        Column(Modifier.width(144.dp).padding(horizontal = 4.dp).clickable { activate(card, onOpen) }) {
                            Box {
                                Thumb(card.thumb, 136, if (card.kind == "artist") 68 else 14)
                                if (card.kind == "radio") Icon(
                                    Icons.Filled.Radio, null,
                                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(22.dp),
                                    tint = androidx.compose.ui.graphics.Color.White,
                                )
                            }
                            Text(card.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (card.subtitle.isNotBlank()) {
                                Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun PlaylistsTab(onOpen: (SearchItem) -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { LibraryStore.ensurePlaylists() }
    val lists = LibraryStore.playlists
    val err = LibraryStore.playlistsError
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Text("New playlist", Modifier.padding(start = 8.dp))
        }
        when {
            lists == null && err != null -> Message(err)
            lists == null -> Loading()
            lists.isEmpty() -> Message("No playlists yet.")
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().edgeFade({ gridState.canScrollBackward }, { gridState.canScrollForward }),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(lists, key = { it.playlistId ?: it.browseId ?: it.title }) { p ->
                    Column(Modifier.clickable { onOpen(p) }) {
                        // the cover fills its cell, so there's no gap beside it on wider screens
                        Thumb(p.thumb, 170, 18, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(p.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (p.subtitle.isNotBlank()) {
                            Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        TextPrompt("New playlist", "", "Playlist name", "Create", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch {
                val ok = LibraryStore.attempt {
                    Remote.playlistOp(JSONObject().put("type", "playlistCreate").put("title", name)).optBoolean("ok")
                }
                Remote.notify(if (ok) "Created “$name”" else "Couldn't create the playlist")
                if (ok) LibraryStore.refreshPlaylists()
            }
        }
    }
}

@Composable
internal fun PlaylistDetail(item: SearchItem, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var tracks by remember(item) { mutableStateOf<List<SearchItem>?>(null) }
    var error by remember(item) { mutableStateOf<String?>(null) }
    var editing by remember(item) { mutableStateOf(false) }
    var title by remember(item) { mutableStateOf(item.title) }
    var dialog by remember { mutableStateOf("") }          // "", "rename", "delete"
    var menu by remember { mutableStateOf(false) }
    val pid = item.playlistId ?: item.browseId?.removePrefix("VL")

    // the copy synced to this phone (your liked songs, or one of your playlists)
    fun syncedCopy(): List<LocalItem>? = when {
        pid == null -> null
        pid == "LM" -> Library.liked.value.takeIf { it.isNotEmpty() }
        else -> Library.playlist(pid)?.tracks
    }
    LaunchedEffect(item) {
        try {
            tracks = withContext(Dispatchers.IO) {
                val copy = syncedCopy()
                var failure: Exception? = null
                // the account, through the computer: the playlist as it is now
                val live: List<SearchItem>? = if (Remote.state.value.ytm) {
                    try { Remote.playlistItems(item, limit = 1000) } catch (e: Exception) { failure = e; null }
                } else null
                when {
                    live != null -> live
                    copy != null -> copy.map { it.toSearchItem() }
                    // a public playlist or album works without the account
                    pid != null && !pid.startsWith("MPRE") -> Streams.playlist(pid).map { it.toSearchItem() }
                    else -> throw failure ?: ApiException(0, "Albums open through YouTube Music on the computer, which isn't connected.")
                }
            }
        } catch (e: Exception) {
            val copy = syncedCopy()
            if (copy != null) tracks = copy.map { it.toSearchItem() } else error = e.friendly()
        }
    }
    // Only playlists you own return a setVideoId on their tracks, which is what editing needs (and the account must be there).
    val editable = pid != null && Remote.state.value.ytm && tracks.orEmpty().any { it.setVideoId != null }
    val keep by Library.keepOffline.collectAsStateWithLifecycle()
    val canKeep = pid != null && (pid == "LM" || Library.playlist(pid) != null)

    fun move(i: Int, dir: Int) {
        val list = tracks ?: return
        val j = i + dir
        if (j !in list.indices || pid == null) return
        val moved = list[i]
        val successor = if (dir < 0) list[j] else list.getOrNull(j + 1)   // the track that ends up right after it
        tracks = list.toMutableList().also { it.removeAt(i); it.add(j, moved) }
        scope.launch {
            val ok = LibraryStore.attempt {
                val b = JSONObject().put("type", "playlistMove").put("playlistId", pid).put("setVideoId", moved.setVideoId ?: "")
                successor?.setVideoId?.let { b.put("before", it) }
                Remote.playlistOp(b).optBoolean("ok")
            }
            if (!ok) { tracks = list; Remote.notify("Couldn't move that track") } else LibraryStore.playlists = null
        }
    }

    fun remove(i: Int) {
        val list = tracks ?: return
        if (pid == null) return
        val gone = list[i]
        tracks = list.toMutableList().also { it.removeAt(i) }
        scope.launch {
            val ok = LibraryStore.attempt {
                Remote.playlistOp(
                    JSONObject().put("type", "playlistRemove").put("playlistId", pid)
                        .put("setVideoId", gone.setVideoId ?: "").put("videoId", gone.videoId ?: "")
                ).optBoolean("ok")
            }
            if (!ok) { tracks = list; Remote.notify("Couldn't remove it") } else { Remote.notify("Removed from playlist"); LibraryStore.playlists = null }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editable) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; dialog = "rename" })
                        DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; dialog = "delete" })
                    }
                }
            }
        }
        val list = tracks
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        when {
            list == null && error != null -> Message(error ?: "")
            list == null -> Loading()
            list.isEmpty() -> Message("Nothing in here yet.")
            else -> LazyColumn(Modifier.fillMaxSize().edgeFade(listState), state = listState) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(item.thumb ?: list.firstOrNull()?.thumb, 200, 20)
                        // who it's by (an album's artist and year, a playlist's owner), when the list we came from said so
                        val by: String? = item.subtitle.takeIf { it.isNotBlank() && !it.first().isDigit() }
                        if (by != null) {
                            Text(
                                by, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (list.size == 1) "1 track" else "${list.size} tracks",
                            Modifier.padding(top = if (by != null) 2.dp else 10.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // (with Edit there are four things here: on a narrow phone the row scrolls sideways instead of squeezing the last)
                        Row(
                            Modifier.padding(top = 12.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // on the phone, the songs already shown here are played; on the computer, YouTube Music plays the playlist
                            val songs = remember(list) { list.mapNotNull { it.toLocal() } }
                            // played on the phone when playing there, or when the computer's YouTube Music can't play it
                            val here = Remote.local || !Remote.state.value.ytm
                            // an album found by search has no playlist id of its own: its first track carries the album's
                            val first = list.firstOrNull { it.videoId != null }
                            val albumId = pid?.takeIf { !it.startsWith("MPRE") } ?: first?.playlistId
                            val canPlay = songs.isNotEmpty() || (!here && (albumId != null || first != null))
                            Button(
                                onClick = {
                                    val vid = first?.videoId
                                    if (here) playOnPhone(songs, 0)
                                    else if (albumId != null) Remote.startPlaylist(albumId, false)
                                    else if (vid != null) Remote.play(vid)
                                },
                                enabled = canPlay,
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Play", Modifier.padding(start = 6.dp))
                            }
                            FilledTonalButton(
                                onClick = { if (here) playOnPhone(songs, songs.indices.random(), shuffle = true) else albumId?.let { Remote.startPlaylist(it, true) } },
                                enabled = if (here) songs.isNotEmpty() else albumId != null,
                            ) {
                                Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text("Shuffle", Modifier.padding(start = 6.dp))
                            }
                            // the whole playlist or album, kept together on the phone as an offline playlist (in its order)
                            val saveId = pid ?: albumId
                            val offline by OfflineLists.lists.collectAsStateWithLifecycle()
                            IconButton(
                                onClick = {
                                    if (saveId != null) OfflineLists.saveFrom(saveId, title, item.thumb ?: list.firstOrNull()?.thumb, songs)
                                    else Downloads.enqueue(songs)
                                },
                                enabled = songs.isNotEmpty(),
                            ) {
                                Icon(
                                    if (saveId != null && offline.any { it.id == "pl:$saveId" }) Icons.Filled.DownloadDone else Icons.Filled.Download,
                                    "Download all and keep this playlist on the phone",
                                )
                            }
                            if (editable) {
                                // a round button: "Edit" in words made this row too wide for a phone
                                androidx.compose.material3.FilledTonalIconButton(onClick = { editing = !editing }) {
                                    Icon(if (editing) Icons.Filled.Check else Icons.Filled.Edit, if (editing) "Done editing" else "Edit playlist")
                                }
                            }
                        }
                        if (canKeep && pid != null) {
                            // like YouTube Music's download toggle: the whole playlist stays on the phone, new songs included
                            Row(
                                Modifier.padding(top = 10.dp).clickable { Library.setKeepOffline(pid, pid !in keep) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Keep on this phone", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.Switch(checked = pid in keep, onCheckedChange = { Library.setKeepOffline(pid, it) })
                            }
                            if (pid in keep && Settings.prefs.value.dlWifiOnly && Downloads.metered()) {
                                Text("Downloads start on Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                itemsIndexed(list, key = { i, t -> "${t.setVideoId ?: t.videoId}-$i" }) { i, t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !editing && t.videoId != null) { Remote.playItem(t, list, pid) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Thumb(t.thumb, 48, 10)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (editing) {
                            IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { move(i, 1) }, enabled = i < list.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                            IconButton(onClick = { remove(i) }) { Icon(Icons.Filled.Close, "Remove") }
                        } else {
                            SongMenuButton(t)
                        }
                    }
                }
            }
        }
    }

    if (dialog == "rename") {
        TextPrompt("Rename playlist", title, "Name", "Rename", onDismiss = { dialog = "" }) { name ->
            dialog = ""
            scope.launch {
                val ok = LibraryStore.attempt {
                    Remote.playlistOp(JSONObject().put("type", "playlistRename").put("playlistId", pid ?: "").put("name", name)).optBoolean("ok")
                }
                if (ok) { title = name; LibraryStore.playlists = null; Remote.notify("Renamed") } else Remote.notify("Couldn't rename it")
            }
        }
    }
    if (dialog == "delete") {
        ConfirmDialog("Delete this playlist?", "“$title” will be removed from your YouTube Music library. This can't be undone.", "Delete",
            onDismiss = { dialog = "" }) {
            dialog = ""
            scope.launch {
                val ok = LibraryStore.attempt {
                    Remote.playlistOp(JSONObject().put("type", "playlistDelete").put("playlistId", pid ?: "")).optBoolean("ok")
                }
                if (ok) { LibraryStore.playlists = null; Remote.notify("Playlist deleted"); onBack() } else Remote.notify("Couldn't delete it")
            }
        }
    }
}
