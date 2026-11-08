package app.pocketfader.client

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Seconds left on the sleep timer, counting down between polls. 0 when none is running. */
@Composable
fun rememberSleepLeft(s: RemoteState): Int {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(s.sleepLeft, s.sleepAt) {
        while (s.sleepLeft > 0) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    if (s.sleepLeft <= 0) return 0
    return (s.sleepLeft - ((maxOf(now, s.sleepAt) - s.sleepAt) / 1000).toInt()).coerceAtLeast(0)
}

/** The moon button next to the song title: shows the time left, and opens the timer choices. */
@Composable
fun SleepButton(s: RemoteState) {
    var open by remember { mutableStateOf(false) }
    val left = rememberSleepLeft(s)
    val active = s.sleepLeft > 0 && left > 0
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (active) Text(sleepText(left), style = MaterialTheme.typography.labelMedium, color = tint)
        PressIconButton(
            if (active) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
            if (active) "Sleep timer: ${sleepText(left)} left" else "Sleep timer", 48, 24, tint, active, true,
        ) { open = true }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Sleep timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (active) "Playback will pause in ${sleepText(left)}." else "Pause playback on the computer after…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (row in listOf(listOf(15, 30, 45), listOf(60, 90, 120))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (m in row) FilterChip(selected = false, onClick = { Remote.setSleep(m); open = false }, label = { Text("$m min") })
                        }
                    }
                    FilterChip(selected = false, onClick = { Remote.setSleep("track"); open = false }, label = { Text("When this song ends") })
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            dismissButton = { if (active) TextButton(onClick = { Remote.setSleep(0); open = false }) { Text("Turn off") } },
        )
    }
}

private fun sleepText(sec: Int): String = when {
    sec >= 3600 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    sec >= 60 -> "${(sec + 59) / 60} min"
    else -> "$sec s"
}
