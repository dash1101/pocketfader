package app.pocketfader.client

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

// The @Immutable promises let Compose skip redrawing parts of the screen whose data didn't change,
// which matters on older phones.

@Immutable
data class Now(
    val title: String = "",
    val artist: String = "",
    val art: String? = null,
    val playing: Boolean = false,
    val duration: Double = 0.0,
    val videoId: String? = null,
    val liked: Boolean = false,
    val repeat: String = "NONE",       // NONE | ALL | ONE
    val shuffle: Boolean = false,
    val autoplay: Boolean? = null,     // null: this extension version doesn't report it
)

/** Playback position as last reported by the computer, and when (SystemClock.elapsedRealtime). */
@Immutable
data class Progress(val position: Double = 0.0, val at: Long = 0L)

@Immutable
data class Track(
    val videoId: String?,
    val title: String,
    val artist: String,
    val art: String?,
    val duration: String,
    val selected: Boolean,
    val section: String,               // "queue" | "autoplay"
    val index: Int,
    val setVideoId: String?,
)

@Immutable
data class VolumeState(
    val available: Boolean = false,
    val percent: Int = 0,
    val muted: Boolean = false,
    val device: String = "",
)

@Immutable
data class Output(
    val name: String,
    val description: String,
    val percent: Int,
    val muted: Boolean,
    val isDefault: Boolean,
    val kind: String,
)

@Immutable
data class EqState(
    val available: Boolean = false,
    val toggle: Boolean = false,       // has an on/off switch (the built-in EQ)
    val enabled: Boolean = true,
    val label: String = "",
    val status: String = "",
    val bands: Map<String, Double> = emptyMap(),
    val presets: List<String> = emptyList(),
    val min: Double = -12.0,
    val max: Double = 12.0,
)

/** Anything YouTube Music lists: a song, playlist, album or shelf card. */
@Immutable
data class SearchItem(
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val videoId: String?,
    val playlistId: String?,
    val kind: String,
    val browseId: String? = null,
    val setVideoId: String? = null,     // present on tracks of a playlist you own (needed to edit it)
)

@Immutable
data class Shelf(val title: String, val items: List<SearchItem>)

@Immutable
data class LyricWord(val start: Double, val end: Double, val text: String)

@Immutable
data class LyricLine(val t: Double?, val text: String, val rom: String?, val tr: String?, val words: List<LyricWord>? = null)

@Immutable
data class UpdateInfo(
    val latest: String = "",
    val notes: String = "",
    val url: String = "",
    val apk: Boolean = false,
    val error: String? = null,
)

@Immutable
data class LyricSource(val id: String, val label: String, val found: Boolean, val synced: Boolean, val words: Boolean = false)

@Immutable
data class LyricsResult(
    val found: Boolean = false,
    val source: String? = null,
    val sources: List<LyricSource> = emptyList(),
    val synced: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    val credit: String? = null,
    val pending: Boolean = false,        // slower sources are still being asked
)

fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(f(it)) }
    return out
}

fun parseNow(o: JSONObject?): Now? {
    if (o == null || o.length() == 0) return null
    return Now(
        title = o.str("title").orEmpty(),
        artist = o.str("artist").orEmpty(),
        art = o.str("art"),
        playing = o.optBoolean("playing", false),
        duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
        videoId = o.str("videoId"),
        liked = o.optString("liked") == "LIKE",
        repeat = o.str("repeat") ?: "NONE",
        shuffle = o.optBoolean("shuffle", false),
        autoplay = if (o.has("autoplay") && !o.isNull("autoplay")) o.optBoolean("autoplay") else null,
    )
}

fun parsePosition(o: JSONObject?): Double =
    o?.optDouble("position", 0.0)?.let { if (it.isNaN()) 0.0 else it } ?: 0.0

fun parseQueue(o: JSONObject): List<Track> =
    o.optJSONArray("queue").mapObjects { q ->
        Track(
            videoId = q.str("videoId"),
            title = q.str("title").orEmpty(),
            artist = q.str("artist").orEmpty(),
            art = q.str("art"),
            duration = q.str("duration").orEmpty(),
            selected = q.optBoolean("selected", false),
            section = q.str("section") ?: "queue",
            index = q.optInt("index", 0),
            setVideoId = q.str("setVideoId"),
        )
    }

fun parseVolume(state: JSONObject): VolumeState {
    val v = state.optJSONObject("volume") ?: return VolumeState()
    return VolumeState(
        available = v.optBoolean("available", false),
        percent = v.optInt("percent", 0),
        muted = v.optBoolean("muted", false),
        device = v.str("device").orEmpty(),
    )
}

fun parseEq(state: JSONObject): EqState {
    val caps = state.optJSONObject("capabilities") ?: return EqState()
    if (!caps.optBoolean("eq", false)) return EqState()
    val e = state.optJSONObject("engine") ?: JSONObject()
    val limits = state.optJSONObject("limits")
    val bands = LinkedHashMap<String, Double>()
    e.optJSONObject("eq")?.let { eq ->
        for (k in listOf("bass", "mid", "high")) if (eq.has(k)) bands[k] = eq.optDouble(k, 0.0)
    }
    val presets = ArrayList<String>()
    e.optJSONArray("presets")?.let { for (i in 0 until it.length()) presets.add(it.optString(i)) }
    val toggle = caps.optBoolean("eq_toggle", false)
    return EqState(
        available = e.optBoolean("available", true) && (toggle || e.optBoolean("connected", false)),
        toggle = toggle,
        enabled = if (toggle) e.optBoolean("enabled", false) else true,
        label = caps.str("engine_label").orEmpty(),
        status = e.str("status") ?: e.str("reason").orEmpty(),
        bands = bands,
        presets = presets,
        min = limits?.optDouble("eq_min", -12.0) ?: -12.0,
        max = limits?.optDouble("eq_max", 12.0) ?: 12.0,
    )
}

fun parseOutputs(o: JSONObject): List<Output> =
    o.optJSONArray("outputs").mapObjects {
        Output(
            name = it.optString("name"),
            description = it.str("description") ?: it.optString("name"),
            percent = it.optInt("percent", 0),
            muted = it.optBoolean("muted", false),
            isDefault = it.optBoolean("default", false),
            kind = it.optString("kind"),
        )
    }

private fun item(it: JSONObject) = SearchItem(
    title = it.optString("title"),
    subtitle = it.str("subtitle").orEmpty(),
    thumb = it.str("thumb"),
    videoId = it.str("videoId"),
    playlistId = it.str("playlistId"),
    kind = it.str("kind") ?: "unknown",
    browseId = it.str("browseId"),
    setVideoId = it.str("setVideoId"),
)

fun parseItems(o: JSONObject, key: String = "items"): List<SearchItem> = o.optJSONArray(key).mapObjects { item(it) }

fun parseShelves(o: JSONObject): List<Shelf> =
    o.optJSONArray("sections").mapObjects { s -> Shelf(s.optString("title"), parseItems(s)) }
        .filter { it.items.isNotEmpty() }

fun parseLyrics(o: JSONObject): LyricsResult {
    val sources = o.optJSONArray("sources").mapObjects {
        LyricSource(it.optString("id"), it.optString("label"), it.optBoolean("found"), it.optBoolean("synced"), it.optBoolean("words"))
    }
    val l = o.optJSONObject("lyrics")
    val lines = l?.optJSONArray("lines").mapObjects {
        LyricLine(
            t = if (it.isNull("t")) null else it.optDouble("t"),
            text = it.optString("text"),
            rom = it.str("rom"),
            tr = it.str("tr"),
            words = it.optJSONArray("w")?.let { arr ->
                val out = ArrayList<LyricWord>(arr.length())
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONArray(i) ?: continue
                    out.add(LyricWord(w.optDouble(0), w.optDouble(1), w.optString(2)))
                }
                out.takeIf { it.isNotEmpty() }
            },
        )
    }
    return LyricsResult(
        pending = o.optBoolean("pending", false),
        found = o.optBoolean("found") && lines.isNotEmpty(),
        source = o.str("source"),
        sources = sources,
        synced = l?.optBoolean("synced") ?: false,
        lines = lines,
        credit = l?.str("credit"),
    )
}

fun formatTime(seconds: Double): String {
    val s = if (seconds.isNaN() || seconds < 0) 0 else seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun parseUpdate(o: JSONObject) = UpdateInfo(
    latest = o.optString("latest", ""),
    notes = o.optString("notes", ""),
    url = o.optString("url", ""),
    apk = o.optBoolean("apk", false),
    error = o.str("error"),
)

/** "0.1.0-alpha.4" / "v0.1.0a4" -> comparable list (final releases sort after their alphas); null if not a version. */
fun versionKey(text: String): List<Int>? {
    val m = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:[-._]?(alpha|beta|rc|a|b)[-._]?(\d+))?""", RegexOption.IGNORE_CASE).matchEntire(text.trim()) ?: return null
    val g = m.groupValues
    val stage = when (g[4].lowercase()) { "" -> 9; "a", "alpha" -> 0; "b", "beta" -> 1; else -> 2 }
    return listOf(g[1].toInt(), g[2].toInt(), g[3].toInt(), stage, g[5].ifEmpty { "0" }.toInt())
}

fun isNewer(latest: String, current: String): Boolean {
    val a = versionKey(latest) ?: return false
    val b = versionKey(current) ?: return true
    for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
    return false
}

// ---- YTM Beacon (Discord presence), as relayed by the computer

@Immutable
data class BeaconSettings(
    val privateMode: Boolean = false,
    val showPaused: Boolean = true,
    val pauseTimeoutMinutes: Int = 5,
    val showProgress: Boolean = true,
    val showStateIcon: Boolean = true,
    val showButtons: Boolean = true,
    val showAlbumArt: Boolean = true,
    val statusDisplay: String = "name",    // name | state | details
)

@Immutable
data class BeaconSong(val title: String, val artist: String, val playing: Boolean)

@Immutable
data class BeaconInstall(
    val installed: Boolean = false,
    val canInstall: Boolean = false,
    val canStart: Boolean = false,
    val latest: String? = null,
    val updateAvailable: Boolean = false,
    val jobState: String = "idle",          // idle | running | done | failed
    val jobMessage: String = "",
)

@Immutable
data class BeaconStatus(
    val reachable: Boolean = false,
    val version: String = "",
    val headline: String = "unreachable",
    val detail: String? = null,
    val visible: Boolean = false,
    val snoozeSec: Int = 0,
    val discordUser: String? = null,
    val song: BeaconSong? = null,
    val settings: BeaconSettings = BeaconSettings(),
    val install: BeaconInstall = BeaconInstall(),
    val receivedAt: Long = 0L,              // SystemClock.elapsedRealtime() when this arrived (counts the hide timer down)
)

fun parseBeacon(o: JSONObject, receivedAt: Long): BeaconStatus {
    val s = o.optJSONObject("settings")
    val np = o.optJSONObject("nowPlaying")
    return BeaconStatus(
        reachable = o.optBoolean("reachable", false),
        version = o.str("version").orEmpty(),
        headline = o.str("headline") ?: "unreachable",
        detail = o.str("detail"),
        visible = o.optBoolean("visible", false),
        snoozeSec = o.optInt("snoozeSec", 0),
        discordUser = o.optJSONObject("discord")?.str("user"),
        song = if (np == null) null else BeaconSong(np.str("title").orEmpty(), np.str("artist").orEmpty(), np.optString("state") == "playing"),
        settings = if (s == null) BeaconSettings() else BeaconSettings(
            privateMode = s.optBoolean("privateMode", false),
            showPaused = s.optBoolean("showPaused", true),
            pauseTimeoutMinutes = s.optInt("pauseTimeoutMinutes", 5),
            showProgress = s.optBoolean("showProgress", true),
            showStateIcon = s.optBoolean("showStateIcon", true),
            showButtons = s.optBoolean("showButtons", true),
            showAlbumArt = s.optBoolean("showAlbumArt", true),
            statusDisplay = s.str("statusDisplay") ?: "name",
        ),
        install = o.optJSONObject("install")?.let { i ->
            val job = i.optJSONObject("job")
            BeaconInstall(
                installed = i.optBoolean("installed", false), canInstall = i.optBoolean("canInstall", false),
                canStart = i.optBoolean("canStart", false), latest = i.str("latest"),
                updateAvailable = i.optBoolean("updateAvailable", false),
                jobState = job?.str("state") ?: "idle", jobMessage = job?.str("message").orEmpty(),
            )
        } ?: BeaconInstall(),
        receivedAt = receivedAt,
    )
}
