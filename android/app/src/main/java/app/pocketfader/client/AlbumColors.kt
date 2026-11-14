package app.pocketfader.client

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

// Album colours: the whole app takes its colours from the cover of what plays, the same tones Refine gives YouTube Music
// and the web app (one hue from the cover, Material You style surfaces, containers and an accent). Everything here is
// plain arithmetic on ARGB ints, so it is tested on the computer without a phone.

/** CSS-style hsl() to ARGB: hue in degrees, saturation and lightness in percent (rounded, as Refine writes them). */
fun hslArgb(hue: Float, sat: Float, light: Float, alpha: Float = 1f): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = (sat.roundToInt() / 100f).coerceIn(0f, 1f)
    val l = (light.roundToInt() / 100f).coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val r: Float; val g: Float; val b: Float
    when {
        h < 60f -> { r = c; g = x; b = 0f }
        h < 120f -> { r = x; g = c; b = 0f }
        h < 180f -> { r = 0f; g = c; b = x }
        h < 240f -> { r = 0f; g = x; b = c }
        h < 300f -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** WCAG relative luminance of an ARGB colour (0 black to 1 white). */
fun luminanceOf(c: Int): Double {
    fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
}

/** WCAG contrast ratio of two colours (1 to 21). */
fun contrastOf(a: Int, b: Int): Double {
    val x = luminanceOf(a)
    val y = luminanceOf(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

/** hsl([hue], [sat], [light]) made darker, a step at a time, until it reads at [min] on [against] (yellows need it most). */
private fun darkEnough(hue: Float, sat: Float, light: Float, against: Int, min: Double): Int {
    var l = light
    var c = hslArgb(hue, sat, l)
    while (contrastOf(c, against) < min && l > 5f) {
        l -= 2f
        c = hslArgb(hue, sat, l)
    }
    return c
}

/** The cover's colour as Refine reads it: the most vivid hue, and how much of the picture is colourful at all (0 to 1). */
data class AlbumHue(val hue: Float, val vivid: Float)

/** Refine's default (a rose red) for when no cover has been read yet. */
val DefaultAlbumHue = AlbumHue(352f, 1f)

/**
 * The most vivid colour of a small copy of the cover (ARGB pixels): hues in 10-degree bins, each pixel weighted by how
 * colourful and bright it is, the bin with the most (with its neighbours) wins. Null for a grey or empty picture.
 * The same method as Refine's, so YouTube Music, the web app and this app agree on a cover's colour.
 */
fun dominantHue(pixels: IntArray): AlbumHue? {
    val bins = DoubleArray(36)
    val sums = DoubleArray(36)
    var total = 0.0
    var vivid = 0
    var px = 0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        px++
        val r = ((p shr 16) and 255) / 255.0
        val g = ((p shr 8) and 255) / 255.0
        val b = (p and 255) / 255.0
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        val s = if (mx > 0.0) d / mx else 0.0
        if (mx < 0.18 || s < 0.22) continue
        var h = when (mx) {
            r -> ((g - b) / d) % 6.0
            g -> (b - r) / d + 2.0
            else -> (r - g) / d + 4.0
        }
        h = (h * 60.0 + 360.0) % 360.0
        val w = s * mx * (0.6 + 0.4 * s)
        val bin = (h / 10.0).toInt() % 36
        bins[bin] += w
        sums[bin] += h * w
        total += w
        vivid++
    }
    if (px == 0 || total < 0.5) return null
    fun around(i: Int) = bins[i] + 0.5 * (bins[(i + 35) % 36] + bins[(i + 1) % 36])
    // only a bin that holds some colour can win (an empty one between two equal neighbours would give 0 / 0)
    var best = -1
    for (i in 0 until 36) if (bins[i] > 0.0 && (best < 0 || around(i) > around(best))) best = i
    if (best < 0) return null
    val hue = (sums[best] / bins[best]).toFloat()
    return if (hue.isFinite()) AlbumHue(hue, vivid.toFloat() / px) else null
}

/** Which kind of theme the album tones are made for. */
enum class AlbumMode { Dark, Black, Light }

/** Every colour of a Material colour scheme, as ARGB ints. */
data class AlbumTones(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int, val inversePrimary: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val background: Int, val onBackground: Int, val surface: Int, val onSurface: Int,
    val surfaceVariant: Int, val onSurfaceVariant: Int, val inverseSurface: Int, val inverseOnSurface: Int,
    val outline: Int, val outlineVariant: Int,
    val surfaceBright: Int, val surfaceDim: Int, val surfaceContainerLowest: Int, val surfaceContainerLow: Int,
    val surfaceContainer: Int, val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
)

/**
 * The tones for a cover's hue: Refine's palette (surface 7 %, containers 10 to 21 %, accent 76 %, container 27 % ...)
 * for the dark theme, the same with true black surfaces for the black theme, and light tones of the same hue for the
 * light theme. A grey cover ([vivid] under 0.12) gives nearly neutral tones.
 */
fun albumTones(hue: Float, vivid: Float, mode: AlbumMode): AlbumTones = albumTonesAt(hue, albumChroma(vivid), mode)

/** How colourful the tones are for a cover: fully, or nearly neutral for a grey one (as Refine does). */
fun albumChroma(vivid: Float): Float = if (vivid < 0.12f) 0.3f else 1f

/** The tones at a given colourfulness [c] (1 full, 0.3 nearly neutral), so a change between the two can glide. */
fun albumTonesAt(hue: Float, c: Float, mode: AlbumMode): AlbumTones {
    val t = hue + 50f     // the third colour sits a little round the colour wheel
    fun hsl(h: Float, s: Float, l: Float) = hslArgb(h, s * c, l)
    val black = 0xFF000000.toInt()
    return when (mode) {
        AlbumMode.Light -> AlbumTones(
            // dark enough to read on the deepest card colour (and so on every lighter one), white text on it reads too
            primary = darkEnough(hue, 62f * c, 38f, hsl(hue, 22f, 90f), 4.5), onPrimary = 0xFFFFFFFF.toInt(), primaryContainer = hsl(hue, 72f, 90f),
            onPrimaryContainer = hsl(hue, 70f, 14f), inversePrimary = hsl(hue, 78f, 76f),
            secondary = darkEnough(hue, 22f * c, 38f, hsl(hue, 22f, 90f), 4.5), onSecondary = 0xFFFFFFFF.toInt(), secondaryContainer = hsl(hue, 40f, 90f),
            onSecondaryContainer = hsl(hue, 40f, 14f),
            tertiary = darkEnough(t, 50f * c, 36f, hsl(hue, 22f, 90f), 4.5), onTertiary = 0xFFFFFFFF.toInt(), tertiaryContainer = hsl(t, 60f, 90f), onTertiaryContainer = hsl(t, 60f, 14f),
            background = hsl(hue, 40f, 98f), onBackground = hsl(hue, 15f, 11f), surface = hsl(hue, 40f, 98f), onSurface = hsl(hue, 15f, 11f),
            surfaceVariant = hsl(hue, 22f, 90f), onSurfaceVariant = hsl(hue, 10f, 30f),
            inverseSurface = hsl(hue, 12f, 19f), inverseOnSurface = hsl(hue, 30f, 95f),
            outline = hsl(hue, 8f, 48f), outlineVariant = hsl(hue, 14f, 80f),
            surfaceBright = hsl(hue, 40f, 98f), surfaceDim = hsl(hue, 18f, 87f), surfaceContainerLowest = 0xFFFFFFFF.toInt(),
            surfaceContainerLow = hsl(hue, 36f, 96f), surfaceContainer = hsl(hue, 30f, 94f), surfaceContainerHigh = hsl(hue, 26f, 92f),
            surfaceContainerHighest = hsl(hue, 22f, 90f),
        )
        else -> {
            val bl = mode == AlbumMode.Black
            val surface = if (bl) black else hsl(hue, 14f, 7f)
            AlbumTones(
                primary = hsl(hue, 78f, 76f), onPrimary = hsl(hue, 62f, 15f), primaryContainer = hsl(hue, 42f, 27f),
                onPrimaryContainer = hsl(hue, 78f, 90f), inversePrimary = hsl(hue, 60f, 40f),
                secondary = hsl(hue, 36f, 74f), onSecondary = hsl(hue, 36f, 15f), secondaryContainer = hsl(hue, 26f, if (bl) 18f else 23f),
                onSecondaryContainer = hsl(hue, 50f, 90f),
                tertiary = hsl(t, 60f, 78f), onTertiary = hsl(t, 50f, 15f), tertiaryContainer = hsl(t, 36f, 26f), onTertiaryContainer = hsl(t, 70f, 90f),
                background = surface, onBackground = hsl(hue, 18f, 93f), surface = surface, onSurface = hsl(hue, 18f, 93f),
                surfaceVariant = hsl(hue, 16f, if (bl) 13f else 21f), onSurfaceVariant = hsl(hue, 10f, 72f),
                inverseSurface = hsl(hue, 18f, 90f), inverseOnSurface = hsl(hue, 14f, 15f),
                outline = hsl(hue, 10f, 46f), outlineVariant = hsl(hue, 10f, if (bl) 20f else 28f),
                surfaceBright = hsl(hue, 14f, if (bl) 18f else 24f), surfaceDim = surface,
                surfaceContainerLowest = if (bl) black else hsl(hue, 14f, 5f),
                surfaceContainerLow = hsl(hue, 14f, if (bl) 4f else 10f), surfaceContainer = hsl(hue, 15f, if (bl) 6f else 13f),
                surfaceContainerHigh = hsl(hue, 16f, if (bl) 9f else 17f), surfaceContainerHighest = hsl(hue, 16f, if (bl) 13f else 21f),
            )
        }
    }
}

/** The tones as a Material colour scheme (errors keep Material's own reds). */
fun AlbumTones.toScheme(light: Boolean): ColorScheme {
    val base = if (light) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer), inversePrimary = Color(inversePrimary),
        secondary = Color(secondary), onSecondary = Color(onSecondary), secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary), onTertiary = Color(onTertiary), tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = Color(background), onBackground = Color(onBackground), surface = Color(surface), onSurface = Color(onSurface),
        surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant), surfaceTint = Color(primary),
        inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface),
        outline = Color(outline), outlineVariant = Color(outlineVariant),
        surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim),
        surfaceContainerLowest = Color(surfaceContainerLowest), surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainer = Color(surfaceContainer), surfaceContainerHigh = Color(surfaceContainerHigh),
        surfaceContainerHighest = Color(surfaceContainerHighest),
    )
}

/** The way from one hue to another the short way round the colour wheel (so red to magenta doesn't pass through green). */
fun hueStep(from: Float, to: Float): Float {
    val d = ((to - from) % 360f + 540f) % 360f - 180f
    return from + d
}

/** The last cover colour, kept so the app opens in it instead of flashing the default first. */
object AlbumMemory {
    private const val FILE = "pocketfader_album"

    fun load(ctx: Context): AlbumHue {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!sp.contains("h")) return DefaultAlbumHue
        val h = sp.getFloat("h", DefaultAlbumHue.hue)
        val s = sp.getFloat("s", DefaultAlbumHue.vivid)
        return if (h.isFinite() && s.isFinite()) AlbumHue(h, s.coerceIn(0f, 1f)) else DefaultAlbumHue
    }

    fun save(ctx: Context, a: AlbumHue) {
        if (!a.hue.isFinite() || !a.vivid.isFinite()) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat("h", a.hue).putFloat("s", a.vivid).apply()
    }
}
