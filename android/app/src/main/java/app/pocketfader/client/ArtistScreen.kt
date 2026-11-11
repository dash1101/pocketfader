package app.pocketfader.client

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text(page?.name?.ifBlank { null } ?: name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    LazyColumn(Modifier.fillMaxSize().edgeFade(state), state = state) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Thumb(p.thumb, 104, 52)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        p.subscribers?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val vid = p.playVideoId
                    val pl = p.playPlaylistId
                    if (vid != null || pl != null) {
                        Button(onClick = { if (vid != null) Remote.play(vid, pl) else if (pl != null) Remote.startPlaylist(pl, false) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp))
                            Text(" Play")
                        }
                    }
                    if (pl != null) {
                        FilledTonalButton(onClick = { Remote.startPlaylist(pl, true) }) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.width(18.dp))
                            Text(" Shuffle")
                        }
                    }
                    p.radioPlaylistId?.let { r ->
                        FilledTonalButton(onClick = { Remote.startPlaylist(r, false) }) {
                            Icon(Icons.Filled.Radio, null, Modifier.width(18.dp))
                            Text(" Radio")
                        }
                    }
                }
                p.description?.let { d ->
                    Text(
                        d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 40 else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }
        }
        for (shelf in p.shelves) {
            item(key = "h-" + shelf.title) {
                Text(
                    shelf.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            if (shelf.items.all { it.kind == "song" && it.videoId != null }) {
                // a list of songs: rows
                items(shelf.items.take(10), key = { "s-" + shelf.title + (it.videoId ?: it.title) }) { song ->
                    Row(
                        Modifier.fillMaxWidth().clickable { song.videoId?.let { Remote.play(it) } }.padding(horizontal = 16.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Thumb(song.thumb, 48, 8)
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                // albums, singles, videos, related artists: a row of cards
                item(key = "r-" + shelf.title) {
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                        items(shelf.items, key = { (it.videoId ?: it.playlistId ?: it.browseId ?: it.title) + it.kind }) { card ->
                            Column(Modifier.width(144.dp).padding(horizontal = 4.dp).clickable { activate(card) { Detail.open(it) } }) {
                                Thumb(card.thumb, 136, if (card.kind == "artist") 68 else 14)
                                Text(card.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (card.subtitle.isNotBlank()) {
                                    Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}
