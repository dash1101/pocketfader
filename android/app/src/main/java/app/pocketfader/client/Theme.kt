package app.pocketfader.client

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

private val Violet = Color(0xFF6B3FD6)

/**
 * One scale of corners for the whole app, shared with the web app and with Refine (docs/DESIGN.md):
 * the bigger the surface, the rounder it is.
 */
object PfShape {
    val Xs = RoundedCornerShape(8.dp)    // small covers, chips that are not chosen
    val Sm = RoundedCornerShape(12.dp)   // the highlight behind a row or a button
    val Md = RoundedCornerShape(16.dp)   // text fields, menus, tiles
    val Lg = RoundedCornerShape(24.dp)   // cards
    val Xl = RoundedCornerShape(28.dp)   // sheets, dialogs, the big cover
    val Full = RoundedCornerShape(percent = 50)   // buttons, chosen chips, switches
}

/** How long things take, and the one curve they move along; the same values as the web app and Refine. */
object Motion {
    const val Quick = 120    // a press
    const val Short = 200    // a fade, a colour, a chip changing shape
    const val Medium = 300   // something moving or opening
    const val Long = 450     // a whole screen or the cover
    val Ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Material's "emphasized decelerate": things arriving glide in fast and settle gently (Refine's rows use it). */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

private val AppShapes = Shapes(extraSmall = PfShape.Md, small = PfShape.Xs, medium = PfShape.Md, large = PfShape.Lg, extraLarge = PfShape.Xl)

private val Dark = darkColorScheme(
    primary = Color(0xFFB69CFF),
    onPrimary = Color(0xFF2A0F6B),
    primaryContainer = Color(0xFF4A2FA0),
    onPrimaryContainer = Color(0xFFE9DEFF),
    secondaryContainer = Color(0xFF3B3552),
    onSecondaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFF0E0E12),
    surface = Color(0xFF0E0E12),
    surfaceVariant = Color(0xFF26252E),
    surfaceContainer = Color(0xFF1A1A21),
    surfaceContainerHigh = Color(0xFF22222A),
    onSurface = Color(0xFFE6E3EC),
    onSurfaceVariant = Color(0xFFB2AFBD),
    outline = Color(0xFF7B7886),
)

private val Light = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DEFF),
    onPrimaryContainer = Color(0xFF26005F),
    secondaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE7E0EC),
)

/** True when the user asked for fewer animations (or the device is slow): screens skip decorative motion. */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * The colours of the cover that plays, as the whole app's theme (Album colours): they move to the new cover's the short
 * way round the colour wheel, and are remembered for the next start. The move takes five steps in about half a second
 * rather than every frame: each step recolours the whole app, which is too much work to do sixty times a second on an
 * older phone (the soft wash behind the player glides smoothly on its own).
 */
@Composable
private fun albumScheme(art: ArtInfo, mode: AlbumMode, calm: Boolean): ColorScheme {
    val ctx = LocalContext.current
    // the last real cover colour: a song with no picture (or before it loads) keeps the colours that are showing
    val last = remember { arrayOf(AlbumMemory.load(ctx)) }
    val target = art.hue?.also { last[0] = it } ?: last[0]
    LaunchedEffect(art.hue) { art.hue?.let { AlbumMemory.save(ctx, it) } }
    // what is shown: the hue, and how colourful the tones are (so a grey cover fades to neutral instead of snapping)
    var shown by remember { mutableStateOf(target.hue to albumChroma(target.vivid)) }
    LaunchedEffect(target) {
        val (h0, c0) = shown
        val h1 = hueStep(h0, target.hue)
        val c1 = albumChroma(target.vivid)
        val steps = if (calm) 1 else 5
        for (i in 1..steps) {
            if (steps > 1) delay(110L)
            val k = Motion.Ease.transform(i / steps.toFloat())
            shown = (((h0 + (h1 - h0) * k) % 360f) + 360f) % 360f to (c0 + (c1 - c0) * k)
        }
    }
    val (h, c) = shown
    return remember(h, c, mode) { albumTonesAt(h, c, mode).toScheme(mode == AlbumMode.Light) }
}

/**
 * Colours follow the settings: system / light / dark / true black (the black theme leaves OLED pixels switched off).
 * With Album colours on (the default) they come from the cover that plays, as Refine does in YouTube Music; otherwise
 * Material You colours on Android 12+ when enabled. A chosen accent colour wins over both.
 */
@Composable
fun PocketfaderTheme(prefs: Prefs, art: ArtInfo = ArtInfo(), content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.theme) {
        "dark", "black" -> true
        "light" -> false
        else -> systemDark
    }
    val ctx = LocalContext.current
    val album = prefs.albumColors && prefs.accent == 0
    val albumMode = when { !dark -> AlbumMode.Light; prefs.theme == "black" -> AlbumMode.Black; else -> AlbumMode.Dark }
    var scheme = when {
        album -> albumScheme(art, albumMode, prefs.reduceMotion)
        prefs.dynamicColor && prefs.accent == 0 && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    if (prefs.theme == "black" && !album) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainer = Color(0xFF0B0B0D), surfaceContainerHigh = Color(0xFF141417),
            surfaceVariant = Color(0xFF1B1B20),
        )
    }
    // A custom accent is checked against the real background, so it can never vanish into it.
    if (prefs.accent != 0) scheme = withAccent(scheme, Color(prefs.accent), dark)
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalReduceMotion provides prefs.reduceMotion, LocalGlowUi provides prefs.glowUi) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes) {
            // The base layer gives every piece of text a readable default colour, whatever theme is chosen.
            androidx.compose.material3.Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}
