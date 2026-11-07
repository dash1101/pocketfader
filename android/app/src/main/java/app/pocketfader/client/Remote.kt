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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    fun configure(cfg: ServerConfig) {
        api = if (cfg.isSet) PfApi(cfg) else null
        _state.value = RemoteState(configured = cfg.isSet)
        _progress.value = Progress()
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
        _progress.value = Progress(parsePosition(nowJson), sampled)
        val queue = parseQueue(ytm)
        val source = ytm.str("source")
        val bridge = ytm.optBoolean("connected", false) && source == "ytm"
        _state.update { cur ->
            cur.copy(
                configured = true,
                online = true,
                error = null,
                now = withHeld(now, cur.now),
                queue = queue,
                source = source,
                bridge = bridge,
                volume = if (st == null || settling) cur.volume else parseVolume(st),
                eq = if (st == null) cur.eq else parseEq(st),
                canOutputs = st?.optJSONObject("capabilities")?.optBoolean("outputs", false) ?: cur.canOutputs,
                canLyrics = st?.optJSONObject("capabilities")?.optBoolean("lyrics", false) ?: cur.canLyrics,
                canBeacon = st?.optJSONObject("capabilities")?.optBoolean("beacon", false) ?: cur.canBeacon,
                outputs = if (settling) cur.outputs else (outs ?: cur.outputs),
            )
        }
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
                if (r.has("error")) {
                    val err = r.optString("error")
                    notify(
                        when {
                            err == "extension_offline" -> "The YouTube Music extension isn't connected"
                            err.startsWith("no_shuffle_button") || err.startsWith("no_repeat_button") ->
                                "Couldn't find that button on YouTube Music. In your browser, reload the Pocketfader extension, then refresh the YouTube Music tab."
                            else -> err.take(160)
                        }
                    )
                }
                onResult?.invoke(r)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.friendly()) }
            }
            boost()
            runCatching { pollOnce(a) }
        }
    }

    fun transport(action: String, position: Double? = null) {
        val b = JSONObject().put("type", "transport").put("action", action)
        if (position != null) b.put("position", position)
        if (action == "playpause") hold("playing")
        if (action == "playpause") _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(playing = !n.playing) }) }
        if (action == "seek" && position != null) _progress.value = Progress(position, SystemClock.elapsedRealtime())
        command(b)
    }

    fun like(videoId: String, on: Boolean) {
        hold("liked")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(liked = on) }) }
        command(JSONObject().put("type", "like").put("videoId", videoId).put("action", if (on) "like" else "remove"))
    }

    fun setRepeat(mode: String) {
        hold("repeat")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(repeat = mode) }) }
        command(JSONObject().put("type", "setRepeat").put("mode", mode))
    }

    fun setShuffle(on: Boolean) {
        hold("shuffle")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(shuffle = on) }) }
        command(JSONObject().put("type", "setShuffle").put("on", on))
    }

    fun setAutoplay(on: Boolean) {
        hold("autoplay")
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(autoplay = on) }) }
        command(JSONObject().put("type", "setAutoplay").put("on", on))
    }

    private fun ref(t: Track) = JSONObject()
        .put("section", t.section).put("index", t.index)
        .put("videoId", t.videoId ?: "").put("setVideoId", t.setVideoId ?: JSONObject.NULL)

    fun queuePlayAt(t: Track) = command(ref(t).put("type", "queuePlayAt"))
    fun queueRemove(t: Track) = command(ref(t).put("type", "queueRemove"))
    fun queueMove(t: Track, to: Int) = command(ref(t).put("type", "queueMove").put("to", to))

    fun queueMoveNext(t: Track) {
        command(ref(t).put("type", "queueMoveNext"))
        notify("Playing next: ${t.title}")
    }

    /** Copies a track (typically an autoplay suggestion) onto the end of the real queue. */
    fun queueAddEnd(t: Track) {
        command(ref(t).put("type", "queueAddEnd"))
        notify("Added to your queue: ${t.title}")
    }

    fun play(videoId: String, playlistId: String? = null) {
        val b = JSONObject().put("type", "play").put("videoId", videoId)
        if (playlistId != null) b.put("playlistId", playlistId)
        command(b)
    }

    fun startPlaylist(playlistId: String, shuffle: Boolean) {
        command(JSONObject().put("type", "startPlaylist").put("playlistId", playlistId).put("shuffle", shuffle))
        notify(if (shuffle) "Shuffling playlist" else "Starting playlist")
    }

    fun startMix(videoId: String) {
        command(JSONObject().put("type", "startMix").put("videoId", videoId))
        notify("Starting radio")
    }

    /** Add a song from search / a playlist / a shelf to the current queue, next or at the end. */
    fun queueAdd(item: SearchItem, next: Boolean) {
        val vid = item.videoId ?: return
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
                if (r.optString("error") == "extension_offline") "The YouTube Music extension isn't connected." else r.optString("error"),
            )
        }
        return r
    }

    fun search(query: String): List<SearchItem> =
        parseItems(ytmCall(JSONObject().put("type", "search").put("query", query), 20000))

    fun home(): List<Shelf> = parseShelves(ytmCall(JSONObject().put("type", "home"), 40000))

    fun playlists(): List<SearchItem> = parseItems(ytmCall(JSONObject().put("type", "playlists")), "playlists")

    fun playlistItems(p: SearchItem): List<SearchItem> =
        parseItems(
            ytmCall(
                JSONObject().put("type", "playlistItems")
                    .put("playlistId", p.playlistId ?: JSONObject.NULL).put("browseId", p.browseId ?: JSONObject.NULL)
            )
        )

    /** A playlist change (move, remove, rename, add, create, delete, queueSave); the reply has `ok`. */
    fun playlistOp(body: JSONObject): JSONObject = ytmCall(body)

    fun lyrics(now: Now, source: String?, refresh: Boolean): LyricsResult {
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
        settleUntil = SystemClock.elapsedRealtime() + 1500
        _state.update { it.copy(volume = it.volume.copy(percent = p)) }
        if (!final && throttled(100)) return
        runApi { it.post("/api/volume", JSONObject().put("percent", p)) }
    }

    fun adjustVolume(delta: Int) {
        val target = (_state.value.volume.percent + delta).coerceIn(0, 100)
        settleUntil = SystemClock.elapsedRealtime() + 1500
        _state.update { it.copy(volume = it.volume.copy(percent = target)) }
        runApi { it.post("/api/volume", JSONObject().put("delta", delta)) }
    }

    fun setMuted(muted: Boolean) {
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
