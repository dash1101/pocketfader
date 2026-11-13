package app.pocketfader.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.VolumeProviderCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URL

/**
 * Mirrors what the computer is playing as a system media session: lock-screen and notification
 * controls, Bluetooth/headset buttons, and the phone's hardware volume keys, which control the
 * computer's volume while this session is active (even with the screen off).
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL = "pocketfader_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PREFIX = "pf.cmd."
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var volume: VolumeProviderCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.quiet)
    private var artUrl: String? = null
    private var art: Bitmap? = null
    private var lastKey = ""
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "YTM Starcast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Remote.transport("playpause")
                override fun onPause() = Remote.transport("playpause")
                override fun onSkipToNext() = Remote.transport("next")
                override fun onSkipToPrevious() = Remote.transport("prev")
                override fun onSeekTo(pos: Long) = Remote.transport("seek", pos / 1000.0)
            })
            isActive = true
        }
        volume = object : VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100, 50) {
            override fun onSetVolumeTo(volume: Int) = Remote.setVolume(volume)
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) Remote.adjustVolume(direction * Settings.prefs.value.volumeStep)
            }
        }
        applyVolumeMode(volumeKeysWanted())
        Remote.ensureConfigured(ConfigStore(this))
        Remote.acquire()
        scope.launch {
            Remote.state.collect {
                publish(it)
                runCatching { applyVolumeMode(volumeKeysWanted()) }     // "Computer audio" may have been switched on or off
            }
        }
        scope.launch { Settings.prefs.collect { applyVolumeMode(volumeKeysWanted()) } }
        // the position drifts on its own while playing, so refresh it now and then (and after seeks)
        scope.launch {
            Remote.progress.collect {
                val t = android.os.SystemClock.elapsedRealtime()
                val playing = Remote.state.value.now?.playing == true
                val expected = lastPos + if (playing) (t - lastPosUpdate) / 1000.0 else 0.0
                // only when the position jumped (a seek on the computer) or it's been a while
                if (Math.abs(it.position - expected) > 2.5 || t - lastPosUpdate > 30000) publish(Remote.state.value, force = true)
            }
        }
        if (!showForeground()) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action
        if (a == "pf.quit") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (a != null && a.startsWith(ACTION_PREFIX)) Remote.transport(a.removePrefix(ACTION_PREFIX))
        return START_STICKY
    }

    /**
     * False when Android won't let the notification start now. That happens when Android restarts this service by itself
     * while the app is in the background (Android 12 and later forbid it): the service then stops quietly, and starts
     * again the next time the app is opened, instead of closing the whole app with an error.
     */
    private fun showForeground(): Boolean {
        if (started) return true
        return try {
            val n = buildNotification(RemoteState())
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            started = true
            true
        } catch (e: Exception) {
            CrashLog.note("starting the playback notification", e)
            false
        }
    }

    /** The cover, read at a size a notification can use (a full-size one costs memory an old device doesn't have). */
    private fun loadArt(url: String): Bitmap? = try {
        val c = URL(url).openConnection()
        c.connectTimeout = 6000
        c.readTimeout = 8000
        val bytes = c.getInputStream().use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {       // including running out of memory
        null
    }

    private var lastPosUpdate = 0L
    private var lastPos = 0.0
    private var remoteVolume = false

    /** The volume keys change the computer's volume: the setting is on, and "Computer audio" is switched on there. */
    private fun volumeKeysWanted(): Boolean = Settings.prefs.value.volumeKeys && Remote.state.value.audioOn

    private fun applyVolumeMode(remote: Boolean) {
        if (remote == remoteVolume && ::volume.isInitialized && started) return
        remoteVolume = remote
        if (remote) session.setPlaybackToRemote(volume)
        else session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
    }

    private var publishFailed = false

    /** A failure here must not end the app (or stop later updates): it's written down once and the next update tries again. */
    private fun publish(s: RemoteState, force: Boolean = false) {
        try {
            publishNow(s, force)
        } catch (e: Exception) {
            if (!publishFailed) { publishFailed = true; CrashLog.note("updating the playback notification", e) }
        }
    }

    private fun publishNow(s: RemoteState, force: Boolean) {
        val now = s.now
        val url = now?.art
        if (url != artUrl) {
            artUrl = url
            art = null
            if (url != null) scope.launch(Dispatchers.IO) {
                val bm = loadArt(url)
                scope.launch { if (artUrl == url) { art = bm; lastKey = ""; publish(Remote.state.value) } }
            }
        }
        if (s.volume.available) volume.currentVolume = s.volume.percent

        val playing = now?.playing == true
        val prog = Remote.progress.value
        val posMs = (prog.position * 1000).toLong()
        lastPosUpdate = android.os.SystemClock.elapsedRealtime()
        lastPos = prog.position
        // Only touch the session when something visible changed (position drifts every poll).
        val key = listOf(now?.title, now?.artist, playing, art != null, s.online, s.remoteOn).joinToString("|")
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    posMs, if (playing) prog.rate.toFloat() else 0f,
                )
                .build()
        )
        if (force || key == lastKey) return    // a position-only refresh stops here
        lastKey = key
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, now?.title ?: "Nothing playing")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, now?.artist.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ((now?.duration ?: 0.0) * 1000).toLong())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .build()
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(s))
    }

    private fun action(cmd: String, icon: Int, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, cmd.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(ACTION_PREFIX + cmd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(icon, label, pi)
    }

    private fun buildNotification(s: RemoteState): android.app.Notification {
        val now = s.now
        val playing = now?.playing == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val quit = PendingIntent.getService(
            this, 99, Intent(this, PlaybackService::class.java).setAction("pf.quit"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(now?.title?.ifBlank { null } ?: "Nothing playing")
            .setContentText(
                when {
                    !s.online && s.configured -> "Can't reach the computer"
                    !s.remoteOn -> "The YouTube Music remote is switched off"
                    else -> now?.artist.orEmpty()
                }
            )
            .setLargeIcon(art)
            .setContentIntent(open)
            .setDeleteIntent(quit)
            .addAction(action("prev", android.R.drawable.ic_media_previous, "Previous"))
            .addAction(
                action(
                    "playpause",
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                )
            )
            .addAction(action("next", android.R.drawable.ic_media_next, "Next"))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)      // a media player's controls (phones group them as such)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "Playback controls", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        Remote.release()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
