package app.pocketfader.client

import android.os.SystemClock
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.extractor.MediaFormat as NpFormat

/** A song as the phone's own player sees it: a YouTube Music song (by id) or a file / content URI. */
@androidx.compose.runtime.Immutable
data class LocalItem(
    val videoId: String?,
    val uri: String?,
    val title: String,
    val artist: String,
    val art: String?,
    val durationSec: Double = 0.0,
    val album: String = "",
) {
    val key: String get() = videoId ?: uri.orEmpty()
}

private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** True for a YouTube video id (not a file or content address). */
fun isYtId(s: String?): Boolean = s != null && YT_ID.matches(s)

fun SearchItem.toLocal(): LocalItem? {
    val id = videoId ?: return null
    // search subtitles look like "Song · Artist · Album · 3:12"; keep the artist part
    val parts = subtitle.split(" • ", " · ").map { it.trim() }.filter { it.isNotBlank() }
    val artist = parts.firstOrNull { it !in setOf("Song", "Video", "Single", "EP", "Album") && !Regex("^\\d+:\\d+$").matches(it) }.orEmpty()
    return LocalItem(id, null, title, artist, thumb)
}

fun Track.toLocal(): LocalItem? {
    val id = videoId ?: return null
    if (!isYtId(id)) return null
    return LocalItem(id, null, title, artist, art)
}

/**
 * YouTube Music on the phone itself, through NewPipe Extractor: search, the audio address of a song, related songs (for
 * autoplay) and public playlists. If the phone can't get a song's audio, the computer is asked instead (when one is set up).
 */
object Streams {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    @Volatile private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(PhoneDownloader, Localization("en", "US"))
                ready = true
            }
        }
    }

    /** NewPipe's network access, done with plain HttpURLConnection (no extra HTTP library). */
    private object PhoneDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            try {
                c.requestMethod = request.httpMethod()
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", UA)
                for ((k, vs) in request.headers()) for (v in vs) c.addRequestProperty(k, v)
                val body = request.dataToSend()
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val headers = HashMap<String, List<String>>()
                for ((k, v) in c.headerFields) if (k != null) headers[k] = v
                return Response(code, c.responseMessage, headers, text, c.url.toString())
            } finally {
                c.disconnect()
            }
        }
    }

    /** Where to fetch a song's audio from, and the headers to send. */
    class Resolved(val url: String, val headers: Map<String, String> = emptyMap(), val mime: String? = null, val ext: String = "m4a")

    private val cache = ConcurrentHashMap<String, Pair<Resolved, Long>>()
    private const val TTL = 3 * 3600_000L   // YouTube's audio addresses last about six hours

    /** The audio of a song: a downloaded copy if there is one, else the phone's own lookup, else the computer. Blocking. */
    fun resolve(videoId: String): Resolved {
        Downloads.fileFor(videoId)?.let { return Resolved(android.net.Uri.fromFile(it).toString()) }
        cache[videoId]?.let { (r, at) -> if (SystemClock.elapsedRealtime() - at < TTL) return r }
        val r = try {
            fromPhone(videoId)
        } catch (e: Exception) {
            fromComputer(videoId) ?: throw e
        }
        cache[videoId] = r to SystemClock.elapsedRealtime()
        return r
    }

    fun forget(videoId: String) { cache.remove(videoId) }

    fun fromPhone(videoId: String): Resolved {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        // AAC (m4a) plays and saves everywhere; Opus only if there's nothing else
        val best = streams.filter { it.format == NpFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: streams.maxByOrNull { it.averageBitrate }
            ?: throw IOException("No audio for this song")
        val ext = if (best.format == NpFormat.M4A) "m4a" else "webm"
        return Resolved(best.content, mime = best.format?.mimeType, ext = ext)
    }

    /** The computer fetches the song with yt-dlp and hands it over (only when a computer is set up and supports it). */
    fun fromComputer(videoId: String): Resolved? {
        val a = Remote.computerApi() ?: return null
        if (!Remote.state.value.canAudio) return null
        return Resolved(a.urlFor("/api/media/audio/$videoId"), a.authHeaders(), ext = "m4a")
    }

    // ---- browsing YouTube Music without the computer

    private fun bestThumb(item: InfoItem): String? = item.thumbnails.maxByOrNull { it.height }?.url

    private fun idOf(url: String): String? = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    private fun StreamInfoItem.toSearchItem(): SearchItem? {
        val id = idOf(url) ?: return null
        val dur = if (duration > 0) formatTime(duration.toDouble()) else ""
        return SearchItem(
            title = name, subtitle = listOf(uploaderName.orEmpty(), dur).filter { it.isNotBlank() }.joinToString(" · "),
            thumb = bestThumb(this), videoId = id, playlistId = null, kind = "song",
        )
    }

    fun search(query: String): List<SearchItem> {
        init()
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf("music_songs"), "")
        ex.fetchPage()
        return ex.initialPage.items.mapNotNull { (it as? StreamInfoItem)?.toSearchItem() }
    }

    /** Songs YouTube relates to this one: what autoplay continues with. */
    fun related(videoId: String): List<LocalItem> {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        return info.relatedItems.mapNotNull { (it as? StreamInfoItem)?.toSearchItem()?.toLocal() }
    }

    /** A public playlist's songs (private ones, like your liked music, need the computer). */
    fun playlist(playlistId: String): List<LocalItem> {
        init()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/playlist?list=$playlistId")
        return info.relatedItems.mapNotNull { (it as? StreamInfoItem)?.toSearchItem()?.toLocal() }
    }

    /** Title, artist and cover of a song, when all we have is its id. */
    fun details(videoId: String): LocalItem {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        return LocalItem(
            videoId, null, info.name, info.uploaderName.orEmpty().removeSuffix(" - Topic"),
            info.thumbnails.maxByOrNull { it.height }?.url, info.duration.toDouble(),
        )
    }
}
