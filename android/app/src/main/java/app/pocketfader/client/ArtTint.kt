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

/** The cover's main colour and a small (64 px), already blurred copy of the picture, used for the ambient glow around it. */
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
            val thumb = try { softCopy(bmp).asImageBitmap() } catch (e: Exception) { null }
            ArtInfo(tint, thumb)
        }
    }
    return art
}

/** The background wash: a brush, and the colour it has at a given fraction of the screen's height (0 top, 1 bottom). */
@Immutable
class Wash(val brush: Brush, val colorAt: (Float) -> Color)

/**
 * A soft wash of the cover's colour fading into the background. It is weakened as needed so the normal text colour
 * always stays easy to read on top of it (a dark cover on the light theme, a bright one on the dark theme).
 */
@Composable
fun artBackground(tint: Color?, black: Boolean): Wash {
    val reduce = LocalReduceMotion.current
    val base = MaterialTheme.colorScheme.background
    val text = MaterialTheme.colorScheme.onBackground
    val target = tint ?: base
    val animated by animateColorAsState(target, tween(if (reduce) 0 else 700), label = "art")
    var strength = if (black) 0.22f else 0.42f
    while (strength > 0.02f && contrast(text, lerp(base, animated, strength)) < 7f) strength *= 0.8f
    val k0 = strength
    val brush = Brush.verticalGradient(
        0f to lerp(base, animated, k0), 0.55f to lerp(base, animated, k0 * 0.35f), 1f to base,
    )
    val colorAt = { t: Float ->
        val k = if (t <= 0.55f) k0 + (k0 * 0.35f - k0) * (t / 0.55f) else k0 * 0.35f * (1f - (t - 0.55f) / 0.45f)
        lerp(base, animated, k.coerceIn(0f, 1f))
    }
    return Wash(brush, colorAt)
}

/** A 64 px copy of the picture, blurred (three box passes, close to a Gaussian), so scaling it up gives a smooth glow with no blocks. */
private fun softCopy(src: Bitmap): Bitmap {
    val n = 64
    val small = Bitmap.createScaledBitmap(src, n, n, true)
    var px = IntArray(n * n)
    small.getPixels(px, 0, n, 0, 0, n, n)
    repeat(3) { px = boxBlur(px, n, 4) }
    val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, n, 0, 0, n, n)
    return out
}

private fun boxBlur(src: IntArray, n: Int, r: Int): IntArray {
    val tmp = IntArray(n * n)
    val out = IntArray(n * n)
    val m = 2 * r + 1
    for (pass in 0..1) {
        val from = if (pass == 0) src else tmp
        val to = if (pass == 0) tmp else out
        for (y in 0 until n) for (x in 0 until n) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = if (pass == 0) from[y * n + (x + k).coerceIn(0, n - 1)] else from[(y + k).coerceIn(0, n - 1) * n + x]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            to[y * n + x] = ((a / m) shl 24) or ((rr / m) shl 16) or ((g / m) shl 8) or (b / m)
        }
    }
    return out
}
