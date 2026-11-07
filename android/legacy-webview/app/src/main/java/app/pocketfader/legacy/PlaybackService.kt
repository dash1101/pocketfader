package app.pocketfader.legacy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service that mirrors the PC's YT Music playback as a native media notification
 * (lock screen + status bar) and forwards its transport buttons back to the server.
 */
class PlaybackService : Service() {

    companion object { const val CH = "vmr_playback"; const val NID = 1 }

    private lateinit var session: MediaSessionCompat
    private var server = ServerAddress.parse(MainActivity.DEFAULT_SERVER).base
    private var token: String? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = true
    private var artUrl: String? = null
    private var art: Bitmap? = null
    private var title = ""; private var artist = ""; private var playing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "vmr").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { transport("playpause") }
                override fun onPause() { transport("playpause") }
                override fun onSkipToNext() { transport("next") }
                override fun onSkipToPrevious() { transport("prev") }
            })
            isActive = true
        }
        startForeground(NID, build())
        poll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("server")?.let { server = it.trim().trimEnd('/') }
        if (intent?.hasExtra("token") == true) token = intent.getStringExtra("token")
        intent?.action?.takeIf { it.startsWith("cmd:") }?.let { transport(it.removePrefix("cmd:")) }
        return START_STICKY
    }

    private fun poll() = thread {
        while (running) {
            try {
                val now = JSONObject(open("$server/api/ytm/state", token).inputStream.bufferedReader().use { it.readText() }).optJSONObject("now")
                val t = now?.optString("title").orEmpty()
                val a = now?.optString("artist").orEmpty()
                val p = now?.optBoolean("playing") ?: false
                val url = now?.optString("art")?.takeIf { it.isNotBlank() && it != "null" }
                ui.post { apply(t, a, p, url) }
            } catch (_: Exception) {}
            Thread.sleep(2000)
        }
    }

    private fun apply(t: String, a: String, p: Boolean, url: String?) {
        title = t; artist = a; playing = p
        if (url != artUrl) {
            artUrl = url; art = null
            if (url != null) thread {
                try { val bm = BitmapFactory.decodeStream(URL(url).openStream()); ui.post { art = bm; publish() } }
                catch (_: Exception) {}
            }
        }
        publish()
    }

    private fun publish() {
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            .build())
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, 0, 1f)
            .build())
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID, build())
    }

    private fun act(cmd: String, icon: Int, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(this, cmd.hashCode(),
            Intent(this, PlaybackService::class.java).setAction("cmd:$cmd"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(icon, label, pi)
    }

    private fun build() = NotificationCompat.Builder(this, CH)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(title.ifBlank { "Nothing playing" })
        .setContentText(artist)
        .setLargeIcon(art)
        .setContentIntent(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(act("prev", android.R.drawable.ic_media_previous, "Prev"))
        .addAction(act("playpause", if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause"))
        .addAction(act("next", android.R.drawable.ic_media_next, "Next"))
        .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(playing)
        .build()

    private fun transport(action: String) = thread {
        try {
            open("$server/api/ytm", token, "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write("""{"type":"transport","action":"$action"}""".toByteArray()) }
                responseCode; disconnect()
            }
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() { running = false; session.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
