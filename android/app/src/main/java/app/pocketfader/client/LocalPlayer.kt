package app.pocketfader.client

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

/**
 * The phone's own player. YouTube Music songs are queued as `ytm:<id>` and their audio address is looked up only when the
 * player gets to them (so a long queue costs nothing up front); downloaded songs and files on the phone play directly.
 * Everything runs on the main thread, as ExoPlayer requires; lookups happen on ExoPlayer's loading thread.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object LocalPlayer {
    private var ctx: Context? = null
    private var exo: ExoPlayer? = null
    private val items = ArrayList<LocalItem>()           // the queue, in the player's order
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var extending = false
    var autoplay = true
        private set

    fun init(c: Context) { ctx = c.applicationContext }

    val hasQueue: Boolean get() = items.isNotEmpty()

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
                val r = Streams.resolve(u.schemeSpecificPart)          // blocking: we're on the loading thread
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { extendIfNeeded() }
            override fun onPlayerError(error: PlaybackException) {
                val cur = items.getOrNull(p.currentMediaItemIndex)
                cur?.videoId?.let { Streams.forget(it) }
                Remote.notify("Couldn't play “${cur?.title ?: "this song"}”: ${error.message ?: error.errorCodeName}")
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }
        })
        exo = p
        return p
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

    private fun startService() {
        val c = ctx ?: return
        runCatching { c.startService(Intent(c, LocalPlaybackService::class.java)) }
    }

    // ---------------------------------------------------------------- what the UI asks for

    /** Replace the queue with [list] and play from [start]. */
    fun playItems(list: List<LocalItem>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val p = player()
        items.clear()
        items.addAll(list)
        p.shuffleModeEnabled = shuffle
        p.setMediaItems(list.map(::toMedia), start.coerceIn(0, list.size - 1), 0L)
        p.prepare()
        p.play()
        startService()
        publish()
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
        p.addMediaItem(at, toMedia(i))
        publish()
    }

    fun addEnd(i: LocalItem) {
        val p = player()
        if (items.isEmpty()) { playItems(listOf(i)); return }
        items.add(i)
        p.addMediaItem(toMedia(i))
        publish()
    }

    fun toggle() {
        val p = player()
        if (p.isPlaying) p.pause() else {
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
        items.removeAt(index)
        player().removeMediaItem(index)
        publish()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        player().moveMediaItem(from, to)
        publish()
    }

    fun setRepeat(mode: String) {
        player().repeatMode = when (mode) { "ONE" -> Player.REPEAT_MODE_ONE; "ALL" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
    }

    fun setShuffle(on: Boolean) { player().shuffleModeEnabled = on }

    fun setAutoplay(on: Boolean) {
        autoplay = on
        publish()
        if (on) extendIfNeeded()
    }

    fun stop() {
        exo?.stop()
        exo?.clearMediaItems()
        items.clear()
        publish()
    }

    fun current(): LocalItem? = exo?.let { items.getOrNull(it.currentMediaItemIndex) }
    fun position(): Double = (exo?.currentPosition ?: 0L) / 1000.0
    fun queue(): List<LocalItem> = items.toList()

    /** Autoplay: when the last song starts, line up songs YouTube relates to it. */
    private fun extendIfNeeded() {
        val p = exo ?: return
        if (!autoplay || extending || p.repeatMode != Player.REPEAT_MODE_OFF) return
        if (p.currentMediaItemIndex < items.size - 1) return
        val seed = items.lastOrNull()?.videoId ?: return
        extending = true
        scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { Streams.related(seed) }.getOrDefault(emptyList()) }
            val have = items.mapNotNull { it.videoId }.toHashSet()
            val fresh = more.filter { it.videoId !in have }.take(15)
            for (i in fresh) { items.add(i); p.addMediaItem(toMedia(i)) }
            extending = false
            publish()
        }
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

    // ---------------------------------------------------------------- state for the screens

    /** Hand the current song, queue and position to [Remote] (which the screens read). */
    fun publish() {
        val p = exo
        val idx = p?.currentMediaItemIndex ?: -1
        val cur = items.getOrNull(idx)
        val now = if (p == null || cur == null) null else Now(
            title = cur.title, artist = cur.artist, art = cur.art,
            playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING),
            duration = if (p.duration > 0) p.duration / 1000.0 else cur.durationSec,
            videoId = cur.videoId ?: cur.key,
            repeat = when (p.repeatMode) { Player.REPEAT_MODE_ONE -> "ONE"; Player.REPEAT_MODE_ALL -> "ALL"; else -> "NONE" },
            shuffle = p.shuffleModeEnabled, autoplay = autoplay,
        )
        val queue = items.mapIndexed { i, it ->
            Track(
                videoId = it.videoId ?: it.key, title = it.title, artist = it.artist, art = it.art,
                duration = if (it.durationSec > 0) formatTime(it.durationSec) else "", selected = i == idx,
                section = "queue", index = i, setVideoId = null,
            )
        }
        Remote.applyLocal(now, queue, Progress((p?.currentPosition ?: 0L) / 1000.0, SystemClock.elapsedRealtime()))
        // keep the position fresh while playing
        if (now?.playing == true && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && exo?.isPlaying == true) {
                    delay(1000)
                    Remote.applyProgress(Progress((exo?.currentPosition ?: 0L) / 1000.0, SystemClock.elapsedRealtime()))
                }
            }
        }
    }
}
