package app.pocketfader.client

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    val stamp: Long = 0L,                 // SystemClock.elapsedRealtime() of the last good poll
)

/**
 * The one place that talks to the server. The Activity and the media service both observe
 * [state]; polling runs while either is alive (see [acquire]/[release]).
 */
object Remote {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state

    @Volatile private var api: PfApi? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var holders = 0
    @Volatile private var visible = false
    @Volatile private var wantOutputs = false
    @Volatile private var fastUntil = 0L
    @Volatile private var settleUntil = 0L   // keep optimistic values for a moment so sliders don't jump back

    fun configure(cfg: ServerConfig) {
        api = if (cfg.isSet) PfApi(cfg) else null
        _state.value = RemoteState(configured = cfg.isSet)
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
            val fast = SystemClock.elapsedRealtime() < fastUntil
            delay(if (fast) 500L else if (visible) 1200L else 3000L)
        }
    }

    private fun pollOnce(a: PfApi) {
        val ytm = a.get("/api/ytm/state")
        val st = a.get("/api/state")
        val outs = if (wantOutputs) runCatching { parseOutputs(a.get("/api/outputs")) }.getOrNull() else null
        val settling = SystemClock.elapsedRealtime() < settleUntil
        _state.update { cur ->
            cur.copy(
                configured = true,
                online = true,
                error = null,
                now = parseNow(ytm.optJSONObject("now")),
                queue = parseQueue(ytm),
                source = ytm.str("source"),
                bridge = ytm.optBoolean("connected", false) && ytm.str("source") == "ytm",
                volume = if (settling) cur.volume else parseVolume(st),
                eq = parseEq(st),
                canOutputs = st.optJSONObject("capabilities")?.optBoolean("outputs", false) ?: false,
                outputs = if (settling) cur.outputs else (outs ?: cur.outputs),
                stamp = SystemClock.elapsedRealtime(),
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
                    val msg = if (r.optString("error") == "extension_offline") {
                        "YouTube Music extension isn't connected"
                    } else r.optString("error")
                    _state.update { it.copy(error = msg) }
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
        // Update the clock locally so the seek bar doesn't wait for the next poll.
        if (action == "playpause") _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(playing = !n.playing) }) }
        if (action == "seek" && position != null) _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(position = position) }) }
        command(b)
    }

    fun like(videoId: String, on: Boolean) {
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(liked = on) }) }
        command(JSONObject().put("type", "like").put("videoId", videoId).put("action", if (on) "like" else "remove"))
    }

    fun setRepeat(mode: String) {
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(repeat = mode) }) }
        command(JSONObject().put("type", "setRepeat").put("mode", mode))
    }

    fun setShuffle(on: Boolean) {
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(shuffle = on) }) }
        command(JSONObject().put("type", "setShuffle").put("on", on))
    }

    fun setAutoplay(on: Boolean) {
        _state.update { s -> s.copy(now = s.now?.let { n -> n.copy(autoplay = on) }) }
        command(JSONObject().put("type", "setAutoplay").put("on", on))
    }

    private fun ref(t: Track) = JSONObject()
        .put("section", t.section).put("index", t.index)
        .put("videoId", t.videoId ?: "").put("setVideoId", t.setVideoId ?: JSONObject.NULL)

    fun queuePlayAt(t: Track) = command(ref(t).put("type", "queuePlayAt"))
    fun queueRemove(t: Track) = command(ref(t).put("type", "queueRemove"))
    fun queueMove(t: Track, to: Int) = command(ref(t).put("type", "queueMove").put("to", to))
    fun queueMoveNext(t: Track) = command(ref(t).put("type", "queueMoveNext"))

    fun play(videoId: String, playlistId: String? = null) {
        val b = JSONObject().put("type", "play").put("videoId", videoId)
        if (playlistId != null) b.put("playlistId", playlistId)
        command(b)
    }

    fun startPlaylist(playlistId: String, shuffle: Boolean) =
        command(JSONObject().put("type", "startPlaylist").put("playlistId", playlistId).put("shuffle", shuffle))

    fun queueAdd(item: SearchItem) {
        val vid = item.videoId ?: return
        command(JSONObject().put("type", "queueAdd").put("videoId", vid).put("query", item.title + " " + item.subtitle))
    }

    /** Search needs the reply, so it's a plain blocking call for a coroutine on [Dispatchers.IO]. */
    fun search(query: String): List<SearchItem> {
        val a = api ?: return emptyList()
        val r = a.post("/api/ytm", JSONObject().put("type", "search").put("query", query), 20000)
        if (r.has("error")) {
            throw ApiException(
                200,
                if (r.optString("error") == "extension_offline") "The YouTube Music extension isn't connected." else r.optString("error"),
            )
        }
        return parseSearch(r)
    }

    // ---- volume / outputs / EQ

    private var lastVolSend = 0L

    /** [final] sends immediately; otherwise drags are throttled to about 10 updates per second. */
    fun setVolume(percent: Int, final: Boolean = true) {
        val p = percent.coerceIn(0, 100)
        settleUntil = SystemClock.elapsedRealtime() + 1500
        _state.update { it.copy(volume = it.volume.copy(percent = p)) }
        val now = SystemClock.elapsedRealtime()
        if (!final && now - lastVolSend < 100) return
        lastVolSend = now
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
        val now = SystemClock.elapsedRealtime()
        if (!final && now - lastVolSend < 100) return
        lastVolSend = now
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
        val now = SystemClock.elapsedRealtime()
        if (!final && now - lastVolSend < 120) return
        lastVolSend = now
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
