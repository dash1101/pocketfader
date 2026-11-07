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
    }
}

@Immutable
data class Prefs(
    // appearance
    val theme: String = "system",            // system | light | dark | black
    val dynamicColor: Boolean = true,        // Material You colours on Android 12+
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
    val lyricsSource: String = "auto",       // auto | lrclib | netease | ytm
    // performance
    val pollSpeed: String = "normal",        // fast | normal | battery
    val reduceMotion: Boolean = false,
    // start screen
    val startTab: String = "playing",        // playing | search | library | mixer
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

    fun reset() = update { Prefs() }

    private fun read(): Prefs {
        val d = Prefs()
        fun s(k: String, v: String) = sp.getString(k, v) ?: v
        fun b(k: String, v: Boolean) = sp.getBoolean(k, v)
        fun i(k: String, v: Int) = sp.getInt(k, v)
        return Prefs(
            theme = s("theme", d.theme), dynamicColor = b("dynamicColor", d.dynamicColor),
            keepAwake = s("keepAwake", d.keepAwake), dimAfterMin = i("dimAfterMin", d.dimAfterMin),
            immersive = b("immersive", d.immersive), pixelShift = b("pixelShift", d.pixelShift),
            pixelShiftMin = i("pixelShiftMin", d.pixelShiftMin), pixelShiftDp = i("pixelShiftDp", d.pixelShiftDp),
            notification = b("notification", d.notification), volumeKeys = b("volumeKeys", d.volumeKeys),
            volumeStep = i("volumeStep", d.volumeStep), haptics = b("haptics", d.haptics),
            lyricsRoman = b("lyricsRoman", d.lyricsRoman), lyricsTranslation = b("lyricsTranslation", d.lyricsTranslation),
            lyricsSize = i("lyricsSize", d.lyricsSize), lyricsSource = s("lyricsSource", d.lyricsSource),
            pollSpeed = s("pollSpeed", d.pollSpeed), reduceMotion = b("reduceMotion", d.reduceMotion),
            startTab = s("startTab", d.startTab),
        )
    }

    private fun write(p: Prefs) {
        sp.edit()
            .putString("theme", p.theme).putBoolean("dynamicColor", p.dynamicColor)
            .putString("keepAwake", p.keepAwake).putInt("dimAfterMin", p.dimAfterMin)
            .putBoolean("immersive", p.immersive).putBoolean("pixelShift", p.pixelShift)
            .putInt("pixelShiftMin", p.pixelShiftMin).putInt("pixelShiftDp", p.pixelShiftDp)
            .putBoolean("notification", p.notification).putBoolean("volumeKeys", p.volumeKeys)
            .putInt("volumeStep", p.volumeStep).putBoolean("haptics", p.haptics)
            .putBoolean("lyricsRoman", p.lyricsRoman).putBoolean("lyricsTranslation", p.lyricsTranslation)
            .putInt("lyricsSize", p.lyricsSize).putString("lyricsSource", p.lyricsSource)
            .putString("pollSpeed", p.pollSpeed).putBoolean("reduceMotion", p.reduceMotion)
            .putString("startTab", p.startTab)
            .apply()
    }
}
