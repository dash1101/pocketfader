package app.pocketfader.client

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A song saved on the phone: the audio, its cover and its lyrics, playable with no connection. */
@Immutable
data class Download(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Double,
    val file: String,
    val art: String?,
    val lyrics: String?,     // the lyrics reply (same shape as the computer's /api/lyrics), saved as text
    val size: Long,
    val addedAt: Long,
) {
    fun toLocal() = LocalItem(videoId, File(file).toURI().toString(), title, artist, art, durationSec)
}

/**
 * Saving songs for offline listening. Files live in the app's own music folder (no storage permission needed; removed with
 * the app). The audio comes from the phone's own YouTube lookup, or from the computer when that fails.
 */
object Downloads {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)    // two at a time is plenty on a phone

    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = _items
    /** Songs being saved now: video id -> progress 0..1 (0 while it starts). */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "music")).apply { mkdirs() }
        load()
    }

    private val index: File get() = File(dir, "downloads.json")

    private fun load() {
        val list = ArrayList<Download>()
        runCatching {
            val arr = JSONArray(index.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = File(o.optString("file"))
                if (!f.exists()) continue
                list.add(
                    Download(
                        o.optString("videoId"), o.optString("title"), o.optString("artist"), o.optDouble("duration", 0.0),
                        f.path, o.str("art"), o.str("lyrics"), f.length(), o.optLong("addedAt"),
                    )
                )
            }
        }
        _items.value = list
    }

    @Synchronized private fun save() {
        val arr = JSONArray()
        for (d in _items.value) {
            arr.put(
                JSONObject().put("videoId", d.videoId).put("title", d.title).put("artist", d.artist).put("duration", d.durationSec)
                    .put("file", d.file).put("art", d.art ?: JSONObject.NULL).put("lyrics", d.lyrics ?: JSONObject.NULL).put("addedAt", d.addedAt)
            )
        }
        val tmp = File(dir, "downloads.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    fun fileFor(videoId: String): File? {
        if (!::dir.isInitialized) return null
        return _items.value.firstOrNull { it.videoId == videoId }?.let { File(it.file) }?.takeIf { it.exists() }
    }

    fun isDownloaded(videoId: String?): Boolean = videoId != null && _items.value.any { it.videoId == videoId }

    fun lyricsFor(videoId: String): LyricsResult? =
        _items.value.firstOrNull { it.videoId == videoId }?.lyrics?.let { runCatching { parseLyrics(JSONObject(it)) }.getOrNull() }

    val totalBytes: Long get() = _items.value.sumOf { it.size }

    /** Save these songs (already saved or being saved ones are skipped). */
    fun enqueue(songs: List<LocalItem>) {
        val todo = songs.filter { isYtId(it.videoId) && !isDownloaded(it.videoId) && it.videoId !in _active.value }
        if (todo.isEmpty()) return
        _active.update { m -> m + todo.associate { it.videoId!! to 0f } }
        Remote.notify(if (todo.size == 1) "Downloading “${todo[0].title}”" else "Downloading ${todo.size} songs")
        for (s in todo) scope.launch { gate.withPermit { saveOne(s) } }
    }

    fun enqueue(song: LocalItem) = enqueue(listOf(song))

    private fun saveOne(item: LocalItem) {
        val id = item.videoId ?: return
        try {
            // what we know about the song; fill the gaps from YouTube when needed
            val meta = if (item.title.isBlank() || item.artist.isBlank()) runCatching { Streams.details(id) }.getOrNull() ?: item else item
            val src = try { Streams.fromPhone(id) } catch (e: Exception) { Streams.fromComputer(id) ?: throw e }
            val audio = File(dir, "$id.${src.ext}")
            fetch(src.url, src.headers, audio) { p -> _active.update { it + (id to p) } }
            // the cover, as large as YouTube gives it
            val artUrl = (meta.art ?: item.art)?.let { bigCover(it) }
            val artFile = artUrl?.let { u -> runCatching { File(dir, "$id.jpg").also { fetch(u, emptyMap(), it) {} } }.getOrNull() }
            // the lyrics: from the computer if it's there (more sources, word timing), else straight from LRCLIB
            val lyrics = runCatching { Remote.lyricsJson(meta.title, meta.artist, meta.durationSec, id) }.getOrNull()
                ?: runCatching { Lrclib.lookup(meta.title, meta.artist, meta.durationSec) }.getOrNull()
            val d = Download(
                id, meta.title, meta.artist, meta.durationSec, audio.path, artFile?.toURI()?.toString(),
                lyrics?.toString(), audio.length(), System.currentTimeMillis(),
            )
            _items.update { list -> list.filter { it.videoId != id } + d }
            save()
            Remote.notify("Downloaded “${meta.title}”")
        } catch (e: Exception) {
            File(dir, "$id.part").delete()
            Remote.notify("Couldn't download “${item.title}”: ${e.friendly()}")
        } finally {
            _active.update { it - id }
        }
    }

    /** Google image addresses carry a size; ask for a big one. */
    private fun bigCover(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w544-h544").replace(Regex("=s\\d+[^&?]*"), "=s544")

    private fun fetch(url: String, headers: Map<String, String>, to: File, progress: (Float) -> Unit) {
        val part = File(to.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("the server answered $code")
            val total = c.contentLengthLong
            var done = 0L
            var lastShown = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastShown > 256 * 1024) { lastShown = done; progress(done.toFloat() / total) }
                    }
                }
            }
            if (!part.renameTo(to)) throw java.io.IOException("couldn't save the file")
        } finally {
            c.disconnect()
        }
    }

    fun delete(videoId: String) {
        val d = _items.value.firstOrNull { it.videoId == videoId } ?: return
        File(d.file).delete()
        d.art?.let { runCatching { File(java.net.URI(it)).delete() } }
        _items.update { list -> list.filter { it.videoId != videoId } }
        scope.launch { save() }
    }

    fun deleteAll() {
        for (d in _items.value) {
            File(d.file).delete()
            d.art?.let { runCatching { File(java.net.URI(it)).delete() } }
        }
        _items.value = emptyList()
        scope.launch { save() }
    }
}
