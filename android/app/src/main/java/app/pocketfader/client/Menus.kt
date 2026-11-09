package app.pocketfader.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The "⋮" menu on any song: play, queue it next or at the end, add to a playlist, start a radio. */
@Composable
fun SongMenuButton(item: SearchItem, modifier: Modifier = Modifier) {
    val vid = item.videoId ?: return
    var open by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play now") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { open = false; Remote.playItem(item) },
            )
            DropdownMenuItem(
                text = { Text("Play next") }, leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
                onClick = { open = false; Remote.queueAdd(item, next = true) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { open = false; Remote.queueAdd(item, next = false) },
            )
            if (Remote.state.value.ytm) DropdownMenuItem(
                text = { Text("Add to playlist…") }, leadingIcon = { Icon(Icons.Filled.LibraryMusic, null) },
                onClick = { open = false; addToPlaylist = true },
            )
            if (Downloads.isDownloaded(vid)) DropdownMenuItem(
                text = { Text("Remove download") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { open = false; Downloads.delete(vid); Remote.notify("Removed from this phone") },
            ) else DropdownMenuItem(
                text = { Text("Download") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = { open = false; item.toLocal()?.let { Downloads.enqueue(it) } },
            )
            DropdownMenuItem(
                text = { Text("Start radio") }, leadingIcon = { Icon(Icons.Filled.Radio, null) },
                onClick = { open = false; Remote.startMix(vid) },
            )
        }
    }
    if (addToPlaylist) AddToPlaylistDialog(item) { addToPlaylist = false }
}

/** Pick one of your playlists (or make a new one) to put [item] in. */
@Composable
fun AddToPlaylistDialog(item: SearchItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { LibraryStore.ensurePlaylists() }
    val lists = LibraryStore.playlists.orEmpty().filter {
        val id = it.playlistId ?: it.browseId?.removePrefix("VL")
        id != null && id != "LM" && id != "SE"
    }
    if (creating) {
        TextPrompt(
            title = "New playlist", initial = "", label = "Playlist name", confirm = "Create",
            onDismiss = { creating = false },
        ) { name ->
            creating = false
            onDone()
            scope.launch {
                val ok = LibraryStore.attempt {
                    Remote.playlistOp(
                        JSONObject().put("type", "playlistCreate").put("title", name)
                            .put("videoIds", JSONArray().put(item.videoId ?: ""))
                    ).optBoolean("ok")
                }
                Remote.notify(if (ok) "Created “$name”" else "Couldn't create the playlist")
                if (ok) LibraryStore.refreshPlaylists()
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Text(item.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, Modifier.size(20.dp))
                    Text("New playlist…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
                if (LibraryStore.playlists == null) {
                    Text("Loading your playlists…", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(lists, key = { it.playlistId ?: it.browseId ?: it.title }) { p ->
                        Text(
                            p.title,
                            Modifier.fillMaxWidth().clickable {
                                onDone()
                                scope.launch {
                                    val id = p.playlistId ?: p.browseId?.removePrefix("VL") ?: return@launch
                                    val ok = LibraryStore.attempt {
                                        Remote.playlistOp(
                                            JSONObject().put("type", "playlistAdd").put("playlistId", id)
                                                .put("videoId", item.videoId ?: "")
                                        ).optBoolean("ok")
                                    }
                                    Remote.notify(if (ok) "Added to ${p.title}" else "Couldn't add to ${p.title}")
                                }
                            }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** A one-field dialog (rename, new playlist, …). */
@Composable
fun TextPrompt(
    title: String,
    initial: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
