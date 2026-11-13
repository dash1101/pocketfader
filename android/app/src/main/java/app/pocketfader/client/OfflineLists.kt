package app.pocketfader.client

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A playlist kept on this phone: a playlist or album saved whole ("Download all" on its page), or one of your own made
 * from downloaded songs. Its songs are ordinary downloads, so every one of them is also in "Downloaded".
 */
@Immutable
data class OfflineList(
    val id: String,               // "pl:<playlist or album id>" for a saved one, "own:<n>" for yours
    val title: String,
    val thumb: String?,
    val songs: List<LocalItem>,
    val source: String? = null,   // the playlist or album it was saved from (null: one of your own)
    val createdAt: Long = 0L,
) {
    val own: Boolean get() = source == null
    val cover: String? get() = thumb ?: songs.firstOrNull()?.art
}

fun offlineListsToJson(lists: List<OfflineList>): JSONArray = JSONArray().apply {
    for (l in lists) {
        val songs = JSONArray()
        for (s in l.songs) {
            songs.put(
                JSONObject().put("id", s.videoId ?: JSONObject.NULL).put("t", s.title).put("a", s.artist)
                    .put("art", s.art ?: JSONObject.NULL).put("d", s.durationSec).put("al", s.album)
            )
        }
        put(
            JSONObject().put("id", l.id).put("title", l.title).put("thumb", l.thumb ?: JSONObject.NULL)
                .put("source", l.source ?: JSONObject.NULL).put("createdAt", l.createdAt).put("songs", songs)
        )
    }
}

fun offlineListsFromJson(arr: JSONArray): List<OfflineList> = arr.mapObjects { o ->
    OfflineList(
        id = o.str("id") ?: return@mapObjects null,
        title = o.str("title").orEmpty(),
        thumb = o.str("thumb"),
        songs = o.optJSONArray("songs").mapObjects { s ->
            LocalItem(s.str("id"), null, s.optString("t"), s.optString("a"), s.str("art"), s.optDouble("d", 0.0), s.optString("al"))
        }.filter { isYtId(it.videoId) },
        source = o.str("source"),
        createdAt = o.optLong("createdAt"),
    )
}.filterNotNull()

object OfflineLists {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.quiet)
    private val _lists = MutableStateFlow<List<OfflineList>>(emptyList())
    val lists: StateFlow<List<OfflineList>> = _lists

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "offline-playlists.json")
        scope.launch { runCatching { if (file.exists()) _lists.value = offlineListsFromJson(JSONArray(file.readText())) } }
    }

    private fun save() {
        if (!::file.isInitialized) return
        val snapshot = _lists.value
        scope.launch {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(offlineListsToJson(snapshot).toString())
                tmp.renameTo(file)
            }
        }
    }

    fun get(id: String): OfflineList? = _lists.value.firstOrNull { it.id == id }

    fun isSaved(sourceId: String?): Boolean = sourceId != null && get("pl:$sourceId") != null

    /** Download a whole playlist or album and keep it together as an offline playlist. Saving it again brings its songs
     *  up to date (in the playlist's order). */
    fun saveFrom(sourceId: String, title: String, thumb: String?, songs: List<LocalItem>) {
        val wanted = songs.filter { isYtId(it.videoId) }.distinctBy { it.videoId }
        if (wanted.isEmpty()) return
        val id = "pl:$sourceId"
        _lists.update { all ->
            val old = all.firstOrNull { it.id == id }
            val l = OfflineList(id, title, thumb ?: old?.thumb, wanted, sourceId, old?.createdAt ?: System.currentTimeMillis())
            if (old != null) all.map { if (it.id == id) l else it } else listOf(l) + all
        }
        save()
        Downloads.enqueue(wanted)
        Remote.notify("“$title” is kept on this phone, in Search → On this phone")
    }

    /** A new playlist of your own (empty) -> its id. */
    fun create(title: String): String {
        val id = "own:" + System.currentTimeMillis().toString(36)
        _lists.update { listOf(OfflineList(id, title.trim(), null, emptyList(), null, System.currentTimeMillis())) + it }
        save()
        return id
    }

    /** Songs into one of your playlists (downloaded too, if they aren't yet). */
    fun add(id: String, items: List<LocalItem>) {
        val add = items.filter { isYtId(it.videoId) }
        if (add.isEmpty()) return
        _lists.update { all -> all.map { if (it.id != id) it else it.copy(songs = (it.songs + add).distinctBy { s -> s.videoId }) } }
        save()
        Downloads.enqueue(add)
    }

    fun removeSong(id: String, videoId: String) {
        _lists.update { all -> all.map { if (it.id != id) it else it.copy(songs = it.songs.filter { s -> s.videoId != videoId }) } }
        save()
    }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        _lists.update { all -> all.map { if (it.id == id) it.copy(title = title.trim()) else it } }
        save()
    }

    /** Remove a playlist. [withSongs]: also remove its songs from the phone, except those another offline playlist has. */
    fun delete(id: String, withSongs: Boolean) {
        val gone = get(id) ?: return
        _lists.update { all -> all.filter { it.id != id } }
        save()
        if (withSongs) {
            val keep = _lists.value.flatMap { it.songs }.mapNotNull { it.videoId }.toHashSet()
            for (s in gone.songs) s.videoId?.let { v -> if (v !in keep) Downloads.delete(v) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- screens

/** The offline playlists as a row of covers (Search → On this phone), with a button to make a new one. */
@Composable
fun OfflineListsRow(onOpen: (String) -> Unit) {
    val lists by OfflineLists.lists.collectAsStateWithLifecycle()
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    val have = remember(downloads) { downloads.map { it.videoId }.toHashSet() }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Offline playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(" New") }
        }
        if (lists.isEmpty()) {
            Text(
                "“Download all” on a playlist or album keeps it here as a whole, in its order. You can also make your own from " +
                    "the songs you've downloaded.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(lists.size, key = { lists[it].id }) { i ->
                    val l = lists[i]
                    val there = l.songs.count { it.videoId in have }
                    Column(Modifier.width(132.dp).clickable { onOpen(l.id) }) {
                        Thumb(l.cover, 132, 16, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(
                            l.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (there == l.songs.size) "${l.songs.size} songs" else "$there of ${l.songs.size} on the phone",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    if (creating) {
        TextPrompt("New offline playlist", "", "Name", "Create", onDismiss = { creating = false }) { name ->
            creating = false
            onOpen(OfflineLists.create(name))
        }
    }
}

/** One offline playlist: play it, its songs, and (for your own) adding, renaming and removing. */
@Composable
fun OfflineListView(id: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val lists by OfflineLists.lists.collectAsStateWithLifecycle()
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val l = lists.firstOrNull { it.id == id }
    if (l == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }      // it was just deleted
        return
    }
    val have = remember(downloads) { downloads.associateBy { it.videoId } }
    val missing = l.songs.filter { it.videoId !in have && it.videoId !in active }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }          // "", "rename", "delete", "add"
    val state = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text(l.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (l.own) DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; dialog = "rename" })
                    DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; dialog = "delete" })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().edgeFade(state), state = state) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Thumb(l.cover, 180, 20)
                    val bytes = l.songs.sumOf { have[it.videoId]?.size ?: 0L }
                    Text(
                        (if (l.songs.size == 1) "1 song" else "${l.songs.size} songs") + (if (bytes > 0) " · " + formatBytes(bytes) else "") +
                            if (l.own) "" else " · saved from YouTube Music",
                        Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { playOnPhone(l.songs, 0); AppNav.showPlayer() }, enabled = l.songs.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text(" Play")
                        }
                        FilledTonalButton(onClick = { playOnPhone(l.songs, l.songs.indices.random(), shuffle = true); AppNav.showPlayer() }, enabled = l.songs.isNotEmpty()) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text(" Shuffle")
                        }
                        if (l.own) OutlinedButton(onClick = { dialog = "add" }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(" Add") }
                    }
                    if (missing.isNotEmpty()) {
                        TextButton(onClick = { Downloads.enqueue(missing) }, Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Text(" Download ${missing.size} missing")
                        }
                    }
                    if (l.own && l.songs.isEmpty()) {
                        Text(
                            "Add songs you've downloaded, or use ⋮ → Add to an offline playlist on any song.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(l.songs, key = { _, s -> "o-" + s.videoId }) { i, s ->
                Row(
                    Modifier.fillMaxWidth().clickable { playOnPhone(l.songs, i) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Thumb(s.art, 48, 10)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val progress = active[s.videoId]
                    when {
                        s.videoId in have -> Icon(Icons.Filled.DownloadDone, "On this phone", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        progress != null -> androidx.compose.material3.CircularProgressIndicator(
                            progress = { progress }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                        )
                        else -> Icon(Icons.Filled.Download, "Not downloaded yet", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (l.own) IconButton(onClick = { s.videoId?.let { OfflineLists.removeSong(l.id, it) } }) {
                        Icon(Icons.Filled.Close, "Take out of this playlist")
                    } else Spacer(Modifier.width(12.dp))
                }
            }
            item(key = "end") { Spacer(Modifier.padding(24.dp)) }
        }
    }
    when (dialog) {
        "rename" -> TextPrompt("Rename", l.title, "Name", "Rename", onDismiss = { dialog = "" }) { name ->
            dialog = ""
            OfflineLists.rename(l.id, name)
        }
        "delete" -> DeletePrompt(l, onDismiss = { dialog = "" }) { withSongs ->
            dialog = ""
            OfflineLists.delete(l.id, withSongs)
        }
        "add" -> PickDownloadsDialog(exclude = l.songs.mapNotNull { it.videoId }.toSet(), onDismiss = { dialog = "" }) { picked ->
            dialog = ""
            OfflineLists.add(l.id, picked)
        }
    }
}

@Composable
private fun DeletePrompt(l: OfflineList, onDismiss: () -> Unit, onDelete: (withSongs: Boolean) -> Unit) {
    var withSongs by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${l.title}”?") },
        text = {
            Column {
                Text("The playlist goes. Its songs stay in Downloaded unless you remove them too.")
                if (l.songs.isNotEmpty()) Row(Modifier.padding(top = 8.dp).clickable { withSongs = !withSongs }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withSongs, onCheckedChange = { withSongs = it })
                    Text("Also remove its songs from this phone (ones in another offline playlist stay)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDelete(withSongs) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose downloaded songs (newest first) to put in a playlist. */
@Composable
private fun PickDownloadsDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (List<LocalItem>) -> Unit) {
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    val choices = remember(downloads, exclude) { downloads.filter { it.videoId !in exclude }.sortedByDescending { it.addedAt } }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add downloaded songs") },
        text = {
            if (choices.isEmpty()) {
                Text("Every downloaded song is in this playlist already. Download more from search, a playlist or the queue (⋮ → Download).")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(choices.size, key = { choices[it].videoId }) { i ->
                        val d = choices[i]
                        val on = d.videoId in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) picked.remove(d.videoId) else picked.add(d.videoId) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (it) picked.add(d.videoId) else picked.remove(d.videoId) })
                            Column(Modifier.weight(1f)) {
                                Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(choices.filter { it.videoId in picked }.map { it.toLocal().copy(uri = null) }) },
                enabled = picked.isNotEmpty(),
            ) { Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** ⋮ → Add to an offline playlist: one of your own, or a new one. The song is downloaded if it isn't yet. */
@Composable
fun AddToOfflineDialog(song: LocalItem, onDone: () -> Unit) {
    val lists by OfflineLists.lists.collectAsStateWithLifecycle()
    val own = lists.filter { it.own }
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextPrompt("New offline playlist", "", "Name", "Create", onDismiss = { creating = false }) { name ->
            val id = OfflineLists.create(name)
            OfflineLists.add(id, listOf(song))
            Remote.notify("Added to “$name”")
            onDone()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to an offline playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item(key = "new") {
                    Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, Modifier.padding(end = 12.dp))
                        Text("New offline playlist", fontWeight = FontWeight.SemiBold)
                    }
                }
                items(own.size, key = { own[it].id }) { i ->
                    val l = own[i]
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            OfflineLists.add(l.id, listOf(song))
                            Remote.notify("Added to “${l.title}”")
                            onDone()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(l.cover, 40, 8)
                        Text(l.title, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
