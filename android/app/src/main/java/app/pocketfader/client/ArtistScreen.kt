package app.pocketfader.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * An artist's page put together on the phone from public searches, for when YouTube Music on the computer isn't there:
 * their songs, albums and playlists with their name. Less complete than the real page (no singles or related artists).
 */
private suspend fun phoneArtist(browseId: String?, name: String): ArtistPage = coroutineScope {
    val songsJob = async { Streams.search(name, "songs") }
    val albumsJob = async { runCatching { Streams.search(name, "albums") }.getOrDefault(emptyList()) }
    val listsJob = async { runCatching { Streams.search(name, "playlists") }.getOrDefault(emptyList()) }
    val whoJob = async { runCatching { Streams.search(name, "artists") }.getOrDefault(emptyList()) }
    val songs: List<SearchItem> = songsJob.await()
    val theirs = songs.filter { it.subtitle.contains(name, ignoreCase = true) }
    val top = (if (theirs.size >= 3) theirs else songs).take(10)
    val albums = albumsJob.await().filter { it.subtitle.contains(name, ignoreCase = true) }
    val lists = listsJob.await().take(10)
    val who = whoJob.await()
    val me = who.firstOrNull { browseId != null && it.browseId == browseId }
        ?: who.firstOrNull { it.title.equals(name, ignoreCase = true) }
        ?: who.firstOrNull()
    val shelves = ArrayList<Shelf>()
    if (top.isNotEmpty()) shelves.add(Shelf("Songs", top))
    if (albums.isNotEmpty()) shelves.add(Shelf("Albums", albums))
    if (lists.isNotEmpty()) shelves.add(Shelf("Playlists", lists))
    if (shelves.isEmpty()) throw ApiException(0, "artist_not_found")
    ArtistPage(
        browseId = browseId ?: me?.browseId.orEmpty(),
        name = me?.title ?: name,
        description = "Found by searching from this phone. The full artist page needs YouTube Music open on the computer.",
        thumb = me?.thumb,
        subscribers = null,
        playVideoId = top.firstOrNull()?.videoId,
        playPlaylistId = null,
        radioPlaylistId = null,
        shelves = shelves,
    )
}

/** An artist's page: who they are, play and radio, their top songs, and rows of albums, singles and more. */
@Composable
fun ArtistScreen(browseId: String?, videoId: String?, name: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var page by remember(browseId, videoId, name) { mutableStateOf<ArtistPage?>(null) }
    var error by remember(browseId, videoId, name) { mutableStateOf<String?>(null) }
    LaunchedEffect(browseId, videoId, name) {
        page = null
        error = null
        try {
            page = withContext(Dispatchers.IO) {
                // the real page through the computer; without it (or if that fails), one made on the phone
                val real: ArtistPage? = if (Remote.state.value.ytm) runCatching { Remote.artist(browseId, videoId, name) }.getOrNull() else null
                real ?: phoneArtist(browseId, name)
            }
        } catch (e: Exception) {
            error = if (e.message == "artist_not_found") "Couldn't find that artist on YouTube Music." else e.friendly()
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIconButton(Icons.Filled.ArrowBack, "Back", onClick = onBack)
            Text(
                page?.name?.ifBlank { null } ?: name, Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val p = page
        when {
            error != null -> Text(
                error.orEmpty(), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ArtistContent(p)
        }
    }
}

@Composable
private fun ArtistContent(p: ArtistPage) {
    var expanded by remember(p.browseId) { mutableStateOf(false) }
    val state = rememberLazyListState()
    // once the page has loaded it glides in, row after row: the header, each shelf's title, its songs or its cards
    val arrival = rememberPageArrival(p)
    val playingSong by rememberPlayingSong()
    LazyColumn(Modifier.fillMaxSize().edgeFade(state), state = state) {
        item(key = "head") {
            Column(
                Modifier.fillMaxWidth().arrive(arrival).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Thumb(p.thumb, 112, 56)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        p.subscribers?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                // three buttons don't fit every phone: the row then scrolls sideways instead of squeezing the last one
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    val vid = p.playVideoId
                    val pl = p.playPlaylistId
                    if (vid != null || pl != null) {
                        PageHeaderPlay(onClick = { if (vid != null) Remote.play(vid, pl) else if (pl != null) Remote.startPlaylist(pl, false) })
                    }
                    if (pl != null) {
                        PageHeaderTonal(Icons.Filled.Shuffle, "Shuffle", onClick = { Remote.startPlaylist(pl, true) })
                    }
                    p.radioPlaylistId?.let { r ->
                        PageHeaderTonal(Icons.Filled.Radio, "Radio", onClick = { Remote.startPlaylist(r, false) })
                    }
                }
                p.description?.let { d ->
                    Text(
                        d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 40 else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded }
                            .then(if (LocalReduceMotion.current) Modifier else Modifier.animateContentSize()),
                    )
                }
            }
        }
        for (shelf in p.shelves) {
            item(key = "h-" + shelf.title) {
                PageSectionTitle(
                    shelf.title, Modifier.arrive(arrival).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                )
            }
            if (shelf.items.all { it.kind == "song" && it.videoId != null }) {
                // a list of songs: rows
                items(shelf.items.take(10), key = { "s-" + shelf.title + (it.videoId ?: it.title) }) { song ->
                    PageSongRow(
                        song.thumb, song.title, song.subtitle, onClick = { song.videoId?.let { Remote.play(it) } }, arrival = arrival,
                        current = song.videoId != null && song.videoId == playingSong.first, playing = playingSong.second,
                    ) {
                        // play next, add to the queue or a playlist, download: as everywhere else a song is listed
                        SongMenuButton(song)
                    }
                }
            } else {
                // albums, singles, videos, related artists: a row of cards
                item(key = "r-" + shelf.title) {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shelf.items, key = { (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind }) { card ->
                            PageCoverTile(
                                card.thumb, card.title, card.subtitle, onClick = { activate(card) { Detail.open(it) } },
                                modifier = Modifier.width(136.dp), arrival = arrival, round = card.kind == "artist", sizeDp = 136,
                            )
                        }
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}
