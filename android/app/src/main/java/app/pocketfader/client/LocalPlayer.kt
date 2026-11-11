package app.pocketfader.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's own player. YouTube Music songs are queued as `ytm:<id>` and their audio address is looked up only when the
 * player gets to them (so a long queue costs nothing up front); downloaded songs and files on the phone play directly.
 * Everything runs on the main thread, as ExoPlayer requires; lookups happen on ExoPlayer's loading thread.
 *
 * Shuffle is done here, not by ExoPlayer: turning it on reorders the songs still to come (and off puts them back), so the
 * queue on screen is always the real order and "Play next" really plays next.
 *
 * The queue and the position are saved, so after the app was closed (or the phone restarted) play carries on where it was.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object LocalPlayer {
    private var ctx: Context? = null
    private var exo: ExoPlayer? = null
    private val items = ArrayList<LocalItem>()           // the queue, in the order it plays
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.quiet)
    private var ticker: Job? = null
    private var extending = false
    var autoplay = true
        private set
    private var shuffled = false
    private var original: List<LocalItem>? = null        // the order before shuffling (to put it back)
    private var restored: Saved? = null                  // a saved queue, applied when the player is first needed
    private var failures = 0                             // songs in a row that couldn't play
    private var retried: String? = null                  // the song already given a second chance

    private class Saved(val items: List<LocalItem>, val index: Int, val positionMs: Long, val repeat: Int, val shuffled: Boolean, val autoplay: Boolean)

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        restored = load()
        restored?.let { autoplay = it.autoplay; shuffled = it.shuffled }
    }

    val hasQueue: Boolean get() = items.isNotEmpty() || (restored?.items?.isNotEmpty() == true)

    /** The player (created on first use). Main thread only. */
    fun player(c: Context? = null): ExoPlayer {
        exo?.let { return it }
        val context = c?.applicationContext ?: ctx ?: throw IllegalStateException("not initialised")
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            val u = spec.uri
            if (u.scheme == "ytm") {
                // blocking is fine: this runs on ExoPlayer's loading thread. Errors become IOExceptions so the player
                // reports them normally (and skips to the next song).
                val r = try {
                    Streams.resolve(u.schemeSpecificPart)
                } catch (e: java.io.IOException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: the lookup runs YouTube's own script, which can run out of stack or
                    // memory on an old device, and an Error on this thread would close the whole app
                    if (e is Error) CrashLog.note("looking up a song's audio", e)
                    throw java.io.IOException(e.message ?: "Couldn't get this song", e)
                }
                var s = spec.withUri(Uri.parse(r.url))
                if (r.headers.isNotEmpty()) s = s.withAdditionalHeaders(r.headers)
                s
            } else spec
        }
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)          // pause when headphones are pulled out
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publish() }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                noteTransition(reason)
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) retried = null
                extendIfNeeded()
                save()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) failures = 0
                if (!isPlaying) save()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // the sleep timer's "when this song ends" paused here: that's the timer done
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfSong) {
                    sleepEndOfSong = false
                    p.pauseAtEndOfMediaItems = false
                    publish()
                }
            }

            // something outside the app (a car, a watch, the lock screen) turned ExoPlayer's own shuffle on: do ours instead
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) {
                    p.shuffleModeEnabled = false
                    setShuffle(!shuffled)
                }
            }

            override fun onPlayerError(error: PlaybackException) = recover(p, error)
        })
        exo = p
        restored?.let { r ->
            restored = null
            if (items.isEmpty() && r.items.isNotEmpty()) {
                items.addAll(r.items)
                p.repeatMode = r.repeat
                p.setMediaItems(r.items.map(::toMedia), r.index.coerceIn(0, r.items.size - 1), r.positionMs)
                p.prepare()
            }
        }
        return p
    }

    /**
     * A song that can't play: its audio address may simply have expired, so it gets one more try with a fresh one; after
     * that the player moves on. Several failures in a row (no connection, most likely) stop playback instead of racing
     * through the whole queue.
     */
    private fun recover(p: ExoPlayer, error: PlaybackException) {
        val cur = items.getOrNull(p.currentMediaItemIndex)
        cur?.videoId?.let { Streams.forget(it) }
        val key = cur?.key
        if (key != null && retried != key) {
            retried = key
            p.prepare()
            p.play()
            return
        }
        failures++
        if (failures >= 3 || !p.hasNextMediaItem()) {
            Remote.notify(
                if (failures >= 3) "Stopped: songs aren't loading. Check the connection (downloaded songs still play)."
                else "Couldn't play “${cur?.title ?: "this song"}”: ${error.message ?: error.errorCodeName}"
            )
            failures = 0
            retried = null
            p.pause()            // left stopped; play tries again (toggle() prepares an idle player)
            return
        }
        Remote.notify("Skipped “${cur?.title ?: "a song"}”: it couldn't be played")
        p.seekToNextMediaItem()
        p.prepare()
        p.play()
    }

    private fun toMedia(i: LocalItem): MediaItem {
        val uri = when {
            i.uri != null -> Uri.parse(i.uri)
            i.videoId != null -> Uri.parse("ytm:" + i.videoId)
            else -> Uri.EMPTY
        }
        val meta = MediaMetadata.Builder().setTitle(i.title).setArtist(i.artist)
        i.art?.let { meta.setArtworkUri(Uri.parse(it)) }
        if (i.album.isNotBlank()) meta.setAlbumTitle(i.album)
        return MediaItem.Builder().setMediaId(i.key).setUri(uri).setMediaMetadata(meta.build()).build()
    }

    private var controller: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    private fun startService() {
        val c = ctx ?: return
        try {
            c.startService(Intent(c, LocalPlaybackService::class.java))
        } catch (e: Exception) {
            // in the background (a widget, a headset button) Android doesn't let us start it; connecting to the media
            // session brings it up instead, and it shows its notification once the music plays
            if (controller == null) runCatching {
                controller = androidx.media3.session.MediaController.Builder(
                    c, androidx.media3.session.SessionToken(c, ComponentName(c, LocalPlaybackService::class.java)),
                ).buildAsync()
            }
        }
    }

    // ---------------------------------------------------------------- what the UI asks for

    /** Replace the queue with [list] and play from [start]. With [shuffle], the songs play in a random order. */
    fun playItems(list: List<LocalItem>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val p = player()
        noteLeaving()
        items.clear()
        val first = start.coerceIn(0, list.size - 1)
        if (shuffle) {
            original = list
            shuffled = true
            items.addAll(listOf(list[first]) + (list.take(first) + list.drop(first + 1)).shuffled())
        } else {
            original = null
            shuffled = false
            items.addAll(list)
        }
        p.shuffleModeEnabled = false
        failures = 0
        p.setMediaItems(items.map(::toMedia), if (shuffle) 0 else first, 0L)
        p.prepare()
        p.play()
        startService()
        publish()
        save()
    }

    /** Play one YouTube Music song by id; title and cover are filled in from YouTube if we don't have them. */
    fun playVideo(videoId: String, title: String = "", artist: String = "", art: String? = null) {
        if (title.isNotBlank()) { playItems(listOf(LocalItem(videoId, null, title, artist, art))); return }
        scope.launch {
            val item = withContext(Dispatchers.IO) { runCatching { Streams.details(videoId) }.getOrNull() }
                ?: LocalItem(videoId, null, "Loading…", "", null)
            playItems(listOf(item))
        }
    }

    fun addNext(i: LocalItem) {
        val p = player()
        if (items.isEmpty()) { playItems(listOf(i)); return }
        val at = (p.currentMediaItemIndex + 1).coerceAtMost(items.size)
        items.add(at, i)
        original = original?.let { it + i }
        p.addMediaItem(at, toMedia(i))
        publish()
        save()
    }

    fun addEnd(i: LocalItem) {
        val p = player()
        if (items.isEmpty()) { playItems(listOf(i)); return }
        items.add(i)
        original = original?.let { it + i }
        p.addMediaItem(toMedia(i))
        publish()
        save()
    }

    fun toggle() {
        val p = player()
        if (p.isPlaying) p.pause() else {
            if (p.mediaItemCount == 0) return
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(0)
            p.play()
            startService()
        }
    }

    fun pause() { exo?.pause() }
    fun next() { player().let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun prev() { player().seekToPrevious() }
    fun seek(sec: Double) { player().seekTo((sec * 1000).toLong().coerceAtLeast(0)) ; publish() }
    fun playAt(index: Int) { val p = player(); if (index in items.indices) { p.seekTo(index, 0L); p.play() } }

    fun remove(index: Int) {
        if (index !in items.indices) return
        val gone = items.removeAt(index)
        original = original?.let { o -> val k = o.indexOfFirst { it === gone }; if (k >= 0) o.filterIndexed { j, _ -> j != k } else o }
        player().removeMediaItem(index)
        publish()
        save()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        player().moveMediaItem(from, to)
        publish()
        save()
    }

    fun setRepeat(mode: String) {
        player().repeatMode = when (mode) { "ONE" -> Player.REPEAT_MODE_ONE; "ALL" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
        save()
    }

    /** Shuffle the songs still to come (on), or put them back in their first order (off). The song playing stays. */
    fun setShuffle(on: Boolean) {
        val p = player()
        if (on == shuffled) { publish(); return }
        val cur = p.currentMediaItemIndex.coerceAtLeast(0)
        if (on) {
            original = items.toList()
            reorderUpcoming(cur, items.drop(cur + 1).shuffled())
        } else {
            val first = original
            if (first != null) {
                // the songs still to come, in the order they had before shuffling (songs added since go last)
                val upcoming = items.drop(cur + 1)
                val pos = HashMap<LocalItem, Int>()
                first.forEachIndexed { i, it -> pos.getOrPut(it) { i } }
                reorderUpcoming(cur, upcoming.sortedBy { pos[it] ?: Int.MAX_VALUE })
            }
            original = null
        }
        shuffled = on
        publish()
        save()
    }

    /** Put the songs after [cur] in the order [wanted] (same songs), moving the player's items to match. */
    private fun reorderUpcoming(cur: Int, wanted: List<LocalItem>) {
        val p = exo ?: return
        for ((k, item) in wanted.withIndex()) {
            val target = cur + 1 + k
            val from = (target until items.size).firstOrNull { items[it] === item } ?: continue
            if (from != target) {
                items.add(target, items.removeAt(from))
                p.moveMediaItem(from, target)
            }
        }
    }

    fun setAutoplay(on: Boolean) {
        autoplay = on
        publish()
        if (on) extendIfNeeded()
        save()
    }

    fun stop() {
        noteLeaving()
        exo?.stop()
        exo?.clearMediaItems()
        items.clear()
        original = null
        shuffled = false
        publish()
        save()
    }

    /** The song in the player now, readable from any thread (the player itself may only be asked on the main one). */
    @Volatile var currentVideoId: String? = null
        private set

    fun current(): LocalItem? = exo?.let { items.getOrNull(it.currentMediaItemIndex) } ?: restored?.let { it.items.getOrNull(it.index) }
    fun position(): Double = exo?.let { it.currentPosition / 1000.0 } ?: ((restored?.positionMs ?: 0L) / 1000.0)
    fun queue(): List<LocalItem> = items.toList()

    /** Autoplay: when the last song starts, line up more from its YouTube Music radio (see [Recommend.continueFrom]). */
    private fun extendIfNeeded() {
        val p = exo ?: return
        if (!autoplay || extending || p.repeatMode != Player.REPEAT_MODE_OFF) return
        if (p.currentMediaItemIndex < items.size - 1) return
        val seed = items.lastOrNull()?.takeIf { isYtId(it.videoId) } ?: items.lastOrNull { isYtId(it.videoId) } ?: return
        extending = true
        scope.launch {
            val have = items.mapNotNull { it.videoId }.toHashSet()
            val fresh = withContext(Dispatchers.IO) { runCatching { Recommend.continueFrom(seed, have) }.getOrDefault(emptyList()) }
            for (i in fresh) { items.add(i); p.addMediaItem(toMedia(i)) }
            extending = false
            publish()
            save()
        }
    }

    // ---------------------------------------------------------------- listening history (for the recommendations)

    private var tracking: LocalItem? = null
    private var trackPos = 0.0
    private var trackDur = 0.0

    /** The song that was playing is being left: note whether it was heard or skipped. */
    private fun noteLeaving(skippedByUser: Boolean = true) {
        val t = tracking ?: return
        val skipped = skippedByUser && trackDur > 0 && trackPos < minOf(30.0, trackDur * 0.5)
        Library.recordPlay(t, trackPos, trackDur, skipped)
        tracking = null
    }

    private fun noteTransition(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return     // handled where the queue is replaced
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            tracking?.let { trackPos = maxOf(trackPos, trackDur) }
            noteLeaving(skippedByUser = false)
        } else noteLeaving(skippedByUser = true)
    }

    private fun track(p: ExoPlayer) {
        val cur = items.getOrNull(p.currentMediaItemIndex) ?: return
        if (tracking !== cur) { tracking = cur; trackPos = 0.0 }
        trackPos = maxOf(trackPos, p.currentPosition / 1000.0)
        trackDur = if (p.duration > 0) p.duration / 1000.0 else cur.durationSec
    }

    // ---------------------------------------------------------------- sleep timer (when playing on the phone)

    private var sleepJob: Job? = null
    private var sleepAt = 0L                              // elapsedRealtime when playback pauses; 0 = none
    private var sleepEndOfSong = false

    /** minutes: 1..720, "track" (when this song ends) or 0 to cancel. */
    fun setSleep(minutes: Any) {
        sleepJob?.cancel()
        sleepAt = 0L
        sleepEndOfSong = false
        exo?.pauseAtEndOfMediaItems = false
        when {
            minutes == "track" -> { sleepEndOfSong = true; player().pauseAtEndOfMediaItems = true }
            minutes is Int && minutes > 0 -> {
                sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
                sleepJob = scope.launch {
                    delay(minutes * 60_000L)
                    sleepAt = 0L
                    exo?.pause()
                    publish()
                }
            }
        }
        publish()
    }

    /** Seconds until the sleep timer pauses playback (0 = none). */
    private fun sleepLeft(p: ExoPlayer?): Int = when {
        sleepEndOfSong && p != null -> (((p.duration - p.currentPosition).coerceAtLeast(0)) / 1000).toInt().coerceAtLeast(1)
        sleepAt > 0 -> ((sleepAt - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
        else -> 0
    }

    // ---------------------------------------------------------------- the phone's volume

    fun phoneVolume(): Int {
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setPhoneVolume(percent: Int) {
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    private var beforeMute = 0

    /** Mute the phone's music, or bring it back to where it was. */
    fun togglePhoneMute() {
        val v = phoneVolume()
        if (v > 0) { beforeMute = v; setPhoneVolume(0) } else setPhoneVolume(if (beforeMute > 0) beforeMute else 50)
    }

    // ---------------------------------------------------------------- state for the screens

    /** Hand the current song, queue and position to [Remote] (which the screens read). */
    fun publish() {
        val p = exo
        if (p == null) {
            // not started yet this time: show the saved queue, paused, so the app opens where you left off
            val r = restored
            val cur = r?.items?.getOrNull(r.index)
            currentVideoId = cur?.videoId
            Remote.applyLocal(
                cur?.let { Now(title = it.title, artist = it.artist, art = it.art, playing = false, duration = it.durationSec, videoId = it.videoId ?: it.key, liked = Library.isLiked(it.videoId), shuffle = shuffled, autoplay = autoplay) },
                r?.items.orEmpty().mapIndexed { i, it -> trackOf(it, i, i == r?.index) },
                Progress((r?.positionMs ?: 0L) / 1000.0, SystemClock.elapsedRealtime()), 0,
            )
            return
        }
        val idx = p.currentMediaItemIndex
        val cur = items.getOrNull(idx)
        currentVideoId = cur?.videoId
        if (cur != null) track(p)
        val now = if (cur == null) null else Now(
            title = cur.title, artist = cur.artist, art = cur.art,
            playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING),
            duration = if (p.duration > 0) p.duration / 1000.0 else cur.durationSec,
            videoId = cur.videoId ?: cur.key,
            liked = Library.isLiked(cur.videoId),
            repeat = when (p.repeatMode) { Player.REPEAT_MODE_ONE -> "ONE"; Player.REPEAT_MODE_ALL -> "ALL"; else -> "NONE" },
            shuffle = shuffled, autoplay = autoplay,
        )
        val queue = items.mapIndexed { i, it -> trackOf(it, i, i == idx) }
        Remote.applyLocal(now, queue, Progress(p.currentPosition / 1000.0, SystemClock.elapsedRealtime()), sleepLeft(p))
        // keep the position fresh while playing
        if (now?.playing == true && ticker?.isActive != true) {
            ticker = scope.launch {
                var n = 0
                while (isActive && exo?.isPlaying == true) {
                    delay(1000)
                    val e = exo ?: break
                    track(e)
                    Remote.applyProgress(Progress(e.currentPosition / 1000.0, SystemClock.elapsedRealtime()))
                    if (++n % 15 == 0) save()             // the position, now and then, in case the app is closed
                }
            }
        }
    }

    private fun trackOf(it: LocalItem, i: Int, selected: Boolean) = Track(
        videoId = it.videoId ?: it.key, title = it.title, artist = it.artist, art = it.art,
        duration = if (it.durationSec > 0) formatTime(it.durationSec) else "", selected = selected,
        section = "queue", index = i, setVideoId = null,
    )

    // ---------------------------------------------------------------- saving the queue

    private fun stateFile(): File? = ctx?.let { File(it.filesDir, "queue.json") }

    private fun save() {
        val f = stateFile() ?: return
        val p = exo ?: return
        val list = items.toList()
        val o = JSONObject()
            .put("index", p.currentMediaItemIndex).put("pos", p.currentPosition).put("repeat", p.repeatMode)
            .put("shuffled", shuffled).put("autoplay", autoplay)
            .put("items", JSONArray().also { a ->
                for (i in list.take(500)) a.put(
                    JSONObject().put("id", i.videoId ?: JSONObject.NULL).put("uri", i.uri ?: JSONObject.NULL).put("t", i.title)
                        .put("a", i.artist).put("art", i.art ?: JSONObject.NULL).put("d", i.durationSec).put("al", i.album)
                )
            })
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(f)
            }
        }
    }

    private fun load(): Saved? = runCatching {
        val o = JSONObject(stateFile()!!.readText())
        val list = o.optJSONArray("items").mapObjects {
            LocalItem(it.str("id"), it.str("uri"), it.optString("t"), it.optString("a"), it.str("art"), it.optDouble("d", 0.0), it.optString("al"))
        }.filter { it.videoId != null || it.uri != null }
        if (list.isEmpty()) null
        else Saved(list, o.optInt("index").coerceIn(0, list.size - 1), o.optLong("pos"), o.optInt("repeat"), o.optBoolean("shuffled"), o.optBoolean("autoplay", true))
    }.getOrNull()
}
