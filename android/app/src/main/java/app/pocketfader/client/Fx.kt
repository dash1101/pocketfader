package app.pocketfader.client

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// Visual effects. Everything here is a drawing trick (a gradient, a path, a transform) rather than a blur or an offscreen
// layer, so it costs a few draw calls; the animated ones only run while music plays and stop with "Reduce animations".

/** The cover's main colour for the current track (for the glow); null when there's none. */
val LocalTint = compositionLocalOf<Color?> { null }

/** "Glow on text, icons and buttons" from the settings. */
val LocalGlowUi = compositionLocalOf { false }

/** off | soft | strong | extreme  ->  how far the glow reaches (in cover radii) and how bright it is. */
private fun glowSpec(level: String): Pair<Float, Float>? = when (level) {
    "soft" -> 0.45f to 0.45f
    "strong" -> 0.75f to 0.75f
    "extreme" -> 1.15f to 1f
    else -> null
}

/** A halo of [color] around whatever this is applied to (drawn under it, so apply it before any clip). */
fun Modifier.coverGlow(color: Color, level: String, pulse: State<Float>? = null): Modifier {
    val spec = glowSpec(level) ?: return this
    return drawBehind {
        val (reach, alpha) = spec
        val p = pulse?.value ?: 1f
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = size.maxDimension / 2f
        val r = base * (1f + reach * p)
        val edge = base / r          // where the cover's own edge sits inside the gradient
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                edge to color.copy(alpha = alpha),
                edge + (1f - edge) * 0.4f to color.copy(alpha = alpha * 0.4f),
                1f to Color.Transparent,
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
    }
}

/** How far the blurred picture reaches (a scale of the cover), how strong it is, and how colourful. */
private fun ambientSpec(level: String): Triple<Float, Float, Float>? = when (level) {
    "soft" -> Triple(1.3f, 0.6f, 1.1f)
    "strong" -> Triple(1.55f, 0.85f, 1.3f)
    "extreme" -> Triple(1.95f, 1f, 1.6f)
    else -> null
}

/** 0..1 rise with zero slope at both ends, so a fade built from it has no visible creases (no Mach bands). */
private fun smoother(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * A soft light that carries the cover's own edges outward, like an ambient backdrop: a blurred copy of the cover is
 * scaled up behind it and faded to nothing toward its outer edge, evenly on all four sides (the fade is applied
 * across and then down, so the light has no outline and no circular halo). [fade] dims it as the cover scrolls away.
 */
@Composable
fun AmbientGlow(thumb: ImageBitmap, level: String, scale: Float, pulse: State<Float>, modifier: Modifier = Modifier, fade: () -> Float = { 1f }) {
    val (reach, strength, sat) = ambientSpec(level) ?: return
    val filter = remember(sat) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(sat) }) }
    // where the cover's edge sits inside the scaled-up picture, as a fraction of its width, measured from each side
    val inner = (1f - 1f / reach) / 2f
    val ramp = remember(inner) {
        val steps = 14
        val up = (0..steps).map { i -> val t = i / steps.toFloat(); (t * inner) to Color.Black.copy(alpha = smoother(t)) }
        val down = up.reversed().map { (p, c) -> (1f - p) to c }
        (up + down).toTypedArray()
    }
    Box(
        modifier
            .graphicsLayer {
                val k = reach * scale
                scaleX = k
                scaleY = k
                this.alpha = strength * pulse.value * fade()
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                drawImage(
                    thumb, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    colorFilter = filter, filterQuality = FilterQuality.High,
                )
                // fade to nothing at the outer edge: across, then down (the two multiply)
                drawRect(Brush.horizontalGradient(*ramp), blendMode = BlendMode.DstIn)
                drawRect(Brush.verticalGradient(*ramp), blendMode = BlendMode.DstIn)
            },
    )
}

/** A slow breathing value (about 0.85 to 1) while [on], 1 otherwise. */
@Composable
fun rememberPulse(on: Boolean): State<Float> {
    if (!on) return remember { mutableStateOf(1f) }
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
}

/** A soft glow behind an icon or button, when the glow setting is on. */
@Composable
fun Modifier.iconGlow(color: Color, active: Boolean = true, radius: Int = 28): Modifier {
    if (!LocalGlowUi.current || !active) return this
    return drawBehind {
        val r = radius.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center = center, radius = r), radius = r)
    }
}

/** The same style with a glow around the letters, when the glow setting is on. */
@Composable
fun TextStyle.glow(color: Color = MaterialTheme.colorScheme.primary): TextStyle =
    if (LocalGlowUi.current) copy(shadow = Shadow(color.copy(alpha = 0.9f), Offset.Zero, 18f)) else this

/** Shrinks a little while pressed and springs back: a small, cheap bit of life for buttons. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, amount: Float = 0.9f): Modifier {
    val reduce = LocalReduceMotion.current
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed && !reduce) amount else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press",
    )
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Cover shapes: rounded | soft | circle | cut. */
fun coverShape(kind: String): Shape = when (kind) {
    "soft" -> RoundedCornerShape(56.dp)
    "circle" -> RoundedCornerShape(percent = 50)
    "cut" -> CutCornerShape(topStart = 28.dp, bottomEnd = 28.dp, topEnd = 6.dp, bottomStart = 6.dp)
    else -> RoundedCornerShape(32.dp)
}

/** Three little bars that dance while music plays (the "now playing" mark). */
@Composable
fun EqBars(color: Color, playing: Boolean, modifier: Modifier = Modifier.size(22.dp)) {
    val reduce = LocalReduceMotion.current
    val animate = playing && !reduce
    val a: State<Float>
    val b: State<Float>
    val c: State<Float>
    if (animate) {
        val t = rememberInfiniteTransition(label = "eq")
        a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
        b = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(390, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
        c = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "c")
    } else {
        a = remember { mutableStateOf(0.55f) }
        b = remember { mutableStateOf(0.9f) }
        c = remember { mutableStateOf(0.4f) }
    }
    Canvas(modifier) {
        val bw = size.width / 5f
        val hs = listOf(a.value, b.value, c.value)
        for (i in 0..2) {
            val h = size.height * hs[i]
            drawRoundRect(
                color, topLeft = Offset(bw * (i * 2f), size.height - h), size = Size(bw * 1.4f, h), cornerRadius = CornerRadius(bw * 0.7f),
            )
        }
    }
}

/**
 * A seek bar whose played part is a wave that ripples while music plays and settles flat when paused.
 * Tap or drag anywhere to seek. With [wavy] off it's a plain rounded bar.
 */
@Composable
fun WavySeekBar(
    value: Float,
    enabled: Boolean,
    playing: Boolean,
    wavy: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val moving = wavy && playing && !reduce && enabled
    val amplitude by animateFloatAsState(if (wavy && playing && enabled) 3.5f else 0f, tween(if (reduce) 0 else 500), label = "amp")
    val phase: State<Float> = if (moving) {
        rememberInfiniteTransition(label = "wave").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "phase")
    } else {
        remember { mutableStateOf(0f) }
    }
    val v = value.coerceIn(0f, 1f)
    Canvas(
        modifier.fillMaxWidth().height(40.dp)
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    onValueChange((o.x / size.width).coerceIn(0f, 1f))
                    onValueChangeFinished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> onValueChange((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                ) { change, _ ->
                    change.consume()
                    onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val pad = 10.dp.toPx()
        val x0 = pad
        val x1 = size.width - pad
        val cy = size.height / 2f
        val xp = x0 + (x1 - x0) * v
        val stroke = 4.dp.toPx()
        val amp = amplitude.dp.toPx()
        // what's left to play: a plain rounded line
        drawLine(track, Offset(xp, cy), Offset(x1, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        // what's played: a wave that eases in at the start and flattens into the handle (so it joins the line with no
        // kink), drawn from many short steps so the curve is smooth; a straight line once it has settled
        if (xp > x0) {
            if (amp < 0.2f) {
                drawLine(color, Offset(x0, cy), Offset(xp, cy), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                val len = 28.dp.toPx()
                val step = 2.dp.toPx()
                val taper = 20.dp.toPx()
                val twoPi = (2 * PI).toFloat()
                val ph = phase.value
                val path = Path()
                var x = x0
                path.moveTo(x0, cy)
                while (x < xp) {
                    x = minOf(x + step, xp)
                    val t = (minOf(x - x0, xp - x) / taper).coerceIn(0f, 1f)
                    val env = t * t * (3f - 2f * t)
                    path.lineTo(x, cy + amp * env * sin((x - x0) / len * twoPi + ph))
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // the handle
        val hw = 4.dp.toPx()
        val hh = 22.dp.toPx()
        drawRoundRect(color, Offset(xp - hw / 2f, cy - hh / 2f), Size(hw, hh), CornerRadius(hw / 2f))
    }
}


// ---------------------------------------------------------------------------------------------
// A soft fade where scrolled content leaves the screen.

/** The colours the page background has at the top and bottom of the content area (so the fade blends in). */
@androidx.compose.runtime.Immutable
class EdgeFade(val top: Color, val bottom: Color)

val LocalEdgeFade = compositionLocalOf<EdgeFade?> { null }

/**
 * Draws a gentle gradient of the page colour over the top and/or bottom edge of a scrolling area, only on an edge that has
 * more content beyond it (so nothing is faded when you're at the start or end). It paints over the content instead of using
 * an offscreen layer, which keeps it cheap on old devices.
 */
@Composable
fun Modifier.edgeFade(canBack: () -> Boolean, canForward: () -> Boolean): Modifier {
    val fade = LocalEdgeFade.current ?: return this
    return drawWithContent {
        drawContent()
        val h = 26.dp.toPx()
        if (canBack()) {
            val c = fade.top
            drawRect(
                Brush.verticalGradient(
                    0f to c, 0.35f to c.copy(alpha = 0.7f), 0.7f to c.copy(alpha = 0.22f), 1f to c.copy(alpha = 0f), startY = 0f, endY = h,
                ),
                size = Size(size.width, h),
            )
        }
        if (canForward()) {
            val c = fade.bottom
            drawRect(
                Brush.verticalGradient(
                    0f to c.copy(alpha = 0f), 0.3f to c.copy(alpha = 0.22f), 0.65f to c.copy(alpha = 0.7f), 1f to c,
                    startY = size.height - h, endY = size.height,
                ),
                topLeft = Offset(0f, size.height - h), size = Size(size.width, h),
            )
        }
    }
}

@Composable
fun Modifier.edgeFade(list: androidx.compose.foundation.lazy.LazyListState): Modifier =
    edgeFade({ list.canScrollBackward }, { list.canScrollForward })

@Composable
fun Modifier.edgeFade(scroll: androidx.compose.foundation.ScrollState): Modifier =
    edgeFade({ scroll.value > 0 }, { scroll.canScrollForward })
