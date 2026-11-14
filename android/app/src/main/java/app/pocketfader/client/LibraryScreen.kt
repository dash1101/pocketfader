package app.pocketfader.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

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
    // when the shelves arrive (or change), each shelf's title and then its cards glide in, one after another
    val arrival = rememberPageArrival(shelves)
    LazyColumn(Modifier.fillMaxSize().edgeFade(homeState), state = homeState) {
        itemsIndexed(shelves, key = { i, it -> "$i-${it.title}" }) { _, shelf ->
            Column {
                PageSectionTitle(shelf.title, Modifier.arrive(arrival).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(shelf.items, key = { i, it -> (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind + i }) { _, card ->
                        PageCoverTile(
                            card.thumb, card.title, card.subtitle, onClick = { activate(card, onOpen) },
                            modifier = Modifier.width(136.dp), arrival = arrival, round = card.kind == "artist", sizeDp = 136,
                            badge = if (card.kind == "radio") Icons.Filled.Radio else null,
                        )
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
    // the covers glide in once the playlists are there (and again when the list changes)
    val arrival = rememberPageArrival(lists)
    Column(Modifier.fillMaxSize()) {
        PageHeaderTonal(Icons.Filled.Add, "New playlist", onClick = { creating = true }, modifier = Modifier.padding(16.dp))
        when {
            lists == null && err != null -> Message(err)
            lists == null -> Loading()
            lists.isEmpty() -> Message("No playlists yet.")
            else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().edgeFade({ gridState.canScrollBackward }, { gridState.canScrollForward }),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(lists, key = { it.playlistId ?: it.browseId ?: it.title }) { p ->
                    // the cover fills its cell, so there's no gap beside it on wider screens
                    PageCoverTile(p.thumb, p.title, p.subtitle, onClick = { onOpen(p) }, arrival = arrival, sizeDp = 170)
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
        val key = pid ?: item.browseId ?: ("t:" + item.title)
        // what is known already shows at once (the list as it was last time, or your synced copy); the current list
        // follows and takes its place
        val quick = withContext(Dispatchers.IO) { PlaylistCache.get(key) ?: syncedCopy()?.map { it.toSearchItem() } }
        if (quick != null) tracks = quick
        try {
            val fresh = withContext(Dispatchers.IO) {
                val copy = syncedCopy()
                var failure: Exception? = null
                // the account, through the computer: the playlist as it is now. Opened for the first time, its first page
                // is shown as soon as it's there and the rest follows.
                val live: List<SearchItem>? = if (Remote.state.value.ytm) {
                    try {
                        if (quick == null) {
                            val first = Remote.playlistItems(item)
                            if (first.isNotEmpty()) withContext(Dispatchers.Main) { if (tracks == null) tracks = first }
                        }
                        Remote.playlistItems(item, limit = 1000)
                    } catch (e: Exception) { failure = e; null }
                } else null
                when {
                    live != null -> live
                    copy != null -> copy.map { it.toSearchItem() }
                    // a public playlist or album works without the account
                    pid != null && !pid.startsWith("MPRE") -> Streams.playlist(pid).map { it.toSearchItem() }
                    else -> throw failure ?: ApiException(0, "Albums open through YouTube Music on the computer, which isn't connected.")
                }
            }
            if (!editing && fresh != tracks) tracks = fresh
            withContext(Dispatchers.IO) { PlaylistCache.put(key, fresh) }
        } catch (e: Exception) {
            if (tracks == null) {
                val copy = syncedCopy()
                if (copy != null) tracks = copy.map { it.toSearchItem() } else error = e.friendly()
            }
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
            ShapeIconButton(Icons.Filled.ArrowBack, "Back", onClick = onBack)
            Text(
                title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (editable) {
                Box {
                    ShapeIconButton(Icons.Filled.MoreVert, "More", onClick = { menu = true }, active = menu)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; dialog = "rename" })
                        DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; dialog = "delete" })
                    }
                }
            }
        }
        val list = tracks
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // the header and then the tracks glide in once the list is there (at once when it was known already); a list
        // that is brought up to date afterwards stays still
        val arrival = rememberPageArrival(item, list != null)
        val playingSong by rememberPlayingSong()
        when {
            list == null && error != null -> Message(error ?: "")
            list == null -> Loading()
            list.isEmpty() -> Message("Nothing in here yet.")
            else -> LazyColumn(Modifier.fillMaxSize().edgeFade(listState), state = listState) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().arrive(arrival).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(item.thumb ?: list.firstOrNull()?.thumb, 200, 24)
                        // who it's by (an album's artist and year, a playlist's owner), when the list we came from said so
                        val by: String? = item.subtitle.takeIf { it.isNotBlank() && !it.first().isDigit() }
                        if (by != null) {
                            Text(
                                by, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (list.size == 1) "1 track" else "${list.size} tracks",
                            Modifier.padding(top = if (by != null) 2.dp else 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // (with Edit there are four things here: on a narrow phone the row scrolls sideways instead of squeezing the last)
                        Row(
                            Modifier.padding(top = 14.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // on the phone, the songs already shown here are played; on the computer, YouTube Music plays the playlist
                            val songs = remember(list) { list.mapNotNull { it.toLocal() } }
                            // played on the phone when playing there, or when the computer's YouTube Music can't play it
                            val here = Remote.local || !Remote.state.value.ytm
                            // an album found by search has no playlist id of its own: its first track carries the album's
                            val first = list.firstOrNull { it.videoId != null }
                            val albumId = pid?.takeIf { !it.startsWith("MPRE") } ?: first?.playlistId
                            val canPlay = songs.isNotEmpty() || (!here && (albumId != null || first != null))
                            PageHeaderPlay(
                                onClick = {
                                    val vid = first?.videoId
                                    if (here) playOnPhone(songs, 0)
                                    else if (albumId != null) Remote.startPlaylist(albumId, false)
                                    else if (vid != null) Remote.play(vid)
                                    AppNav.showPlayer()
                                },
                                enabled = canPlay,
                            )
                            PageHeaderTonal(
                                Icons.Filled.Shuffle, "Shuffle",
                                onClick = {
                                    if (here) playOnPhone(songs, songs.indices.random(), shuffle = true) else albumId?.let { Remote.startPlaylist(it, true) }
                                    AppNav.showPlayer()
                                },
                                enabled = if (here) songs.isNotEmpty() else albumId != null,
                            )
                            // the whole playlist or album, kept together on the phone as an offline playlist (in its order)
                            val saveId = pid ?: albumId
                            val offline by OfflineLists.lists.collectAsStateWithLifecycle()
                            val kept = saveId != null && offline.any { it.id == "pl:$saveId" }
                            PageHeaderIcon(
                                if (kept) Icons.Filled.DownloadDone else Icons.Filled.Download,
                                "Download all and keep this playlist on the phone",
                                onClick = {
                                    if (saveId != null) OfflineLists.saveFrom(saveId, title, item.thumb ?: list.firstOrNull()?.thumb, songs)
                                    else Downloads.enqueue(songs)
                                },
                                enabled = songs.isNotEmpty(), on = kept,
                            )
                            if (editable) {
                                // a round button: "Edit" in words made this row too wide for a phone
                                PageHeaderIcon(
                                    if (editing) Icons.Filled.Check else Icons.Filled.Edit, if (editing) "Done editing" else "Edit playlist",
                                    onClick = { editing = !editing }, on = editing,
                                )
                            }
                        }
                        if (canKeep && pid != null) {
                            // like YouTube Music's download toggle: the whole playlist stays on the phone, new songs included
                            Row(
                                Modifier.padding(top = 12.dp).clip(PfShape.Full).clickable { Library.setKeepOffline(pid, pid !in keep) }
                                    .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
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
                    PageSongRow(
                        t.thumb, t.title, t.subtitle, onClick = { Remote.playItem(t, list, pid) },
                        enabled = !editing && t.videoId != null, arrival = arrival,
                        current = t.videoId != null && t.videoId == playingSong.first, playing = playingSong.second,
                    ) {
                        if (editing) {
                            val tint = LocalContentColor.current
                            ShapeIconButton(Icons.Filled.KeyboardArrowUp, "Move up", onClick = { move(i, -1) }, tint = tint, enabled = i > 0)
                            ShapeIconButton(Icons.Filled.KeyboardArrowDown, "Move down", onClick = { move(i, 1) }, tint = tint, enabled = i < list.size - 1)
                            ShapeIconButton(Icons.Filled.Close, "Remove", onClick = { remove(i) }, tint = tint)
                        } else {
                            SongMenuButton(t)
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(16.dp)) }
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

// ---------------------------------------------------------------------------------------------
// Refine's look for the lists and pages (search, library, artists, albums, playlists, what's on this phone): titles on
// one scale, rows with a rounded highlight, cover tiles that dip when pressed, and a page header's accent play button
// with tonal buttons beside it. Colours come from the theme (the cover that plays, with Album colours on).

/**
 * [rememberArrival] for a list or a page, or none while screen transitions are switched off in the settings: its rows
 * then simply appear (they also stay still with "Reduce animations", which [arrive] checks itself).
 */
@Composable
internal fun rememberPageArrival(vararg keys: Any?): Arrival? {
    val arrival = rememberArrival(*keys)
    val transitions = Settings.prefs.collectAsStateWithLifecycle().value.transitions
    return if (transitions) arrival else null
}

/** A section's title on a page or a list (a shelf, "Downloaded"). */
@Composable
internal fun PageSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** A small section's label: short, in capitals and in the accent colour ("Recent searches"). */
@Composable
internal fun PageSmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The song that plays now (its id, or a file's address for a file on this phone) and whether it plays, to mark its row
 * in a list. It is read through a derived state, so a list is redrawn only when one of the two changes, not each time
 * anything else the computer reports changes.
 */
@Composable
internal fun rememberPlayingSong(): State<Pair<String?, Boolean>> {
    val st = Remote.state.collectAsStateWithLifecycle()
    return remember(st) {
        derivedStateOf(structuralEqualityPolicy<Pair<String?, Boolean>>()) {
            val n = st.value.now
            Pair(n?.videoId, n?.playing == true)
        }
    }
}

/**
 * A song (or album, artist, file) in a list: its cover, its name and a line under it, and [trailing] buttons, which take
 * the row's quiet text colour as their [LocalContentColor]. The row is inset from the screen's edges and its highlight is
 * rounded, as Refine draws YouTube Music's lists; rows change colour when pressed, not size. The song that plays now
 * ([current]) sits on a tonal background, with bars dancing over its cover while it plays.
 */
@Composable
internal fun PageSongRow(
    thumb: String?, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    arrival: Arrival? = null, enabled: Boolean = true, round: Boolean = false, thumbDp: Int = 48,
    current: Boolean = false, playing: Boolean = false, trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().arrive(arrival)
            .padding(horizontal = 8.dp).clip(PfShape.Sm)
            .then(if (current) Modifier.background(scheme.primaryContainer) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Thumb(thumb, thumbDp, if (round) thumbDp / 2 else 8)
            if (current) {
                Box(
                    Modifier.size(thumbDp.dp)
                        .background(scheme.primaryContainer.copy(alpha = 0.72f), if (round) PfShape.Full else PfShape.Xs)
                        .semantics { contentDescription = "Now playing" },
                    contentAlignment = Alignment.Center,
                ) { EqBars(scheme.onPrimaryContainer, playing, Modifier.size(20.dp)) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = if (current) scheme.onPrimaryContainer else Color.Unspecified,
                fontWeight = if (current) FontWeight.SemiBold else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (current) scheme.onPrimaryContainer.copy(alpha = 0.78f) else scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CompositionLocalProvider(LocalContentColor provides (if (current) scheme.onPrimaryContainer else scheme.onSurfaceVariant)) {
            trailing()
        }
    }
}

/**
 * A cover with its name under it (a shelf's card, a playlist in a grid), on the shared corner scale: it fills the width
 * it is given ([sizeDp] is the picture size to load) and dips a little while pressed. [round]: an artist's picture, with
 * the name centred under it. [badge] sits on the cover (a radio's mark); [mark] follows the name (kept on this phone).
 */
@Composable
internal fun PageCoverTile(
    thumb: String?, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    arrival: Arrival? = null, round: Boolean = false, sizeDp: Int = 136, badge: ImageVector? = null,
    mark: ImageVector? = null, markDescription: String? = null,
) {
    val src = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val align = if (round) TextAlign.Center else TextAlign.Start
    Column(
        modifier.arrive(arrival).pressScale(src, 0.96f).clip(PfShape.Md)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(bottom = 6.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box {
            Thumb(thumb, sizeDp, if (round) sizeDp / 2 else 16, Modifier.fillMaxWidth().aspectRatio(1f))
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).background(scheme.primaryContainer, PfShape.Full),
                    contentAlignment = Alignment.Center,
                ) { Icon(badge, null, Modifier.size(18.dp), tint = scheme.onPrimaryContainer) }
            }
        }
        Row(Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align,
            )
            if (mark != null) Icon(mark, markDescription, Modifier.padding(start = 4.dp).size(16.dp), tint = scheme.primary)
        }
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
            )
        }
    }
}

/**
 * The play button of a page's header (an album, a playlist, an artist), as Refine draws YouTube Music's: the accent
 * colour, a soft square that rounds into a circle while it is pressed (or a pointer is over it), and a little dip.
 */
@Composable
internal fun PageHeaderPlay(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String = "Play") {
    val reduce = LocalReduceMotion.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val hovered by src.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    // a springy change of shape, as the player's play button has
    val round by animateFloatAsState(
        if ((pressed || hovered) && enabled) 50f else 30f,
        if (reduce) snap<Float>() else spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "header-play-shape",
    )
    Box(
        modifier.size(56.dp).pressScale(src, 0.92f)
            .clip(RoundedCornerShape(percent = round.roundToInt().coerceIn(0, 100)))
            .background(if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.12f))
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, description, Modifier.size(32.dp), tint = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f))
    }
}

/** A tonal pill beside a header's play button (Shuffle, Radio): it softens into a rounded square while pressed. */
@Composable
internal fun PageHeaderTonal(
    icon: ImageVector?, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val reduce = LocalReduceMotion.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val round by animateFloatAsState(
        if (pressed && enabled) 28f else 50f, tween<Float>(if (reduce) 0 else Motion.Medium, easing = Motion.Ease), label = "header-tonal-shape",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).pressScale(src, 0.96f),
        enabled = enabled,
        shape = RoundedCornerShape(percent = round.roundToInt().coerceIn(0, 100)),
        contentPadding = PaddingValues(start = if (icon != null) 16.dp else 20.dp, end = 20.dp),
        interactionSource = src,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, textAlign = TextAlign.Center)
    }
}

/**
 * A tonal round icon button for a header (download all, edit): it becomes a squircle while pressed, and stays one, in
 * the accent's tone, while [on] (downloaded, editing).
 */
@Composable
internal fun PageHeaderIcon(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, on: Boolean = false,
) {
    val reduce = LocalReduceMotion.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val round by animateFloatAsState(
        if ((pressed || on) && enabled) 30f else 50f, tween<Float>(if (reduce) 0 else Motion.Medium, easing = Motion.Ease), label = "header-icon-shape",
    )
    val bg by animateColorAsState(
        when {
            !enabled -> scheme.onSurface.copy(alpha = 0.12f)
            on -> scheme.primaryContainer
            else -> scheme.secondaryContainer
        },
        tween<Color>(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "header-icon-bg",
    )
    val fg = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        on -> scheme.onPrimaryContainer
        else -> scheme.onSecondaryContainer
    }
    Box(
        modifier.size(48.dp).pressScale(src)
            .clip(RoundedCornerShape(percent = round.roundToInt().coerceIn(0, 100)))
            .background(bg)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = fg)
    }
}
