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
    val auto: Boolean = false,   // saved by an automatic download rule (removed again when no rule wants it)
    val quality: String = "",    // best | normal | saver ("": saved before there was a choice)
) {
    fun toLocal() = LocalItem(videoId, File(file).toURI().toString(), title, artist, art, durationSec)
}

/**
 * Saving songs for offline listening. Files live in the app's own music folder (no storage permission needed; removed with
 * the app). The audio comes from the phone's own YouTube lookup, or from the computer when that fails.
 */
object Downloads {
    private lateinit var dir: File
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.quiet)
    private val gate = Semaphore(2)    // two at a time is plenty on a phone

    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = _items
    /** Songs being saved now: video id -> progress 0..1 (0 while it starts). */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        app = ctx.applicationContext
        dir = (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "music")).apply { mkdirs() }
        load()
        // leftovers of downloads that were cut off (the app was closed, the phone restarted)
        scope.launch { dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
    }

    private val index: File get() = File(dir, "downloads.json")

    /** Space left where songs are saved, in bytes (-1 before the app has started). */
    fun freeBytes(): Long = if (::dir.isInitialized) dir.usableSpace else -1L

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
                        f.path, o.str("art"), o.str("lyrics"), f.length(), o.optLong("addedAt"), o.optBoolean("auto"),
                        o.optString("q"),
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
                    .put("file", d.file).put("art", d.art ?: JSONObject.NULL).put("lyrics", d.lyrics ?: JSONObject.NULL).put("addedAt", d.addedAt).put("auto", d.auto).put("q", d.quality)
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

    /**
     * Save these songs (already saved or being saved ones are skipped). [auto]: an automatic download rule asked, not you;
     * those are removed again when no rule wants them. Asking yourself for a song that was saved automatically keeps it for good.
     */
    fun enqueue(songs: List<LocalItem>, auto: Boolean = false) {
        if (!::dir.isInitialized) return
        if (!auto) {
            val ids = songs.mapNotNull { it.videoId }.toHashSet()
            if (_items.value.any { it.auto && it.videoId in ids }) {
                _items.update { list -> list.map { if (it.auto && it.videoId in ids) it.copy(auto = false) else it } }
                scope.launch { save() }
            }
        }
        val todo = songs.filter { isYtId(it.videoId) && !isDownloaded(it.videoId) && it.videoId !in _active.value }.distinctBy { it.videoId }
        if (todo.isEmpty()) return
        _active.update { m -> m + todo.associate { it.videoId!! to 0f } }
        if (!auto || todo.size > 1) {
            Remote.notify(
                if (todo.size == 1) "Downloading “${todo[0].title}”"
                else if (auto) "Downloading ${todo.size} songs for offline listening" else "Downloading ${todo.size} songs"
            )
        }
        DownloadService.start(app)
        for (s in todo) scope.launch { gate.withPermit { saveOne(s, auto, quiet = auto || todo.size > 1) } }
    }

    fun enqueue(song: LocalItem) = enqueue(listOf(song))

    /** True on mobile data (or any connection that may cost money). */
    fun metered(ctx: Context = app): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    private fun saveOne(item: LocalItem, auto: Boolean, quiet: Boolean) {
        if (!_active.value.containsKey(item.videoId)) return         // cancelled while waiting
        val id = item.videoId ?: return
        try {
            // what we know about the song; fill the gaps from YouTube when needed
            val meta = if (item.title.isBlank() || item.artist.isBlank()) runCatching { Streams.details(id) }.getOrNull() ?: item else item
            val quality = Settings.prefs.value.dlQuality
            val src = try { Streams.fromPhone(id, quality) } catch (e: Exception) { Streams.fromComputer(id) ?: throw e }
            val audio = File(dir, "$id.${src.ext}")
            fetch(src.url, src.headers, audio) { p -> _active.update { it + (id to p) } }
            // the cover, as large as YouTube gives it
            val artUrl = bigArt(meta.art ?: item.art)
            val artFile = artUrl?.let { u -> runCatching { File(dir, "$id.jpg").also { fetch(u, emptyMap(), it) {} } }.getOrNull() }
            // the lyrics: from the computer if it's there (more sources, word timing), else straight from LRCLIB
            val lyrics = runCatching { Remote.lyricsJson(meta.title, meta.artist, meta.durationSec, id) }.getOrNull()
                ?: runCatching { Lrclib.lookup(meta.title, meta.artist, meta.durationSec) }.getOrNull()
            val d = Download(
                id, meta.title, meta.artist, meta.durationSec, audio.path, artFile?.toURI()?.toString(),
                lyrics?.toString(), audio.length(), System.currentTimeMillis(), auto, quality,
            )
            _items.update { list -> list.filter { it.videoId != id } + d }
            save()
            if (!quiet) Remote.notify("Downloaded “${meta.title}”")
            done++
        } catch (e: Exception) {
            dir.listFiles()?.filter { it.name.startsWith("$id.") && it.name.endsWith(".part") }?.forEach { it.delete() }
            failed++
            if (!quiet) Remote.notify("Couldn't download “${item.title}”: ${e.friendly()}")
        } finally {
            _active.update { it - id }
            if (_active.value.isEmpty() && quiet && (done > 0 || failed > 0)) {
                Remote.notify(if (failed == 0) "Downloads finished ($done)" else "Downloads finished: $done saved, $failed couldn't be downloaded")
                done = 0; failed = 0
            }
        }
    }

    @Volatile private var done = 0
    @Volatile private var failed = 0

    /** Stop the downloads that haven't started yet (the one in progress finishes). */
    fun cancelPending() { _active.update { m -> m.filterValues { it > 0f } } }

    /**
     * Fetches [url] into [to]. The file is asked for in pieces of a megabyte (Range requests): YouTube serves a whole
     * audio file in one request only at about playback speed, but pieces at full speed. Servers that don't do ranges get
     * one plain request.
     */
    private fun fetch(url: String, headers: Map<String, String>, to: File, progress: (Float) -> Unit) {
        val part = File(to.path + ".part")
        val chunk = 1L shl 20
        var total = -1L
        var done = 0L
        var lastShown = 0L
        part.outputStream().use { out ->
            while (total < 0 || done < total) {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    for ((k, v) in headers) c.setRequestProperty(k, v)
                    c.setRequestProperty("Range", "bytes=$done-${done + chunk - 1}")
                    val code = c.responseCode
                    if (code == 416 && total < 0 && done == 0L) throw java.io.IOException("the file is empty")
                    if (code !in 200..299) throw java.io.IOException("the server answered $code")
                    val whole = code == 200                       // no ranges: this one answer is the whole file
                    if (whole) {
                        if (done > 0) throw java.io.IOException("the server stopped sending pieces")
                        total = c.contentLengthLong
                    } else if (total < 0) {
                        total = c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                    }
                    var got = 0L
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            got += n
                            if (total > 0 && done - lastShown > 256 * 1024) { lastShown = done; progress(done.toFloat() / total) }
                        }
                    }
                    if (whole || total < 0) break                 // one plain answer, or a length we can't know: that was all
                    if (got == 0L) throw java.io.IOException("the download stopped")
                } finally {
                    c.disconnect()
                }
            }
        }
        if (total > 0 && done < total) throw java.io.IOException("the download was cut short")
        if (!part.renameTo(to)) throw java.io.IOException("couldn't save the file")
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
