package app.pocketfader.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private var cfg by mutableStateOf(ServerConfig())

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = ConfigStore(this)
        cfg = store.load()
        Remote.configure(cfg)
        setContent {
            PocketfaderTheme {
                App(
                    cfg = cfg,
                    onSave = { c -> applyConfig(c) },
                    onForget = { applyConfig(ServerConfig()) },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applyConfig(c: ServerConfig) {
        store.save(c)
        cfg = c
        Remote.configure(c)
        if (c.isSet) startPlayback() else stopService(Intent(this, PlaybackService::class.java))
    }

    override fun onStart() {
        super.onStart()
        Remote.acquire()
        Remote.setVisible(true)
        if (cfg.isSet) startPlayback()
    }

    override fun onStop() {
        Remote.setVisible(false)
        Remote.release()
        super.onStop()
    }

    /** While the app is open the phone's volume keys control the computer. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (cfg.isSet && Remote.state.value.volume.available) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { Remote.adjustVolume(5); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { Remote.adjustVolume(-5); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startPlayback() {
        val i = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}

private enum class Dest(val label: String, val icon: ImageVector) {
    Playing("Playing", Icons.Filled.PlayCircle),
    Queue("Queue", Icons.Filled.LibraryMusic),
    Search("Search", Icons.Filled.Search),
    Mixer("Mixer", Icons.Filled.Tune),
    Library("Library", Icons.Filled.Language),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(cfg: ServerConfig, onSave: (ServerConfig) -> Unit, onForget: () -> Unit) {
    val s by Remote.state.collectAsStateWithLifecycle()

    if (!cfg.isSet) {
        Scaffold { inner ->
            ConnectForm(initial = cfg, onSave = onSave, modifier = Modifier.padding(inner).fillMaxSize())
        }
        return
    }

    var showConnect by rememberSaveable { mutableStateOf(false) }
    var dest by rememberSaveable { mutableStateOf(Dest.Playing) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(s.error, s.online) {
        val e = s.error
        if (e != null && s.online) snackbar.showSnackbar(e)
    }

    // On a wide screen "Playing" is always visible on the left, so the right pane never shows it.
    val rightDest = if (dest == Dest.Playing) Dest.Queue else dest

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (wide) "Pocketfader" else dest.label) },
                actions = {
                    IconButton(onClick = { showConnect = true }) { Icon(Icons.Filled.Settings, "Connection settings") }
                },
            )
        },
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    Dest.values().forEach { d ->
                        NavigationBarItem(
                            selected = dest == d,
                            onClick = { dest = d },
                            icon = { Icon(d.icon, d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (!s.online) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Can't reach the computer. ${s.error ?: "Trying again…"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    PlayingScreen(s, Modifier.weight(0.85f).fillMaxSize())
                    Column(Modifier.weight(1.15f).fillMaxSize()) {
                        val tabs = listOf(Dest.Queue, Dest.Search, Dest.Mixer, Dest.Library)
                        TabRow(selectedTabIndex = tabs.indexOf(rightDest).coerceAtLeast(0)) {
                            tabs.forEach { d ->
                                Tab(selected = rightDest == d, onClick = { dest = d }, text = { Text(d.label) })
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { Content(rightDest, s, cfg) }
                    }
                }
            } else {
                Content(dest, s, cfg)
            }
        }
    }

    if (showConnect) {
        ConnectDialog(
            initial = cfg,
            onSave = { showConnect = false; onSave(it) },
            onForget = { showConnect = false; onForget() },
            onDismiss = { showConnect = false },
        )
    }
}

@Composable
private fun Content(dest: Dest, s: RemoteState, cfg: ServerConfig) {
    val m = Modifier.fillMaxSize()
    when (dest) {
        Dest.Playing -> PlayingScreen(s, m)
        Dest.Queue -> QueueScreen(s, m)
        Dest.Search -> SearchScreen(s, m)
        Dest.Mixer -> MixerScreen(s, m)
        Dest.Library -> WebScreen(cfg, m)
    }
}
