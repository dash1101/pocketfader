package app.pocketfader.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows download progress in a notification and keeps the app alive while songs are being saved, so a long batch (a
 * playlist kept on the phone, smart downloads) finishes even when the app is in the background.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.quiet)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) Downloads.cancelPending()
        val n = build(Downloads.active.value)
        try {
            ServiceCompat.startForeground(
                this, ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher?.isActive != true) watcher = scope.launch {
            Downloads.active.collect { active ->
                if (active.isEmpty()) {
                    delay(600)
                    if (Downloads.active.value.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(active))
                    delay(700)                      // at most a couple of updates a second
                }
            }
        }
        return START_NOT_STICKY
    }

    private var watcher: Job? = null

    private fun build(active: Map<String, Float>): android.app.Notification {
        channel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = active.size
        val pct = if (count == 0) 100 else (active.values.average() * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (count == 1) "Downloading 1 song" else "Downloading $count songs")
            .setContentText("For listening with no connection")
            .setProgress(100, pct, count > 0 && pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 7301
        private const val CHANNEL = "downloads"
        private const val ACTION_CANCEL = "app.pocketfader.downloads.CANCEL"

        private fun channel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }

        /** Start showing progress. Android may refuse while the app is in the background; the downloads go on regardless. */
        fun start(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java)) }
        }
    }
}

/**
 * The automatic download rules: liked songs, playlists kept on the phone, and smart downloads (your most played and current
 * quick picks), within the space you allow. Songs the rules no longer want are removed again; songs you downloaded yourself
 * are never touched.
 */
object AutoDownload {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.quiet)
    private var job: Job? = null
    private const val TYPICAL_SECONDS = 210.0   // a song whose length isn't known
    private const val RESERVE = 2L * 1_073_741_824L   // "fill the space" with no limit leaves this much of the phone free

    /** Bytes a second of audio takes at a download quality: what this phone's own downloads at that quality turned out to
     *  be once there are a few, else what YouTube's streams usually are (container included). */
    fun bytesPerSecond(quality: String = Settings.prefs.value.dlQuality): Double {
        val seen = Downloads.items.value.filter { it.quality == quality && it.durationSec > 30 && it.size > 0 }
        if (seen.size >= 5) return seen.sumOf { it.size }.toDouble() / seen.sumOf { it.durationSec }
        val kbps = when (quality) { "best" -> 160.0; "saver" -> 56.0; else -> 130.0 }
        return kbps * 1000 / 8 * 1.03
    }

    /** About how much a song will take, from its length and the download quality. */
    fun estimate(i: LocalItem, bps: Double = bytesPerSecond()): Long =
        ((if (i.durationSec > 0) i.durationSec else TYPICAL_SECONDS) * bps).toLong()

    /** About how much a typical song takes at the download quality. */
    fun typicalSong(): Long = (TYPICAL_SECONDS * bytesPerSecond()).toLong()

    /** Run the rules in a few seconds (changes often come in bursts). */
    fun soon() {
        job?.cancel()
        job = scope.launch {
            delay(4000)
            runCatching { run() }
        }
    }

    /** How many songs the rules want, and roughly how much space that takes. */
    fun plan(): Pair<Int, Long> {
        val list = wanted().first
        val have = Downloads.items.value.associateBy { it.videoId }
        val bps = bytesPerSecond()
        return list.size to list.sumOf { have[it.videoId]?.size ?: estimate(it, bps) }
    }

    /** The songs wanted, in order of priority, cut at the space limit; and whether every rule could be worked out. */
    private fun wanted(): Pair<List<LocalItem>, Boolean> {
        val p = Settings.prefs.value
        val out = LinkedHashMap<String, LocalItem>()
        var complete = true
        for (pid in Library.keepOffline.value) {
            val l = if (pid == "LM") Library.liked.value else Library.playlist(pid)?.tracks
            if (l == null) complete = false else l.forEach { i -> i.videoId?.let { out.getOrPut(it) { i } } }
        }
        val fill = p.smartDownloads < 0
        if (p.smartDownloads != 0) {
            val s = Recommend.smartPicks(if (fill) 2000 else p.smartDownloads)
            if (s == null) complete = false else s.forEach { i -> i.videoId?.let { out.getOrPut(it) { i } } }
        }
        if (p.autoDlLiked) Library.liked.value.forEach { i -> i.videoId?.let { out.getOrPut(it) { i } } }
        if (fill) {
            // as many as fit: after the picks, what you like, then what you play most, then your playlists
            Library.liked.value.forEach { i -> i.videoId?.let { out.getOrPut(it) { i } } }
            Library.history.value.groupBy { it.id }.values.sortedByDescending { it.size }
                .forEach { plays -> plays.first().let { pl -> out.getOrPut(pl.id) { pl.toLocal() } } }
            Library.playlists.value.forEach { l -> l.tracks.forEach { i -> i.videoId?.let { out.getOrPut(it) { i } } } }
        }
        val have = Downloads.items.value.associateBy { it.videoId }
        val limit = when {
            p.autoDlLimitMb > 0 -> p.autoDlLimitMb * 1_048_576L
            // no limit set: fill what the phone has, keeping some of it free (what automatic downloads hold counts as theirs)
            fill -> (Downloads.freeBytes() + have.values.filter { it.auto }.sumOf { it.size } - RESERVE).coerceAtLeast(0L)
            else -> Long.MAX_VALUE
        }
        val bps = bytesPerSecond()
        var used = 0L
        val kept = ArrayList<LocalItem>()
        for (i in out.values) {
            if (!isYtId(i.videoId)) continue
            val d = have[i.videoId]
            if (d != null && !d.auto) { kept.add(i); continue }      // yours already: costs the rules nothing
            val size = d?.size ?: estimate(i, bps)
            if (used + size > limit) break
            used += size
            kept.add(i)
        }
        return kept to complete
    }

    private suspend fun run() {
        // the library is read from storage when the app starts; until then, nothing may be judged unwanted
        while (!Library.loaded) delay(500)
        val autos = Downloads.items.value.filter { it.auto }
        val p = Settings.prefs.value
        val anyRule = p.autoDlLiked || p.smartDownloads != 0 || Library.keepOffline.value.isNotEmpty()
        if (!anyRule && autos.isEmpty()) return
        val (list, complete) = wanted()
        val ids = list.mapNotNull { it.videoId }.toHashSet()
        if (complete) {
            val playing = LocalPlayer.currentVideoId      // not current(): this isn't the player's thread
            for (d in autos) if (d.videoId !in ids && d.videoId != playing) Downloads.delete(d.videoId)
        }
        if (p.dlWifiOnly && Downloads.metered()) return
        val todo = list.filter { !Downloads.isDownloaded(it.videoId) }
        if (todo.isNotEmpty()) Downloads.enqueue(todo, auto = true)
    }
}
