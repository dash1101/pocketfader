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
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
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

private val TIME = Regex("^(\\d+:)?\\d+:\\d{2}$")

/** "3:12" or "1:02:03" -> seconds (0 when it isn't a time). */
fun parseClock(t: String): Double {
    if (!TIME.matches(t)) return 0.0
    return t.split(":").fold(0.0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: 0) }
}

fun SearchItem.toLocal(): LocalItem? {
    val id = videoId ?: return null
    // search subtitles look like "Song · Artist · Album · 3:12"; keep the artist part (and the length)
    val parts = subtitle.split(" • ", " · ").map { it.trim() }.filter { it.isNotBlank() }
    val artist = parts.firstOrNull { it !in setOf("Song", "Video", "Single", "EP", "Album") && !TIME.matches(it) && !it.endsWith(" views") && !it.endsWith(" plays") }.orEmpty()
    val dur = parts.lastOrNull { TIME.matches(it) }?.let(::parseClock) ?: 0.0
    return LocalItem(id, null, title, artist, thumb, dur)
}

/** The other way round: a song on the phone as a row in a list. */
fun LocalItem.toSearchItem(): SearchItem = SearchItem(
    title = title,
    subtitle = listOf(artist, if (durationSec > 0) formatTime(durationSec) else "").filter { it.isNotBlank() }.joinToString(" · "),
    thumb = art, videoId = videoId, playlistId = null, kind = "song",
)

private val VIDEO_TAG = Regex(
    "(?i)\\s*[(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|4k|hd|hq|mv|m/v|clip|color coded)\\b[^)\\]]*[)\\]]"
)
private val ARTIST_NOISE = Regex("(?i)(\\s*-\\s*topic|vevo|\\s+official(\\s+(channel|music))?)$")

/**
 * Songs found through plain YouTube often carry video titles: "Queen – Bohemian Rhapsody (Official Video Remastered)" by
 * "Queen Official". This turns them into what YouTube Music would show: "Bohemian Rhapsody" by "Queen". Names that don't
 * look like that are left exactly as they are.
 */
fun cleanSong(rawTitle: String, rawArtist: String): Pair<String, String> {
    var artist = rawArtist.trim().replace(ARTIST_NOISE, "").removePrefix("Official ").trim()
    var title = rawTitle.replace(VIDEO_TAG, "").trim()
    val dash = Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(title)
    if (dash != null) {
        val (a, t) = dash.destructured
        val aa = a.trim()
        if (artist.isBlank() || aa.contains(artist, ignoreCase = true) || artist.contains(aa, ignoreCase = true)) {
            title = t.trim()
            if (artist.isBlank() || aa.length > artist.length) artist = aa
        }
    }
    title = title.replace(Regex("\\s+"), " ").trim().ifBlank { rawTitle.trim() }
    return title to artist.ifBlank { rawArtist.trim() }
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

    /**
     * Runs a lookup so that an Error inside it (YouTube's own script running out of stack or memory, which happens on old
     * devices) becomes an ordinary failure with a message, instead of closing the whole app.
     */
    private inline fun <T> safely(block: () -> T): T = try {
        init()
        block()
    } catch (e: Error) {
        CrashLog.note("a YouTube lookup on this device", e)
        throw IOException("This device couldn't finish the YouTube lookup", e)
    }

    fun fromPhone(videoId: String): Resolved = safely {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        // AAC (m4a) plays and saves everywhere; Opus only if there's nothing else
        val best = streams.filter { it.format == NpFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: streams.maxByOrNull { it.averageBitrate }
            ?: throw IOException("No audio for this song")
        val ext = if (best.format == NpFormat.M4A) "m4a" else "webm"
        Resolved(best.content, mime = best.format?.mimeType, ext = ext)
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

    private fun StreamInfoItem.toSearchItem(): SearchItem? = toLocalItem()?.toSearchItem()

    /** A song from a YouTube list, with its name tidied up (see [cleanSong]). */
    private fun StreamInfoItem.toLocalItem(): LocalItem? {
        val id = idOf(url) ?: return null
        val (t, a) = cleanSong(name, uploaderName.orEmpty())
        return LocalItem(id, null, t, a, bestThumb(this), if (duration > 0) duration.toDouble() else 0.0)
    }

    /** Songs, not hour-long mixes or live streams: what belongs in a queue that plays on its own. */
    private fun StreamInfoItem.isSong(): Boolean = duration in 1..900 && streamType != org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM

    private val LIST_ID = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val CHANNEL_ID = Regex("/channel/([A-Za-z0-9_-]+)")

    /** A search result of any kind as a row: a song, an artist, an album or a playlist. */
    private fun rowOf(item: InfoItem, albums: Boolean): SearchItem? = when (item) {
        is StreamInfoItem -> item.toSearchItem()
        is PlaylistInfoItem -> LIST_ID.find(item.url)?.groupValues?.get(1)?.let { id ->
            val by = item.uploaderName.orEmpty()
            val count = if (item.streamCount > 0) "${item.streamCount} songs" else ""
            SearchItem(
                title = item.name,
                subtitle = listOf(if (albums) "Album" else "Playlist", by, count).filter { it.isNotBlank() }.joinToString(" · "),
                thumb = bestThumb(item), videoId = null, playlistId = id, kind = if (albums) "album" else "playlist",
            )
        }
        is ChannelInfoItem -> CHANNEL_ID.find(item.url)?.groupValues?.get(1)?.let { id ->
            SearchItem(title = item.name, subtitle = "Artist", thumb = bestThumb(item), videoId = null, playlistId = null, kind = "artist", browseId = id)
        }
        else -> null
    }

    /** Search YouTube Music from the phone. [filter]: "songs", "videos", "artists", "albums" or "playlists". */
    fun search(query: String, filter: String = "songs"): List<SearchItem> = safely {
        val f = when (filter) {
            "artists" -> "music_artists"
            "albums" -> "music_albums"
            "playlists" -> "music_playlists"
            "videos" -> "music_videos"
            else -> "music_songs"
        }
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(f), "")
        ex.fetchPage()
        ex.initialPage.items.mapNotNull { rowOf(it, filter == "albums") }
    }

    /**
     * YouTube Music's own radio for a song (the "RDAMVM" mix it plays when you start a radio): about 25 songs per page,
     * the song itself first. This is what autoplay and the recommendations are built from.
     */
    fun radio(videoId: String, pages: Int = 1): List<LocalItem> = safely {
        val url = "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val all = ArrayList<StreamInfoItem>(info.relatedItems)
        var page = info.nextPage
        var n = 1
        while (n < pages && page != null && org.schabi.newpipe.extractor.Page.isValid(page)) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            all.addAll(more.items)
            page = more.nextPage
            n++
        }
        val seen = HashSet<String>()
        all.filter { it.isSong() }.mapNotNull { it.toLocalItem() }.filter { seen.add(it.videoId!!) }
    }

    /** Songs to continue with after this one: its radio, or (if that fails) what YouTube relates to it. */
    fun related(videoId: String): List<LocalItem> {
        runCatching { radio(videoId) }.getOrNull()?.filter { it.videoId != videoId }?.takeIf { it.isNotEmpty() }?.let { return it }
        return safely {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            info.relatedItems.mapNotNull { (it as? StreamInfoItem)?.takeIf { s -> s.isSong() }?.toLocalItem() }
        }
    }

    /** What people search for, as you type (works without the computer). */
    fun suggestions(query: String): List<String> = safely {
        ServiceList.YouTube.suggestionExtractor.suggestionList(query).take(8)
    }

    /** A public playlist's songs (private ones, like your liked music, need the computer, or a synced copy). */
    fun playlist(playlistId: String): List<LocalItem> = safely {
        val url = "https://www.youtube.com/playlist?list=$playlistId"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val all = ArrayList<StreamInfoItem>(info.relatedItems)
        var page = info.nextPage
        var n = 1
        while (n < 10 && page != null && org.schabi.newpipe.extractor.Page.isValid(page)) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            all.addAll(more.items)
            page = more.nextPage
            n++
        }
        all.mapNotNull { it.toLocalItem() }
    }

    /** Title, artist and cover of a song, when all we have is its id. */
    fun details(videoId: String): LocalItem = safely {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val (t, a) = cleanSong(info.name, info.uploaderName.orEmpty())
        LocalItem(videoId, null, t, a, info.thumbnails.maxByOrNull { it.height }?.url, info.duration.toDouble())
    }
}
