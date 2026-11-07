package app.pocketfader.client

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Violet = Color(0xFF6B3FD6)

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
 * Colours follow the settings: system / light / dark / true black (the black theme leaves OLED pixels
 * switched off), with Material You colours on Android 12+ when enabled.
 */
@Composable
fun PocketfaderTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.theme) {
        "dark", "black" -> true
        "light" -> false
        else -> systemDark
    }
    val ctx = LocalContext.current
    var scheme = when {
        prefs.dynamicColor && prefs.accent == 0 && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    if (prefs.theme == "black") {
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
    CompositionLocalProvider(LocalReduceMotion provides prefs.reduceMotion) {
        MaterialTheme(colorScheme = scheme) {
            // The base layer gives every piece of text a readable default colour, whatever theme is chosen.
            androidx.compose.material3.Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}
