package app.pocketfader.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// Browsers only take the YTM Starcast extension by hand ("Load unpacked" on their extensions page), so this page walks
// through it. The computer keeps a copy of the extension in a fixed folder, and can open that folder, or a browser's
// extensions page, on its own screen when a button here asks it to.

/** A browser found on the computer, with the address of its extensions page. */
data class ExtBrowser(val id: String, val name: String, val page: String)

/** GET /api/extension: is an extension connected, and what the setup needs. */
data class ExtInfo(
    val connected: Boolean,
    val everConnected: Boolean,
    val outdated: Boolean,
    val version: String,
    val folder: String,
    val browsers: List<ExtBrowser>,
)

fun parseExtInfo(o: JSONObject): ExtInfo {
    val list = mutableListOf<ExtBrowser>()
    val arr = o.optJSONArray("browsers")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            list.add(ExtBrowser(b.optString("id"), b.optString("name"), b.optString("page")))
        }
    }
    return ExtInfo(
        connected = o.optBoolean("connected", false),
        everConnected = o.optBoolean("everConnected", false),
        outdated = o.optBoolean("outdated", false),
        version = if (o.isNull("version")) "" else o.optString("version", ""),
        folder = o.optString("folder", ""),
        browsers = list,
    )
}

private val OkGreen = Color(0xFF3ECF8E)

/** The page: what is connected now, and the steps to add (or reload) the extension. */
@Composable
fun ExtensionSetup(s: RemoteState) {
    var info by remember { mutableStateOf<ExtInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // checked again every few seconds while this page is open, so it turns green by itself once the extension is added
    LaunchedEffect(s.online) {
        while (true) {
            val j = Remote.extensionStatus()
            if (j != null) info = parseExtInfo(j)
            checked = true
            delay(2500)
        }
    }
    val i = info
    if (i == null) {
        Text(
            if (!checked) "Checking…"
            else if (!s.online) "Can't reach the computer right now."
            else "This computer's YTM Starcast is too old to help with this. Update it on the computer first.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val ok = i.connected && !i.outdated
    Text(
        when {
            ok -> "Connected" + if (i.version.isNotBlank()) " · version ${i.version}" else ""
            i.connected -> "An older version is running in the browser. Reload it:"
            i.everConnected -> "Not connected right now. Open YouTube Music in the computer's browser."
            else -> "Not added to a browser yet. On the computer:"
        },
        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
        color = if (ok) OkGreen else MaterialTheme.colorScheme.onSurface,
    )
    val open: (JSONObject) -> Unit = { body -> scope.launch { Remote.notify(Remote.openOnComputer(body)) } }
    if (i.connected && i.outdated) {
        Step(1, "Reload the extension") {
            Hint("Open the extensions page in the browser that plays YouTube Music, find YTM Starcast (an older copy may still be called \"Pocketfader\") and press its reload button.")
            for (b in i.browsers) {
                OutlinedButton(onClick = { open(JSONObject().put("what", "page").put("browser", b.id)) }, enabled = s.online) {
                    Text("Open in ${b.name}")
                }
            }
        }
        Step(2, "Refresh the YouTube Music tab") {}
        return
    }
    Step(1, "Open the browser's extensions page") {
        for (b in i.browsers) {
            OutlinedButton(onClick = { open(JSONObject().put("what", "page").put("browser", b.id)) }, enabled = s.online) {
                Text("Open in ${b.name}")
            }
        }
        Hint(
            if (i.browsers.isNotEmpty()) "Opens it on the computer. Or type its address into the address bar: " +
                i.browsers.joinToString(", ") { it.page } + "."
            else "Type chrome://extensions (Brave: brave://extensions, Edge: edge://extensions) into the computer's browser.",
        )
    }
    Step(2, "Turn on Developer mode") { Hint("The switch is in the top-right corner of that page.") }
    Step(3, "Click \"Load unpacked\" and choose this folder") {
        SelectionContainer {
            Text(
                i.folder, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, PfShape.Sm)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(i.folder)); Remote.notify("Copied") }) { Text("Copy") }
            OutlinedButton(onClick = { open(JSONObject().put("what", "folder")) }, enabled = s.online) { Text("Show it on the computer") }
        }
        Hint("You can also drag that folder onto the extensions page.")
    }
    Step(4, "Open YouTube Music") { Hint("Open music.youtube.com in that browser, or refresh its tab if it is already open.") }
    Text(
        if (ok) "✓ The extension is connected." else "Waiting for the extension…",
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (ok) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Step(n: Int, title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = PfShape.Md,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

/** At the top of Settings until an extension has ever connected: leads to the steps. */
@Composable
fun ExtensionPrompt(onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = PfShape.Lg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add the browser extension", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "YTM Starcast needs its extension in the computer's browser to see and control YouTube Music.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            FilledTonalButton(onClick = onOpen) { Text("Show me how") }
        }
    }
}
