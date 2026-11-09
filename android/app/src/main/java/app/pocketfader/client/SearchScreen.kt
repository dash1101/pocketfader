package app.pocketfader.client

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(s: RemoteState, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        busy = true
        error = null
        focus.clearFocus()
        scope.launch {
            try {
                results = withContext(Dispatchers.IO) { Remote.search(q) }
                searched = true
            } catch (e: Exception) {
                error = e.friendly()
            }
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search YouTube Music") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; results = emptyList(); searched = false; error = null }) {
                        Icon(Icons.Filled.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
        )

        when {
            busy -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Note(error!!)
            !s.bridge -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Note("Search uses the YouTube Music extension on the computer. It isn't connected right now.")
                if (s.online) OpenYtmButton()
            }
            searched && results.isEmpty() -> Note("No results.")
            // nothing typed yet: what YouTube Music recommends (the same picks as your home page)
            !searched && query.isBlank() -> Column(Modifier.fillMaxSize()) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { Remote.command(org.json.JSONObject().put("type", "lucky")) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("🎲  I'm feeling lucky") }
                HomeTab(onOpen = { Detail.open(it) })
            }
            else -> LazyColumn(Modifier.fillMaxSize().edgeFade(resultsState), state = resultsState) {
                items(results, key = { (it.videoId ?: it.playlistId ?: it.title) + it.kind }) { item -> ResultRow(item) }
            }
        }
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

@Composable
private fun ResultRow(item: SearchItem) {
    val playable = item.videoId != null || item.playlistId != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = playable || item.kind == "artist") {
                if (item.kind == "artist") Detail.open(item)
                else if (item.videoId != null) Remote.play(item.videoId, item.playlistId.takeIf { item.kind != "song" })
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
        if (item.videoId != null && item.kind == "song") {
            SongMenuButton(item)
        } else if (item.playlistId != null) {
            IconButton(onClick = { Remote.startPlaylist(item.playlistId, true) }) { Icon(Icons.Filled.Shuffle, "Shuffle play") }
        }
    }
}
