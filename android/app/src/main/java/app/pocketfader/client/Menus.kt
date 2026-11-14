package app.pocketfader.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Menus and dialogs follow the shared corner scale (docs/DESIGN.md): dialogs are the roundest surface (PfShape.Xl) on a
// raised tonal colour; menus get PfShape.Md from the theme (Theme.kt gives Material's menus, which use its smallest
// corner, that size), so they are not given a shape here.

/** The "⋮" menu on any song: play, queue it next or at the end, add to a playlist, start a radio. */
@Composable
fun SongMenuButton(item: SearchItem, modifier: Modifier = Modifier) {
    val vid = item.videoId ?: return
    var open by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    var addOffline by remember { mutableStateOf(false) }
    Box(modifier) {
        // round, a squircle while pressed, and a tonal one while its menu is open; it takes the colour of the row it is in
        ShapeIconButton(Icons.Filled.MoreVert, "More", onClick = { open = true }, tint = LocalContentColor.current, active = open)
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
            if (Remote.state.value.ytm || Library.playlists.value.any { it.owned }) DropdownMenuItem(
                text = { Text("Add to playlist…") }, leadingIcon = { Icon(Icons.Filled.LibraryMusic, null) },
                onClick = { open = false; addToPlaylist = true },
            )
            DropdownMenuItem(
                text = { Text("Add to an offline playlist…") }, leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) },
                onClick = { open = false; addOffline = true },
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
    if (addOffline) item.toLocal()?.let { song -> AddToOfflineDialog(song) { addOffline = false } }
}

/** Pick one of your playlists (or make a new one) to put [item] in. */
@Composable
fun AddToPlaylistDialog(item: SearchItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    // without the account at hand, your own synced playlists take it, and the account gets it at the next sync
    val online = remember { Remote.state.value.ytm }
    LaunchedEffect(Unit) { if (online) LibraryStore.ensurePlaylists() }
    val lists = if (online) LibraryStore.playlists.orEmpty().filter {
        val id = it.playlistId ?: it.browseId?.removePrefix("VL")
        id != null && id != "LM" && id != "SE"
    } else Library.playlists.value.filter { it.owned }.map { it.toSearchItem() }
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
        shape = PfShape.Xl,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Text(
                    item.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (online) PageDialogRow("New playlist…", onClick = { creating = true }, modifier = Modifier.padding(top = 8.dp), icon = Icons.Filled.Add)
                if (online && LibraryStore.playlists == null) {
                    Text("Loading your playlists…", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(lists, key = { it.playlistId ?: it.browseId ?: it.title }) { p ->
                        PageDialogRow(
                            p.title, withThumb = true, thumb = p.thumb,
                            onClick = {
                                onDone()
                                if (!online) {
                                    // your synced copy takes it now; YouTube Music at the next sync
                                    val id = p.playlistId
                                    if (id != null) {
                                        item.toLocal()?.let { Library.addToPlaylistLater(id, it) }
                                        Remote.notify("Added to ${p.title}. YouTube Music gets it at the next sync.")
                                    }
                                } else {
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
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** A one-field dialog (rename, new playlist, …). The keyboard's Done key confirms too. */
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
        shape = PfShape.Xl,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = PfShape.Md,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // with nothing typed, Done just closes the keyboard, as it did before
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onConfirm(text.trim()) else defaultKeyboardAction(ImeAction.Done) },
                ),
            )
        },
        confirmButton = { PageDialogConfirm(confirm, onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = PfShape.Xl,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { PageDialogConfirm(confirm, onClick = onConfirm, danger = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dialog's main action: a tonal pill, in the error colour's tone when it removes something ([danger]). */
@Composable
internal fun PageDialogConfirm(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (danger) {
            ButtonDefaults.filledTonalButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) { Text(text) }
}

/**
 * A choice in a dialog (a playlist, "New playlist…"), with Refine's rounded highlight. [icon]: an action, on a tonal
 * square; [withThumb]: a cover beside the name ([thumb], or a blank tile when it has none).
 */
@Composable
internal fun PageDialogRow(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    thumb: String? = null, withThumb: Boolean = false, icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(PfShape.Sm).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).background(scheme.secondaryContainer, PfShape.Xs), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = scheme.onSecondaryContainer)
            }
        } else if (withThumb) {
            Thumb(thumb, 40, 8)
        }
        Text(
            title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (icon != null) FontWeight.SemiBold else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
