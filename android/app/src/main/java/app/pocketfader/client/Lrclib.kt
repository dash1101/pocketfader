package app.pocketfader.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics straight from LRCLIB (a free, open lyrics database), for when no computer is around. The answer is shaped like the
 * computer's /api/lyrics reply, so the rest of the app treats both the same.
 */
object Lrclib {
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "YTM Starcast (https://github.com/dash1101/ytm-starcast)")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** [title] by [artist]; null when LRCLIB has nothing. Blocking. */
    fun lookup(title: String, artist: String, durationSec: Double): JSONObject? {
        val base = "https://lrclib.net/api/"
        var hit: JSONObject? = null
        if (durationSec > 0) {
            get(base + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist) + "&duration=" + durationSec.toInt())
                ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { hit = it }
        }
        if (hit == null) {
            val arr = get(base + "search?track_name=" + enc(title) + "&artist_name=" + enc(artist))?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.str("syncedLyrics") != null) { hit = o; break }
                    if (hit == null && o.str("plainLyrics") != null) hit = o
                }
            }
        }
        val h = hit ?: return null
        val synced = h.str("syncedLyrics")
        val lines = JSONArray()
        if (synced != null) {
            parseLrc(synced).forEach { (t, text) -> lines.put(JSONObject().put("t", t).put("text", text)) }
        } else {
            (h.str("plainLyrics") ?: return null).lines().forEach { lines.put(JSONObject().put("t", JSONObject.NULL).put("text", it)) }
        }
        if (lines.length() == 0) return null
        val isSynced = synced != null
        return JSONObject()
            .put("found", true).put("source", "lrclib")
            .put("sources", JSONArray().put(JSONObject().put("id", "lrclib").put("label", "LRCLIB").put("found", true).put("synced", isSynced)))
            .put("lyrics", JSONObject().put("synced", isSynced).put("lines", lines).put("credit", "Lyrics from LRCLIB"))
    }

    /** "[01:02.34] words" lines (a line may carry several timestamps) to (seconds, words), in time order. */
    fun parseLrc(text: String): List<Pair<Double, String>> {
        val tag = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
        val out = ArrayList<Pair<Double, String>>()
        for (raw in text.lines()) {
            val stamps = tag.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val words = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val sec = m.groupValues[1].toInt() * 60 + (m.groupValues[2].replace(':', '.').toDoubleOrNull() ?: 0.0)
                out.add(sec to words)
            }
        }
        return out.sortedBy { it.first }
    }
}
