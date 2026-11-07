package app.pocketfader.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
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

private fun activate(item: SearchItem, onOpen: (SearchItem) -> Unit) {
    val vid = item.videoId
    when {
        item.kind == "song" && vid != null -> Remote.play(vid)
        item.playlistId != null || item.browseId != null -> onOpen(item)
        vid != null -> Remote.play(vid)
    }
}

@Composable
fun LibraryScreen(s: RemoteState, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf<SearchItem?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = opened != null) { opened = null }

    val item = opened
    if (item != null) {
        PlaylistDetail(item, onBack = { opened = null }, modifier = modifier)
        return
    }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Home") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
            }
            IconButton(onClick = {
                scope.launch { if (tab == 0) LibraryStore.refreshHome() else LibraryStore.refreshPlaylists() }
            }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (!s.bridge && (if (tab == 0) LibraryStore.home else LibraryStore.playlists) == null) {
            Message("Your library comes from YouTube Music, so it needs the Pocketfader extension connected on the computer.")
        } else if (tab == 0) {
            HomeTab(onOpen = { opened = it })
        } else {
            PlaylistsTab(onOpen = { opened = it })
        }
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
private fun HomeTab(onOpen: (SearchItem) -> Unit) {
    LaunchedEffect(Unit) { LibraryStore.ensureHome() }
    val shelves = LibraryStore.home
    val err = LibraryStore.homeError
    when {
        shelves == null && err != null -> Message(err)
        shelves == null -> Loading()
        shelves.isEmpty() -> Message("Nothing on your home page yet.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(shelves, key = { it.title }) { shelf ->
                Text(
                    shelf.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    items(shelf.items, key = { (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind }) { card ->
                        Column(Modifier.width(144.dp).padding(horizontal = 4.dp).clickable { activate(card, onOpen) }) {
                            Cover(card.thumb, Modifier.size(136.dp), corner = 14)
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
}

@Composable
private fun PlaylistsTab(onOpen: (SearchItem) -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { LibraryStore.ensurePlaylists() }
    val lists = LibraryStore.playlists
    val err = LibraryStore.playlistsError
    Column(Modifier.fillMaxSize()) {
        FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Text("New playlist", Modifier.padding(start = 8.dp))
        }
        when {
            lists == null && err != null -> Message(err)
            lists == null -> Loading()
            lists.isEmpty() -> Message("No playlists yet.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(lists, key = { it.playlistId ?: it.browseId ?: it.title }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Cover(p.thumb, Modifier.size(56.dp), corner = 12)
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.subtitle.isNotBlank()) {
                                Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
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
private fun PlaylistDetail(item: SearchItem, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var tracks by remember(item) { mutableStateOf<List<SearchItem>?>(null) }
    var error by remember(item) { mutableStateOf<String?>(null) }
    var editing by remember(item) { mutableStateOf(false) }
    var title by remember(item) { mutableStateOf(item.title) }
    var dialog by remember { mutableStateOf("") }          // "", "rename", "delete"
    var menu by remember { mutableStateOf(false) }
    val pid = item.playlistId ?: item.browseId?.removePrefix("VL")

    LaunchedEffect(item) {
        try {
            tracks = withContext(Dispatchers.IO) { Remote.playlistItems(item) }
        } catch (e: Exception) {
            error = e.friendly()
        }
    }
    // Only playlists you own return a setVideoId on their tracks, which is what editing needs.
    val editable = pid != null && tracks.orEmpty().any { it.setVideoId != null }

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
        when {
            list == null && error != null -> Message(error ?: "")
            list == null -> Loading()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(item.thumb, Modifier.size(200.dp), corner = 20)
                        Text("${list.size} tracks", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { pid?.let { Remote.startPlaylist(it, false) } }, enabled = pid != null) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Play", Modifier.padding(start = 6.dp))
                            }
                            FilledTonalButton(onClick = { pid?.let { Remote.startPlaylist(it, true) } }, enabled = pid != null) {
                                Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text("Shuffle", Modifier.padding(start = 6.dp))
                            }
                            if (editable) {
                                FilledTonalButton(onClick = { editing = !editing }) {
                                    Icon(if (editing) Icons.Filled.Check else Icons.Filled.Edit, null, Modifier.size(18.dp))
                                    Text(if (editing) "Done" else "Edit", Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
                itemsIndexed(list, key = { i, t -> "${t.setVideoId ?: t.videoId}-$i" }) { i, t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !editing && t.videoId != null) { t.videoId?.let { Remote.play(it, pid) } }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(t.thumb, Modifier.size(48.dp), corner = 10)
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
