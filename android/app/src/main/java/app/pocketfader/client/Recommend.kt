package app.pocketfader.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * Recommendations made on the phone, for when YouTube Music on the computer isn't there to make them.
 *
 * It works the way YouTube Music's own home page does: it takes songs you play a lot, played recently or liked as seeds,
 * asks YouTube Music for each seed's radio (the same "RDAMVM" mixes YouTube Music plays), and ranks everything those radios
 * suggest. A song several of your seeds lead to ranks higher, so do songs by artists you listen to; songs you just heard
 * or keep skipping drop down. Autoplay on the phone uses the same radios, filtered the same way.
 *
 * Nothing leaves the phone except the radio and search requests to YouTube, the same ones playing a song already makes.
 */
object Recommend {
    private lateinit var cacheFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)

    private val _feed = MutableStateFlow<List<Shelf>?>(null)
    val feed: StateFlow<List<Shelf>?> = _feed
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    @Volatile private var builtAt = 0L
    @Volatile private var builtWithPlays = 0
    @Volatile private var dirty = false
    /** True when the last build couldn't reach YouTube (the shelves are what's on the phone). */
    @Volatile var offline = false
        private set
    /** The quick picks of the last build, for lucky and for smart downloads. */
    @Volatile var picks: List<LocalItem> = emptyList()
        private set

    // radios are cached for a few hours: the same seeds come back often
    private val radios = java.util.concurrent.ConcurrentHashMap<String, Pair<List<LocalItem>, Long>>()

    fun init(ctx: Context) {
        if (::cacheFile.isInitialized) return
        cacheFile = File(ctx.filesDir, "library/feed.json")
        scope.launch { loadCache() }
    }

    fun invalidate() { dirty = true }

    /** A song was heard: rebuild the shelves once enough has changed. */
    fun noteListen() {
        if (Library.history.value.size - builtWithPlays >= 6) dirty = true
    }

    /** Build the shelves if they are missing, old, or out of date with what you've played. */
    fun refreshIfStale() {
        val old = System.currentTimeMillis() - builtAt > 3 * 3600_000L
        if (_feed.value == null || old || dirty || offline) refresh()
    }

    fun refresh() {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                val shelves = build()
                _feed.value = shelves
                builtAt = System.currentTimeMillis()
                builtWithPlays = Library.history.value.size
                dirty = false
                saveCache(shelves)
                AutoDownload.soon()
            } catch (e: Exception) {
                if (_feed.value == null) _feed.value = emptyList()
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------------------------------------------------------------- what you listen to

    private const val DAY = 86_400_000.0

    /** How much each song and each artist means to you, from plays (recent ones count more), skips and likes. */
    private class Taste(plays: List<Play>, liked: List<LocalItem>, now: Long) {
        val song = HashMap<String, Double>()
        val artist = HashMap<String, Double>()
        val lastPlayed = HashMap<String, Long>()
        val skips = HashMap<String, Int>()
        val meta = HashMap<String, LocalItem>()
        val playCount = HashMap<String, Int>()

        init {
            for (p in plays) {
                val age = (now - p.at) / DAY
                val decay = exp(-age / 30.0)                    // a play a month ago counts about a third
                val w = when {
                    p.skipped -> -0.7
                    p.dur > 0 && p.heard >= p.dur * 0.8 -> 1.0
                    else -> 0.5
                } * decay
                song[p.id] = (song[p.id] ?: 0.0) + w
                val a = artistKey(p.artist)
                if (a.isNotEmpty()) artist[a] = (artist[a] ?: 0.0) + w
                lastPlayed[p.id] = maxOf(lastPlayed[p.id] ?: 0L, p.at)
                if (p.skipped && age < 30) skips[p.id] = (skips[p.id] ?: 0) + 1
                if (!p.skipped) playCount[p.id] = (playCount[p.id] ?: 0) + 1
                meta[p.id] = p.toLocal()
            }
            for ((i, l) in liked.withIndex()) {
                val id = l.videoId ?: continue
                val boost = 1.5 * exp(-i / 200.0)               // newer likes count more
                song[id] = (song[id] ?: 0.0) + boost
                val a = artistKey(l.artist)
                if (a.isNotEmpty()) artist[a] = (artist[a] ?: 0.0) + boost * 0.5
                meta.getOrPut(id) { l }
            }
        }

        fun artistAffinity(name: String): Double = (artist[artistKey(name)] ?: 0.0).coerceAtLeast(0.0)
    }

    /** "Queen & David Bowie" and "queen" both count for Queen. */
    fun artistKey(name: String): String = name.lowercase().split(",", "&", " and ", " feat", " ft.", " x ").first().trim()

    private fun taste(): Taste = Taste(Library.history.value, Library.liked.value, System.currentTimeMillis())

    // ---------------------------------------------------------------- radios

    private suspend fun radioOf(videoId: String): List<LocalItem> {
        radios[videoId]?.let { (list, at) -> if (System.currentTimeMillis() - at < 4 * 3600_000L) return list }
        val list = gate.withPermit { withContext(Dispatchers.IO) { Streams.radio(videoId) } }
        radios[videoId] = list to System.currentTimeMillis()
        return list
    }

    /** Up to [n] seeds: your current favourites, what you played lately and recent likes, spread over different artists. */
    private fun seeds(t: Taste, n: Int): List<LocalItem> {
        val out = LinkedHashMap<String, LocalItem>()
        val artists = HashMap<String, Int>()
        fun add(i: LocalItem?) {
            val id = i?.videoId ?: return
            if (id in out || out.size >= n) return
            val a = artistKey(i.artist)
            if ((artists[a] ?: 0) >= 2) return
            artists[a] = (artists[a] ?: 0) + 1
            out[id] = i
        }
        val recent = Library.history.value.asReversed().filter { !it.skipped }.distinctBy { it.id }.take(8)
        val top = t.song.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(20).mapNotNull { t.meta[it.key] }
        val likes = Library.liked.value.take(30).shuffled().take(6)
        val iter = listOf(recent.map { it.toLocal() }.iterator(), top.iterator(), likes.iterator())
        while (out.size < n && iter.any { it.hasNext() }) for (it in iter) if (it.hasNext()) add(it.next())
        if (out.size < n) Downloads.items.value.sortedByDescending { it.addedAt }.take(10).forEach { add(it.toLocal()) }
        return out.values.toList()
    }

    private class Cand(val item: LocalItem) {
        var score = 0.0
        var hits = 0
    }

    private fun rank(t: Taste, radios: List<Pair<LocalItem, List<LocalItem>>>, seedWeight: (LocalItem) -> Double): List<Cand> {
        val now = System.currentTimeMillis()
        val cands = HashMap<String, Cand>()
        for ((seed, list) in radios) {
            val w = seedWeight(seed)
            for ((rank, it) in list.withIndex()) {
                val id = it.videoId ?: continue
                if (id == seed.videoId) continue
                val c = cands.getOrPut(id) { Cand(it) }
                c.score += w / (rank + 4)
                c.hits++
            }
        }
        for (c in cands.values) {
            val id = c.item.videoId!!
            c.score += 0.08 * ln(1 + t.artistAffinity(c.item.artist))
            if (Library.isLiked(id)) c.score += 0.05
            val last = t.lastPlayed[id]
            if (last != null) {
                val hours = (now - last) / 3_600_000.0
                c.score -= when { hours < 12 -> 1.0; hours < 72 -> 0.15; else -> 0.0 }
            } else {
                c.score += 0.02                                  // something new
            }
            c.score -= 0.12 * (t.skips[id] ?: 0)
        }
        return cands.values.sortedByDescending { it.score }
    }

    /** At most [per] songs by the same artist in a row of results, so a shelf isn't one artist. */
    private fun diverse(list: List<LocalItem>, per: Int, n: Int): List<LocalItem> {
        val counts = HashMap<String, Int>()
        val out = ArrayList<LocalItem>()
        for (i in list) {
            val a = artistKey(i.artist)
            if ((counts[a] ?: 0) >= per) continue
            counts[a] = (counts[a] ?: 0) + 1
            out.add(i)
            if (out.size >= n) break
        }
        return out
    }

    // ---------------------------------------------------------------- the shelves

    private suspend fun build(): List<Shelf> = coroutineScopeIO {
        val t = taste()
        val now = System.currentTimeMillis()
        val seeds = seeds(t, 7)
        val fetched = seeds.map { s -> async { s to runCatching { radioOf(s.videoId!!) }.getOrNull() } }.awaitAll()
        offline = seeds.isNotEmpty() && fetched.all { it.second == null }
        val got = fetched.mapNotNull { (s, l) -> l?.let { s to it } }
        val ranked = rank(t, got) { s -> 1.0 + 0.3 * ln(1 + (t.song[s.videoId] ?: 0.0).coerceAtLeast(0.0)) }
        val shelves = ArrayList<Shelf>()
        val used = HashSet<String>()
        fun songs(title: String, list: List<LocalItem>) {
            if (list.isEmpty()) return
            shelves.add(Shelf(title, list.map { it.toSearchItem() }))
        }

        // offline, what's saved on the phone comes first: it's what can play
        val saved = Downloads.items.value.sortedByDescending { it.addedAt }.map { it.toLocal() }
        if (offline) songs("Saved on this phone", saved.take(30))

        // Quick picks: the best of everything your seeds' radios suggest
        val quick = diverse(ranked.filter { it.score > -0.5 }.map { it.item }, per = 2, n = 20)
        picks = quick
        quick.forEach { used.add(it.videoId!!) }
        if (quick.isNotEmpty()) songs("Quick picks", quick)
        else if (seeds.isEmpty() && !offline) {
            // nothing to go on yet: what's popular, until you've played a few songs
            val popular = runCatching { withContext(Dispatchers.IO) { Streams.search("top songs this week") } }.getOrDefault(emptyList())
                .mapNotNull { it.toLocal() }
            picks = popular
            songs("Popular right now", popular.take(20))
        }

        // Listen again: what you've played most lately
        val again = t.song.entries.filter { it.value > 0.4 && (t.playCount[it.key] ?: 0) >= 1 }.sortedByDescending { it.value }
            .mapNotNull { t.meta[it.key] }.filter { it.videoId !in used }
        diverse(again, per = 3, n = 16).also { l -> l.forEach { used.add(it.videoId!!) }; songs("Listen again", l) }

        // your playlists (synced) and liked songs
        val lists = ArrayList<SearchItem>()
        if (Library.liked.value.isNotEmpty()) lists.add(SearchItem("Liked music", "${Library.liked.value.size} songs", Library.liked.value.first().art, null, "LM", "playlist"))
        Library.playlists.value.take(20).forEach { lists.add(it.toSearchItem()) }
        if (lists.isNotEmpty()) shelves.add(Shelf("Your playlists", lists))

        // the account's own home page, from the last sync (minus shelves we already made fresh)
        val mine = shelves.map { it.title.lowercase() }.toSet()
        val account = Library.home.value.filter { it.title.lowercase() !in mine && it.title.lowercase() !in setOf("quick picks", "listen again") }.take(4)
        shelves.addAll(account)

        // Mixed for you: a radio for each of your top artists
        val topArtists = t.artist.entries.filter { it.value > 0.5 }.sortedByDescending { it.value }.take(6).map { it.key }
        val mixes = topArtists.mapNotNull { a ->
            val best = t.song.entries.filter { e -> t.meta[e.key]?.let { artistKey(it.artist) == a } == true && e.value > 0 }
                .maxByOrNull { it.value }?.let { t.meta[it.key] } ?: return@mapNotNull null
            SearchItem("${best.artist} radio", "With ${best.title} and more", best.art, best.videoId, null, "radio")
        }
        if (mixes.size >= 2) shelves.add(Shelf("Mixed for you", mixes))

        // Forgotten favorites: played a lot, but not for a month
        val forgotten = t.playCount.entries.filter { it.value >= 3 && now - (t.lastPlayed[it.key] ?: now) > 30 * DAY }
            .sortedByDescending { it.value }.mapNotNull { t.meta[it.key] }.filter { it.videoId !in used }
        diverse(forgotten, per = 2, n = 15).also { l -> l.forEach { used.add(it.videoId!!) }; songs("Forgotten favorites", l) }

        // Discover: new artists the radios lead to
        val known = t.artist.filter { it.value > 0.3 }.keys
        val discover = ranked.map { it.item }.filter { it.videoId !in used && artistKey(it.artist) !in known && t.lastPlayed[it.videoId] == null }
        diverse(discover, per = 1, n = 15).also { songs("Discover something new", it) }

        // a "Liked songs" shuffle and what's downloaded
        if (!offline) songs("Saved on this phone", saved.take(15))
        shelves
    }

    private suspend fun <T> coroutineScopeIO(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) { kotlinx.coroutines.coroutineScope { block() } }

    // ---------------------------------------------------------------- autoplay and lucky

    /**
     * What to play after [seed] (blocking; call off the main thread): its YouTube Music radio, without songs already in the
     * queue, songs heard in the last couple of hours and songs you keep skipping. The radio's own order is kept: it already
     * flows from one song to the next the way YouTube Music's autoplay does.
     */
    fun continueFrom(seed: LocalItem, inQueue: Set<String>, n: Int = 15): List<LocalItem> {
        val id = seed.videoId ?: return emptyList()
        val list = runCatching { kotlinx.coroutines.runBlocking { radioOf(id) } }.getOrNull()
            ?: runCatching { Streams.related(id) }.getOrDefault(emptyList())
        val t = taste()
        val now = System.currentTimeMillis()
        fun fresh(i: LocalItem, hours: Double): Boolean {
            val last = t.lastPlayed[i.videoId] ?: return true
            return (now - last) > hours * 3_600_000
        }
        val base = list.filter { it.videoId != null && it.videoId != id && it.videoId !in inQueue && (t.skips[it.videoId] ?: 0) < 2 }
        val picked = base.filter { fresh(it, 2.0) }.let { if (it.size >= 5) it else base }
        return diverse(picked, per = 3, n = n)
    }

    /** One song you'd probably like right now, for "I'm feeling lucky". */
    suspend fun lucky(): LocalItem? {
        picks.takeIf { it.isNotEmpty() }?.let { return it.take(12).random() }
        val t = taste()
        val seed = seeds(t, 6).randomOrNull() ?: return withContext(Dispatchers.IO) {
            runCatching { Streams.search("top songs this week") }.getOrDefault(emptyList()).mapNotNull { it.toLocal() }.take(10).randomOrNull()
        }
        val radio = runCatching { radioOf(seed.videoId!!) }.getOrDefault(emptyList())
        return radio.drop(1).take(10).randomOrNull() ?: seed
    }

    /** Songs worth keeping downloaded (smart downloads): your most played and the current quick picks. */
    fun smartPicks(n: Int): List<LocalItem>? {
        if (_feed.value == null) return null
        val t = taste()
        val top = t.song.entries.filter { it.value > 0.5 }.sortedByDescending { it.value }.mapNotNull { t.meta[it.key] }
        val out = LinkedHashMap<String, LocalItem>()
        for (i in top.take(n / 2)) out[i.videoId!!] = i
        for (i in picks) { if (out.size >= n) break; out.getOrPut(i.videoId!!) { i } }
        for (i in top) { if (out.size >= n) break; out.getOrPut(i.videoId!!) { i } }
        return out.values.toList()
    }

    // ---------------------------------------------------------------- cache

    private fun saveCache(shelves: List<Shelf>) {
        if (!::cacheFile.isInitialized) return
        runCatching {
            val arr = JSONArray()
            for (s in shelves) {
                val items = JSONArray()
                for (i in s.items) items.put(
                    JSONObject().put("title", i.title).put("subtitle", i.subtitle).put("thumb", i.thumb ?: JSONObject.NULL)
                        .put("videoId", i.videoId ?: JSONObject.NULL).put("playlistId", i.playlistId ?: JSONObject.NULL).put("kind", i.kind)
                )
                arr.put(JSONObject().put("title", s.title).put("items", items))
            }
            val picksArr = JSONArray()
            for (p in picks) picksArr.put(JSONObject().put("id", p.videoId).put("t", p.title).put("a", p.artist).put("art", p.art ?: JSONObject.NULL).put("d", p.durationSec))
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(JSONObject().put("at", builtAt).put("plays", builtWithPlays).put("sections", arr).put("picks", picksArr).toString())
            tmp.renameTo(cacheFile)
        }
    }

    private fun loadCache() {
        runCatching {
            val o = JSONObject(cacheFile.readText())
            builtAt = o.optLong("at")
            builtWithPlays = o.optInt("plays")
            picks = o.optJSONArray("picks").mapObjects { LocalItem(it.str("id"), null, it.optString("t"), it.optString("a"), it.str("art"), it.optDouble("d", 0.0)) }
                .filter { isYtId(it.videoId) }
            if (_feed.value == null) _feed.value = parseShelves(o)
        }
    }
}
