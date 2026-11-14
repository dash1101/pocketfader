package app.pocketfader.client

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// The pieces of Refine's look (YTM Starcast's improvements inside YouTube Music), for this app: YTM Starcast's logo, the
// pill bar of tabs with a tonal chosen tab, rows that glide in one after another, and round buttons that soften into a
// squircle when pressed. docs/DESIGN.md has the shared scale they follow.

// ---------------------------------------------------------------------------------------------
// YTM Starcast's logo: the deep blue tile with two arcs and Nova's four-point star (the same drawing as Refine's).

/** Nova's four-point star around ([cx], [cy]) with radius [r], in the logo's 512-unit square. */
private fun sparkle(cx: Float, cy: Float, r: Float): Path {
    val a = 0.07f * r; val b = 0.32f * r; val c = 0.29f * r; val d = 0.09f * r
    return Path().apply {
        moveTo(cx, cy - r)
        cubicTo(cx + a, cy - b, cx + c, cy - d, cx + r, cy)
        cubicTo(cx + c, cy + d, cx + a, cy + b, cx, cy + r)
        cubicTo(cx - a, cy + b, cx - c, cy + d, cx - r, cy)
        cubicTo(cx - c, cy - d, cx - a, cy - b, cx, cy - r)
        close()
    }
}

private val LogoStar by lazy { sparkle(176f, 336f, 118f) }
private val LogoBg = Brush.linearGradient(
    0f to Color(0xFF2A3A9A), 0.55f to Color(0xFF111A52), 1f to Color(0xFF070B2A),
    start = Offset(16f, 16f), end = Offset(16f + 0.35f * 480f, 496f),
)

/** The logo tile alone, [size] wide. */
@Composable
fun StarcastTile(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 512f, pivot = Offset.Zero) {
            drawRoundRect(LogoBg, topLeft = Offset(16f, 16f), size = Size(480f, 480f), cornerRadius = CornerRadius(112f))
            for ((rad, w, o) in listOf(Triple(132f, 38f, 1f), Triple(200f, 36f, 0.7f))) {
                drawArc(
                    Color(0xFF8CC8FF), startAngle = -90f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(176f - rad, 336f - rad), size = Size(2 * rad, 2 * rad),
                    style = Stroke(width = w, cap = StrokeCap.Round), alpha = o,
                )
            }
            drawPath(LogoStar, Color(0xFFF2F6FF))
        }
    }
}

/** The logo with its name ("Starcast"), as Refine puts it in YouTube Music's top bar. [word] off: the tile alone. */
@Composable
fun StarcastLogo(modifier: Modifier = Modifier, tile: Dp = 30.dp, word: Boolean = true) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "YTM Starcast" }, verticalAlignment = Alignment.CenterVertically) {
        StarcastTile(tile)
        if (word) {
            Text(
                "Starcast", Modifier.padding(start = 9.dp), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tabs as one pill bar: the chosen tab sits on a tonal pill that slides to it, as Refine shows YouTube Music's
// Up next / Lyrics / Comments / Related.

/**
 * A pill bar of [labels], equal in width. [crossed] tabs can't be opened for what plays now (Comments turned off, say):
 * they stay in their place, crossed out, as in Refine.
 */
@Composable
fun PillTabs(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, crossed: Set<Int> = emptySet(),
) {
    val reduce = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = PfShape.Full, color = scheme.surfaceContainer) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val n = labels.size.coerceAtLeast(1)
            val w = maxWidth / n
            // read only while placing the pill, so its slide moves it without recomposing the bar
            val x = animateDpAsState(
                w * selected.coerceIn(0, n - 1),
                if (reduce) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "tab-pill",
            )
            if (selected in labels.indices) {
                Box(Modifier.offset { IntOffset(x.value.roundToPx(), 0) }.width(w).height(40.dp).background(scheme.primaryContainer, PfShape.Full))
            }
            Row(Modifier.fillMaxWidth().height(40.dp).selectableGroup()) {
                labels.forEachIndexed { i, label ->
                    val off = i in crossed
                    val sel = i == selected
                    val fg by animateColorAsState(
                        if (sel) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "tab-fg",
                    )
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(PfShape.Full)
                            .selectable(selected = sel, enabled = !off, role = Role.Tab) { onSelect(i) }
                            .then(if (off) Modifier.semantics { contentDescription = "$label, not available for this song" } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = if (off) fg.copy(alpha = 0.5f) else fg, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            textDecoration = if (off) TextDecoration.LineThrough else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows that glide in one after another when a list's content arrives (a tab opened, a page loaded, new results), as
// Refine's Up next does. Only the rows that are drawn right then take part, numbered in the order they appear (top
// first); rows scrolled into view later just appear.

/** One arrival of a list's content. Keep it with [rememberArrival]; give its rows [arrive]. */
@Stable
class Arrival internal constructor() {
    internal var epoch = 0
        private set
    private var at = 0L
    private var next = 0

    internal fun begin() { epoch++; at = SystemClock.uptimeMillis(); next = 0 }

    /** This row's turn, or -1 when the arrival is over (a row that scrolls into view later). */
    internal fun take(): Int = if (at != 0L && SystemClock.uptimeMillis() - at < WINDOW_MS && next < MAX_ROWS) next++ else -1

    companion object {
        internal const val WINDOW_MS = 700L
        internal const val MAX_ROWS = 30
        internal const val STEP_MS = 26L
    }
}

/** A new arrival whenever one of [keys] changes (and when this first appears). */
@Composable
fun rememberArrival(vararg keys: Any?): Arrival {
    val a = remember { Arrival() }
    // started during composition, so the rows composed right after it (in the same frame) already belong to it
    remember(*keys) { a.begin(); 0 }
    return a
}

/** This row glides in from the right and fades up, after the rows above it, when it is part of [arrival]. */
@Composable
fun Modifier.arrive(arrival: Arrival?): Modifier {
    if (arrival == null || LocalReduceMotion.current) return this
    val turn = remember(arrival.epoch) { arrival.take() }
    if (turn < 0) return this
    val p = remember(arrival.epoch) { Animatable(0f) }
    LaunchedEffect(p) {
        delay(turn * Arrival.STEP_MS + 40L)
        p.animateTo(1f, tween(520, easing = Motion.Emphasized))
    }
    return graphicsLayer {
        val v = p.value
        alpha = (v / 0.6f).coerceAtMost(1f)
        translationX = (1f - v) * 32.dp.toPx()
        val s = 0.97f + 0.03f * v
        scaleX = s
        scaleY = s
    }
}

// ---------------------------------------------------------------------------------------------
// Expressive round buttons: round at rest, a tonal squircle while pressed or switched on (Refine's player buttons).

/**
 * A round icon button that softens into a squircle with a light tint while pressed, and sits on a tonal squircle while
 * [active] (shuffle on, say). It dips a little when pressed, like the app's other buttons.
 */
@Composable
fun ShapeIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    sizeDp: Int = 48, iconDp: Int = 24, tint: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false, enabled: Boolean = true,
) {
    val reduce = LocalReduceMotion.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val round by animateFloatAsState(
        if ((pressed || active) && enabled) 30f else 50f, tween(if (reduce) 0 else Motion.Medium, easing = Motion.Ease), label = "btn-shape",
    )
    val bg by animateColorAsState(
        when {
            !enabled -> Color.Transparent
            active -> scheme.secondaryContainer
            pressed -> scheme.primary.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "btn-bg",
    )
    val fg = when {
        !enabled -> tint.copy(alpha = 0.38f)
        active -> scheme.onSecondaryContainer
        else -> tint
    }
    Box(
        modifier.size(sizeDp.dp).pressScale(src)
            .clip(RoundedCornerShape(percent = round.roundToInt()))
            .background(bg)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(iconDp.dp), tint = fg)
    }
}
