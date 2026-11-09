package app.pocketfader.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Which artist, album or playlist page is open on top of the current screen (Back closes it). */
object Detail {
    var item by mutableStateOf<SearchItem?>(null)
        private set
    var artist by mutableStateOf<ArtistQuery?>(null)
        private set

    val active: Boolean get() = item != null || artist != null

    fun open(i: SearchItem) {
        artist = null
        item = i
    }

    /** From the player: the artist of the song that is playing (looked up by the song, then by name). */
    fun openArtist(videoId: String?, name: String) {
        item = null
        artist = ArtistQuery(videoId, name)
    }

    fun close() {
        item = null
        artist = null
    }
}

class ArtistQuery(val videoId: String?, val name: String)

@Composable
fun DetailHost(modifier: Modifier = Modifier) {
    BackHandler { Detail.close() }
    val q = Detail.artist
    val d = Detail.item
    when {
        q != null -> ArtistScreen(browseId = null, videoId = q.videoId, name = q.name, onBack = { Detail.close() }, modifier = modifier)
        d != null && d.kind == "artist" -> ArtistScreen(browseId = d.browseId, videoId = null, name = d.title, onBack = { Detail.close() }, modifier = modifier)
        d != null -> PlaylistDetail(d, onBack = { Detail.close() }, modifier = modifier)
    }
}

/** For when there's no YouTube Music tab: asks the computer to open it in its browser (the music then starts from here). */
@Composable
fun OpenYtmButton(modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = { Remote.openYtm() }, modifier = modifier) {
        Row {
            Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp))
            Text("  Open YouTube Music on the computer")
        }
    }
}
