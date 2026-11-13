package app.pocketfader.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun searchItemsToJson(items: List<SearchItem>): JSONArray = JSONArray().apply {
    for (i in items) {
        put(
            JSONObject().put("title", i.title).put("subtitle", i.subtitle).put("thumb", i.thumb ?: JSONObject.NULL)
                .put("videoId", i.videoId ?: JSONObject.NULL).put("playlistId", i.playlistId ?: JSONObject.NULL)
                .put("kind", i.kind).put("browseId", i.browseId ?: JSONObject.NULL).put("setVideoId", i.setVideoId ?: JSONObject.NULL)
        )
    }
}

fun searchItemsFromJson(arr: JSONArray): List<SearchItem> = parseItems(JSONObject().put("items", arr))

/**
 * The songs of playlists and albums as they were the last time they were opened: shown at once when one is opened again,
 * while the current list is fetched (from your account through the computer, or from YouTube). Kept in memory and in the
 * app's cache folder (Android may clear that when space runs low; nothing is lost then).
 */
object PlaylistCache {
    private const val IN_MEMORY = 24
    private const val ON_DISK = 80
    private var dir: File? = null
    private val mem = object : LinkedHashMap<String, List<SearchItem>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchItem>>?) = size > IN_MEMORY
    }

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "playlists").apply { mkdirs() }
    }

    private fun file(key: String): File? = dir?.let { d ->
        File(d, Integer.toHexString(key.hashCode()) + "-" + key.filter { it.isLetterOrDigit() }.take(40) + ".json")
    }

    /** The list last seen for [key], or null. Reads storage: call it off the main thread. */
    @Synchronized
    fun get(key: String): List<SearchItem>? {
        mem[key]?.let { return it }
        val f = file(key)?.takeIf { it.exists() } ?: return null
        val items = runCatching { searchItemsFromJson(JSONArray(f.readText())) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        mem[key] = items
        return items
    }

    /** Remember the list for [key]. Writes storage: call it off the main thread. */
    @Synchronized
    fun put(key: String, items: List<SearchItem>) {
        if (items.isEmpty()) return
        mem[key] = items
        val f = file(key) ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(searchItemsToJson(items).toString())
            tmp.renameTo(f)
            // the oldest ones go once there are many
            val all = dir?.listFiles { x -> x.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }
            all.drop(ON_DISK).forEach { it.delete() }
        }
    }
}
