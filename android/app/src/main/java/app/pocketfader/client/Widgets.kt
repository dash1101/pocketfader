package app.pocketfader.client

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Home-screen widgets. They're drawn with RemoteViews (what every launcher understands, back to Android 5), from the same state
// the app shows; buttons send broadcasts to WidgetActions, which acts through Remote (computer or phone, whichever is playing).

class NowWideWidget : WidgetBase() { override val kind = WidgetKind.Wide }
class NowBarWidget : WidgetBase() { override val kind = WidgetKind.Bar }
class CoverWidget : WidgetBase() { override val kind = WidgetKind.Cover }
class VolumeWidget : WidgetBase() { override val kind = WidgetKind.Volume }
class ActionsWidget : WidgetBase() { override val kind = WidgetKind.Actions }
class BeaconWidget : WidgetBase() { override val kind = WidgetKind.Beacon }
class UpNextWidget : WidgetBase() { override val kind = WidgetKind.UpNext }

enum class WidgetKind(val layout: Int) {
    Wide(R.layout.widget_now_wide), Bar(R.layout.widget_now_bar), Cover(R.layout.widget_cover),
    Volume(R.layout.widget_volume), Actions(R.layout.widget_actions),
    Beacon(R.layout.widget_beacon), UpNext(R.layout.widget_upnext),
}

abstract class WidgetBase : AppWidgetProvider() {
    abstract val kind: WidgetKind

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.render(context)
        Widgets.refreshSoon(context)
        Widgets.refreshBeacon(context)
    }

    override fun onEnabled(context: Context) { Widgets.render(context) }
}

/** Taps on widget buttons. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Settings.init(context)
        Remote.ensureConfigured(ConfigStore(context))
        val s = Remote.state.value
        val step = Settings.prefs.value.volumeStep
        when (intent.action) {
            A_PLAY -> Remote.transport("playpause")
            A_NEXT -> Remote.transport("next")
            A_PREV -> Remote.transport("prev")
            A_LIKE -> {
                val n = s.now
                val vid = n?.videoId
                if (n != null && vid != null && isYtId(vid)) Remote.like(vid, !n.liked)
            }
            // the computer's volume only while "Computer audio" is switched on there
            A_VOL_UP, A_VOL_DOWN -> {
                val d = if (intent.action == A_VOL_UP) step else -step
                if (Remote.local) LocalPlayer.setPhoneVolume(LocalPlayer.phoneVolume() + d) else if (s.audioOn) Remote.adjustVolume(d)
            }
            A_MUTE -> if (Remote.local) LocalPlayer.togglePhoneMute() else if (s.audioOn) Remote.setMuted(!s.volume.muted)
            A_LUCKY -> Remote.lucky()
            A_SLEEP -> Remote.setSleep(if (s.sleepLeft > 0) 0 else 30)     // on, or off again
            A_PAUSE_OTHERS -> Remote.pauseOthers()
            A_PLAY_AT -> {
                val i = intent.getIntExtra("index", -1)
                s.queue.firstOrNull { it.section == "queue" && it.index == i }?.let { Remote.queuePlayAt(it) }
            }
            A_SWITCH -> if (ConfigStore(context).load().isSet) Remote.setPlayOn(if (Remote.local) "computer" else "phone", carryOver = true)
        }
        // keep the process alive for a moment: the command goes out, the state comes back, the widgets redraw
        val pending = goAsync()
        val action = intent.action
        Widgets.scope.launch {
            try {
                if (action == A_BEACON_TOGGLE || action == A_BEACON_HIDE) {
                    val b = Widgets.beacon
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (action == A_BEACON_HIDE) Remote.beaconVisibility(false, 60)
                            else if (b != null && b.reachable) Remote.beaconVisibility(!b.visible)
                        }
                    }
                    Widgets.refreshBeacon(context)
                }
                withContext(Dispatchers.IO) { Remote.pollNow() }
                Widgets.render(context)
                delay(1500)
                withContext(Dispatchers.IO) { Remote.pollNow() }
                Widgets.render(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val A_PLAY = "app.pocketfader.widget.PLAY"
        const val A_NEXT = "app.pocketfader.widget.NEXT"
        const val A_PREV = "app.pocketfader.widget.PREV"
        const val A_LIKE = "app.pocketfader.widget.LIKE"
        const val A_VOL_UP = "app.pocketfader.widget.VOL_UP"
        const val A_VOL_DOWN = "app.pocketfader.widget.VOL_DOWN"
        const val A_MUTE = "app.pocketfader.widget.MUTE"
        const val A_LUCKY = "app.pocketfader.widget.LUCKY"
        const val A_SLEEP = "app.pocketfader.widget.SLEEP"
        const val A_PAUSE_OTHERS = "app.pocketfader.widget.PAUSE_OTHERS"
        const val A_SWITCH = "app.pocketfader.widget.SWITCH"
        const val A_PLAY_AT = "app.pocketfader.widget.PLAY_AT"
        const val A_BEACON_TOGGLE = "app.pocketfader.widget.BEACON_TOGGLE"
        const val A_BEACON_HIDE = "app.pocketfader.widget.BEACON_HIDE"
    }
}

object Widgets {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.quiet)
    private var started = false

    /** The cover, rounded, for the current song (made once per song). */
    private class Art(val url: String?, val cover: Bitmap?)
    private const val DEFAULT_ACCENT = 0xFF7D8FF5.toInt()
    @Volatile private var art = Art(null, null)

    /** Redraw whenever what the widgets show changes (only while some widget exists). */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            Remote.state
                .map { s -> listOf(s.now?.title, s.now?.artist, s.now?.art, s.now?.playing, s.now?.liked, s.volume.percent, s.volume.muted, s.source, s.online, s.ytm, Remote.local, s.queue, s.parts) }
                .distinctUntilChanged()
                .collect { if (any(app)) render(app) }
        }
        // the progress bar moves on its own, gently (every 10 s is enough for a home screen)
        scope.launch {
            while (true) {
                delay(10_000)
                if (any(app)) {
                    if (WidgetKind.Beacon.let { ids(app, it).isNotEmpty() }) refreshBeacon(app)
                    if (Remote.state.value.now?.playing == true) render(app)
                }
            }
        }
    }

    fun refreshSoon(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) { Remote.pollNow() }
            render(app)
        }
    }

    private val providers = mapOf(
        WidgetKind.Wide to NowWideWidget::class.java, WidgetKind.Bar to NowBarWidget::class.java,
        WidgetKind.Cover to CoverWidget::class.java, WidgetKind.Volume to VolumeWidget::class.java,
        WidgetKind.Actions to ActionsWidget::class.java, WidgetKind.Beacon to BeaconWidget::class.java,
        WidgetKind.UpNext to UpNextWidget::class.java,
    )

    /** The Discord status as the computer reports it (null: not reachable or not asked yet). */
    @Volatile var beacon: BeaconStatus? = null

    fun refreshBeacon(ctx: Context) {
        val app = ctx.applicationContext
        if (ids(app, WidgetKind.Beacon).isEmpty()) return
        scope.launch {
            beacon = withContext(Dispatchers.IO) { runCatching { Remote.beaconStatus() }.getOrNull() }
            draw(app, Remote.state.value)
        }
    }

    private fun ids(ctx: Context, kind: WidgetKind): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, providers.getValue(kind)))

    private fun any(ctx: Context) = WidgetKind.values().any { ids(ctx, it).isNotEmpty() }

    /** Draw every widget now (fetching the cover first if the song changed). Main thread. */
    fun render(ctx: Context) {
        val s = Remote.state.value
        val url = s.now?.art
        if (url != art.url) {
            scope.launch {
                art = withContext(Dispatchers.IO) { loadArt(ctx, url) }
                draw(ctx, Remote.state.value)
            }
        }
        draw(ctx, s)
    }

    private fun draw(ctx: Context, s: RemoteState) {
        val m = AppWidgetManager.getInstance(ctx)
        for (kind in WidgetKind.values()) {
            val ids = ids(ctx, kind)
            if (ids.isEmpty()) continue
            val v = RemoteViews(ctx.packageName, kind.layout)
            when (kind) {
                WidgetKind.Wide, WidgetKind.Bar, WidgetKind.Cover -> nowPlaying(ctx, v, kind, s)
                WidgetKind.Volume -> volume(ctx, v, s)
                WidgetKind.Actions -> actions(ctx, v, s)
                WidgetKind.Beacon -> beaconView(ctx, v)
                WidgetKind.UpNext -> upNext(ctx, v, s)
            }
            for (id in ids) runCatching { m.updateAppWidget(id, v) }
        }
    }

    private fun pi(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(), Intent(ctx, WidgetActions::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Black or white, whichever reads better on [c]. */
    private fun onColor(c: Int): Int {
        val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return if (l > 0.6) 0xFF111318.toInt() else Color.WHITE
    }

    /** The widgets' accent: the one chosen in Settings → Appearance, else a soft periwinkle. */
    private fun accent(): Int = Settings.prefs.value.accent.takeIf { it != 0 } ?: DEFAULT_ACCENT

    private fun nowPlaying(ctx: Context, v: RemoteViews, kind: WidgetKind, s: RemoteState) {
        val now = s.now
        val has = now != null && now.title.isNotBlank()
        v.setTextViewText(R.id.title, if (has) now!!.title else "Nothing playing")
        v.setTextViewText(
            R.id.artist,
            when {
                has -> now!!.artist
                !s.configured && !Remote.local -> "Open YTM Starcast to connect"
                !Remote.local && !s.remoteOn -> "The YouTube Music remote is off"
                else -> "Tap to open YTM Starcast"
            },
        )
        val c = art.cover
        if (c != null) v.setImageViewBitmap(R.id.cover, c) else v.setImageViewResource(R.id.cover, R.drawable.ic_w_music)
        val acc = accent()
        v.setInt(R.id.play_bg, "setColorFilter", acc)
        v.setInt(R.id.play, "setColorFilter", onColor(acc))
        v.setImageViewResource(R.id.play, if (now?.playing == true) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setOnClickPendingIntent(R.id.play_box, pi(ctx, WidgetActions.A_PLAY))
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        if (kind != WidgetKind.Cover) {
            v.setOnClickPendingIntent(R.id.prev, pi(ctx, WidgetActions.A_PREV))
            v.setOnClickPendingIntent(R.id.next, pi(ctx, WidgetActions.A_NEXT))
        }
        if (kind == WidgetKind.Wide) {
            val p = Remote.progress.value
            val playing = now?.playing == true
            val dur = now?.duration ?: 0.0
            val pos = p.positionAt(SystemClock.elapsedRealtime(), playing).coerceIn(0.0, if (dur > 0) dur else 0.0)
            v.setProgressBar(R.id.progress, 1000, if (dur > 0) ((pos / dur) * 1000).toInt().coerceIn(0, 1000) else 0, false)
            // the elapsed time counts by itself on the home screen; the bar follows every few seconds. At another speed
            // both times are how long it takes to hear, so the clock still runs at one second per second.
            v.setChronometer(R.id.pos, SystemClock.elapsedRealtime() - (pos / p.rate * 1000).toLong(), null, playing)
            v.setTextViewText(R.id.dur, if (dur > 0) formatTime(dur / p.rate) else "0:00")
            val canLike = s.ytm && isYtId(now?.videoId)
            v.setViewVisibility(R.id.like, if (canLike) android.view.View.VISIBLE else android.view.View.GONE)
            v.setImageViewResource(R.id.like, if (now?.liked == true) R.drawable.ic_w_like else R.drawable.ic_w_like_off)
            if (now?.liked == true) v.setInt(R.id.like, "setColorFilter", acc) else v.setInt(R.id.like, "setColorFilter", 0xFFA9ACB6.toInt())
            v.setOnClickPendingIntent(R.id.like, pi(ctx, WidgetActions.A_LIKE))
        }
    }

    private fun beaconView(ctx: Context, v: RemoteViews) {
        val b = beacon
        val acc = accent()
        val tone = when {
            b == null || !b.reachable -> 0xFF8A8D98.toInt()
            b.headline in setOf("showing", "waiting", "idle") -> 0xFF3DDC97.toInt()
            b.headline == "off" -> 0xFF8A8D98.toInt()
            b.headline in setOf("discord-down", "extension-missing", "unsupported") -> 0xFFFF6B6B.toInt()
            else -> 0xFFFFB84D.toInt()
        }
        v.setInt(R.id.b_dot_bg, "setColorFilter", tone)
        v.setTextViewText(R.id.b_status, if (b == null) "Checking…" else beaconSummary(b))
        v.setTextViewText(R.id.b_song, b?.song?.let { (if (it.playing) "▶ " else "⏸ ") + it.title + if (it.artist.isNotBlank()) " · " + it.artist else "" } ?: "")
        val usable = b != null && b.reachable && b.headline != "unsupported"
        v.setInt(R.id.b_toggle_bg, "setColorFilter", if (usable) acc else 0xFF45474F.toInt())
        v.setInt(R.id.b_toggle_icon, "setColorFilter", if (usable) onColor(acc) else 0xFFA9ACB6.toInt())
        v.setImageViewResource(R.id.b_toggle_icon, if (b != null && b.visible) R.drawable.ic_w_eye else R.drawable.ic_w_eye_off)
        v.setOnClickPendingIntent(R.id.b_toggle, pi(ctx, WidgetActions.A_BEACON_TOGGLE))
        v.setOnClickPendingIntent(R.id.b_hide, pi(ctx, WidgetActions.A_BEACON_HIDE))
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
    }

    private val rowIds = listOf(
        listOf(R.id.u0, R.id.u0_n, R.id.u0_t, R.id.u0_a, R.id.u0_d), listOf(R.id.u1, R.id.u1_n, R.id.u1_t, R.id.u1_a, R.id.u1_d),
        listOf(R.id.u2, R.id.u2_n, R.id.u2_t, R.id.u2_a, R.id.u2_d), listOf(R.id.u3, R.id.u3_n, R.id.u3_t, R.id.u3_a, R.id.u3_d),
    )

    private fun upNext(ctx: Context, v: RemoteViews, s: RemoteState) {
        val songs = s.queue.filter { it.section == "queue" }
        val cur = songs.indexOfFirst { it.selected }
        val next = songs.drop(if (cur >= 0) cur + 1 else 0)
        v.setTextViewText(R.id.u_count, if (next.isEmpty()) "" else "${next.size} songs")
        v.setViewVisibility(R.id.u_empty, if (next.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
        for ((i, ids) in rowIds.withIndex()) {
            val t = next.getOrNull(i)
            v.setViewVisibility(ids[0], if (t == null) android.view.View.GONE else android.view.View.VISIBLE)
            if (t == null) continue
            v.setTextViewText(ids[2], t.title)
            v.setTextViewText(ids[3], t.artist)
            v.setTextViewText(ids[4], t.duration)
            v.setOnClickPendingIntent(
                ids[0],
                PendingIntent.getBroadcast(
                    ctx, 1000 + i, Intent(ctx, WidgetActions::class.java).setAction(WidgetActions.A_PLAY_AT).putExtra("index", t.index),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
    }

    private fun volume(ctx: Context, v: RemoteViews, s: RemoteState) {
        val phone = Remote.local
        val off = !phone && !s.audioOn       // "Computer audio" is switched off on the computer
        val pct = if (phone) LocalPlayer.phoneVolume() else if (off) 0 else s.volume.percent
        val muted = !phone && !off && s.volume.muted
        v.setTextViewText(
            R.id.label,
            when {
                phone -> "Phone volume"
                off -> "Computer audio is off"
                s.volume.device.isNotBlank() -> s.volume.device
                else -> "Computer volume"
            },
        )
        v.setTextViewText(R.id.percent, if (!phone && (off || !s.volume.available)) "–" else if (muted) "Muted" else "$pct%")
        v.setProgressBar(R.id.level, 100, if (muted) 0 else pct, false)
        v.setImageViewResource(R.id.mute_icon, if (muted || pct == 0) R.drawable.ic_w_mute else R.drawable.ic_w_vol_up)
        v.setOnClickPendingIntent(R.id.mute, pi(ctx, WidgetActions.A_MUTE))
        v.setOnClickPendingIntent(R.id.vol_up, pi(ctx, WidgetActions.A_VOL_UP))
        v.setOnClickPendingIntent(R.id.vol_down, pi(ctx, WidgetActions.A_VOL_DOWN))
    }

    private fun actions(ctx: Context, v: RemoteViews, s: RemoteState) {
        val phone = Remote.local
        val acc = accent()
        for ((bg, icon) in listOf(
            R.id.act_lucky_bg to R.id.act_lucky_icon, R.id.act_sleep_bg to R.id.act_sleep_icon,
            R.id.act_pauseall_bg to R.id.act_pauseall_icon, R.id.act_switch_bg to R.id.act_switch_icon,
        )) {
            v.setInt(bg, "setColorFilter", acc)
            v.setInt(icon, "setColorFilter", onColor(acc))
        }
        v.setOnClickPendingIntent(R.id.act_lucky, pi(ctx, WidgetActions.A_LUCKY))
        v.setOnClickPendingIntent(R.id.act_sleep, pi(ctx, WidgetActions.A_SLEEP))
        v.setOnClickPendingIntent(R.id.act_pauseall, pi(ctx, WidgetActions.A_PAUSE_OTHERS))
        v.setOnClickPendingIntent(R.id.act_switch, pi(ctx, WidgetActions.A_SWITCH))
        v.setImageViewResource(R.id.act_switch_icon, if (phone) R.drawable.ic_w_computer else R.drawable.ic_w_phone)
        v.setTextViewText(R.id.act_switch_label, if (phone) "Play on computer" else "Play on phone")
        val left = s.sleepLeft
        v.setTextViewText(R.id.act_sleep_label, if (left > 0) "Sleep ${(left + 59) / 60} min" else "Sleep 30 min")
    }

    // ---------------------------------------------------------------- the cover, prepared for a widget

    private suspend fun loadArt(ctx: Context, url: String?): Art {
        if (url == null) return Art(null, null)
        val src = try {
            val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(320).allowHardware(false).build())
            (r as? SuccessResult)?.drawable?.toBitmap()
        } catch (e: Exception) { null } ?: return Art(url, null)
        val sq = Bitmap.createScaledBitmap(src, 300, 300, true)
        return Art(url, rounded(sq, 40f))
    }

    private fun rounded(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, paint)
        return out
    }
}
