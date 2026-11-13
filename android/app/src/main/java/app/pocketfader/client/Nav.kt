package app.pocketfader.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Asks the app to show the player (after "Play" on a playlist or album, say). Read by the app's frame. */
object AppNav {
    var toPlayer by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun showPlayer() { toPlayer++ }
}

/**
 * The bottom navigation as a floating pill: a rounded capsule above the content in which the current destination is a
 * highlighted capsule that shows its name, and the others are just icons.
 */
@Composable
fun PillNav(labels: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = PfShape.Full,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                labels.indices.forEach { i -> PillItem(labels[i], icons[i], i == selected) { onSelect(i) } }
            }
        }
    }
}

@Composable
private fun PillItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(if (reduce) 0 else Motion.Short, easing = Motion.Ease), label = "pill-bg",
    )
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(PfShape.Full)
            .background(bg)
            .then(if (reduce) Modifier else Modifier.animateContentSize())
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(horizontal = if (selected) 18.dp else 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, if (selected) null else label, Modifier.size(24.dp).iconGlow(fg, selected, 22), tint = fg)
        if (selected) {
            Text(label, Modifier.padding(start = 8.dp), color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}
