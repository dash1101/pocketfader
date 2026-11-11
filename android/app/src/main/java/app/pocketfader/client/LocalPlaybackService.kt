package app.pocketfader.client

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Keeps music playing on the phone in the background, with the notification, lock-screen and headset controls. */
class LocalPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    // After a pause the service would leave the foreground at once, and Android is then free to close the whole app within
    // moments (older and low-memory devices do): the queue's place is lost and the notification's play button starts the
    // app from nothing. So it stays in the foreground for a while after music stops; then it lets go as before.
    private var played = false
    private var pausedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val letGo = Runnable {
        val s = session
        if (s != null && pausedAt != 0L && !s.player.isPlaying) {
            try { onUpdateNotification(s, false) } catch (e: Exception) { CrashLog.note("letting the player's notification go", e) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, LocalPlayer.player(this)).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) {
            played = true
            pausedAt = 0L
            handler.removeCallbacks(letGo)
            super.onUpdateNotification(session, true)
            return
        }
        val p = session.player
        val now = SystemClock.elapsedRealtime()
        if (played && pausedAt == 0L) {
            pausedAt = now
            handler.removeCallbacks(letGo)
            handler.postDelayed(letGo, LINGER_MS + 1000)
        }
        val keep = played && pausedAt != 0L && now - pausedAt < LINGER_MS &&
            p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE
        if (!keep) {
            super.onUpdateNotification(session, false)
            return
        }
        try {
            super.onUpdateNotification(session, true)
        } catch (e: IllegalStateException) {
            // Android wouldn't allow it (the app is too far in the background): carry on the ordinary way
            pausedAt = now - LINGER_MS
            super.onUpdateNotification(session, false)
        }
    }

    /**
     * Swiping the app away stops the music, as closing an app is expected to; the queue and the place in the song are
     * kept. With "Keep playing when the app is swiped away" on, music that is playing carries on instead.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        val playing = p != null && p.playWhenReady && p.mediaItemCount > 0
        if (playing && Settings.prefs.value.keepPlayingClosed) return
        // no lingering in the foreground this time: the notification goes with the app
        played = false
        pausedAt = 0L
        handler.removeCallbacks(letGo)
        try { LocalPlayer.closedByUser() } catch (e: Exception) { CrashLog.note("stopping the music when the app was swiped away", e) }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(letGo)
        session?.release()        // the player itself lives on in LocalPlayer
        session = null
        super.onDestroy()
    }

    private companion object {
        const val LINGER_MS = 10 * 60_000L
    }
}
