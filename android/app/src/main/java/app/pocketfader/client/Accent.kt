package app.pocketfader.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Colour that is always readable. Every colour the app takes from the user (the accent) or from a cover picture
// is passed through [readableOn] before it is used for text or icons, so a dark accent on the black theme (or a
// pale one on the light theme) is moved just far enough toward white or black to stay visible.

/** WCAG contrast ratio between two opaque colours (1 = identical, 21 = black on white). */
fun contrast(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
}

/** [c], or the nearest shade of it that has at least [min] contrast on [bg]. */
fun readableOn(c: Color, bg: Color, min: Float = 4.5f): Color {
    val base = c.copy(alpha = 1f)
    if (contrast(base, bg) >= min) return base
    val target = if (contrast(Color.White, bg) >= contrast(Color.Black, bg)) Color.White else Color.Black
    var t = 0f
    while (t < 1f) {
        t = (t + 0.05f).coerceAtMost(1f)
        val x = lerp(base, target, t)
        if (contrast(x, bg) >= min) return x
    }
    return target
}

/** Text or icon colour to put on top of [bg] (white or near-black, whichever reads better). */
fun onColorFor(bg: Color): Color =
    if (contrast(Color.White, bg) >= contrast(Color(0xFF0E0E12), bg)) Color.White else Color(0xFF0E0E12)

/** The theme with a user-chosen accent: every accent-coloured part is checked against the real background. */
fun withAccent(base: ColorScheme, accent: Color, dark: Boolean): ColorScheme {
    val bg = base.background
    val primary = readableOn(accent, bg, 4.5f)
    val primaryContainer = lerp(bg, primary, if (dark) 0.30f else 0.20f)
    val secondaryContainer = lerp(bg, primary, if (dark) 0.18f else 0.12f)
    return base.copy(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(primary, primaryContainer, 7f),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(primary, secondaryContainer, 7f),
        surfaceTint = primary,
    )
}

private val presets = listOf(
    "Violet" to 0xFF8B5CF6.toInt(), "Blue" to 0xFF3B82F6.toInt(), "Teal" to 0xFF14B8A6.toInt(),
    "Green" to 0xFF22C55E.toInt(), "Amber" to 0xFFF59E0B.toInt(), "Orange" to 0xFFF97316.toInt(),
    "Red" to 0xFFEF4444.toInt(), "Pink" to 0xFFEC4899.toInt(), "White" to 0xFFFFFFFF.toInt(), "Black" to 0xFF000000.toInt(),
)

/**
 * Accent colour chooser: automatic (Album colours, or Material You), a handful of colours, or any colour. Shows how it
 * will really look.
 */
@Composable
fun AccentPicker(prefs: Prefs, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val chosen = prefs.accent
    val isPreset = presets.any { it.second == chosen }
    var custom by remember { mutableStateOf(chosen != 0 && !isPreset) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Used for highlights, the lyric being sung, and buttons. A chosen colour replaces Album colours. It's adjusted " +
                "automatically if it would be hard to see on your theme.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(colors = refineChipColors(), border = refineChipBorder(chosen == 0), selected = chosen == 0, shape = chipShape(chosen == 0), onClick = { custom = false; Settings.update { it.copy(accent = 0) } }, label = { Text("Auto") })
            presets.forEach { (name, argb) ->
                Swatch(Color(argb), name, selected = chosen == argb) { custom = false; Settings.update { it.copy(accent = argb) } }
            }
            FilterChip(colors = refineChipColors(), border = refineChipBorder(custom), selected = custom, shape = chipShape(custom), onClick = { custom = true; if (chosen == 0) Settings.update { it.copy(accent = presets[0].second) } }, label = { Text("Custom") })
        }
        if (chosen != 0) {
            val raw = Color(chosen)
            val shown = readableOn(raw, bg, 4.5f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(shown).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Text(
                    if (contrast(raw, bg) < 4.5f) "Shown a little ${if (bg.luminance() < 0.5f) "lighter" else "darker"} so it stays visible on this theme."
                    else "Looks good on this theme.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (custom && chosen != 0) CustomColor(chosen)
    }
}

@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(color).border(ring, CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = onColorFor(color))
    }
}

@Composable
private fun CustomColor(argb: Int) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    var hex by remember(argb) { mutableStateOf("%06X".format(argb and 0xFFFFFF)) }
    fun apply(h: Float, s: Float, v: Float) {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) or (0xFF shl 24)
        Settings.update { it.copy(accent = c) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Hue", style = MaterialTheme.typography.labelMedium)
        Slider(value = hsv[0], onValueChange = { apply(it, hsv[1].coerceAtLeast(0.05f), hsv[2].coerceAtLeast(0.05f)) }, valueRange = 0f..360f)
        Text("Colourfulness", style = MaterialTheme.typography.labelMedium)
        Slider(value = hsv[1], onValueChange = { apply(hsv[0], it, hsv[2]) }, valueRange = 0f..1f)
        Text("Brightness", style = MaterialTheme.typography.labelMedium)
        Slider(value = hsv[2], onValueChange = { apply(hsv[0], hsv[1], it) }, valueRange = 0f..1f)
        OutlinedTextField(
            value = hex,
            onValueChange = { t ->
                val clean = t.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                hex = clean
                if (clean.length == 6) Settings.update { it.copy(accent = (0xFF000000 or clean.toLong(16)).toInt()) }
            },
            label = { Text("Hex") }, prefix = { Text("#") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
