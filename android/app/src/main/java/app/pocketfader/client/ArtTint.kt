package app.pocketfader.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Highlight colour taken from the cover (only when the setting asks for it); null means use the theme accent. */
val LocalAccent = compositionLocalOf<Color?> { null }

/** The main colour of the current cover (small, cached copy of the picture; computed once per track). */
@Composable
fun rememberArtTint(url: String?): State<Color?> {
    val ctx = LocalContext.current
    val tint = remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) { tint.value = null; return@LaunchedEffect }
        val result = try {
            ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(64).allowHardware(false).build())
        } catch (e: Exception) { null }
        val bmp = (result as? SuccessResult)?.drawable?.toBitmap()
        tint.value = if (bmp == null) null else withContext(Dispatchers.Default) {
            val p = Palette.from(bmp).maximumColorCount(10).generate()
            (p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb?.let { Color(it) }
        }
    }
    return tint
}

/**
 * A soft wash of the cover's colour fading into the background. It is weakened as needed so the normal text colour
 * always stays easy to read on top of it (a dark cover on the light theme, a bright one on the dark theme).
 */
@Composable
fun artBackground(tint: Color?, black: Boolean): Brush {
    val reduce = LocalReduceMotion.current
    val base = MaterialTheme.colorScheme.background
    val text = MaterialTheme.colorScheme.onBackground
    val target = tint ?: base
    val animated by animateColorAsState(target, tween(if (reduce) 0 else 700), label = "art")
    var strength = if (black) 0.22f else 0.42f
    while (strength > 0.02f && contrast(text, lerp(base, animated, strength)) < 7f) strength *= 0.8f
    return Brush.verticalGradient(
        0f to lerp(base, animated, strength), 0.55f to lerp(base, animated, strength * 0.35f), 1f to base,
    )
}
