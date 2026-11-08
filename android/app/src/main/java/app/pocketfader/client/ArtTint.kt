package app.pocketfader.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

/** The cover's main colour and a tiny (32 px) copy of the picture, used for the ambient glow around it. */
@Immutable
data class ArtInfo(val tint: Color? = null, val thumb: ImageBitmap? = null)

/** The tiny copy of the current cover (null until it's loaded). */
val LocalArtThumb = compositionLocalOf<ImageBitmap?> { null }

/** Colour and tiny picture of the current cover (small, cached copy; computed once per track). */
@Composable
fun rememberArt(url: String?): State<ArtInfo> {
    val ctx = LocalContext.current
    val art = remember { mutableStateOf(ArtInfo()) }
    LaunchedEffect(url) {
        if (url == null) { art.value = ArtInfo(); return@LaunchedEffect }
        val result = try {
            ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(64).allowHardware(false).build())
        } catch (e: Exception) { null }
        val bmp = (result as? SuccessResult)?.drawable?.toBitmap()
        art.value = if (bmp == null) ArtInfo() else withContext(Dispatchers.Default) {
            val p = Palette.from(bmp).maximumColorCount(10).generate()
            val tint = (p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb?.let { Color(it) }
            // scaled up smoothly later, this little picture becomes a soft blur of the cover's own edges
            val thumb = try { Bitmap.createScaledBitmap(bmp, 32, 32, true).asImageBitmap() } catch (e: Exception) { null }
            ArtInfo(tint, thumb)
        }
    }
    return art
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
