package app.pocketfader.client

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Which artist, album or playlist page is open on top of the current screen (Back closes it). */
object Detail {
    var item by mutableStateOf<SearchItem?>(null)
        private set
    var artist by mutableStateOf<ArtistQuery?>(null)
        private set

    val active: Boolean get() = item != null || artist != null

    // the pages this one was opened from (an artist, then one of their albums): Back goes through them
    private val behind = ArrayList<Pair<SearchItem?, ArtistQuery?>>()

    private fun keepCurrent() {
        if (!active) return
        behind.add(Pair(item, artist))
        if (behind.size > 12) behind.removeAt(0)
    }

    fun open(i: SearchItem) {
        if (i == item) return
        keepCurrent()
        artist = null
        item = i
    }

    /** From the player: the artist of the song that is playing (looked up by the song, then by name). */
    fun openArtist(videoId: String?, name: String) {
        keepCurrent()
        item = null
        artist = ArtistQuery(videoId, name)
    }

    /** Back: the page this one was opened from, or out of the pages altogether. */
    fun back() {
        if (behind.isEmpty()) { close(); return }
        val last = behind.removeAt(behind.size - 1)
        item = last.first
        artist = last.second
    }

    fun close() {
        behind.clear()
        item = null
        artist = null
    }
}

class ArtistQuery(val videoId: String?, val name: String)

@Composable
fun DetailHost(modifier: Modifier = Modifier) {
    BackHandler { Detail.back() }
    val q = Detail.artist
    val d = Detail.item
    // each page arrives with a short fade and a small rise (off with "Reduce motion" or screen transitions turned off)
    val still = LocalReduceMotion.current || !Settings.prefs.value.transitions
    val enter = remember(q, d) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(enter) { if (enter.value < 1f) enter.animateTo(1f, tween(Motion.Short, easing = Motion.Ease)) }
    val arriving = modifier.graphicsLayer {
        val t = enter.value
        alpha = t
        translationY = (1f - t) * 14.dp.toPx()
    }
    when {
        q != null -> ArtistScreen(browseId = null, videoId = q.videoId, name = q.name, onBack = { Detail.back() }, modifier = arriving)
        d != null && d.kind == "artist" -> ArtistScreen(browseId = d.browseId, videoId = null, name = d.title, onBack = { Detail.back() }, modifier = arriving)
        d != null -> PlaylistDetail(d, onBack = { Detail.back() }, modifier = arriving)
    }
}

/** For when there's no YouTube Music tab: asks the computer to open it in its browser (the music then starts from here). */
@Composable
fun OpenYtmButton(modifier: Modifier = Modifier) {
    PageHeaderTonal(Icons.Filled.OpenInNew, "Open YouTube Music on the computer", onClick = { Remote.openYtm() }, modifier = modifier)
}
