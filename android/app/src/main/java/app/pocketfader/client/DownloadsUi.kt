package app.pocketfader.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * What is downloading now: how far all of it is (bytes, speed, about how long is left), then each song with its own bar
 * (the first few; the rest are counted). Shown on Search → This phone while anything downloads.
 */
@Composable
fun DownloadsInProgress(modifier: Modifier = Modifier, shown: Int = 5) {
    val progress by Downloads.progress.collectAsStateWithLifecycle()
    if (progress.isEmpty()) return
    val sum = remember(progress) { Downloads.summary(progress) }
    val list = remember(progress) { progress.values.sortedByDescending { it.started > 0 } }
    // a card on the shared scale (PfShape.Lg) on a raised tonal surface; the bars have round ends, as Material You's do
    Card(
        modifier.fillMaxWidth(),
        shape = PfShape.Lg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val all = sum.count + sum.finished
                Text(
                    if (all <= 1) "Downloading 1 song" else "Downloading ${sum.finished + 1} of $all",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                if (sum.count > sum.running) {
                    TextButton(onClick = { Downloads.cancelPending() }) { Text("Cancel the rest") }
                }
            }
            LinearProgressIndicator(progress = { sum.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round)
            Text(
                listOfNotNull(
                    if (sum.totalBytes > 0) formatBytes(sum.doneBytes) + " of about " + formatBytes(sum.totalBytes) else null,
                    if (sum.bps > 0) formatSpeed(sum.bps) else "Starting…",
                    sum.secondsLeft?.let { formatLeft(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (d in list.take(shown)) DownloadRow(d)
            if (list.size > shown) {
                Text(
                    "and ${list.size - shown} more waiting",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (Settings.prefs.value.dlWifiOnly && Downloads.metered()) {
                Text(
                    "Automatic downloads wait for Wi-Fi; songs you asked for download now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(d: DlProgress) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Thumb(d.item.art, 40, 8)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.started > 0) {
                if (d.total > 0) {
                    LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp), strokeCap = StrokeCap.Round)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), strokeCap = StrokeCap.Round)     // the size isn't known yet
                }
            }
            Text(
                when {
                    d.started == 0L -> "Waiting"
                    d.total > 0 -> "${(d.fraction * 100).toInt()}% · ${formatBytes(d.done)} of ${formatBytes(d.total)}" +
                        if (d.bps > 0) " · " + formatSpeed(d.bps) else ""
                    else -> "Finding the audio…"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
