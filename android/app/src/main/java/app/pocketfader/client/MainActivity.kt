package app.pocketfader.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private var cfg by mutableStateOf(ServerConfig())

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val handler = Handler(Looper.getMainLooper())
    private var dimMinutes = 0
    private var dimmed = false
    private val dimRunnable = Runnable { setDim(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        enableEdgeToEdge()
        store = ConfigStore(this)
        cfg = store.load()
        Remote.configure(cfg)
        setContent {
            val prefs by Settings.prefs.collectAsStateWithLifecycle()
            val s by Remote.state.collectAsStateWithLifecycle()
            val playing = s.now?.playing == true
            LaunchedEffect(prefs.keepAwake, prefs.dimAfterMin, prefs.immersive, playing) { applyWindow(prefs, playing) }
            LaunchedEffect(prefs.notification, cfg.isSet, prefs.playOn) {
                // on the phone, the phone's own player shows its notification; the computer's would be a duplicate
                if (cfg.isSet && prefs.notification && prefs.playOn != "phone") startPlayback()
                else stopService(Intent(this@MainActivity, PlaybackService::class.java))
            }
            PocketfaderTheme(prefs) {
                PixelShift(prefs.pixelShift, prefs.pixelShiftMin, prefs.pixelShiftDp) {
                    App(cfg = cfg, s = s, prefs = prefs, onSave = { c -> applyConfig(c) }, onForget = { applyConfig(ServerConfig()) })
                }
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
        LibraryStore.clear()
        if (!c.isSet) stopService(Intent(this, PlaybackService::class.java))
    }

    /** Keep-awake, full screen and dimming follow the settings (and, for "while playing", the music). */
    private fun applyWindow(p: Prefs, playing: Boolean) {
        val keep = p.keepAwake == "always" || (p.keepAwake == "playing" && playing)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bars: WindowInsetsControllerCompat = WindowCompat.getInsetsController(window, window.decorView)
        if (p.immersive) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        dimMinutes = if (keep) p.dimAfterMin else 0
        rearmDim()
    }

    private fun rearmDim() {
        handler.removeCallbacks(dimRunnable)
        setDim(false)
        if (dimMinutes > 0) handler.postDelayed(dimRunnable, dimMinutes * 60_000L)
    }

    private fun setDim(on: Boolean) {
        if (dimmed == on) return
        dimmed = on
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN && (dimMinutes > 0 || dimmed)) rearmDim()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        Remote.acquire()
        Remote.setVisible(true)
        if (cfg.isSet && Settings.prefs.value.notification && !Remote.local) startPlayback()
    }

    override fun onStop() {
        handler.removeCallbacks(dimRunnable)
        setDim(false)
        Remote.setVisible(false)
        Remote.release()
        super.onStop()
    }

    /** While the app is open the phone's volume keys control the computer. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = Settings.prefs.value
        if (p.volumeKeys && cfg.isSet && !Remote.local && Remote.state.value.volume.available) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { Remote.adjustVolume(p.volumeStep); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { Remote.adjustVolume(-p.volumeStep); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startPlayback() {
        val i = Intent(this, PlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            // Android refuses this now and then (the app isn't quite in front yet); the next time the app shows tries again
            CrashLog.note("starting the playback notification", e)
        }
    }
}

/** Where music plays: the computer or this phone. Switching offers to bring the current song along. */
@Composable
private fun PlayOnButton(prefs: Prefs, hasComputer: Boolean) {
    var open by remember { mutableStateOf(false) }
    val phone = prefs.playOn == "phone"
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                if (phone) Icons.Filled.PhoneAndroid else Icons.Filled.Computer,
                if (phone) "Playing on this phone" else "Playing on the computer",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Play on this phone" + if (phone) "  ✓" else "") },
                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null) },
                onClick = { open = false; Remote.setPlayOn("phone", carryOver = false) },
            )
            if (!phone && hasComputer) androidx.compose.material3.DropdownMenuItem(
                text = { Text("Continue this song on the phone") },
                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null) },
                onClick = { open = false; Remote.setPlayOn("phone", carryOver = true) },
            )
            if (hasComputer) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Play on the computer" + if (!phone) "  ✓" else "") },
                    leadingIcon = { Icon(Icons.Filled.Computer, null) },
                    onClick = { open = false; Remote.setPlayOn("computer", carryOver = false) },
                )
                if (phone) androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Continue this song on the computer") },
                    leadingIcon = { Icon(Icons.Filled.Computer, null) },
                    onClick = { open = false; Remote.setPlayOn("computer", carryOver = true) },
                )
            }
        }
    }
}

@Composable
private fun DestContent(d: Dest, s: RemoteState, cfg: ServerConfig, onEditConnection: () -> Unit) {
    when (d) {
        Dest.Playing -> PlayingPage(s, Modifier.fillMaxSize())
        Dest.Search -> SearchScreen(s, Modifier.fillMaxSize())
        Dest.Settings -> SettingsScreen(cfg, s, onEditConnection = onEditConnection, modifier = Modifier.fillMaxSize())
    }
}

private enum class Dest(val key: String, val label: String, val icon: ImageVector) {
    Playing("playing", "Playing", Icons.Filled.PlayCircle),
    Search("search", "Search", Icons.Filled.Search),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

// On wide screens the player is always visible on the left, so the right pane gets its own tabs.
private enum class Pane(val label: String) { Queue("Queue"), Lyrics("Lyrics"), Search("Search") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(cfg: ServerConfig, s: RemoteState, prefs: Prefs, onSave: (ServerConfig) -> Unit, onForget: () -> Unit) {
    if (!cfg.isSet && !prefs.standalone) {
        Scaffold { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                ConnectForm(initial = cfg, onSave = onSave, modifier = Modifier.weight(1f).fillMaxWidth())
                // no computer? the app works on its own too: YouTube Music, downloads and the phone's own music
                androidx.compose.material3.TextButton(
                    onClick = { Settings.update { it.copy(standalone = true, playOn = "phone") }; Remote.configure(cfg) },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).padding(bottom = 12.dp),
                ) { Text("No computer? Use Pocketfader on this phone only") }
            }
        }
        return
    }

    var showConnect by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var dest by rememberSaveable {
        mutableStateOf(Dest.values().firstOrNull { it.key == prefs.startTab } ?: if (prefs.startTab == "library") Dest.Search else Dest.Playing)
    }
    var pane by rememberSaveable {
        mutableStateOf(
            when (prefs.startTab) { "search", "library" -> Pane.Search; else -> Pane.Queue }
        )
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = showSettings) { showSettings = false }
    // leaving for another tab closes an artist / album page that was open on top
    LaunchedEffect(dest) { Detail.close() }
    LaunchedEffect(Unit) { Remote.messages.collect { snackbar.showSnackbar(it) } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()
    // Once the computer is reachable, look for a new version of the app (the computer asks GitHub).
    LaunchedEffect(s.online, prefs.autoUpdate) {
        if (s.online && prefs.autoUpdate && UpdateManager.info == null) UpdateManager.check(ctx, refresh = false)
    }
    LaunchedEffect(s.error, s.online) {
        val e = s.error
        if (e != null && s.online) snackbar.showSnackbar(e)
    }

    val art by rememberArt(s.now?.art)
    val tint = art.tint
    val showArt = !showSettings && (wide || dest == Dest.Playing)
    val wash = artBackground(if (showArt && prefs.coverWash) tint else null, prefs.theme == "black")
    val washBrush = wash.brush
    val pageBg = MaterialTheme.colorScheme.background
    val highlight = if (showArt && prefs.coverHighlight) tint?.let { readableOn(it, pageBg, 5f) } else null
    androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides highlight, LocalTint provides (if (showArt) tint else null), LocalArtThumb provides (if (showArt) art.thumb else null), LocalEdgeFade provides EdgeFade(wash.colorAt(0.11f), wash.colorAt(0.93f))) {
    Box(Modifier.fillMaxSize().background(washBrush)) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // Without this, text that doesn't name a colour falls back to black, which vanishes on the dark themes.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = { Text(if (showSettings) "Settings" else if (wide) "Pocketfader" else if (dest == Dest.Playing) "Now playing" else dest.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = {
                    if (showSettings) IconButton(onClick = { showSettings = false }) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (!showSettings) PlayOnButton(prefs, cfg.isSet)
                    if (wide && !showSettings) IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            if (!wide && !showSettings) {
                val dests = Dest.values()
                if (prefs.navStyle == "pill") {
                    PillNav(dests.map { it.label }, dests.map { it.icon }, dest.ordinal) { dest = dests[it] }
                } else {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)) {
                        dests.forEach { d ->
                            NavigationBarItem(
                                selected = dest == d, onClick = { dest = d },
                                icon = { Icon(d.icon, d.label) }, label = { Text(d.label) },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (!showSettings && !UpdateManager.dismissed && UpdateManager.available(ctx)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "A new version is ready: ${UpdateManager.info?.latest}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        androidx.compose.material3.TextButton(onClick = { SettingsNav.deepLink = "updates"; if (wide) showSettings = true else dest = Dest.Settings }) { Text("Update") }
                        androidx.compose.material3.TextButton(onClick = { UpdateManager.dismissed = true }) { Text("Later") }
                    }
                }
            }
            if (!s.online && !showSettings && cfg.isSet && !Remote.local) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Can't reach the computer. ${s.error ?: "Trying again…"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when {
                Detail.active -> DetailHost(Modifier.fillMaxSize())
                showSettings -> SettingsScreen(cfg, s, onEditConnection = { showConnect = true }, modifier = Modifier.fillMaxSize())
                wide -> Row(Modifier.fillMaxSize()) {
                    PlayerPane(s, Modifier.weight(0.85f).fillMaxSize())
                    Column(Modifier.weight(1.15f).fillMaxSize()) {
                        TabRow(selectedTabIndex = pane.ordinal) {
                            Pane.values().forEach { p -> Tab(selected = pane == p, onClick = { pane = p }, text = { Text(p.label) }) }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (pane) {
                                Pane.Queue -> QueuePane(s, Modifier.fillMaxSize())
                                Pane.Lyrics -> LyricsPane(s, Modifier.fillMaxSize())
                                Pane.Search -> SearchScreen(s, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                else -> if (prefs.transitions && !prefs.reduceMotion) {
                    // a short fade with a hint of upward slide; only the incoming screen is really drawn for long
                    AnimatedContent(
                        targetState = dest,
                        transitionSpec = {
                            (fadeIn(tween(200, delayMillis = 60)) + slideInVertically(tween(260)) { it / 28 }) togetherWith fadeOut(tween(90))
                        },
                        label = "screen",
                    ) { d -> DestContent(d, s, cfg) { showConnect = true } }
                } else {
                    DestContent(dest, s, cfg) { showConnect = true }
                }
            }
        }
    }

    }   // Box
    }   // CompositionLocalProvider

    if (showConnect) {
        ConnectDialog(
            initial = cfg,
            onSave = { showConnect = false; showSettings = false; onSave(it) },
            onForget = { showConnect = false; showSettings = false; onForget() },
            onDismiss = { showConnect = false },
        )
    }
}
