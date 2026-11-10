package app.pocketfader.client

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One of your playlists, as last synced from your YouTube Music account. */
@Immutable
data class SyncedList(
    val id: String,
    val title: String,
    val thumb: String?,
    val subtitle: String,
    val tracks: List<LocalItem>,
    val owned: Boolean,
    val syncedAt: Long,
) {
    fun toSearchItem() = SearchItem(title, subtitle.ifBlank { "${tracks.size} songs" }, thumb, null, id, "playlist")
}

/** A song you listened to (on this phone or on the computer), for the recommendations. */
@Immutable
data class Play(
    val id: String,
    val title: String,
    val artist: String,
    val art: String?,
    val dur: Double,
    val at: Long,           // when it ended, System.currentTimeMillis()
    val heard: Double,      // seconds listened
    val skipped: Boolean,   // moved on early (a "not now" signal)
) {
    fun toLocal() = LocalItem(id, null, title, artist, art, dur)
}

/**
 * Your YouTube Music library on the phone: playlists, liked songs and your account's home page, synced from the account
 * while the computer's YouTube Music tab is connected, so they are all still here without it. Changes made without the
 * account (likes, songs added to a playlist) are kept and sent to the account at the next sync.
 *
 * Also keeps what you listen to (on the phone and on the computer), which is what the recommendations learn from.
 * Everything stays in the app's private storage.
 */
object Library {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _playlists = MutableStateFlow<List<SyncedList>>(emptyList())
    val playlists: StateFlow<List<SyncedList>> = _playlists
    private val _liked = MutableStateFlow<List<LocalItem>>(emptyList())
    /** Your liked songs, newest first. */
    val liked: StateFlow<List<LocalItem>> = _liked
    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds
    private val _home = MutableStateFlow<List<Shelf>>(emptyList())
    /** Your account's home page (Quick picks, Listen again, mixes...) as it was at the last sync. */
    val home: StateFlow<List<Shelf>> = _home
    private val _keep = MutableStateFlow<Set<String>>(emptySet())
    /** Playlists kept downloaded on this phone (their new songs are downloaded at each sync). */
    val keepOffline: StateFlow<Set<String>> = _keep
    private val _syncedAt = MutableStateFlow(0L)
    val syncedAt: StateFlow<Long> = _syncedAt
    private val _status = MutableStateFlow<String?>(null)
    /** "Syncing 3 of 12 playlists…" while a sync runs; null otherwise. */
    val status: StateFlow<String?> = _status
    private val _history = MutableStateFlow<List<Play>>(emptyList())
    val history: StateFlow<List<Play>> = _history
    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _searches

    private val pending = ArrayList<JSONObject>()     // changes waiting for the account
    @Volatile private var syncing = false
    @Volatile private var lastAttempt = 0L

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "library").apply { mkdirs() }
        scope.launch { load() }
    }

    private fun file(name: String) = File(dir, name)

    private fun readJson(name: String): JSONObject? = runCatching { JSONObject(file(name).readText()) }.getOrNull()

    private fun writeJson(name: String, o: JSONObject) {
        if (!::dir.isInitialized) return
        runCatching {
            val tmp = file("$name.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(name))
        }
    }

    // ---------------------------------------------------------------- storage

    private fun itemJson(i: LocalItem) = JSONObject().put("id", i.videoId ?: JSONObject.NULL).put("t", i.title).put("a", i.artist)
        .put("art", i.art ?: JSONObject.NULL).put("d", i.durationSec).put("al", i.album)

    private fun itemFrom(o: JSONObject) = LocalItem(o.str("id"), null, o.optString("t"), o.optString("a"), o.str("art"), o.optDouble("d", 0.0), o.optString("al"))

    private fun items(arr: JSONArray?): List<LocalItem> = arr.mapObjects { itemFrom(it) }.filter { isYtId(it.videoId) }

    private fun load() {
        readJson("library.json")?.let { o ->
            _playlists.value = o.optJSONArray("playlists").mapObjects { p ->
                SyncedList(
                    p.optString("id"), p.optString("title"), p.str("thumb"), p.optString("subtitle"),
                    items(p.optJSONArray("tracks")), p.optBoolean("owned"), p.optLong("syncedAt"),
                )
            }
            _liked.value = items(o.optJSONArray("liked"))
            _likedIds.value = _liked.value.mapNotNull { it.videoId }.toHashSet()
            _home.value = o.optJSONArray("home").mapObjects { s -> Shelf(s.optString("title"), parseItems(s)) }.filter { it.items.isNotEmpty() }
            _keep.value = o.optJSONArray("keep").strings().toHashSet()
            _syncedAt.value = o.optLong("syncedAt")
            synchronized(lock) { pending.clear(); pending.addAll(o.optJSONArray("pending").mapObjects { it }) }
            _searches.value = o.optJSONArray("searches").strings()
        }
        readJson("history.json")?.let { o ->
            _history.value = o.optJSONArray("plays").mapObjects { p ->
                Play(p.optString("id"), p.optString("t"), p.optString("a"), p.str("art"), p.optDouble("d", 0.0), p.optLong("at"), p.optDouble("h", 0.0), p.optBoolean("s"))
            }
        }
    }

    private fun shelfJson(s: Shelf): JSONObject {
        val arr = JSONArray()
        for (i in s.items) {
            arr.put(
                JSONObject().put("title", i.title).put("subtitle", i.subtitle).put("thumb", i.thumb ?: JSONObject.NULL)
                    .put("videoId", i.videoId ?: JSONObject.NULL).put("playlistId", i.playlistId ?: JSONObject.NULL)
                    .put("browseId", i.browseId ?: JSONObject.NULL).put("kind", i.kind)
            )
        }
        return JSONObject().put("title", s.title).put("items", arr)
    }

    private fun save() {
        val o = JSONObject()
        o.put("playlists", JSONArray().also { a ->
            for (p in _playlists.value) a.put(
                JSONObject().put("id", p.id).put("title", p.title).put("thumb", p.thumb ?: JSONObject.NULL).put("subtitle", p.subtitle)
                    .put("owned", p.owned).put("syncedAt", p.syncedAt).put("tracks", JSONArray().also { t -> p.tracks.forEach { t.put(itemJson(it)) } })
            )
        })
        o.put("liked", JSONArray().also { a -> _liked.value.forEach { a.put(itemJson(it)) } })
        o.put("home", JSONArray().also { a -> _home.value.forEach { a.put(shelfJson(it)) } })
        o.put("keep", JSONArray(_keep.value.toList()))
        o.put("syncedAt", _syncedAt.value)
        o.put("pending", JSONArray().also { a -> synchronized(lock) { pending.forEach { a.put(it) } } })
        o.put("searches", JSONArray(_searches.value))
        writeJson("library.json", o)
    }

    private fun saveSoon() { scope.launch { synchronized(this@Library) { save() } } }

    private fun saveHistory() {
        val a = JSONArray()
        for (p in _history.value) a.put(
            JSONObject().put("id", p.id).put("t", p.title).put("a", p.artist).put("art", p.art ?: JSONObject.NULL)
                .put("d", p.dur).put("at", p.at).put("h", p.heard).put("s", p.skipped)
        )
        writeJson("history.json", JSONObject().put("plays", a))
    }

    // ---------------------------------------------------------------- reading

    fun playlist(id: String): SyncedList? = _playlists.value.firstOrNull { it.id == id || "VL" + it.id == id || it.id == "VL$id" }

    fun isLiked(videoId: String?): Boolean = videoId != null && videoId in _likedIds.value

    val hasLibrary: Boolean get() = _playlists.value.isNotEmpty() || _liked.value.isNotEmpty()

    // ---------------------------------------------------------------- changes made on the phone

    /** Like or unlike a song. Sent to the account now when [sendNow] is true; otherwise at the next sync. */
    fun setLiked(item: LocalItem, on: Boolean, sendNow: Boolean) {
        val id = item.videoId ?: return
        _liked.update { list -> if (on) listOf(item) + list.filter { it.videoId != id } else list.filter { it.videoId != id } }
        _likedIds.value = _liked.value.mapNotNull { it.videoId }.toHashSet()
        if (!sendNow) queue(JSONObject().put("type", "like").put("videoId", id).put("action", if (on) "like" else "remove"))
        saveSoon()
        AutoDownload.soon()
    }

    /** Add a song to one of your playlists without the account at hand: shown here now, added to the account at the next sync. */
    fun addToPlaylistLater(playlistId: String, item: LocalItem) {
        val id = item.videoId ?: return
        _playlists.update { all -> all.map { if (it.id == playlistId) it.copy(tracks = it.tracks + item) else it } }
        queue(JSONObject().put("type", "playlistAdd").put("playlistId", playlistId).put("videoId", id))
        saveSoon()
    }

    private fun queue(op: JSONObject) {
        synchronized(lock) {
            // a newer like/unlike of the same song replaces the older one
            if (op.optString("type") == "like") pending.removeAll { it.optString("type") == "like" && it.optString("videoId") == op.optString("videoId") }
            pending.add(op)
            while (pending.size > 500) pending.removeAt(0)
        }
    }

    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun setKeepOffline(playlistId: String, on: Boolean) {
        _keep.update { if (on) it + playlistId else it - playlistId }
        saveSoon()
        AutoDownload.soon()
    }

    fun rememberSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        _searches.update { (listOf(t) + it.filter { s -> !s.equals(t, ignoreCase = true) }).take(12) }
        saveSoon()
    }

    fun forgetSearches() { _searches.value = emptyList(); saveSoon() }

    // ---------------------------------------------------------------- listening history

    /** A song finished, or was skipped. Very short plays that weren't skips (a quick tap through) aren't kept. */
    fun recordPlay(item: LocalItem, heard: Double, dur: Double, skipped: Boolean) {
        val id = item.videoId ?: return
        if (!isYtId(id)) return
        if (heard < 10 && !skipped) return
        val p = Play(id, item.title, item.artist, item.art, if (dur > 0) dur else item.durationSec, System.currentTimeMillis(), heard, skipped)
        _history.update { (it + p).takeLast(3000) }
        scope.launch { synchronized(this@Library) { saveHistory() } }
        Recommend.noteListen()
    }

    // what the computer is playing (watched from the app's polling), so the recommendations learn from that too
    private var compItem: LocalItem? = null
    private var compPos = 0.0
    private var compDur = 0.0

    @Synchronized fun observeComputer(now: Now?, position: Double) {
        val id = now?.videoId?.takeIf(::isYtId)
        val prev = compItem
        if (prev != null && prev.videoId != id) {
            val skipped = compPos < minOf(30.0, compDur * 0.5) && compDur > 0
            recordPlay(prev, compPos, compDur, skipped)
        }
        if (id == null || now == null) { compItem = null; return }
        if (prev?.videoId != id) {
            compItem = LocalItem(id, null, now.title, now.artist, now.art, now.duration)
            compPos = position
        } else {
            compPos = maxOf(compPos, position)
        }
        compDur = now.duration
    }

    fun clearHistory() {
        _history.value = emptyList()
        scope.launch { synchronized(this@Library) { saveHistory() } }
        Recommend.invalidate()
    }

    // ---------------------------------------------------------------- syncing with the account

    /** Sync when the computer's YouTube Music is connected and the last sync is old (or there are changes to send). */
    fun syncIfDue() {
        if (!Settings.prefs.value.autoSync || syncing) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 10 * 60_000L) return
        val due = now - _syncedAt.value > 6 * 3600_000L || pendingCount > 0
        if (due) sync()
    }

    /** Bring your playlists, liked songs and home page over from the account (needs the computer's YouTube Music tab). */
    fun sync() {
        if (syncing) return
        syncing = true
        lastAttempt = System.currentTimeMillis()
        scope.launch {
            try {
                if (!Remote.state.value.ytm) {
                    _status.value = null
                    Remote.notify("Syncing needs YouTube Music open on the computer.")
                    return@launch
                }
                _status.value = "Sending your changes…"
                flushPending()
                _status.value = "Syncing your liked songs…"
                runCatching { Remote.playlistItems(SearchItem("Liked music", "", null, null, "LM", "playlist"), limit = 5000) }
                    .getOrNull()?.mapNotNull { it.toLocal() }?.let { list ->
                        // likes made here since that list was read stay in it
                        val pendingLikes = synchronized(lock) { pending.filter { it.optString("type") == "like" } }
                        var merged = list
                        for (op in pendingLikes) {
                            val vid = op.optString("videoId")
                            merged = if (op.optString("action") == "like") {
                                (_liked.value.firstOrNull { it.videoId == vid }?.let { listOf(it) } ?: emptyList()) + merged.filter { it.videoId != vid }
                            } else merged.filter { it.videoId != vid }
                        }
                        _liked.value = merged
                        _likedIds.value = merged.mapNotNull { it.videoId }.toHashSet()
                    }
                val lists = Remote.playlists().filter {
                    val id = it.playlistId ?: it.browseId?.removePrefix("VL")
                    id != null && id != "LM" && id != "SE"
                }
                val old = _playlists.value.associateBy { it.id }
                val out = ArrayList<SyncedList>()
                for ((i, p) in lists.withIndex()) {
                    val id = p.playlistId ?: p.browseId?.removePrefix("VL") ?: continue
                    _status.value = "Syncing playlists (${i + 1} of ${lists.size})…"
                    val tracks = runCatching { Remote.playlistItems(p, limit = 3000) }.getOrNull()
                    if (tracks == null) {
                        old[id]?.let { out.add(it.copy(title = p.title, thumb = p.thumb ?: it.thumb)) }   // keep the last copy
                    } else {
                        out.add(
                            SyncedList(
                                id, p.title, p.thumb, p.subtitle, tracks.mapNotNull { it.toLocal() },
                                owned = tracks.any { it.setVideoId != null }, syncedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                    delay(200)
                }
                _playlists.value = out
                _keep.update { keep -> keep.filter { k -> out.any { it.id == k } }.toHashSet() }
                _status.value = "Syncing your home page…"
                runCatching { Remote.home() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { _home.value = it }
                _syncedAt.value = System.currentTimeMillis()
                synchronized(this@Library) { save() }
                Remote.notify("Library synced: ${out.size} playlists, ${_liked.value.size} liked songs")
                Recommend.invalidate()
                AutoDownload.soon()
            } catch (e: Exception) {
                Remote.notify("Sync stopped: ${e.friendly()}")
            } finally {
                _status.value = null
                syncing = false
            }
        }
    }

    /** Send the changes made without the account. Ones the account refuses are dropped after a few tries. */
    private fun flushPending() {
        val ops = synchronized(lock) { pending.toList() }
        if (ops.isEmpty()) return
        val done = ArrayList<JSONObject>()
        for (op in ops) {
            val ok = runCatching { Remote.playlistOp(JSONObject(op.toString()).apply { remove("tries") }).optBoolean("ok", true) }.getOrDefault(false)
            val tries = op.optInt("tries") + 1
            if (ok || tries >= 3) done.add(op) else op.put("tries", tries)
        }
        synchronized(lock) { pending.removeAll { p -> done.any { it === p } } }
        synchronized(this) { save() }
    }
}

/** Every string in a JSON array. */
fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
}
