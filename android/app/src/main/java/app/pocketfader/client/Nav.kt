package app.pocketfader.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Asks the app to show the player (after "Play" on a playlist or album, say). Read by the app's frame. */
object AppNav {
    var toPlayer by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun showPlayer() { toPlayer++ }
}

/** Where one item of the pill navigation sits along the bar, in pixels. */
private data class PillSpan(val left: Float, val right: Float)

/**
 * The bottom navigation as a floating pill: a rounded capsule above the content in which the current destination sits
 * on a tonal capsule that shows its name, and the others are just icons. The tonal capsule slides from one destination
 * to the next (stretching a little on the way, as Refine's tabs do) while the names open and close.
 * [idleIcons] are the icons of the destinations that aren't chosen (outlined ones, say); by default the same as [icons].
 */
@Composable
fun PillNav(
    labels: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    idleIcons: List<ImageVector> = icons,
    onSelect: (Int) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val pill = MaterialTheme.colorScheme.primaryContainer
    // each item's place along the bar as laid out right now: it changes while a name opens or closes
    val spans = remember { mutableStateMapOf<Int, PillSpan>() }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val chosen by rememberUpdatedState(selected)
    val calm by rememberUpdatedState(reduce)
    // The capsule follows the chosen item's edges. They move every frame while the names open and close, so each new
    // position starts a fresh spring that keeps the speed it already had (it chases smoothly instead of restarting).
    // Its edges are only read while drawing: the bar isn't composed again for each frame of the slide.
    LaunchedEffect(Unit) {
        snapshotFlow { spans[chosen] }.filterNotNull().collect { to ->
            if (!placed || calm) {
                // the first time (and with fewer animations) it is simply put in place
                left.snapTo(to.left)
                right.snapTo(to.right)
                placed = true
            } else {
                // the leading edge moves a little faster than the trailing one, so the capsule stretches as it slides
                val forward = to.left > left.value
                launch { left.animateTo(to.left, spring(dampingRatio = 0.9f, stiffness = if (forward) 380f else 700f)) }
                launch { right.animateTo(to.right, spring(dampingRatio = 0.9f, stiffness = if (forward) 700f else 380f)) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = PfShape.Full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                Modifier
                    .padding(6.dp)
                    .drawBehind {
                        // before the first slide (and with fewer animations) the capsule sits right on the chosen
                        // item, from the very first frame
                        val exact = if (!placed || calm) spans[chosen] else null
                        if (exact != null || placed) {
                            val l = exact?.left ?: left.value
                            val w = ((exact?.right ?: right.value) - l).coerceAtLeast(0f)
                            val h = size.height
                            drawRoundRect(pill, topLeft = Offset(l, 0f), size = Size(w, h), cornerRadius = CornerRadius(h / 2f))
                        }
                    }
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.indices.forEach { i ->
                    val on = i == selected
                    PillItem(
                        label = labels[i],
                        icon = if (on) icons[i] else idleIcons.getOrElse(i) { icons[i] },
                        selected = on,
                        modifier = Modifier.onPlaced { c ->
                            val x = c.positionInParent().x
                            val span = PillSpan(x, x + c.size.width)
                            if (spans[i] != span) spans[i] = span
                        },
                    ) { onSelect(i) }
                }
            }
        }
    }
}

@Composable
private fun PillItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    val fg by animateColorAsState(
        if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "pill-fg",
    )
    val pad by animateDpAsState(if (selected) 18.dp else 14.dp, tween(if (reduce) 0 else Motion.Medium, easing = Motion.Ease), label = "pill-pad")
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .clip(PfShape.Full)
            .selectable(selected = selected, interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .padding(horizontal = pad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the icon dips a little while pressed
        Icon(icon, if (selected) null else label, Modifier.size(24.dp).pressScale(src, 0.86f).iconGlow(fg, selected, 22), tint = fg)
        // the name opens out beside the icon (and folds away) instead of popping in
        AnimatedVisibility(
            visible = selected,
            enter = if (reduce) EnterTransition.None else
                fadeIn(tween(Motion.Short, delayMillis = 60, easing = Motion.Ease)) +
                    expandHorizontally(tween(Motion.Medium, easing = Motion.Ease), expandFrom = Alignment.Start),
            exit = if (reduce) ExitTransition.None else
                fadeOut(tween(Motion.Quick, easing = Motion.Ease)) +
                    shrinkHorizontally(tween(Motion.Medium, easing = Motion.Ease), shrinkTowards = Alignment.Start),
        ) {
            Text(
                label, Modifier.padding(start = 8.dp), color = fg, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
        }
    }
}
