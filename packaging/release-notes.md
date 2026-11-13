## Pocketfader is now YTM Starcast, and YTM Beacon is built in

YTM Starcast is your YouTube Music companion: a remote for your phone, your music as your Discord status, and a better
YouTube Music. It is Pocketfader under a new name, and it now also does what the separate YTM Beacon app did. One app on
the computer, one browser extension and one Android app do it all, and every part has its own on/off switch.

This is an early build for testing; expect rough edges.

### What's new in 0.2.0-alpha.5

**A new icon**
- A star casting its light, with a second star riding the arcs, on midnight blue. On Android it follows the Material You
  colours of your wallpaper when themed icons are on.

**Refine, spread out**
- ✦ opens a short menu of tiles for what is switched most, the lyrics window, the playlist maker and **All settings**,
  which shows everything together in one large dialog.
- A **Discord** button beside ✦ for Discord status, with a dot that turns green while your music shows on Discord.
- **Lyrics**, **Make a playlist** and **Refine** in YouTube Music's sidebar, and YTM Starcast's logo in the top bar.
- **Material You page parts**: the tabs beside the player are a pill bar instead of underlined capitals, the queue's
  header and Save button are tonal, YouTube Music's switches take the Material 3 shape.
- **Cover visualizers** (experimental): Halo, Orbit, Pulse and Bars around the big cover.

**Lyrics**
- They follow you: leaving the player with the Lyrics tab open brings them along in a window, which goes again when you
  come back. Switching to another tab can keep them above other windows, where the browser offers that.
- Fixed: lyrics could switch to another song of the same name partway through. Sources now have to agree on the words
  (YouTube Music's own lyrics settle it when there are some), matches need the right artist and length, and lyrics on
  screen are only replaced by the same words.
- A "Title - Artist" line at the start of some lyrics is gone.

### What's new in 0.2.0-alpha.4

**Live lyrics, word by word, in YouTube Music's Lyrics tab**
- Refine's lyrics now fill YouTube Music's own Lyrics tab next to Up next, also for songs where YouTube Music has none;
  the separate lyrics button is gone.
- One button moves them into a window that can be dragged anywhere, resized, pinned (it stays put and comes back after a
  reload) or kept above other windows, and back into the tab.
- Many more songs are timed word by word: NetEase's word timings are used now, alongside KuGou's. Songs timed only line
  by line get their words estimated, so the line still fills as it is sung (Options → Words).
- Options per song: pick the source, and move the lyrics earlier or later.
- Fixed: after starting another song from the home page, the lyrics could keep saying "Play a song to see its lyrics".
- A one-line "instrumental" placeholder no longer wins over real lyrics, and credit lines ("Composer: …") are left out.

**Slowed songs**
- The speed now stays from song to song (and after a reload) until it is changed; YouTube Music used to put its own back.
- **Clear slowed sound**: slowed songs keep their pitch without the smeared, warbly sound of the browser's own way.
- **Room**: a soft reverb for a "slowed + reverb" sound.
- At another speed the times follow it, in the player bar and on Discord: a 3:00 song at 0.8x shows 3:45.

**Refine**
- Animations: new pages rise in shelf by shelf, cards and buttons answer a press, menus drop in, a new song slides into
  the player bar, and the tabs beside the player move into place. They follow the system's reduced-motion setting.
- **Computer sound** in the ✦ menu: the computer's volume, output and equalizer (on/off, presets, bass, mid, treble), as
  in YTM Starcast's mixer.
- The extension's toolbar button takes on Refine's look and the colours of the album that plays.

### What's new in 0.2.0-alpha.3

**Discord status: everything YTM Beacon had, and more**
- The extension's toolbar button and Refine's ✦ menu have every Discord status option again: show or hide, hide for 30
  minutes, an hour or three hours, private mode, album art, the progress bar, the play/pause icon, the "Listen on YouTube
  Music" button, paused songs and how long they stay, what the member list shows, never-show words and a 30-second test song.
- A live preview of the card shows what Discord shows, in the extension and in the apps.
- **Alt+Shift+M** shows or stops showing your music on Discord from anywhere in the browser.

**Refine**
- The wavy progress bar works with YouTube Music's newer player bar.
- The big cover and the floating mini player no longer show grey corners, and the glow behind the cover is back.
- Live lyrics: the rest of a line is readable before it is sung (it used to appear only as it was sung), and more of what
  comes next is visible.
- YouTube Music's menus open above the lyrics panel, and a second click on ⋮ closes its menu.
- The "something you might like" button moved from the player to the home page, next to the first shelf (Listen again),
  so a slip can't replace the song that plays.
- More of YouTube Music's buttons take the Material You look ("More", "Play all", the shelf arrows).

### What's new in 0.2.0-alpha.2

**Adding the browser extension, step by step**
- Settings → **Browser extension** (in the web app, the Android app and the desktop window) shows whether the extension
  is connected and walks through adding it: open the browser's extensions page, turn on Developer mode, Load unpacked,
  open YouTube Music. Buttons open the extensions page and the extension's folder on the computer, and the page turns
  green by itself once the extension connects.
- Until an extension has connected, Home and Settings point to these steps.
- The installer copies the extension to a folder that never moves and prints the same steps; on Linux it offers to open
  the extensions page and the folder right away.
- Updates refresh that folder. When the browser still runs an older copy, the apps say to reload it.

### What's new in 0.2.0-alpha.1

**One app**

- **Pocketfader is now YTM Starcast.** Same app, new name: your settings, access token, paired phones and Android app carry
  over.
- **Discord status is built in.** YTM Starcast shows the song you play in YouTube Music on your Discord profile by itself; the
  YTM Beacon app isn't needed any more. Control it from Settings → Discord status: show or hide it, hide it for a while,
  private mode, show while paused (and for how long), progress bar, play/pause icon, Listen button, album art and the
  name next to your username.
- **Never show songs matching:** words that keep a song off Discord, in the web app and the Android app.
- **Adverts stay off Discord,** and closing the YouTube Music tab clears your status at once.
- **A switch for every part:** Settings → Parts in the web app, the Android app and the desktop window turns the YouTube
  Music remote, Discord status, Refine and the computer's audio controls on or off. A part that's off is hidden.
- **The extension has a toolbar button** (extension 2.0): whether YTM Starcast is running, the switches for the remote, Discord
  status and Refine, and what Discord status is doing. Refine's ✦ menu has the same switches.
- **The extension is a download now:** `ytm-starcast-extension.zip` in each release.
- **Moving over:** run YTM Starcast's installer once on the computer. It replaces Pocketfader and removes the YTM Beacon app,
  keeping your settings. Then remove the YTM Beacon extension from your browser by hand (the release page on GitHub
  has every step). YTM Starcast needs a Chromium-based browser on Linux or Windows: with YTM Beacon in Firefox or on
  macOS, keep YTM Beacon for now.

**Refine: a better YouTube Music, inside YouTube Music.** With YTM Starcast's extension loaded, music.youtube.com gets a ✦
button in its top bar. Everything below has its own switch there, and opening `music.youtube.com/?pf-refine=off` starts a
page without any of it. These changes depend on how YouTube Music's page is built, so treat them as experimental.

- **A Material 3 Expressive look**, in layers you can switch separately: rounder shapes (covers, lists, menus, search), colours
  taken from the album art, expressive player controls (a bigger play button that turns from a circle into a rounded square while
  playing, tinted pressed buttons), a **wavy progress bar** that ripples while a song plays and flattens when you pause, and a
  glow behind the cover.
- **A richer speed menu** on YouTube Music's own speed button: any speed from 0.25× to 3×, quick presets, keep or drop the
  pitch, and remember a speed for one song or a whole playlist (it comes back when that song or playlist plays, and doesn't spill
  over onto others).
- **Live lyrics** in YouTube Music: a lyrics button in the player opens time-synced lyrics (word by word where the source has
  it) from YTM Starcast, following the song; tap a line to jump there.
- **Speed from the keyboard:** Shift+. faster, Shift+, slower.
- **A "play something you might like" button** among the player controls.
- **A playlist maker:** start from the song playing, your queue, one of your playlists or your liked songs. It asks YouTube Music
  for the radio of each starting song, ranks what they have in common, keeps any one artist from taking over, and shows the
  result to review: untick songs, rename it, try again for a different mix, then save it as a new private playlist or add it to
  one of yours, and play it. A song's own menu in YouTube Music offers "Playlist from this song".
- **Lyrics options:** text size, alignment, and whether the romanized line and the translation are shown.
- Both layouts of YouTube Music's player bar are supported.

**Android app**

- **Search finds artists, albums and playlists,** not only songs (All, Songs, Artists, Albums, Playlists). Each opens its own
  page, also when the computer is not reachable. Back steps through the pages you opened.
- **The library syncs every time the phone pairs with or connects to a computer,** quietly; if an automatic sync fails,
  Settings says so.
- **The app no longer closes by itself in the background** a moment after music pauses: the phone player stays alive for ten
  minutes after a pause, and errors in background work are written down (Settings → About) instead of ending the app.
- **Swiping the app away stops music that plays on the phone** (your queue and place are kept). To keep it playing, turn on
  Settings → Controls → "Keep playing when the app is swiped away".
- **Lyrics:** the line being sung no longer re-wraps as it is highlighted.
- **The light behind the cover follows the cover's shape,** so nothing square shows behind its rounded corners.
- Smoother changes between songs and states (cover, title, play button), the small player shows progress, button rows fit
  narrow phones, lists fade at the edges.

**Web app**

- Search can show only songs, artists, albums or playlists; recent searches are kept as chips.
- Back returns from a playlist, album or artist page; the song that is playing is marked in lists; the Controls widget shows
  progress.
- Fits narrow phones (down to 320 px), and the transport buttons are named for screen readers.
- The Discord status card can hide a paused song after a while, as the Android app already could.

**One look everywhere.** The web app, the Android app and Refine now share one scale of corners, timings and press feedback,
the same chip and play-button shapes, and the same violet (see `docs/DESIGN.md`).

### Moving over from Pocketfader or YTM Beacon

**YTM Starcast needs a Chromium-based browser (Chrome, Brave, Edge…) on a Linux or Windows computer.** YTM Beacon also works
in Firefox and on macOS; there, keep using YTM Beacon for now. With YTM Beacon in Firefox on Linux or Windows, install YTM
Starcast with `--keep-ytm-beacon` (Linux) or `-KeepYtmBeacon` (Windows), or Discord status stops.

1. **On the computer, run YTM Starcast's installer once.**
   - Linux: download the source code of this release (or clone the repository) and run `./install.sh`.
   - Windows: extract `ytm-starcast-v…-windows-x64.zip` and run `install-exe.ps1` in it (right-click → Run with PowerShell).

   It replaces Pocketfader: your settings, access token, paired phones and options such as `--lan` carry over (on Linux,
   so does its start-at-login choice). On Windows, YTM Starcast starts at every logon, and if Pocketfader ran from the
   folder its zip was extracted to, delete that folder by hand. The installer also removes the YTM Beacon app with Beacon's
   own uninstaller; YTM Starcast takes over your Discord settings from it. Pocketfader's own update check can't find this
   release (its Updates row reports "GitHub answered 301"), so use the installer.
2. **In your browser, by hand:** remove the **YTM Beacon** extension on the extensions page, and swap the Pocketfader
   extension for YTM Starcast's: unzip `ytm-starcast-extension.zip` into a folder you keep, then Developer mode → Load unpacked →
   that folder. Keep only one copy of YTM Starcast's extension.
3. **On your phone:** update the Android app. It is the same app, so your settings, library, downloads and widgets stay.
   Once the computer runs YTM Starcast, the app offers the update (Settings → Updates); or install `ytm-starcast-android.apk` over it.

Installed Pocketfader or YTM Beacon with Orbit? Update Orbit first and let it move you over.

To keep the YTM Beacon app for now, install with `--keep-ytm-beacon` (Linux) or `-KeepYtmBeacon` (Windows). YTM Starcast leaves
Discord to Beacon while it runs, and Settings → Discord status → **Move over** finishes the move later.

### Downloads

| File | For | How to use |
|---|---|---|
| `ytm-starcast-v…-linux-x86_64.tar.gz` | Linux computer (server) | Extract and run `./ytm-starcast`. Needs PipeWire/PulseAudio (`pactl`); `busctl` (systemd) is used for media-player control. For a background service that starts at login, and to move over from Pocketfader or YTM Beacon, use `install.sh` from the source code instead. |
| `ytm-starcast-v…-windows-x64.zip` | Windows computer (server) | Extract and run `install-exe.ps1` (right-click → Run with PowerShell): it installs YTM Starcast for your user, starts it at every logon, and moves you over from Pocketfader and YTM Beacon. `ytm-starcast-gui.exe` is a small control window. **Experimental.** |
| `ytm-starcast-extension.zip` | The browser on the computer (Chrome, Brave, Edge…) | Unzip into a folder you keep, then the extensions page → Developer mode → Load unpacked → that folder. Needed for the YouTube Music remote, Discord status and Refine. |
| `ytm-starcast-android.apk` | Android 5.0+ phone or tablet (client) | Install the APK (allow "install unknown apps"), open it, enter your computer's address (e.g. `my-computer.tailnet.ts.net:8777`) and tap **Test**, then **Save**. A native app: now playing, queue, search, output devices and equalizer, Discord status, lock-screen controls, widgets, and the volume buttons control the computer. It installs over the Pocketfader app. |
| `ytm-starcast-android-unshrunk.apk` | Android, if the first one misbehaves | The same app, built without shrinking. |
| `ytm_starcast-…-py3-none-any.whl` / `.tar.gz` | Any OS with Python 3.9+ | `pipx install ytm_starcast-<version>-py3-none-any.whl` |
| `SHA256SUMS.txt` | | Verify downloads with `sha256sum -c SHA256SUMS.txt`. |

You don't need the Android app to use YTM Starcast: any phone, tablet or laptop can open the web page the server prints and add
it to the home screen.

### Quick start

1. Install YTM Starcast on your computer. It prints an address such as `http://100.x.y.z:8777/`.
2. Install [Tailscale](https://tailscale.com) on the computer and the phone (recommended), or install with `--lan` to use
   your home Wi-Fi (this turns on an access token).
3. Open the address on the phone (or enter it in the Android app).
4. Add the browser extension (`ytm-starcast-extension.zip`) to the browser on the computer and keep a music.youtube.com tab
   open.
5. For Discord status, keep the Discord desktop app open on the computer.

### Earlier (released as Pocketfader)

- alpha.15: your library without the computer (library sync, recommendations made on the phone, automatic downloads),
  better autoplay and sturdier downloads on the phone, and the extension follows the tab that is playing.
- alpha.14: new Android application ID `app.novalabs.pocketfader` (uninstall the old app once).
- alpha.13: widgets without a background, plus Discord presence and Up next widgets.
- alpha.12: widgets redesigned (clean card, rounded cover, live elapsed time); extension 1.13 quieter in the background.
- alpha.11: home-screen widgets and a new icon.
- alpha.10: play on the phone itself (YouTube Music and local files), downloads with lyrics, Search with Playlists and On this phone tabs.
- alpha.9: smoother cover glow, artist pages, search recommendations, soft edges, web app restyle.
- alpha.8: ambient cover glow, Settings in pages, Settings in the navigation, floating pill navigation, scrolling fixes.
- alpha.7: install/start/update YTM Beacon from the app, effects (cover glow, wavy seek bar, cover shapes, transitions), a sleep timer, sturdier queue reordering.
- alpha.6: readable colours on every theme, accent colour picker, Discord presence card, safer app updates.
- alpha.5: jump to the current lyric line, lyrics layout options, steadier buttons.
- alpha.4: word-by-word lyrics, faster lyrics, closer sync, a lighter Up next, and in-app updates.

### Status of this build

- **Linux server**: built and smoke-tested automatically (starts and serves the web app). Volume and
  media control are covered by automated tests and were checked against a live PipeWire system.
- **Discord status**: its engine follows YTM Beacon's and is covered by automated tests against a stand-in for Discord;
  it has not yet been run with the Discord app itself. The Windows connection to Discord has not been run on Windows.
- **Moving over**: the Linux installer was tested in a sandbox with stand-ins for systemd, Pocketfader and YTM Beacon,
  not yet on a real installation. The Windows installers (`install-exe.ps1`, `install.ps1`) have not been run on Windows.
- **Browser extension 2.0**: the toolbar button, the switches, the advert and album reporting and the goodbye on closing
  a tab were tested on stand-in pages and against YTM Starcast's own server code, not yet with the extension installed in a
  browser on the real YouTube Music. Chromium only; no Firefox version yet.
- **Windows server**: built and smoke-tested automatically (starts and serves); the Windows audio and
  media code has not been run on a real Windows machine since a refactor. Voicemeeter support is optional.
- **Android app**: built automatically; this version has not been run on a phone yet. It keeps Pocketfader's app ID and
  signing key, so it installs over the Pocketfader app.
- **Lyrics**: the sources were checked live from a Linux machine for a few songs (including Japanese and
  Korean); they are third-party services and may change or have nothing for a song.
- **Desktop window**: the Linux (GTK) window was rendered and its controller tested, but not driven through every
  button on many desktops; the Windows (Tk) window and its start-at-login entry have not been run on Windows.
- **Equalizer and output switching**: verified against an isolated PipeWire instance (measured gain per band),
  not yet across many hardware setups.
- **Playlist editing**: the web app's editing screens were checked against a stand-in for the extension; the
  YouTube Music requests themselves have not yet been tried on a signed-in account.
- **Cloudflare setup**: the guide has not been run end to end against a real Cloudflare account.
- **macOS**: not supported yet.

QR pairing, push updates and a computer-statistics view are planned; see the roadmap in the repository.
