package app.pocketfader.client

import org.json.JSONArray
import org.json.JSONObject

data class Now(
    val title: String = "",
    val artist: String = "",
    val art: String? = null,
    val playing: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val videoId: String? = null,
    val liked: Boolean = false,
    val repeat: String = "NONE",       // NONE | ALL | ONE
    val shuffle: Boolean = false,
    val autoplay: Boolean? = null,     // null: this extension version doesn't report it
)

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

data class VolumeState(
    val available: Boolean = false,
    val percent: Int = 0,
    val muted: Boolean = false,
    val device: String = "",
)

data class Output(
    val name: String,
    val description: String,
    val percent: Int,
    val muted: Boolean,
    val isDefault: Boolean,
    val kind: String,
)

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

data class SearchItem(
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val videoId: String?,
    val playlistId: String?,
    val kind: String,
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
        position = o.optDouble("position", 0.0).let { if (it.isNaN()) 0.0 else it },
        duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
        videoId = o.str("videoId"),
        liked = o.optString("liked") == "LIKE",
        repeat = o.str("repeat") ?: "NONE",
        shuffle = o.optBoolean("shuffle", false),
        autoplay = if (o.has("autoplay") && !o.isNull("autoplay")) o.optBoolean("autoplay") else null,
    )
}

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

fun parseSearch(o: JSONObject): List<SearchItem> =
    o.optJSONArray("items").mapObjects {
        SearchItem(
            title = it.optString("title"),
            subtitle = it.str("subtitle").orEmpty(),
            thumb = it.str("thumb"),
            videoId = it.str("videoId"),
            playlistId = it.str("playlistId"),
            kind = it.str("kind") ?: "unknown",
        )
    }

fun formatTime(seconds: Double): String {
    val s = if (seconds.isNaN() || seconds < 0) 0 else seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
