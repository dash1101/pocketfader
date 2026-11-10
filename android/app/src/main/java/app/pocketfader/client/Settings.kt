package app.pocketfader.client

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PocketfaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Downloads.init(this)
        Library.init(this)
        Recommend.init(this)
        LocalPlayer.init(this)
        Widgets.start(this)
        AutoDownload.soon()
    }
}

@Immutable
data class Prefs(
    // appearance
    val theme: String = "system",            // system | light | dark | black
    val dynamicColor: Boolean = true,        // Material You colours on Android 12+
    val accent: Int = 0,                     // chosen accent colour (ARGB); 0 = automatic
    val coverWash: Boolean = true,           // tint the background with the cover's colour
    val coverHighlight: Boolean = false,     // lyric highlight takes the cover's colour instead of the accent
    val queueArt: Boolean = true,            // little cover pictures in the queue (off helps very old tablets)
    val playOn: String = "computer",         // computer | phone: where music plays
    val standalone: Boolean = false,         // used without a computer (set up from the first screen)
    val navStyle: String = "bar",            // bar | pill: how the bottom navigation looks
    val coverGlow: String = "strong",        // off | soft | strong | extreme: halo around the cover
    val glowUi: Boolean = false,             // glow around text, icons and buttons
    val wavyBar: Boolean = true,             // the seek bar ripples while music plays
    val coverShape: String = "rounded",      // rounded | soft | circle | cut
    val transitions: Boolean = true,         // fade/slide between screens
    // library & offline
    val autoSync: Boolean = true,            // sync playlists and likes from the account whenever the computer's YouTube Music is there
    val autoDlLiked: Boolean = false,        // keep liked songs downloaded
    val smartDownloads: Int = 0,             // keep this many of your most played / recommended songs downloaded (0 = off)
    val dlWifiOnly: Boolean = true,          // automatic downloads wait for Wi-Fi
    val autoDlLimitMb: Int = 2048,           // space automatic downloads may use (0 = no limit)
    // display
    val keepAwake: String = "off",           // off | playing | always
    val dimAfterMin: Int = 0,                // 0 = never; dims while kept awake and untouched
    val immersive: Boolean = false,          // hide the status and navigation bars
    val pixelShift: Boolean = false,         // nudge the whole screen now and then (OLED burn-in)
    val pixelShiftMin: Int = 2,              // minutes between nudges
    val pixelShiftDp: Int = 4,               // how far
    // controls
    val notification: Boolean = true,        // media notification + background connection
    val volumeKeys: Boolean = true,          // phone volume buttons control the computer
    val volumeStep: Int = 5,                 // percent per press
    val haptics: Boolean = true,
    // lyrics
    val lyricsRoman: Boolean = false,
    val lyricsTranslation: Boolean = false,
    val lyricsSize: Int = 1,                 // 0 small, 1 normal, 2 large
    val lyricsSource: String = "auto",       // auto | kugou | lrclib | netease | ytm
    val lyricsAlign: String = "start",       // start | center | end
    val lyricsSp: Int = 22,                  // base text size
    val lyricsSpacing: Int = 7,              // space around each line, dp
    val lyricsDim: Int = 45,                 // how bright the lines not being sung are, percent
    val lyricsOffsetMs: Int = 0,             // nudge the lyrics earlier (-) or later (+)
    // performance
    val pollSpeed: String = "normal",        // fast | normal | battery
    val reduceMotion: Boolean = false,
    // updates
    val autoUpdate: Boolean = true,
    // start screen
    val startTab: String = "playing",        // playing | search | settings
)

/** App settings: persisted in private storage and observable from the UI, the activity and the service. */
object Settings {
    private lateinit var sp: SharedPreferences
    private val state = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = state

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("pocketfader_settings", Context.MODE_PRIVATE)
        state.value = read()
    }

    fun update(change: (Prefs) -> Prefs) {
        val next = change(state.value)
        state.value = next
        if (::sp.isInitialized) write(next)
    }

    // the reset keeps where music plays and the offline rules (resetting those could delete downloads)
    fun reset() = update {
        Prefs(
            playOn = it.playOn, standalone = it.standalone, autoSync = it.autoSync, autoDlLiked = it.autoDlLiked,
            smartDownloads = it.smartDownloads, dlWifiOnly = it.dlWifiOnly, autoDlLimitMb = it.autoDlLimitMb,
        )
    }

    private fun read(): Prefs {
        val d = Prefs()
        fun s(k: String, v: String) = sp.getString(k, v) ?: v
        fun b(k: String, v: Boolean) = sp.getBoolean(k, v)
        fun i(k: String, v: Int) = sp.getInt(k, v)
        return Prefs(
            theme = s("theme", d.theme), dynamicColor = b("dynamicColor", d.dynamicColor),
            accent = i("accent", d.accent), coverWash = b("coverWash", d.coverWash),
            coverHighlight = b("coverHighlight", d.coverHighlight), navStyle = s("navStyle", d.navStyle), queueArt = b("queueArt", d.queueArt),
            playOn = s("playOn", d.playOn), standalone = b("standalone", d.standalone),
            coverGlow = s("coverGlow", d.coverGlow), glowUi = b("glowUi", d.glowUi), wavyBar = b("wavyBar", d.wavyBar),
            coverShape = s("coverShape", d.coverShape), transitions = b("transitions", d.transitions),
            keepAwake = s("keepAwake", d.keepAwake), dimAfterMin = i("dimAfterMin", d.dimAfterMin),
            immersive = b("immersive", d.immersive), pixelShift = b("pixelShift", d.pixelShift),
            pixelShiftMin = i("pixelShiftMin", d.pixelShiftMin), pixelShiftDp = i("pixelShiftDp", d.pixelShiftDp),
            notification = b("notification", d.notification), volumeKeys = b("volumeKeys", d.volumeKeys),
            volumeStep = i("volumeStep", d.volumeStep), haptics = b("haptics", d.haptics),
            lyricsRoman = b("lyricsRoman", d.lyricsRoman), lyricsTranslation = b("lyricsTranslation", d.lyricsTranslation),
            lyricsSize = i("lyricsSize", d.lyricsSize), lyricsSource = s("lyricsSource", d.lyricsSource), lyricsOffsetMs = i("lyricsOffsetMs", d.lyricsOffsetMs),
            lyricsAlign = s("lyricsAlign", d.lyricsAlign), lyricsSp = i("lyricsSp", d.lyricsSp),
            lyricsSpacing = i("lyricsSpacing", d.lyricsSpacing), lyricsDim = i("lyricsDim", d.lyricsDim),
            autoUpdate = b("autoUpdate", d.autoUpdate),
            pollSpeed = s("pollSpeed", d.pollSpeed), reduceMotion = b("reduceMotion", d.reduceMotion),
            startTab = s("startTab", d.startTab),
            autoSync = b("autoSync", d.autoSync), autoDlLiked = b("autoDlLiked", d.autoDlLiked),
            smartDownloads = i("smartDownloads", d.smartDownloads), dlWifiOnly = b("dlWifiOnly", d.dlWifiOnly),
            autoDlLimitMb = i("autoDlLimitMb", d.autoDlLimitMb),
        )
    }

    private fun write(p: Prefs) {
        sp.edit()
            .putString("theme", p.theme).putBoolean("dynamicColor", p.dynamicColor)
            .putInt("accent", p.accent).putBoolean("coverWash", p.coverWash)
            .putBoolean("coverHighlight", p.coverHighlight).putString("navStyle", p.navStyle).putBoolean("queueArt", p.queueArt)
            .putString("playOn", p.playOn).putBoolean("standalone", p.standalone)
            .putString("coverGlow", p.coverGlow).putBoolean("glowUi", p.glowUi).putBoolean("wavyBar", p.wavyBar)
            .putString("coverShape", p.coverShape).putBoolean("transitions", p.transitions)
            .putString("keepAwake", p.keepAwake).putInt("dimAfterMin", p.dimAfterMin)
            .putBoolean("immersive", p.immersive).putBoolean("pixelShift", p.pixelShift)
            .putInt("pixelShiftMin", p.pixelShiftMin).putInt("pixelShiftDp", p.pixelShiftDp)
            .putBoolean("notification", p.notification).putBoolean("volumeKeys", p.volumeKeys)
            .putInt("volumeStep", p.volumeStep).putBoolean("haptics", p.haptics)
            .putBoolean("lyricsRoman", p.lyricsRoman).putBoolean("lyricsTranslation", p.lyricsTranslation)
            .putInt("lyricsSize", p.lyricsSize).putString("lyricsSource", p.lyricsSource)
            .putInt("lyricsOffsetMs", p.lyricsOffsetMs)
            .putString("lyricsAlign", p.lyricsAlign).putInt("lyricsSp", p.lyricsSp)
            .putInt("lyricsSpacing", p.lyricsSpacing).putInt("lyricsDim", p.lyricsDim).putBoolean("autoUpdate", p.autoUpdate)
            .putString("pollSpeed", p.pollSpeed).putBoolean("reduceMotion", p.reduceMotion)
            .putString("startTab", p.startTab)
            .putBoolean("autoSync", p.autoSync).putBoolean("autoDlLiked", p.autoDlLiked)
            .putInt("smartDownloads", p.smartDownloads).putBoolean("dlWifiOnly", p.dlWifiOnly)
            .putInt("autoDlLimitMb", p.autoDlLimitMb)
            .apply()
    }
}
