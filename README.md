# YTM Starcast

**Your YouTube Music companion: a remote for your phone, your music as your Discord status, and a better YouTube Music.**

YTM Starcast runs on the computer that plays your music. It has four parts, and each one has its own switch:

| Part | What it does |
|---|---|
| **YouTube Music remote** | Control YouTube Music in your computer's browser from your phone, a tablet or another computer: search, the queue, play next, mixes, your library and playlists, artist pages, likes and live lyrics. |
| **Discord status** | Shows the song you're playing in YouTube Music on your Discord profile ("Listening to …"). This used to be the separate YTM Beacon app; it is built in now. See [Discord status](docs/DISCORD.md). |
| **Refine** | Improvements inside YouTube Music itself: a ✦ button in its top bar with a Material 3 Expressive look and animations, a richer speed menu with a clear slowed sound, word-by-word lyrics in YouTube Music's Lyrics tab (or a window of their own), the computer's sound and a playlist maker. See [Refine](docs/REFINE.md). |
| **Computer audio** | The computer's volume, output devices and equalizer from your phone. |

Switch parts on or off in **Settings → Parts** in the web app, the Android app and the desktop window. The browser
extension's toolbar button and Refine's ✦ menu have the same switches for the remote, Discord status and Refine. With
the remote or computer audio switched off, the apps hide their controls.

- **Queue control for YouTube Music**: reorder by dragging, jump to any track, shuffle, loop modes and an autoplay switch, with autoplay suggestions shown separately (and labelled when they won't play, e.g. while looping).
- **Live lyrics** for what's playing: lines that follow the song (word by word where a source has it), from several sources (KuGou, LRCLIB, NetEase, YouTube Music) you can switch between, with romanization for Korean, Japanese kana, Cyrillic and Greek and translations where a source has them.
- **Your music on Discord**, with private mode, a pause timeout, words that keep a song off Discord, and "hide for an hour" from your phone.
- **A desktop window** for the server (GTK on Linux, Tk on Windows): start or stop it, start at login, switch parts, and copy the addresses to open on your phone.
- **Switch the audio output** from your phone and set each device's level *before* switching to it.
- **Built-in equalizer** (Linux/PipeWire): bass / mid / treble with presets; no extra software, off until you turn it on.
- Works with the **normal system volume** (PipeWire / PulseAudio on Linux, the Windows volume on Windows). No special audio software.
- The phone side is just a web page: **any phone, tablet or laptop with a browser**. Add it to the home screen and it behaves like an app. There is also a native [Android app](android/README.md).
- Also controls **any media player** on the computer (Spotify, VLC, Firefox, phone sessions via KDE Connect…) through MPRIS on Linux.
- Private by design: it only listens where you tell it to, and exposed modes are protected by an access token.
- Zero dependencies on Linux: just Python's standard library.

> Status: **0.2, alpha**. Pre-releases only. See [What's tested](#whats-tested).

## Coming from Pocketfader or YTM Beacon?

YTM Starcast is Pocketfader's new name, and it now does YTM Beacon's job too.

> **YTM Starcast needs a Chromium-based browser (Chrome, Brave, Edge…) on a Linux or Windows computer.** YTM Beacon also
> works in Firefox and on macOS; there, keep using YTM Beacon for now. On Linux or Windows with YTM Beacon in Firefox,
> install YTM Starcast with `--keep-ytm-beacon` (Linux) or `-KeepYtmBeacon` (Windows), or Discord status stops.

**Run YTM Starcast's installer once** (see [Install](#install)). It:

- **replaces Pocketfader**: stops it, removes its start-at-login entry and its program, and keeps its options (such as
  `--lan`). Your settings, access token and paired phones carry over: when YTM Starcast first starts, it moves the settings
  folder `~/.config/pocketfader` to `~/.config/ytm-starcast` (on Windows `%APPDATA%\pocketfader` to `%APPDATA%\ytm-starcast`).
  On Linux it also keeps whether Pocketfader started at login. On Windows, YTM Starcast always starts at logon (turn the
  **YTM Starcast** task off in Task Scheduler to stop that), and if Pocketfader ran from the folder its zip was extracted
  to, that folder stays: delete it by hand;
- **removes the YTM Beacon app** with Beacon's own uninstaller. Beacon's settings file stays where it is, and YTM Starcast takes
  over your Discord settings from it when it first starts. To keep Beacon installed, use `--keep-ytm-beacon` (Linux) or
  `-KeepYtmBeacon` (Windows); while it runs, YTM Starcast leaves Discord to it, and **Settings → Discord status → Move over**
  finishes the move later.

Then, by hand:

1. In your browser, **remove the YTM Beacon extension**, and swap the Pocketfader extension for YTM Starcast's (see
   [The browser extension](#the-browser-extension)). Keep only one copy of YTM Starcast's extension.
2. **Update the Android app.** It is the same app under its new name, so your settings, library, downloads and widgets
   stay. Once the computer runs YTM Starcast, the app offers the update in Settings → Updates; or install `ytm-starcast-android.apk`
   over the old app.

Use the installer for this move. Pocketfader's own update check can't find YTM Starcast: its Updates row reports
"GitHub answered 301" (or no update at all), and the Android app offers nothing until the computer runs YTM Starcast.

## Download

Ready-made builds are on the repository's **Releases** page:

| File | What it is |
|---|---|
| `ytm-starcast-v…-windows-x64.zip` | The Windows program (`ytm-starcast.exe`, the desktop window `ytm-starcast-gui.exe` and `install-exe.ps1`). |
| `ytm-starcast-v…-linux-x86_64.tar.gz` | A single Linux program, to run without installing. |
| `ytm_starcast-…-py3-none-any.whl` | The Python package, for any system with Python 3.9+. |
| `ytm-starcast-extension.zip` | The browser extension, to load unpacked. |
| `ytm-starcast-android.apk` | The Android app (Android 5.0+). `ytm-starcast-android-unshrunk.apk` is the same app, in case the first one misbehaves on your phone. |
| `SHA256SUMS.txt` | Checksums of everything above (`sha256sum -c SHA256SUMS.txt`). |

The Android app is optional: any phone with a browser works as the remote. The app is native, with lock-screen controls,
a queue and search screen, output and equalizer controls, home-screen widgets and playing on the phone itself, and it lets
the phone's volume buttons control the computer.

## Install

### Linux

```bash
git clone https://github.com/dash1101/ytm-starcast.git     # or download and extract the source code from a release
cd ytm-starcast
./install.sh
```

That installs YTM Starcast in your home folder (`~/.local/share/ytm-starcast`, with the commands `ytm-starcast` and `ytm-starcast-gui` in
`~/.local/bin` and an entry in your application menu), sets it up as a background service that starts when you log in
(`ytm-starcast.service`; no root needed), and prints the address to open on your phone. To see that address again:
`journalctl --user -u ytm-starcast -n 20`.

It needs `python3` (3.9+, with `venv`) and `pactl` (package `pulseaudio-utils`, works with PipeWire). For control of
other media players you also want `busctl` (part of systemd, already there on most distros). The installer also puts a
current `yt-dlp` in YTM Starcast's own folder, which lets the computer fetch songs for the phone.

| Option | Does |
|---|---|
| `./install.sh --lan` | Also listen on your home network (turns on the access token). |
| `./install.sh --token --allow-host=music.example.com` | For a reverse proxy or a [Cloudflare Tunnel](docs/CLOUDFLARE.md). |
| `./install.sh --no-systemd` | Install only; start it yourself with `ytm-starcast`. |
| `./install.sh --keep-ytm-beacon` | Leave the separate YTM Beacon app installed. |
| `./install.sh --uninstall` | Remove YTM Starcast. Your settings are kept in `~/.config/ytm-starcast`. |

Prefer not to install anything? `python3 -m starcast` in the source folder runs it in the foreground, and so does
`./ytm-starcast` from the Linux download.

### Windows

1. Download `ytm-starcast-v…-windows-x64.zip` from the Releases page and extract it.
2. In the extracted folder, right-click `install-exe.ps1` → **Run with PowerShell** (or run
   `powershell -ExecutionPolicy Bypass -File .\install-exe.ps1` in that folder).

It copies YTM Starcast to `%LOCALAPPDATA%\Programs\YTM Starcast`, adds **YTM Starcast** to the Start menu, and starts it now and every time
you log in. No admin rights needed. Options: `-Lan` (also listen on your home network, with an access token),
`-Audio voicemeeter` (the optional [Voicemeeter](docs/VOICEMEETER.md) extras), `-KeepYtmBeacon`, and `-Uninstall`
(your settings in `%APPDATA%\ytm-starcast` are kept).

With Python installed you can instead install from the source: `.\packaging\install.ps1` (same options). Windows
support is experimental (see [What's tested](#whats-tested)).

### macOS

Not tested yet, and there is no installer here (see the [roadmap](docs/ROADMAP.md)).
[Orbit](https://github.com/dash1101/orbit) can set it up on a Mac as an experiment: the YouTube Music remote and the
Discord status are meant to work there, the computer's volume and other players are not available.

## Connect your phone

Pick **one**:

| | Best for | How |
|---|---|---|
| **Tailscale** (recommended) | Works anywhere, safest | Install [Tailscale](https://tailscale.com) on the computer and the phone, sign in to the same account. Open `http://<computer-name>:8777` on the phone. YTM Starcast detects Tailscale automatically. |
| **Cloudflare Tunnel** | Anywhere, over HTTPS, with a login in front | Step-by-step in the [Cloudflare guide](docs/CLOUDFLARE.md). |
| **Home Wi-Fi** | Simple, at home only | Install with `./install.sh --lan` (or run `ytm-starcast --lan`). It also listens on your home-network address and **requires an access token**. Open the full link it prints (it includes `?token=…`) once per device. |

Then use your browser's **Add to Home Screen** (Android Chrome: ⋮ → *Add to Home screen* / *Install app*;
iPhone Safari: Share → *Add to Home Screen*).

> Tip: with Tailscale you can get real HTTPS (and the full "installed app" experience) via
> `tailscale serve --bg 8777`; then open the `https://….ts.net` address instead.

## The browser extension

The YouTube Music remote, Discord status and Refine all need YTM Starcast's extension in the browser that plays YouTube
Music. It works in Chromium-based browsers (Chrome, Brave, Edge…); Firefox isn't supported yet.

YTM Starcast keeps a copy of the extension in a folder that never moves (`~/.local/share/ytm-starcast/extension` on
Linux, `%LOCALAPPDATA%\ytm-starcast\extension` on Windows). Browsers only take an extension like this one by hand, once:

1. Open the browser's extensions page (`chrome://extensions`, `brave://extensions` or `edge://extensions`).
2. Turn on **Developer mode** (top-right corner).
3. Click **Load unpacked** and choose that folder (or drag the folder onto the page).
4. Open **music.youtube.com** and keep that tab open (pinning it is ideal).

The installer prints these steps, and **Settings → Browser extension** in the web app, the Android app and the desktop
window walks through them with buttons that open the extensions page and the folder on the computer. It turns green by
itself once the extension connects. (Running from the source, the [`extension/`](extension/) folder works too, and
`ytm-starcast-extension.zip` on the Releases page holds the same files.)

The extension's **toolbar button** shows whether YTM Starcast is running, the switches for the remote, Discord status and
Refine, and a link to open YTM Starcast. It reaches YTM Starcast through an open YouTube Music tab: if it asks you to open YouTube
Music, reload the tab or allow access to music.youtube.com, do that.

**Updates** replace the folder's files by themselves. The browser picks them up when you click **Reload** on the
extension's card (Settings → Browser extension says when that's needed), then reload the YouTube Music tab.

The extension only talks to YTM Starcast on the same computer (`127.0.0.1:8777`); it opens no network port of its own. When it
isn't connected, the apps fall back to simple play/pause/next/seek for whatever media player *is* running. More in the
[extension's README](extension/README.md).

## Discord status

With the Discord desktop app open on the same computer, YTM Starcast shows what you play in YouTube Music on your Discord
profile. Control it from the web app or the Android app (Settings → Discord status, and a home-screen widget): show or
hide it, hide it for a while, private mode, show while paused, progress bar, Listen button, album art, the name next to
your username, and words that keep a song off Discord. See [Discord status](docs/DISCORD.md).

## Refine

Refine improves YouTube Music itself: a ✦ button in its top bar (with a Discord button beside it, entries in the
sidebar and an All settings dialog) switches on a Material 3 Expressive look with colours from the album art, a wavy
progress bar, animations and experimental cover visualizers, a richer speed menu (any speed that stays from song to song,
pitch, a clear slowed sound that keeps the pitch without smearing, a "room" reverb, times that follow the speed), live
lyrics word by word in YouTube Music's own Lyrics tab or in a window you can move, resize, pin and keep above other
windows, the computer's volume, output and equalizer, a random-song button, and a playlist maker that builds on YouTube
Music's own radios. Each piece has its own switch. See [Refine](docs/REFINE.md).

## Use it on the phone itself

The Android app isn't only a remote. Tap the speaker/phone button at the top to **play on this phone**: search YouTube Music
and play songs directly (no computer needed), play the music files already on the phone, and **download** songs with their
cover and lyrics to listen with no connection. "Continue this song on the phone" (and back) moves what's playing between the two.
With no computer at all, choose "Use YTM Starcast on this phone only" on the first screen. When the phone can't get a song
itself, a connected computer fetches it with yt-dlp (installed by `install.sh`). Your playlists, likes and recommendations come
from your YouTube Music account, so they need the computer with the extension. More in the [Android app's README](android/README.md).

## Updates

YTM Starcast looks for new releases on GitHub. The **computer** does the asking, so a phone never needs a GitHub login:

- the Android app shows a banner (and Settings → Updates) when a newer version exists and installs it through the computer;
- the desktop window has an **Updates** row with **Update now**; or run `ytm-starcast --check-update` and `ytm-starcast --update`
  (pip and `install.sh` installs; the ready-made programs link you to the download page).

Set `STARCAST_REPO=owner/name` to follow a different repository; for a private one, sign in on the computer with
`gh auth login` or set `STARCAST_GITHUB_TOKEN` (or `GITHUB_TOKEN`). In-place Android updates need the app to be signed with
the same key every time, see [docs/RELEASING.md](docs/RELEASING.md).

## The desktop window

Run **YTM Starcast** from your application menu (or `ytm-starcast-gui`, or `ytm-starcast --gui`). It shows whether the
server is running and lets you:

- turn it on or off,
- start it when you log in,
- allow your home Wi-Fi (turns on the access token) or set a Cloudflare hostname,
- switch YTM Starcast's parts on or off,
- copy the addresses to open on your phone, and see what's playing and which sound output is in use.

On Linux it uses GTK (the `python3-gi` package, installed on most desktops); elsewhere it uses Python's built-in
Tk. If neither is available you can still run `ytm-starcast` from a terminal and open the address it prints.

## Options

```text
ytm-starcast [--port 8777] [--lan] [--bind ADDR] [--token]
       [--audio auto|pulse|windows|voicemeeter|none] [--eq auto|builtin|none]
       [--media auto|mpris|gsmtc|none] [--allow-host NAME] [--dry-run] [--gui]
       [--check-update] [--update] [--version]
```

`--dry-run` reads real state but never changes volume, mute or playback, never contacts Discord, and keeps Discord
status settings in memory only: handy for trying things safely. Settings and the access token live in
`~/.config/ytm-starcast/` (`%APPDATA%\ytm-starcast\` on Windows).

## Security

- **Default**: listens on `localhost` and your Tailscale address only. Your tailnet is the lock.
- **`--lan` / `--bind`**: also listens on that address and *requires* the access token (generated once, stored with `0600` permissions). Never expose it to the public internet.
- Every request's `Host` header is checked (anti DNS-rebinding) and every `POST`'s `Origin` (anti cross-site requests), so a web page you visit can't silently drive your speakers. The only other site allowed to talk to YTM Starcast is music.youtube.com, for the extension.
- It can only set the volume/mute of the *current default output*, switch the output when you ask it to, and send transport commands to players.
- **Discord status** talks only to the Discord desktop app on the same computer, through Discord's own local connection; YTM Starcast opens no port for it. The apps can change only the settings listed in [Discord status](docs/DISCORD.md).

## What's tested

- ✅ Unit tests (`python -m unittest discover -s tests -t .`): volume and player control, the HTTP API, parts, token auth, Host/Origin protections, the Discord status engine (against a stand-in for Discord), moving over from YTM Beacon, and the Linux installer (in a sandbox with stand-in commands). They use fakes and cannot touch real audio, Discord or your settings.
- ✅ Live on Linux/PipeWire in `--dry-run` (earlier versions): real volume and player state are read correctly, the UI renders with no console errors, and no changes are made.
- ⚠️ Discord status has not yet been run against the Discord app itself; its engine follows YTM Beacon's, and its connection code was tested against a stand-in for Discord.
- ⚠️ Moving over from Pocketfader and YTM Beacon was tested with stand-ins, not yet on a real installation. The Windows installers have not been run on Windows.
- ⚠️ The new switches, the Discord status card and the extension's toolbar button were checked with scripted tests and stand-in pages, not yet on screen with the extension installed in a browser.
- ⚠️ A real volume/transport *write* is first exercised when you move a slider in the app. The commands it issues are the plain `pactl set-sink-volume @DEFAULT_SINK@ N%` / `busctl … PlayPause` calls covered by the tests.
- ⚠️ The Windows backends (and Voicemeeter) were refactored onto the new interface but not re-run since.
- ⚠️ LAN mode was verified on loopback; the private-address binding needs a machine that has one.

## More

[Discord status](docs/DISCORD.md) · [Refine: a better YouTube Music](docs/REFINE.md) · [Browser extension](extension/README.md) · [Android app](android/README.md) · [Cloudflare guide](docs/CLOUDFLARE.md) · [Architecture](docs/ARCHITECTURE.md) · [Design language](docs/DESIGN.md) · [HTTP API](docs/API.md) · [Releasing](docs/RELEASING.md) · [Roadmap](docs/ROADMAP.md) · [Android plan](docs/ANDROID_PLAN.md) · [Voicemeeter](docs/VOICEMEETER.md)

MIT licensed, except the Android app (`android/`), which is **GPL-3.0-or-later** because it includes
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) to play YouTube Music on the phone itself.
