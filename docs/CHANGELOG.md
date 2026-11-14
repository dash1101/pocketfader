# Changelog

What changed in each version of YTM Starcast, newest first. Versions are named `0.2.1-alpha`, `0.2.2-alpha` and so on;
before that they were `0.2.0-alpha.1` to `0.2.0-alpha.10`.

## 0.2.5-alpha

- **The web app looks like Refine:** its colours now come from the cover of what plays, the same tones Refine gives
  YouTube Music (the new Album colours theme, now the default; the other themes are still in Settings → Appearance).
  The tabs sit in one pill bar beside YTM Starcast's name, with the chosen one tonal, as do the song playing in the
  queue and the chosen chips. The play button and switches take Refine's shapes.
- **Download in YouTube Music's song menu (Refine):** the ⋮ menu's Download opens to two choices: YouTube Music
  Premium's own download, or **Save to this computer**, which saves the song as an audio file, with its details and
  cover, in your Music folder (Music › YTM Starcast). Saving uses yt-dlp on the computer (and ffmpeg, when there, for
  the details and cover).
- **No more stutter when a song changes on the player page:** the Halo visualizer behind the cover is drawn far more
  cheaply, and looks the same.
- With the wavy bar off, the song's progress bar takes the album's colour instead of YouTube Music's red.
- A tab beside the player that YouTube Music can't open for a song (Comments, when they are turned off) is crossed out.
- Up next: every song in view glides in, one after another, when the tab or the player page opens (it used to be only
  the first twelve).
- YTM Starcast's logo no longer runs into the search box in a narrow window: only its tile shows there.

## 0.2.4-alpha

- **Discord status for music playing on the phone:** when the Android app plays music on the phone itself, your
  computer shows it as your Discord status, through the Discord app on the computer, just as it does for YouTube Music
  in the browser. It works wherever the phone can reach the computer (at home or over Tailscale), and the phone needs no
  Discord app. Switch it in the Android app: Settings → Discord status → Show what this phone plays (on by default).
  Whichever started playing last, the phone or the computer, is the one shown.
- **Privacy and terms:** [Privacy](https://github.com/dash1101/ytm-starcast/blob/main/PRIVACY.md) lists everything YTM
  Starcast keeps and what it sends where; [Terms of use](https://github.com/dash1101/ytm-starcast/blob/main/TERMS.md)
  covers the rest.
- The web app no longer tells Google or GitHub the address it was opened at when it loads covers and images.

## 0.2.3-alpha

- **The web app looks and works like the Android app:** Playing, Search and Settings. On phones the player is a page of
  its own, with a navigation bar (or the floating pill) and the mini player on every other page; on wide screens the
  player stays on the left with Queue, Lyrics, Search and Settings beside it. Search holds For you, Library and
  Favorites as tabs; Settings is a list of categories (the mixer is Sound & outputs).
- **The desktop window is redrawn in Material You** (on Windows): rounded cards, switches and buttons, dark or light as
  Windows is (it follows a change), a dark title bar, YTM Starcast's icon in the window and the taskbar, and sizes
  that follow the display's scaling. The Windows programs carry the icon too. On Linux the window follows the
  desktop's dark setting (KDE, GNOME and others).
- **Android downloads:** a Downloading card with the progress of everything (megabytes, speed, about how long is left)
  and of each song, and Cancel the rest; the notification says the same. The quality choices name their bitrates.
  Warnings when the space for automatic downloads (or the number of smart downloads) is more than the phone has room
  for, and the phone's free space shows by your downloads. Automatic downloads always leave 1 GB of the phone free.
- **Android:** Settings → Sound & outputs shows the phone itself while music plays on it: its volume, where the sound
  goes (speaker, headphones, Bluetooth, USB) and Android's output picker.
- The extension's toolbar popup no longer shows an empty update card.

## 0.2.2-alpha

- The Android app's notification shows the song again when music plays on the phone (with its cover and progress, on
  the lock screen too): the phone's player never handed its session to the notification.
- The now-playing card stays at the bottom of every page on phones (the pages make room for it); tap it for the player.
- Play on a playlist, album, offline playlist or your downloads takes you to the player.
- Download quality: Best (about 1.2 MB a minute), Standard or Data saver (about 0.4 MB a minute), in Settings → Downloads
  & offline. Space estimates now go by each song's length and the quality (and by your own downloads, once there are a
  few), and say how many songs fit.
- Smart downloads can **Fill the space**: as many songs as fit in the space you set (most played and quick picks first,
  then liked songs, what you play most and your playlists).
- Playlists and albums open at once when you've opened them before, and the first page shows while the rest loads.
- The Comments tab only shows for songs that have comments.
- A new battery in full screen (on its side, filled to the charge, with a bolt while charging).
- Settings: Library & offline is part of Downloads & offline now.

## 0.2.1-alpha

- New version names: `0.2.1-alpha`, `0.2.2-alpha` and so on, instead of ever more numbers after "alpha".
- The Android app finds new versions on GitHub itself and downloads them from there (checked against the release's
  checksums), so updating the app no longer goes through the computer. When GitHub can't be reached from the phone, it
  still asks the computer.
- The Android app has a **Comments** tab beside Up next and Lyrics: the comments of the song or video that's playing,
  with replies.
- **Offline playlists** on the phone: "Download all" on a playlist or album keeps it together, in its order, and you can
  make your own from downloaded songs (⋮ → Add to an offline playlist). Every song is still in Downloaded.
- Automatic downloads can keep up to 1000 songs and use up to 100 GB (or any amount with Custom).
- Full screen can show the time and the battery in the app's top bar.
- Songs played on the phone show their covers sharp, in the player, the notification and on the lock screen.
- Music on the phone pauses when headphones, an AUX cable, or a Bluetooth or USB speaker disconnect (a setting, on by
  default).
- The volume sliders follow the phone's volume buttons at once, and glide.
- On tablets, the cover's glow no longer stops at a hard edge beside the tabs.
- The browser extension (2.4.0) updates itself: YTM Starcast copies each new version into the extension's folder, and the
  extension reloads into it (straight away with no YouTube Music tab open, otherwise from **Update now** in its toolbar
  button). This first time, reload it once by hand on the browser's extensions page.
- Release notes show only what's new. Earlier versions are in the changelog.

## 0.2.0-alpha.10

- Windows: YTM Starcast no longer opens a console window when it starts at login, and nothing flashes up every few
  seconds while the desktop app is open (the tailscale and other commands it runs stay hidden). Its messages go to
  `server.log` in `%APPDATA%\ytm-starcast`; the desktop app shows that log. Typed in a terminal, `ytm-starcast.exe
  --version` still answers there.
- Windows: the desktop app's "Start when I log in" starts YTM Starcast directly, without a PowerShell window.
- New installer options: `-Port` (Windows) and `--port=` (Linux) for another port than 8777, and `-NoDesktopApp`
  (Windows) and `--no-desktop-app` (Linux) to leave out the desktop app. Updates keep both.
- Settings → Open on another device: a QR code to scan with your phone, and the addresses YTM Starcast can be opened at.
  Apps can get the same from the new `GET /api/connect`.
- The desktop app finds YTM Starcast on the port it was installed with, and lists the addresses it really listens on.

## 0.2.0-alpha.9

- Refine's All settings: the Tab key stays inside it while it's open, and closing it puts you back where you opened it.
- The "something you might like" button says what it plays without play counts ("Playing Song · Artist").

## 0.2.0-alpha.8

- Fixed: when the YouTube Music tab the app followed went away without saying so (the browser crashed or was closed
  hard, or the tab was reloaded and its goodbye got lost), a paused YouTube Music tab still open didn't take over, and
  the remote's buttons did nothing until something changed in that tab. It takes over within a few seconds now.

## 0.2.0-alpha.7

- The Android app and the web app follow the playback speed too: at 0.8x a 3:00 song shows 3:45 in the player, the
  mini player and the home-screen widget, as it does in YouTube Music and on Discord. The phone's lock screen moves its
  progress at the speed the song plays.
- Album and playlist pages' big play button takes the album's colours and shape (with Material You page parts on).
- If an older, greyed-out YTM Starcast stays on the browser's extensions page after reloading, the setup steps in the
  apps now say to remove it.

## 0.2.0-alpha.6

Fixes from checking Refine on YouTube Music itself:
- The tabs beside the player (Up next, Lyrics, Comments, Related) disappeared with "Material You page parts" on. They
  show again, as a pill bar.
- The Save button above the queue takes the Material You look too.
- The extension connects without asking: it no longer needs the browser's "access other apps and services on this
  device" permission for YouTube Music (new Brave and Chrome setups asked for it, and nothing worked until it was given).
  After updating, reload YTM Starcast once on the browser's extensions page: it gets a fixed ID, and the browser may keep
  a greyed-out older YTM Starcast entry beside it, which you can remove.
- Lyrics that aren't time-synced show their blank lines as space, not as ♪ ♪ ♪.
- Word-by-word lyrics from NetEase no longer run some words together ("howto", "I'mgoing").
- The player bar's times stay an advert's own while one plays.
- The Pulse and Bars visualizers follow the beat on loud songs too (they used to sit near full).
- Lyrics, Make a playlist and Refine line up with YouTube Music's own entries in the sidebar.

## 0.2.0-alpha.5

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

## 0.2.0-alpha.4

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

## 0.2.0-alpha.3

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

## 0.2.0-alpha.2

**Adding the browser extension, step by step**
- Settings → **Browser extension** (in the web app, the Android app and the desktop window) shows whether the extension
  is connected and walks through adding it: open the browser's extensions page, turn on Developer mode, Load unpacked,
  open YouTube Music. Buttons open the extensions page and the extension's folder on the computer, and the page turns
  green by itself once the extension connects.
- Until an extension has connected, Home and Settings point to these steps.
- The installer copies the extension to a folder that never moves and prints the same steps; on Linux it offers to open
  the extensions page and the folder right away.
- Updates refresh that folder. When the browser still runs an older copy, the apps say to reload it.

## 0.2.0-alpha.1

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

## Earlier (released as Pocketfader)

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
