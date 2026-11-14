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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
            LaunchedEffect(prefs.notification, cfg.isSet, prefs.playOn) { syncPlayback() }
            // the cover is read once here: its colour is the whole app's theme (Album colours) and the player's light
            val art by rememberArt(s.now?.art)
            PocketfaderTheme(prefs, art) {
                androidx.compose.runtime.CompositionLocalProvider(LocalArtInfo provides art) {
                    PixelShift(prefs.pixelShift, prefs.pixelShiftMin, prefs.pixelShiftDp) {
                        App(cfg = cfg, s = s, prefs = prefs, onSave = { c -> applyConfig(c) }, onForget = { applyConfig(ServerConfig()) })
                    }
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
        if (c.isSet) Library.paired()       // bring the library over from this computer as soon as its YouTube Music is there
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
        val st = Remote.state.value
        if (p.volumeKeys && cfg.isSet && !Remote.local && st.audioOn && st.volume.available) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { Remote.adjustVolume(p.volumeStep); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { Remote.adjustVolume(-p.volumeStep); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** The notification for what the computer plays: on when wanted. On the phone, the phone's own player shows its
     *  notification; the computer's would be a duplicate. */
    private fun syncPlayback() {
        val p = Settings.prefs.value
        if (cfg.isSet && p.notification && p.playOn != "phone") startPlayback()
        else stopService(Intent(this, PlaybackService::class.java))
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
private fun PlayOnButton(prefs: Prefs, hasComputer: Boolean, remoteOn: Boolean) {
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
                // carrying the song over needs the computer's YouTube Music remote, so it isn't offered while that is off
                if (phone && remoteOn) androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Continue this song on the computer") },
                    leadingIcon = { Icon(Icons.Filled.Computer, null) },
                    onClick = { open = false; Remote.setPlayOn("computer", carryOver = true) },
                )
            }
        }
    }
}

@Composable
private fun DestContent(d: Dest, s: RemoteState, cfg: ServerConfig, remoteOff: Boolean, onEditConnection: () -> Unit) {
    when (d) {
        // with the YouTube Music remote switched off on the computer, its screens give way to a short note
        Dest.Playing -> if (remoteOff) RemoteOffPage(s, Modifier.fillMaxSize()) else PlayingPage(s, Modifier.fillMaxSize())
        Dest.Search -> if (remoteOff) RemoteOffPage(s, Modifier.fillMaxSize()) else SearchScreen(s, Modifier.fillMaxSize())
        Dest.Settings -> SettingsScreen(cfg, s, onEditConnection = onEditConnection, modifier = Modifier.fillMaxSize())
    }
}

// [icon] for the chosen page, [idle] (outlined) for the others, as Material's navigation does
private enum class Dest(val key: String, val label: String, val icon: ImageVector, val idle: ImageVector) {
    Playing("playing", "Playing", Icons.Filled.PlayCircle, Icons.Filled.PlayCircleOutline),
    Search("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    Settings("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

// On wide screens the player is always visible on the left, so the right pane gets its own tabs.
private enum class Pane(val label: String) { Queue("Queue"), Lyrics("Lyrics"), Comments("Comments"), Search("Search") }

@Composable
private fun WidePaneContent(p: Pane, s: RemoteState, comments: CommentsUi) {
    when (p) {
        Pane.Queue -> QueuePane(s, Modifier.fillMaxSize())
        Pane.Lyrics -> LyricsPane(s, Modifier.fillMaxSize())
        Pane.Comments -> CommentsPane(comments, Modifier.fillMaxSize())
        Pane.Search -> SearchScreen(s, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(cfg: ServerConfig, s: RemoteState, prefs: Prefs, onSave: (ServerConfig) -> Unit, onForget: () -> Unit) {
    if (!cfg.isSet && !prefs.standalone) {
        Scaffold { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                ConnectForm(initial = cfg, onSave = onSave, modifier = Modifier.weight(1f).fillMaxWidth(), logo = true)
                // no computer? the app works on its own too: YouTube Music, downloads and the phone's own music
                androidx.compose.material3.TextButton(
                    onClick = { Settings.update { it.copy(standalone = true, playOn = "phone") }; Remote.configure(cfg) },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).padding(bottom = 12.dp),
                ) { Text("No computer? Use YTM Starcast on this phone only") }
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
    val comments = rememberComments(if (wide) s.now?.videoId else null)     // the tablet layout's Comments tab
    val snackbar = remember { SnackbarHostState() }
    // The YouTube Music remote is switched off on the computer (music playing on this phone doesn't need it).
    val remoteOff = cfg.isSet && prefs.playOn != "phone" && !s.remoteOn
    // its Search tab goes away while it's off; the Playing tab says so, with a button to turn it back on
    val dests = Dest.values().filter { !(remoteOff && it == Dest.Search) }
    LaunchedEffect(remoteOff) {
        if (remoteOff) {
            if (dest == Dest.Search) dest = Dest.Playing
            Detail.close()     // an artist or album page needs the remote too
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    // leaving for another tab closes an artist / album page that was open on top
    LaunchedEffect(dest) { Detail.close() }
    // "Play" on a playlist or album (and the like) shows the player
    var shownPlayerFor by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(AppNav.toPlayer) }
    LaunchedEffect(AppNav.toPlayer) {
        if (AppNav.toPlayer != shownPlayerFor) {
            shownPlayerFor = AppNav.toPlayer
            showSettings = false
            Detail.close()
            dest = Dest.Playing
            pane = Pane.Queue
        }
    }
    LaunchedEffect(Unit) { Remote.messages.collect { snackbar.showSnackbar(it) } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()
    // Look for a new version of the app on GitHub (through the computer when GitHub can't be reached from the phone).
    LaunchedEffect(s.online, prefs.autoUpdate) {
        if (prefs.autoUpdate && UpdateManager.shouldCheckNow(s.online)) UpdateManager.check(ctx, refresh = false)
    }
    LaunchedEffect(s.error, s.online) {
        val e = s.error
        if (e != null && s.online) snackbar.showSnackbar(e)
    }

    val art = LocalArtInfo.current
    val tint = art.tint
    val showArt = !showSettings && (wide || dest == Dest.Playing)
    val wash = artBackground(if (showArt && prefs.coverWash) tint else null, prefs.theme == "black")
    val washBrush = wash.brush
    val pageBg = MaterialTheme.colorScheme.background
    val highlight = if (showArt && prefs.coverHighlight) tint?.let { readableOn(it, pageBg, 5f) } else null
    // the frame's own small motions (the title's fade, the tablet's panes, the notices) follow the screen-transition and
    // fewer-animations settings, as the screens do
    val calm = prefs.reduceMotion || !prefs.transitions
    val noticeIn = if (prefs.reduceMotion) EnterTransition.None else
        expandVertically(tween(Motion.Medium, easing = Motion.Ease)) + fadeIn(tween(Motion.Short, delayMillis = 60))
    val noticeOut = if (prefs.reduceMotion) ExitTransition.None else
        shrinkVertically(tween(Motion.Short, easing = Motion.Ease)) + fadeOut(tween(Motion.Quick))
    androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides highlight, LocalTint provides (if (showArt) tint else null), LocalArtThumb provides (if (showArt) art.thumb else null), LocalEdgeFade provides EdgeFade(wash.colorAt(0.11f), wash.colorAt(0.93f))) {
    Box(Modifier.fillMaxSize().background(washBrush)) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // Without this, text that doesn't name a colour falls back to black, which vanishes on the dark themes.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            // Phones name the page they show; where the page would just be called "YTM Starcast" (tablets, and the
            // player while the remote is off) the app's logo stands in its place.
            val heading: String? = when {
                showSettings -> "Settings"
                wide -> null
                dest == Dest.Playing -> if (remoteOff) null else "Now playing"
                else -> dest.label
            }
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = {
                    // the old name fades into the new one as the page changes
                    Crossfade(heading, animationSpec = tween(if (calm) 0 else Motion.Short, easing = Motion.Ease), label = "title") { h ->
                        if (h == null) StarcastLogo()
                        else Text(h, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    when {
                        showSettings -> IconButton(onClick = { showSettings = false }) { Icon(Icons.Filled.ArrowBack, "Back") }
                        // on phones a small logo leads the page's name (not when the logo is the title already)
                        !wide && heading != null -> StarcastTile(26.dp, Modifier.padding(start = 12.dp, end = 8.dp))
                    }
                },
                actions = {
                    // full screen hides the status bar's clock and battery: shown here instead when asked for
                    if (prefs.immersive) StatusCorner(prefs.barClock, prefs.barBattery)
                    if (!showSettings) PlayOnButton(prefs, cfg.isSet, s.remoteOn)
                    if (wide && !showSettings) IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            // what's playing, on every page but the player itself (on tablets the player is always in view); the pages
            // make room for it, as for the navigation
            val showMini = !wide && s.now?.title?.isNotBlank() == true &&
                (showSettings || Detail.active || dest != Dest.Playing || remoteOff)
            Column {
            androidx.compose.animation.AnimatedVisibility(
                visible = showMini,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(Motion.Medium, easing = Motion.Ease)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Short)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(Motion.Short, easing = Motion.Ease)) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Quick)),
            ) {
                MiniControls(
                    s,
                    onTap = { showSettings = false; Detail.close(); dest = Dest.Playing },
                    // with no navigation under it (Settings), it keeps clear of the phone's own bar
                    modifier = if (showSettings) Modifier.navigationBarsPadding() else Modifier,
                )
            }
            if (!wide && !showSettings) {
                if (prefs.navStyle == "pill") {
                    PillNav(
                        dests.map { it.label }, dests.map { it.icon }, dests.indexOf(dest).coerceAtLeast(0),
                        idleIcons = dests.map { it.idle },
                    ) { dest = dests[it] }
                } else {
                    val scheme = MaterialTheme.colorScheme
                    NavigationBar(containerColor = scheme.surfaceContainer.copy(alpha = 0.92f)) {
                        // the chosen page sits on a tonal pill in the cover's colours, as in Refine
                        val itemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = scheme.onPrimaryContainer,
                            selectedTextColor = scheme.onSurface,
                            indicatorColor = scheme.primaryContainer,
                            unselectedIconColor = scheme.onSurfaceVariant,
                            unselectedTextColor = scheme.onSurfaceVariant,
                        )
                        dests.forEach { d ->
                            val on = dest == d
                            NavigationBarItem(
                                selected = on, onClick = { dest = d },
                                icon = { Icon(if (on) d.icon else d.idle, d.label) },
                                label = { Text(d.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium) },
                                colors = itemColors,
                            )
                        }
                    }
                }
            }
            }   // Column
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            // notices float as rounded tonal cards under the top bar, and open and close smoothly
            AnimatedVisibility(
                visible = !showSettings && !UpdateManager.dismissed && UpdateManager.available(ctx),
                enter = noticeIn, exit = noticeOut,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = PfShape.Lg,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "A new version is ready: ${UpdateManager.info?.latest}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        androidx.compose.material3.TextButton(onClick = { SettingsNav.deepLink = "updates"; if (wide) showSettings = true else dest = Dest.Settings }) { Text("Update") }
                        androidx.compose.material3.TextButton(onClick = { UpdateManager.dismissed = true }) { Text("Later") }
                    }
                }
            }
            AnimatedVisibility(
                visible = !s.online && !showSettings && cfg.isSet && !Remote.local,
                enter = noticeIn, exit = noticeOut,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer, shape = PfShape.Md,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Can't reach the computer. ${s.error ?: "Trying again…"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when {
                Detail.active && !wide -> DetailHost(Modifier.fillMaxSize())
                showSettings -> SettingsScreen(cfg, s, onEditConnection = { showConnect = true }, modifier = Modifier.fillMaxSize())
                wide && remoteOff -> RemoteOffPage(s, Modifier.fillMaxSize())
                wide -> Row(Modifier.fillMaxSize()) {
                    PlayerPane(s, Modifier.weight(0.85f).fillMaxSize())
                    Column(Modifier.weight(1.15f).fillMaxSize()) {
                        if (Detail.active) {
                            // an artist, album or playlist page opens beside the player, which stays in view
                            DetailHost(Modifier.fillMaxSize())
                        } else {
                            // Refine's pill bar of tabs: the chosen one sits on a tonal pill that slides to it.
                            // Comments only for songs that have comments to show; when a song has them turned off the
                            // tab stays in its place, crossed out, as in Refine
                            val commentsOff = comments.loaded && comments.disabled
                            val panes = Pane.values().filter { it != Pane.Comments || comments.hasComments || commentsOff }
                            LaunchedEffect(comments.hasComments) { if (!comments.hasComments && pane == Pane.Comments) pane = Pane.Queue }
                            PillTabs(
                                labels = panes.map { it.label },
                                selected = panes.indexOf(pane).coerceAtLeast(0),
                                onSelect = { i -> panes.getOrNull(i)?.let { pane = it } },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                crossed = if (commentsOff) setOf(panes.indexOf(Pane.Comments)) else emptySet(),
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (!calm) {
                                    // the new pane fades in with a hint of a slide from the side of its tab
                                    AnimatedContent(
                                        targetState = pane,
                                        transitionSpec = {
                                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                            val enter = fadeIn(tween(Motion.Short, delayMillis = 60, easing = Motion.Ease)) +
                                                slideInHorizontally(tween(Motion.Medium, easing = Motion.Ease)) { dir * it / 24 }
                                            enter togetherWith fadeOut(tween(Motion.Quick, easing = Motion.Ease))
                                        },
                                        label = "pane",
                                    ) { p -> WidePaneContent(p, s, comments) }
                                } else {
                                    WidePaneContent(pane, s, comments)
                                }
                            }
                        }
                    }
                }
                else -> if (prefs.transitions && !prefs.reduceMotion) {
                    // a short fade with a hint of upward slide; only the incoming screen is really drawn for long
                    AnimatedContent(
                        targetState = dest,
                        transitionSpec = {
                            (fadeIn(tween(Motion.Short, delayMillis = 60, easing = Motion.Ease)) + slideInVertically(tween(Motion.Medium, easing = Motion.Ease)) { it / 28 }) togetherWith fadeOut(tween(Motion.Quick, easing = Motion.Ease))
                        },
                        label = "screen",
                    ) { d -> DestContent(d, s, cfg, remoteOff) { showConnect = true } }
                } else {
                    DestContent(dest, s, cfg, remoteOff) { showConnect = true }
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
