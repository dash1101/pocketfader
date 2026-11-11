package app.pocketfader.client

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

@Immutable
data class RemoteState(
    val configured: Boolean = false,
    val online: Boolean = false,
    val error: String? = null,
    val now: Now? = null,
    val queue: List<Track> = emptyList(),
    val source: String? = null,           // "ytm" (extension) | "media" (host player) | null
    val bridge: Boolean = false,          // the YouTube Music extension is connected
    val volume: VolumeState = VolumeState(),
    val eq: EqState = EqState(),
    val outputs: List<Output> = emptyList(),
    val canOutputs: Boolean = false,
    val canLyrics: Boolean = false,
    val canBeacon: Boolean = false,
    val ytm: Boolean = false,             // the computer's YouTube Music extension is connected (account: library, likes, artists)
    val canAudio: Boolean = false,        // the computer can fetch songs for the phone (yt-dlp)
    val canSleep: Boolean = false,
    val sleepLeft: Int = 0,               // seconds until playback is paused by the sleep timer (0 = none)
    val sleepAt: Long = 0L,               // SystemClock.elapsedRealtime() when sleepLeft was read
)

/**
 * The one place that talks to the server. The Activity and the media service both observe
 * [state]; polling runs while either is alive (see [acquire]/[release]).
 *
 * [state] only changes when something visible changed. The playback position, which moves on every
 * poll, lives in [progress] so the whole screen isn't redrawn each second.
 */
object Remote {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    @Volatile private var api: PfApi? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.quiet)
    private var job: Job? = null
    private var holders = 0
    @Volatile private var visible = false
    @Volatile private var wantOutputs = false
    @Volatile private var fastUntil = 0L
    @Volatile private var settleUntil = 0L   // keep optimistic values for a moment so sliders don't jump back
    private var tick = 0
    // Values you just changed (shuffle, loop, autoplay, like, play/pause) are held for a few seconds, so a poll that
    // lands before the computer has applied the change doesn't flip the button back and forth.
    private val held = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun hold(key: String) { held[key] = SystemClock.elapsedRealtime() + 3500 }

    private fun withHeld(fresh: Now?, cur: Now?): Now? {
        if (fresh == null || cur == null || fresh.videoId != cur.videoId) { held.clear(); return fresh }
        val t = SystemClock.elapsedRealtime()
        fun keep(k: String, same: Boolean): Boolean {
            val until = held[k] ?: return false
            if (t > until || same) { held.remove(k); return false }     // expired, or the computer caught up
            return true
        }
        var n = fresh
        if (keep("shuffle", fresh.shuffle == cur.shuffle)) n = n.copy(shuffle = cur.shuffle)
        if (keep("repeat", fresh.repeat == cur.repeat)) n = n.copy(repeat = cur.repeat)
        if (keep("autoplay", fresh.autoplay == cur.autoplay)) n = n.copy(autoplay = cur.autoplay)
        if (keep("liked", fresh.liked == cur.liked)) n = n.copy(liked = cur.liked)
        if (keep("playing", fresh.playing == cur.playing)) n = n.copy(playing = cur.playing)
        return n
    }

    fun notify(text: String) { _messages.tryEmit(text) }

    // ------------------------------------------------------------------ playing on the phone itself

    /** True when music plays on this phone (not the computer): the controls and the queue are the phone's own. */
    val local: Boolean get() = Settings.prefs.value.playOn == "phone"

    /** The computer's API, when one is set up (for the phone's player to fall back on). */
    fun computerApi(): PfApi? = api

    /** The phone's player reports what it's doing (only shown while playing on the phone). */
    fun applyLocal(now: Now?, queue: List<Track>, prog: Progress, sleepLeft: Int = 0) {
        if (!local) return
        _progress.value = prog
        _state.update {
            // the sleep timer only gets a new timestamp when it changes, not each time this runs (that would redraw everything)
            val t = SystemClock.elapsedRealtime()
            val sameTimer = it.sleepLeft > 0 && sleepLeft > 0 && kotlin.math.abs((it.sleepAt + it.sleepLeft * 1000L) - (t + sleepLeft * 1000L)) < 2500
            it.copy(
                now = now, queue = queue, source = "phone", bridge = true, canLyrics = true, canSleep = true,
                sleepLeft = if (sameTimer) it.sleepLeft else sleepLeft, sleepAt = if (sameTimer) it.sleepAt else if (sleepLeft > 0) t else 0L,
            )
        }
    }

    fun applyProgress(prog: Progress) { if (local) _progress.value = prog }

    /**
     * Switch where music plays. [carryOver] moves what's playing along: the song (from where it is), the rest of the queue,
     * and pauses the place it came from.
     */
    fun setPlayOn(mode: String, carryOver: Boolean) {
        if (Settings.prefs.value.playOn == mode) return
        val before = _state.value
        val pos = _progress.value
        if (mode == "phone") {
            val songs = before.queue.filter { it.section == "queue" }
            val cur = songs.indexOfFirst { it.selected }
            // the song playing, then the rest of the queue after it (the queue may not mark which row is playing)
            val n = before.now
            val playingNow = n?.videoId?.takeIf(::isYtId)?.let { LocalItem(it, null, n.title, n.artist, n.art, n.duration) }
            val upcoming = (if (cur >= 0) songs.drop(cur + 1) else emptyList()).mapNotNull { it.toLocal() }
            val list = listOfNotNull(playingNow) + upcoming.filter { it.videoId != playingNow?.videoId }
            Settings.update { it.copy(playOn = "phone") }
            if (carryOver && before.now?.videoId != null && list.isNotEmpty()) {
                if (before.now.playing) transportComputer("playpause")
                LocalPlayer.playItems(list, 0)
                LocalPlayer.seek(pos.position + if (before.now.playing) (SystemClock.elapsedRealtime() - pos.at) / 1000.0 else 0.0)
            } else {
                LocalPlayer.publish()
            }
        } else {
            val cur = LocalPlayer.current()
            val at = LocalPlayer.position()
            LocalPlayer.pause()
            Settings.update { it.copy(playOn = "computer") }
            _state.update { it.copy(now = null, queue = emptyList(), source = null, bridge = false) }
            boost()
            val vid = cur?.videoId
            if (carryOver && isYtId(vid) && vid != null) {
                play(vid)
                scope.launch { delay(2500); transportComputer("seek", at) }
            }
        }
    }

    private fun transportComputer(action: String, position: Double? = null) {
        val b = JSONObject().put("type", "transport").put("action", action)
        if (position != null) b.put("position", position)
        command(b)
    }

    fun configure(cfg: ServerConfig) {
        api = if (cfg.isSet) PfApi(cfg) else null
        _state.value = RemoteState(configured = cfg.isSet)
        _progress.value = Progress()
        if (local) LocalPlayer.publish()
        fastUntil = SystemClock.elapsedRealtime() + 3000
    }

    /** For when the service is restarted by the system without the UI: load what was saved. */
    fun ensureConfigured(store: ConfigStore) {
        if (api == null) {
            val c = store.load()
            if (c.isSet) configure(c)
        }
    }

    @Synchronized fun acquire() {
        holders++
        if (job?.isActive != true) job = scope.launch { loop() }
    }

    @Synchronized fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) { job?.cancel(); job = null }
    }

    /** True while the UI is on screen: polls faster. */
    fun setVisible(v: Boolean) { visible = v }
    val isVisible: Boolean get() = visible

    /** The mixer screen is open: also keep the output list fresh. */
    fun setWantOutputs(v: Boolean) { wantOutputs = v }

    private fun delayFor(): Long {
        val speed = Settings.prefs.value.pollSpeed
        val fast = SystemClock.elapsedRealtime() < fastUntil
        val playing = _state.value.now?.playing == true
        return when {
            fast -> 500L
            visible -> when (speed) { "fast" -> 800L; "battery" -> 2500L; else -> 1200L }
            // in the background the notification only needs to notice track changes
            else -> when (speed) { "fast" -> 2500L; "battery" -> 8000L; else -> if (playing) 4000L else 8000L }
        }
    }

    private suspend fun loop() {
        var failures = 0
        while (scope.isActive) {
            val a = api
            if (a != null) {
                try {
                    pollOnce(a)
                    failures = 0
                } catch (e: Exception) {
                    failures++
                    if (failures >= 2) _state.update { it.copy(online = false, error = e.friendly()) }
                }
            }
            delay(delayFor())
        }
    }

    /** One poll right now (blocking; for the widgets, which may run while nothing else keeps the app polling). */
    fun pollNow() {
        val a = api ?: return
        runCatching { pollOnce(a) }
    }

    private fun pollOnce(a: PfApi) {
        val t0 = SystemClock.elapsedRealtime()
        val ytmStart = SystemClock.elapsedRealtime()
        val ytm = a.get("/api/ytm/state")
        // The server sampled the position about halfway through this request, not when it began.
        val sampled = (ytmStart + SystemClock.elapsedRealtime()) / 2
        // Volume, outputs and the equalizer change rarely, so ask for them every other poll
        // (and always right after something was sent).
        val full = tick++ % 2 == 0 || t0 < fastUntil
        val st = if (full) a.get("/api/state") else null
        val outs = if (wantOutputs && full) runCatching { parseOutputs(a.get("/api/outputs")) }.getOrNull() else null
        val settling = SystemClock.elapsedRealtime() < settleUntil
        val nowJson = ytm.optJSONObject("now")
        val now = parseNow(nowJson)
        val loc = local
        if (!loc) _progress.value = Progress(parsePosition(nowJson), sampled)
        if (!loc && ytm.str("source") == "ytm") Library.observeComputer(now, parsePosition(nowJson))
        val queue = parseQueue(ytm)
        val source = ytm.str("source")
        val bridge = ytm.optBoolean("connected", false) && source == "ytm"
        _state.update { cur ->
            cur.copy(
                configured = true,
                online = true,
                error = null,
                now = if (loc) cur.now else withHeld(now, cur.now),
                queue = if (loc) cur.queue else queue,
                source = if (loc) "phone" else source,
                bridge = if (loc) true else bridge,
                ytm = bridge,
                canAudio = st?.optJSONObject("capabilities")?.optBoolean("audio", false) ?: cur.canAudio,
                volume = if (st == null || settling) cur.volume else parseVolume(st),
                eq = if (st == null) cur.eq else parseEq(st),
                canOutputs = st?.optJSONObject("capabilities")?.optBoolean("outputs", false) ?: cur.canOutputs,
                canLyrics = if (loc) true else st?.optJSONObject("capabilities")?.optBoolean("lyrics", false) ?: cur.canLyrics,
                canBeacon = st?.optJSONObject("capabilities")?.optBoolean("beacon", false) ?: cur.canBeacon,
                canSleep = if (loc) true else st?.optJSONObject("capabilities")?.optBoolean("sleep", false) ?: cur.canSleep,
                sleepLeft = if (loc) cur.sleepLeft else sleepFrom(st, cur).first,
                sleepAt = if (loc) cur.sleepAt else sleepFrom(st, cur).second,
                outputs = if (settling) cur.outputs else (outs ?: cur.outputs),
            )
        }
        // the account is reachable: a good moment to bring the library over (and send changes made without it)
        if (bridge) Library.syncIfDue()
    }

    /**
     * The sleep timer as (seconds left, when that was read). While the same timer is simply counting down it keeps its old
     * values: a fresh timestamp on every poll would change the state each time and redraw the whole screen for nothing.
     */
    private fun sleepFrom(st: JSONObject?, cur: RemoteState): Pair<Int, Long> {
        if (st == null) return cur.sleepLeft to cur.sleepAt
        val left = st.optJSONObject("sleep")?.optInt("remainingSec", 0) ?: 0
        if (left <= 0) return 0 to 0L
        val nowMs = SystemClock.elapsedRealtime()
        val curEnd = cur.sleepAt + cur.sleepLeft * 1000L
        if (cur.sleepLeft > 0 && kotlin.math.abs(curEnd - (nowMs + left * 1000L)) < 3000) return cur.sleepLeft to cur.sleepAt
        return left to nowMs
    }

    // ------------------------------------------------------------------ commands

    private fun boost() { fastUntil = SystemClock.elapsedRealtime() + 2500 }

    private fun runApi(block: (PfApi) -> Unit) {
        val a = api ?: return
        scope.launch {
            try {
                block(a)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            }
            boost()
            runCatching { pollOnce(a) }
        }
    }

    /** Sends a YouTube Music / transport command and optionally hands back the reply. */
    fun command(body: JSONObject, onResult: ((JSONObject) -> Unit)? = null) {
        val a = api ?: return
        scope.launch {
            try {
                val r = a.post("/api/ytm", body)
                if (r.optBoolean("opened")) notify("Opening YouTube Music on the computer…")
                if (r.has("error")) notify(ytmErrorText(r.optString("error")))
                onResult?.invoke(r)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            }
            boost()
            runCatching { pollOnce(a) }
        }
    }

    /** Opens YouTube Music in the computer's browser, so music can start when no tab was open. */
    fun openYtm() {
        notify("Opening YouTube Music on the computer…")
        runApi { it.post("/api/ytm", JSONObject().put("type", "open")) }
    }

    fun serverPrefs(): JSONObject = need().get("/api/prefs")

    fun setServerPref(key: String, value: Boolean) {
        runApi { it.post("/api/prefs", JSONObject().put(key, value)) }
    }

    /** Pauses every other player that is playing on the computer. */
    fun pauseOthers() {
        val a = api ?: return
        scope.launch {
            try {
                val r = a.post("/api/media/pause-others", JSONObject())
                val names = r.optJSONArray("paused")
                notify(
                    when {
                        !r.optBoolean("supported", true) -> "This computer can't pause other players from here."
                        names == null || names.length() == 0 -> "Nothing else was playing."
                        else -> "Paused " + (0 until names.length()).joinToString(", ") { names.optString(it) }
                    }
                )
            } catch (e: Exception) {
                notify("Couldn't pause: ${e.friendly()}")
            }
        }
    }

    /** minutes: 1..720, "track" (when this song ends) or 0 to cancel. Playback (on the phone, or the computer) pauses when time is up. */
    fun setSleep(minutes: Any) {
        if (local) { LocalPlayer.setSleep(minutes); return }
        runApi { it.post("/api/sleep", JSONObject().put("minutes", minutes)) }
    }

    /**
     * "I'm feeling lucky": something you'd probably like. On the computer with YouTube Music, YouTube Music picks from your
     * home page; otherwise the phone's own recommendations pick, and the song plays where music is playing (on a computer
     * with no YouTube Music tab open, one is opened with it).
     */
    fun lucky() {
        if (!local && _state.value.ytm) {
            command(JSONObject().put("type", "lucky")) { r -> r.optString("picked").takeIf { it.isNotBlank() }?.let { notify("Playing $it") } }
            return
        }
        scope.launch {
            val pick = Recommend.lucky()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                when {
                    pick == null -> notify("Nothing to pick from yet. Play a few songs first.")
                    local || api == null -> {
                        if (!local) setPlayOn("phone", carryOver = false)
                        LocalPlayer.setAutoplay(true)
                        LocalPlayer.playItems(listOf(pick))
                        notify("Playing ${pick.title}")
                    }
                    else -> { play(pick.videoId!!); notify("Playing ${pick.title}") }
                }
            }
        }
    }

    fun transport(action: String, position: Double? = null) {
        if (local) {
            when (action) {
                "playpause" -> LocalPlayer.toggle()
                "next" -> LocalPlayer.next()
                "prev" -> LocalPlayer.prev()
                "seek" -> if (position != null) { _progress.value = Progress(position, SystemClock.elapsedRealtime()); LocalPlayer.seek(position) }
            }
            return
        }
        val b = JSONObject().put("type", "transport").put("action", action)
        if (position != null) b.put("position", position)
        if (action == "playpause") hold("playing")
        if (action == "playpause") _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(playing = !n.playing) }) }
        if (action == "seek" && position != null) _progress.value = Progress(position, SystemClock.elapsedRealtime())
        command(b)
    }

    /**
     * Like or unlike a song. It counts right away on the phone (your liked songs, the recommendations); YouTube Music gets it
     * now when the computer's tab is connected, or at the next sync when it isn't.
     */
    fun like(videoId: String, on: Boolean) {
        val connected = _state.value.ytm
        val n = _state.value.now
        val item = if (n != null && n.videoId == videoId) LocalItem(videoId, null, n.title, n.artist, n.art, n.duration)
        else LocalPlayer.queue().firstOrNull { it.videoId == videoId } ?: LocalItem(videoId, null, "", "", null)
        Library.setLiked(item, on, sendNow = connected)
        if (!local) hold("liked")
        _state.update { s -> s.copy(now = s.now?.let { now -> if (now.videoId == videoId) now.copy(liked = on) else now }) }
        if (local) LocalPlayer.publish()
        if (connected) command(JSONObject().put("type", "like").put("videoId", videoId).put("action", if (on) "like" else "remove"))
        else if (on) notify("Liked. It'll be added to your YouTube Music likes at the next sync.")
    }

    fun setRepeat(mode: String) {
        if (local) { LocalPlayer.setRepeat(mode); return }
        hold("repeat")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(repeat = mode) }) }
        command(JSONObject().put("type", "setRepeat").put("mode", mode))
    }

    fun setShuffle(on: Boolean) {
        if (local) { LocalPlayer.setShuffle(on); return }
        hold("shuffle")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(shuffle = on) }) }
        command(JSONObject().put("type", "setShuffle").put("on", on))
    }

    fun setAutoplay(on: Boolean) {
        if (local) { LocalPlayer.setAutoplay(on); return }
        hold("autoplay")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(autoplay = on) }) }
        command(JSONObject().put("type", "setAutoplay").put("on", on))
    }

    private fun ref(t: Track) = JSONObject()
        .put("section", t.section).put("index", t.index)
        .put("videoId", t.videoId ?: "").put("setVideoId", t.setVideoId ?: JSONObject.NULL)

    fun queuePlayAt(t: Track) { if (local) LocalPlayer.playAt(t.index) else command(ref(t).put("type", "queuePlayAt")) }
    fun queueRemove(t: Track) { if (local) LocalPlayer.remove(t.index) else command(ref(t).put("type", "queueRemove")) }
    fun queueMove(t: Track, to: Int) { if (local) LocalPlayer.move(t.index, to) else command(ref(t).put("type", "queueMove").put("to", to)) }

    fun queueMoveNext(t: Track) {
        if (local) {
            val cur = _state.value.queue.indexOfFirst { it.selected }
            LocalPlayer.move(t.index, if (t.index > cur) cur + 1 else cur)
        } else command(ref(t).put("type", "queueMoveNext"))
        notify("Playing next: ${t.title}")
    }

    /** Copies a track (typically an autoplay suggestion) onto the end of the real queue. */
    fun queueAddEnd(t: Track) {
        if (local) t.toLocal()?.let { LocalPlayer.addEnd(it) } else command(ref(t).put("type", "queueAddEnd"))
        notify("Added to your queue: ${t.title}")
    }

    /**
     * Play a song from a list (search results, a playlist, an album...). On the phone the rest of the list follows it;
     * on the computer YouTube Music decides (its own radio, or the playlist).
     */
    fun playItem(item: SearchItem, context: List<SearchItem> = emptyList(), playlistId: String? = null) {
        val vid = item.videoId ?: return
        if (local) {
            val list = context.mapNotNull { it.toLocal() }
            val at = list.indexOfFirst { it.videoId == vid }
            if (at >= 0) LocalPlayer.playItems(list, at) else item.toLocal()?.let { LocalPlayer.playItems(listOf(it)) }
        } else play(vid, playlistId ?: item.playlistId.takeIf { item.kind != "song" })
    }

    fun play(videoId: String, playlistId: String? = null) {
        if (local) { LocalPlayer.playVideo(videoId); return }
        val b = JSONObject().put("type", "play").put("videoId", videoId)
        if (playlistId != null) b.put("playlistId", playlistId)
        command(b)
    }

    fun startPlaylist(playlistId: String, shuffle: Boolean) {
        if (local) {
            scope.launch {
                // your own playlists (and liked music) need the account on the computer; public ones load on the phone
                val synced = if (playlistId == "LM") Library.liked.value.takeIf { it.isNotEmpty() } else Library.playlist(playlistId)?.tracks
                val list = runCatching {
                    if (_state.value.ytm) playlistItems(SearchItem("", "", null, null, playlistId, "playlist"), limit = 1000).mapNotNull { it.toLocal() }
                    else synced ?: Streams.playlist(playlistId)
                }.recoverCatching { synced ?: Streams.playlist(playlistId) }.getOrNull().orEmpty()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (list.isEmpty()) notify("Couldn't load that playlist on the phone.")
                    else LocalPlayer.playItems(list, if (shuffle) list.indices.random() else 0, shuffle)
                }
            }
            return
        }
        command(JSONObject().put("type", "startPlaylist").put("playlistId", playlistId).put("shuffle", shuffle))
        notify(if (shuffle) "Shuffling playlist" else "Starting playlist")
    }

    fun startMix(videoId: String) {
        if (local) { LocalPlayer.setAutoplay(true); LocalPlayer.playVideo(videoId); return }
        command(JSONObject().put("type", "startMix").put("videoId", videoId))
        notify("Starting radio")
    }

    /** Add a song from search / a playlist / a shelf to the current queue, next or at the end. */
    fun queueAdd(item: SearchItem, next: Boolean) {
        val vid = item.videoId ?: return
        if (local) {
            item.toLocal()?.let { if (next) LocalPlayer.addNext(it) else LocalPlayer.addEnd(it) }
            notify(if (next) "Playing next: ${item.title}" else "Added to your queue: ${item.title}")
            return
        }
        command(
            JSONObject().put("type", "queueAdd").put("videoId", vid)
                .put("query", item.title + " " + item.subtitle).put("mode", if (next) "next" else "end")
        )
        notify(if (next) "Playing next: ${item.title}" else "Added to your queue: ${item.title}")
    }

    // ---- calls that need an answer (run these on Dispatchers.IO)

    private fun need(): PfApi = api ?: throw ApiException(0, "Not connected to a computer.")

    private fun ytmCall(body: JSONObject, timeoutMs: Int = 25000): JSONObject {
        val r = need().post("/api/ytm", body, timeoutMs)
        if (r.has("error")) {
            throw ApiException(
                200,
                ytmErrorText(r.optString("error")),
            )
        }
        return r
    }

    fun artist(browseId: String?, videoId: String?, name: String?): ArtistPage {
        val b = JSONObject().put("type", "artist")
        if (browseId != null) b.put("browseId", browseId)
        if (videoId != null) b.put("videoId", videoId)
        if (name != null) b.put("name", name)
        return parseArtist(ytmCall(b, 30000))
    }

    fun search(query: String): List<SearchItem> =
        parseItems(ytmCall(JSONObject().put("type", "search").put("query", query), 20000))

    fun home(): List<Shelf> = parseShelves(ytmCall(JSONObject().put("type", "home"), 40000))

    fun playlists(): List<SearchItem> = parseItems(ytmCall(JSONObject().put("type", "playlists")), "playlists")

    /** A playlist's songs: the first hundred or so, or up to [limit] (long playlists come in pages). */
    fun playlistItems(p: SearchItem, limit: Int? = null): List<SearchItem> {
        val b = JSONObject().put("type", "playlistItems")
            .put("playlistId", p.playlistId ?: JSONObject.NULL).put("browseId", p.browseId ?: JSONObject.NULL)
        if (limit != null) b.put("limit", limit)
        return parseItems(ytmCall(b, if (limit != null) 120000 else 25000))
    }

    /** Search suggestions as you type: your account's (through the computer) when it's there, else YouTube's. */
    fun suggest(query: String): List<String> {
        if (_state.value.ytm) {
            runCatching { ytmCall(JSONObject().put("type", "suggest").put("query", query), 6000) }.getOrNull()
                ?.optJSONArray("suggestions").strings().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return Streams.suggestions(query)
    }

    /** A playlist change (move, remove, rename, add, create, delete, queueSave); the reply has `ok`. */
    fun playlistOp(body: JSONObject): JSONObject = ytmCall(body)

    fun lyrics(now: Now, source: String?, refresh: Boolean): LyricsResult {
        val vid = now.videoId
        if (!refresh && (source == null || source == "auto") && vid != null) Downloads.lyricsFor(vid)?.let { return it }
        val a = api
        if (a == null || !_state.value.online) {
            val j = Lrclib.lookup(now.title, now.artist, now.duration) ?: return LyricsResult()
            return parseLyrics(j)
        }
        return lyricsFromComputer(now, source, refresh)
    }

    /** The computer's lyrics reply as JSON (for saving with a download); null without a computer. */
    fun lyricsJson(title: String, artist: String, durationSec: Double, videoId: String?): JSONObject? {
        if (api == null || !_state.value.online) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = StringBuilder("/api/lyrics?title=").append(enc(title)).append("&artist=").append(enc(artist))
        if (durationSec > 0) q.append("&duration=").append(durationSec.toInt())
        videoId?.let { q.append("&videoId=").append(enc(it)) }
        val j = need().get(q.toString(), 25000)
        return if (j.optBoolean("found")) j else null
    }

    private fun lyricsFromComputer(now: Now, source: String?, refresh: Boolean): LyricsResult {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = StringBuilder("/api/lyrics?title=").append(enc(now.title)).append("&artist=").append(enc(now.artist))
        if (now.duration > 0) q.append("&duration=").append(now.duration.toInt())
        now.videoId?.let { q.append("&videoId=").append(enc(it)) }
        if (source != null && source != "auto") q.append("&source=").append(enc(source))
        if (refresh) q.append("&refresh=1")
        return parseLyrics(need().get(q.toString(), 25000))
    }

    // ---- Discord presence (YTM Beacon, relayed by the computer; the phone never talks to Beacon itself)

    fun beaconStatus(): BeaconStatus = parseBeacon(need().get("/api/beacon", 6000), SystemClock.elapsedRealtime())

    fun beaconVisibility(show: Boolean, minutes: Int? = null) {
        val body = JSONObject().put("action", if (show) "show" else "hide")
        if (!show && minutes != null) body.put("minutes", minutes)
        need().post("/api/beacon/visibility", body, 8000)
    }

    /** action: "install" | "update" | "start" (the computer downloads the latest release and installs it). */
    fun beaconInstall(action: String) {
        need().post("/api/beacon/install", JSONObject().put("action", action), 8000)
    }

    fun beaconSetting(key: String, value: Any) {
        need().post("/api/beacon/settings", JSONObject().put(key, value), 8000)
    }

    // ---- app updates (the computer finds the release, the phone just asks it)

    fun updateInfo(refresh: Boolean): UpdateInfo =
        parseUpdate(need().get("/api/update" + if (refresh) "?refresh=1" else "", 20000))

    fun downloadUpdate(dest: java.io.File) = need().download("/api/update/apk", dest)

    // ---- volume / outputs / EQ

    private var lastSend = 0L

    private fun throttled(ms: Long): Boolean {
        val n = SystemClock.elapsedRealtime()
        if (n - lastSend < ms) return true
        lastSend = n
        return false
    }

    /** [final] sends immediately; otherwise drags are throttled to about 10 updates per second. */
    fun setVolume(percent: Int, final: Boolean = true) {
        val p = percent.coerceIn(0, 100)
        if (local) { LocalPlayer.setPhoneVolume(p); return }
        settleUntil = SystemClock.elapsedRealtime() + 1500
        _state.update { it.copy(volume = it.volume.copy(percent = p)) }
        if (!final && throttled(100)) return
        runApi { it.post("/api/volume", JSONObject().put("percent", p)) }
    }

    fun adjustVolume(delta: Int) {
        if (local) { LocalPlayer.setPhoneVolume(LocalPlayer.phoneVolume() + delta); return }
        val target = (_state.value.volume.percent + delta).coerceIn(0, 100)
        settleUntil = SystemClock.elapsedRealtime() + 1500
        _state.update { it.copy(volume = it.volume.copy(percent = target)) }
        runApi { it.post("/api/volume", JSONObject().put("delta", delta)) }
    }

    fun setMuted(muted: Boolean) {
        if (local) { if ((LocalPlayer.phoneVolume() == 0) != muted) LocalPlayer.togglePhoneMute(); return }
        _state.update { it.copy(volume = it.volume.copy(muted = muted)) }
        settleUntil = SystemClock.elapsedRealtime() + 1000
        runApi { it.post("/api/volume", JSONObject().put("muted", muted)) }
    }

    fun setOutput(name: String) {
        _state.update { s -> s.copy(outputs = s.outputs.map { it.copy(isDefault = it.name == name) }) }
        settleUntil = SystemClock.elapsedRealtime() + 1500
        runApi { it.post("/api/output", JSONObject().put("name", name)) }
    }

    fun setOutputVolume(name: String, percent: Int, final: Boolean = true) {
        val p = percent.coerceIn(0, 100)
        settleUntil = SystemClock.elapsedRealtime() + 1500
        _state.update { s -> s.copy(outputs = s.outputs.map { if (it.name == name) it.copy(percent = p) else it }) }
        if (!final && throttled(100)) return
        runApi { it.post("/api/output/volume", JSONObject().put("name", name).put("percent", p)) }
    }

    fun setOutputMuted(name: String, muted: Boolean) {
        _state.update { s -> s.copy(outputs = s.outputs.map { if (it.name == name) it.copy(muted = muted) else it }) }
        settleUntil = SystemClock.elapsedRealtime() + 1000
        runApi { it.post("/api/output/volume", JSONObject().put("name", name).put("muted", muted)) }
    }

    fun eqEnable(on: Boolean) {
        _state.update { it.copy(eq = it.eq.copy(enabled = on)) }
        runApi { it.post("/api/eq", JSONObject().put("enabled", on)) }
    }

    fun eqPreset(name: String) = runApi { it.post("/api/eq", JSONObject().put("preset", name)) }

    fun eqBand(band: String, db: Double, final: Boolean = true) {
        _state.update { s -> s.copy(eq = s.eq.copy(bands = s.eq.bands + (band to db))) }
        settleUntil = SystemClock.elapsedRealtime() + 800
        if (!final && throttled(120)) return
        runApi { it.post("/api/eq", JSONObject().put("band", band).put("db", db)) }
    }

    /** One-off connection test used by the setup dialog; never touches [state]. */
    fun test(cfg: ServerConfig): String {
        val a = PfApi(cfg)
        val info = a.get("/api/info", 6000)
        val st = a.get("/api/state", 6000)   // also proves the token / Access credentials are accepted
        val name = info.optString("name", "Pocketfader")
        val v = info.optString("version")
        val dev = st.optJSONObject("volume")?.optString("device").orEmpty()
        return buildString {
            append("Connected to $name")
            if (v.isNotBlank()) append(" $v")
            if (dev.isNotBlank()) append(" · $dev")
        }
    }
}

/** What went wrong with a YouTube Music command, in words (the extension and the server answer with short codes). */
internal fun ytmErrorText(code: String): String {
    val c = code.substringBefore(':').trim()
    return when {
        c == "extension_offline" -> "YouTube Music isn't open on the computer."
        c == "extension_timeout" || c == "timeout" -> "YouTube Music didn't answer in time. Try again."
        c.startsWith("no_shuffle_button") || c.startsWith("no_repeat_button") ->
            "Couldn't find that button on YouTube Music. In your browser, reload the Pocketfader extension, then refresh the YouTube Music tab."
        c == "autoplay_off_while_looping" -> "Turn loop off first: YouTube Music ignores autoplay while the queue is looping."
        c == "autoplay_not_applied" -> "YouTube Music didn't change autoplay. Try once more, or switch it in the browser."
        c == "no_autoplay_toggle" -> "Couldn't find YouTube Music's autoplay switch."
        c == "like_not_applied" || c == "like_failed" -> "YouTube Music didn't accept the like. Make sure you're signed in there."
        c == "no_like_button" -> "Couldn't find the like button on the YouTube Music page."
        c == "order_mismatch" || c == "queue_changed" -> "The queue changed while moving that song. Try again."
        c == "endpoint_not_accepted" -> "YouTube Music didn't start it. Try again."
        c == "no_player" -> "YouTube Music's player isn't ready yet."
        c == "no_app" -> "The YouTube Music page isn't ready yet."
        c == "row_not_found" -> "Couldn't find that song on the YouTube Music page."
        c == "not_in_queue" -> "That song isn't in the queue any more."
        c == "move_too_large" -> "That move is too big to do in one go."
        c == "cannot_move_played_or_current" -> "Songs already played (or playing) can't be moved."
        c == "menu_item_missing" -> "YouTube Music's menu didn't offer that here."
        c == "no_menu" || c == "no_menu_button" -> "Couldn't open that song's menu on the YouTube Music page."
        c == "nothing_to_pick" -> "Couldn't find something to play."
        c == "artist_not_found" -> "Couldn't find that artist on YouTube Music."
        c == "no_lyrics" -> "No lyrics for this song."
        c == "unknown_type" -> "The YouTube Music tab runs an older Pocketfader extension: reload it in the browser."
        c.isEmpty() -> "Something went wrong."
        else -> c.replace('_', ' ').replaceFirstChar { it.uppercase() }.take(160)
    }
}
