package app.pocketfader.client

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * Shows what this phone plays as the Discord status (Settings → Discord status → "Show what this phone plays").
 *
 * The phone never talks to Discord itself: it tells the YTM Starcast computer it is paired with, and the computer shows the
 * song through its own Discord app, as it does for YouTube Music in its browser. Only the song playing is sent, and only
 * to that computer.
 *
 * A song is sent when it starts, changes, pauses or resumes, or jumps to another place; then again every half minute or so
 * (the computer says how often), so the computer knows the phone is still there. A paused song stays at most ten minutes.
 * It comes off at once when the app is swiped away, music moves to the computer, this is switched off, or the app is paired
 * with another computer.
 *
 * Decisions are made on the main thread, where the player reports; the requests go out one at a time on a background
 * thread, and only the newest state is sent (one still waiting is replaced by a newer one).
 */
object PhonePresence {
    private const val PATH = "/api/phone/playing"
    private const val TIMEOUT_MS = 5000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.quiet)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.quiet)

    private class Outgoing(val api: PfApi, val body: JSONObject)
    private val outbox = Channel<Outgoing>(Channel.CONFLATED)
    private val wire = Any()                     // held while a request is on its way: they arrive in the order sent

    private var store: ConfigStore? = null
    @Volatile private var device: String? = null
    private var latest: PhoneSong? = null        // what the player reported last
    private var latestAt = 0L                    // when (SystemClock.elapsedRealtime)
    private var shown: PhoneSong? = null         // what the computer was sent last (null: nothing shown)
    private var shownAt = 0L
    private var pausedSince = 0L                 // when the song was paused (0: playing, or no song)
    private var heartbeat: Job? = null
    @Volatile private var every = DEFAULT_EVERY_SEC
    // A computer with an older YTM Starcast doesn't know this request: nothing more is sent to it until the app connects
    // to a computer again (opening the app does), in case it was updated in the meantime.
    @Volatile private var unsupported = false

    fun init(c: Context) {
        if (store != null) return
        store = ConfigStore(c)
        io.launch { for (m in outbox) synchronized(wire) { deliver(m) } }
    }

    /** On while music plays on this phone, the setting is on, a computer is set up and it knows this request. */
    private fun active(): Boolean =
        !unsupported && Remote.local && Settings.prefs.value.discordFromPhone && Remote.computerApi() != null

    /** The phone's player reports its song (null: nothing in the player). */
    @Synchronized fun update(song: PhoneSong?) {
        val t = SystemClock.elapsedRealtime()
        val s = song?.takeIf { it.title.isNotBlank() }      // the computer needs a title to show anything
        latest = s
        latestAt = t
        pausedSince = when {
            s == null || s.playing -> 0L
            pausedSince == 0L -> t
            else -> pausedSince
        }
        if (!active()) { takeOff(); return }
        if (shouldSendPresence(shown, shownAt, s, t)) send(s, t)
    }

    /** The setting, or where music plays, changed: start or stop showing the song to match. */
    @Synchronized fun refresh() {
        if (!active()) { takeOff(); return }
        val t = SystemClock.elapsedRealtime()
        val s = latest?.movedOn(latestAt, t)
        if (shouldSendPresence(shown, shownAt, s, t)) send(s, t)
    }

    /**
     * The music was ended on purpose (the app was swiped away): the song comes off now rather than when the pause runs out.
     * It is shown again once something plays.
     */
    @Synchronized fun end() = takeOff()

    /**
     * The app is about to use another computer, or none ([sameServer]: the same one again, as when the app is reopened).
     * The song comes off the computer it was shown on; and a computer that didn't know this request is asked again.
     */
    @Synchronized fun serverChanging(old: PfApi?, sameServer: Boolean) {
        unsupported = false
        if (sameServer) return
        val was = shown
        shown = null
        heartbeat?.cancel()
        heartbeat = null
        val id = deviceId()
        if (was != null && old != null && id != null) {
            // straight to the old computer, not through the outbox: the song for the new one, sent next, would replace it
            // there. A song still waiting to go was for the old computer too, and is dropped; one already on its way
            // arrives first, so the goodbye is the last thing the old computer hears.
            outbox.tryReceive()
            val body = presenceBody(id, null, System.currentTimeMillis())
            io.launch { synchronized(wire) { runCatching { old.post(PATH, body, TIMEOUT_MS) } } }
        }
    }

    private fun deviceId(): String? = device ?: store?.deviceId()?.also { device = it }

    /** Takes the song off the computer (when one is shown there) and stops the heartbeat. */
    private fun takeOff() {
        if (shown != null && !unsupported) send(null, SystemClock.elapsedRealtime())
        shown = null
        heartbeat?.cancel()
        heartbeat = null
    }

    private fun send(song: PhoneSong?, t: Long) {
        val a = Remote.computerApi() ?: return
        val id = deviceId() ?: return
        shown = song
        shownAt = t
        outbox.trySend(Outgoing(a, presenceBody(id, song, System.currentTimeMillis())))
        beatLater()
    }

    /** While a song is shown, the next heartbeat is due [every] seconds after the last request. */
    private fun beatLater() {
        heartbeat?.cancel()
        heartbeat = if (shown == null) null else scope.launch {
            delay(every * 1000L)
            beat()
        }
    }

    @Synchronized private fun beat() {
        if (!active()) { takeOff(); return }
        if (shown == null) return
        val t = SystemClock.elapsedRealtime()
        // the player only reports when something happens, so a song simply playing on is counted forward to now
        val s = latest?.movedOn(latestAt, t)
        if (s == null || !keepShowing(s.playing, pausedSince, t)) send(null, t) else send(s, t)
    }

    /** Runs on the background thread, one request at a time. */
    private fun deliver(m: Outgoing) {
        try {
            every = everyFromReply(m.api.post(PATH, m.body, TIMEOUT_MS))
        } catch (e: ApiException) {
            if (e.code == 404 && m.api === Remote.computerApi()) unsupported = true
        } catch (e: Exception) {
            // no connection, or the computer is asleep: the next heartbeat tries again
        }
    }
}

// ---------------------------------------------------------------- the decisions, free of Android (unit tested)

/** A song in the phone's player, as the computer is told about it. [key] tells songs apart: a YouTube id or a file's address. */
data class PhoneSong(
    val key: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val art: String? = null,
    val videoId: String? = null,
    val playing: Boolean = false,
    val position: Double = 0.0,         // seconds, when it was read
    val duration: Double = 0.0,         // seconds (0: unknown)
    val speed: Double = 1.0,
)

internal const val DEFAULT_EVERY_SEC = 30
internal const val SEEK_SLACK_SEC = 2.5
internal const val PAUSED_KEEP_MS = 10 * 60_000L

/** Where the song is at [clock] when it was read at [readAt] (ms): it moves on only while playing, and never past its end. */
internal fun PhoneSong.movedOn(readAt: Long, clock: Long): PhoneSong {
    if (!playing || clock <= readAt) return this
    val p = position + (clock - readAt) / 1000.0 * speed
    return copy(position = if (duration > 0) minOf(p, duration) else p)
}

/**
 * Whether [song] has to go to the computer now rather than with the next heartbeat: it was taken out, changed, paused or
 * resumed, changed speed, or jumped (more than [SEEK_SLACK_SEC] away from where the song last sent, [shown] at [shownAt], would be by
 * [clock]). Nothing starts being shown by a paused song: that is a queue left from before, not music playing.
 */
internal fun shouldSendPresence(shown: PhoneSong?, shownAt: Long, song: PhoneSong?, clock: Long): Boolean = when {
    song == null -> shown != null
    shown == null -> song.playing
    song.key != shown.key || song.title != shown.title || song.playing != shown.playing -> true
    abs(song.speed - shown.speed) > 1e-3 -> true     // played faster or slower: the times on Discord change pace
    else -> abs(song.position - shown.movedOn(shownAt, clock).position) > SEEK_SLACK_SEC
}

/** Whether a song still in the player stays shown: a paused one comes off ten minutes after the pause. */
internal fun keepShowing(playing: Boolean, pausedSince: Long, clock: Long): Boolean =
    playing || pausedSince == 0L || clock - pausedSince < PAUSED_KEEP_MS

/** The body of POST /api/phone/playing. [ts]: when the position was read (ms since 1970). A null [song]: nothing plays. */
internal fun presenceBody(device: String, song: PhoneSong?, ts: Long): JSONObject {
    val body = JSONObject().put("device", device).put("ts", ts)
    if (song == null) return body.put("now", JSONObject.NULL)
    val duration = seconds(song.duration)
    val now = JSONObject()
        .put("playing", song.playing)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("album", song.album)
        .put("position", seconds(song.position).let { if (duration > 0) minOf(it, duration) else it })
        .put("duration", duration)
        .put("speed", if (song.speed.isFinite() && song.speed > 0) song.speed else 1.0)
    // only covers anyone can load (not pictures stored on the phone), and only real YouTube ids (not a file's address)
    song.art?.takeIf { it.startsWith("https://") }?.let { now.put("art", it) }
    song.videoId?.takeIf(::isYtId)?.let { now.put("videoId", it) }
    return body.put("now", now)
}

private fun seconds(v: Double): Double = if (v.isFinite() && v > 0) v else 0.0

/** Seconds between heartbeats, as the computer asks (within sensible bounds). */
internal fun everyFromReply(reply: JSONObject): Int = reply.optInt("every", DEFAULT_EVERY_SEC).coerceIn(10, 300)

private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{6,32}$")

/** A new random name for this phone (12 letters and digits): it tells the computer which phone a song comes from. */
internal fun newDeviceId(random: java.util.Random = java.security.SecureRandom()): String =
    buildString { repeat(12) { append(ID_CHARS[random.nextInt(ID_CHARS.length)]) } }

/** True for a name the computer accepts for a phone. */
internal fun isDeviceId(s: String?): Boolean = s != null && DEVICE_ID.matches(s)
