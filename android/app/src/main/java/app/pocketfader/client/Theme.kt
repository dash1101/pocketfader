package app.pocketfader.client

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Violet = Color(0xFF8B5CF6)

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

/** Material You colours on Android 12+, the Pocketfader violet palette everywhere else. */
@Composable
fun PocketfaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
