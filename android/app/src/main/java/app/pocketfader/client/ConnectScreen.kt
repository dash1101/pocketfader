package app.pocketfader.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TestResult {
    data object Idle : TestResult
    data object Running : TestResult
    data class Ok(val text: String) : TestResult
    data class Fail(val text: String) : TestResult
}

/** The connection form, shared by the first-run screen and the settings dialog. */
@Composable
fun ConnectForm(
    initial: ServerConfig,
    onSave: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
    onForget: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    var address by rememberSaveable { mutableStateOf(initial.url) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var cfId by rememberSaveable { mutableStateOf(initial.cfClientId) }
    var cfSecret by rememberSaveable { mutableStateOf(initial.cfClientSecret) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(initial.hasCloudflareToken) }
    var result by remember { mutableStateOf<TestResult>(TestResult.Idle) }
    val scope = rememberCoroutineScope()

    fun build(): ServerConfig {
        val p = Address.parse(address)
        return ServerConfig(
            url = p.url,
            token = (p.token ?: token).trim(),
            cfClientId = cfId.trim(),
            cfClientSecret = cfSecret.trim(),
        )
    }

    val canSave = Address.parse(address).url.isNotEmpty()
    val field = RoundedCornerShape(16.dp)

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(
            if (initial.isSet) "Connection" else "Connect to your computer",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Enter the address of the computer running Pocketfader.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                Address.parse(it).token?.let { t -> token = t }   // pasted a full ?token= link
                result = TestResult.Idle
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server address") },
            placeholder = { Text("my-pc.tailnet.ts.net:8777") },
            leadingIcon = { Icon(Icons.Filled.Link, null) },
            supportingText = { Text("A Tailscale name, a home-network address, or an https:// hostname.") },
            singleLine = true,
            shape = field,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it; result = TestResult.Idle },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Access token (optional)") },
            leadingIcon = { Icon(Icons.Filled.Key, null) },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide")
                }
            },
            supportingText = { Text("Only needed if the server was started with --token or --lan.") },
            singleLine = true,
            shape = field,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )

        TextButton(onClick = { advanced = !advanced }) {
            Icon(Icons.Filled.Shield, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Cloudflare Access")
            Spacer(Modifier.width(4.dp))
            Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "If your server sits behind a Cloudflare Access login, create a service token in " +
                        "Zero Trust and enter it here. The app sends it with every request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = cfId,
                    onValueChange = { cfId = it; result = TestResult.Idle },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Client ID") },
                    singleLine = true,
                    shape = field,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = cfSecret,
                    onValueChange = { cfSecret = it; result = TestResult.Idle },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Client secret") },
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide")
                        }
                    },
                    singleLine = true,
                    shape = field,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
            }
        }

        AnimatedVisibility(result !is TestResult.Idle) {
            val r = result
            val ok = r is TestResult.Ok
            val bad = r is TestResult.Fail
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when {
                    ok -> MaterialTheme.colorScheme.primaryContainer
                    bad -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (r) {
                        is TestResult.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        is TestResult.Ok -> Icon(Icons.Filled.CheckCircle, null)
                        is TestResult.Fail -> Icon(Icons.Filled.Error, null)
                        else -> {}
                    }
                    Text(
                        when (r) {
                            is TestResult.Running -> "Connecting…"
                            is TestResult.Ok -> r.text
                            is TestResult.Fail -> r.text
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    val cfg = build()
                    result = TestResult.Running
                    scope.launch {
                        result = try {
                            TestResult.Ok(withContext(Dispatchers.IO) { Remote.test(cfg) })
                        } catch (e: Exception) {
                            TestResult.Fail(e.friendly())
                        }
                    }
                },
                enabled = canSave && result !is TestResult.Running,
                modifier = Modifier.weight(1f),
            ) { Text("Test") }
            Button(
                onClick = { onSave(build()) },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
        }
        if (onForget != null || onCancel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onForget != null) {
                    TextButton(onClick = onForget) { Text("Remove this server", color = MaterialTheme.colorScheme.error) }
                } else Spacer(Modifier.width(1.dp))
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** Settings dialog: a rounded card over the app rather than a stock alert. */
@Composable
fun ConnectDialog(
    initial: ServerConfig,
    onSave: (ServerConfig) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 680.dp),
        ) {
            ConnectForm(initial = initial, onSave = onSave, onForget = onForget, onCancel = onDismiss)
        }
    }
}
