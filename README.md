# Pocketfader

**Your computer's volume and music, in your pocket.**

Open a web page on your phone and get a proper remote for the computer that's playing your music:
a master volume fader, now-playing with cover art, play/pause/skip/seek, and — the favourite
feature — a **full YouTube Music remote** (search, queue, play next, mixes, playlists, likes) that
drives YouTube Music running in the browser on your computer.

- **Queue control for YouTube Music**: reorder by dragging, jump to any track, shuffle, loop modes and an autoplay switch, with autoplay suggestions shown separately (and labelled when they won't play, e.g. while looping).
- **Live lyrics** for what's playing: lines that follow the song (word by word where a source has it), from several sources (KuGou, LRCLIB, NetEase, YouTube Music) you can switch between, with romanization for Korean, Japanese kana, Cyrillic and Greek and translations where a source has them.
- **A desktop window** for the server (GTK on Linux, Tk on Windows): start or stop it, start at login, and copy the addresses to open on your phone.
- **Switch the audio output** from your phone and set each device's level *before* switching to it.
- **Built-in equalizer** (Linux/PipeWire): bass / mid / treble with presets; no extra software, off until you turn it on.
- Works with the **normal system volume** (PipeWire / PulseAudio on Linux, the Windows volume on Windows). No special audio software.
- Phone side is just a web page — **any phone, tablet or laptop with a browser**. Add it to the home screen and it behaves like an app.
- Also controls **any media player** on the computer (Spotify, VLC, Firefox, phone sessions via KDE Connect…) through MPRIS on Linux.
- Private by design: it only listens where you tell it to, and exposed modes are protected by an access token.
- Zero dependencies on Linux — just Python's standard library.

> Status: **v0.1** — the Linux port of an earlier Windows-only project. See [What's tested](#whats-tested).

## Download

Ready-made builds are on the repository's **Releases** page: a Linux binary, a Windows `.zip`,
an **Android app** (Android 5.0+), and a Python package. The Android app is optional — any phone with a
browser works as the remote — but it is a native app with lock-screen controls, a queue and search screen,
output and equalizer controls, and it lets the phone's volume buttons control the computer.

## Install (host computer, from source)

### Linux

```bash
git clone https://github.com/<you>/pocketfader.git
cd pocketfader
./install.sh
```

That sets it up as a background service that starts when you log in (no root needed) and prints the
address to open on your phone. Needs `python3` (3.9+, with `venv`) and `pactl` (package
`pulseaudio-utils`, works with PipeWire). For generic player control you also want `busctl`
(part of systemd, already there on most distros).

Prefer not to install a service? `python3 -m pocketfader` runs it in the foreground.
Remove it with `./install.sh --uninstall`.

### Windows

```powershell
git clone https://github.com/<you>/pocketfader.git
cd pocketfader
.\packaging\install.ps1
```

Uses the Windows volume and media session. [Voicemeeter](docs/VOICEMEETER.md) is an **optional** extra
(`-Audio voicemeeter`) that adds EQ and a level meter. macOS is not supported yet (see the [roadmap](docs/ROADMAP.md)).

## Connect your phone

Pick **one**:

| | Best for | How |
|---|---|---|
| **Tailscale** (recommended) | Works anywhere, safest | Install [Tailscale](https://tailscale.com) on the computer and the phone, sign in to the same account. Open `http://<computer-name>:8777` on the phone. Pocketfader detects Tailscale automatically. |
| **Cloudflare Tunnel** | Anywhere, over HTTPS, with a login in front | Step-by-step in the [Cloudflare guide](docs/CLOUDFLARE.md). |
| **Home Wi-Fi** | Simple, at home only | Install with `./install.sh --lan` (or run `pocketfader --lan`). It also listens on your home-network address and **requires an access token**. Open the full link it prints (it includes `?token=…`) once per device. |

Then use your browser's **Add to Home Screen** (Android Chrome: ⋮ → *Add to Home screen* / *Install app*;
iPhone Safari: Share → *Add to Home Screen*).

> Tip: with Tailscale you can get real HTTPS (and the full "installed app" experience) via
> `tailscale serve --bg 8777`; then open the `https://….ts.net` address instead.

## Use it on the phone itself

The Android app isn't only a remote. Tap the speaker/phone button at the top to **play on this phone**: search YouTube Music
and play songs directly (no computer needed), play the music files already on the phone, and **download** songs with their
cover and lyrics to listen with no connection. "Continue this song on the phone" (and back) moves what's playing between the two.
With no computer at all, choose "Use Pocketfader on this phone only" on the first screen. When the phone can't get a song
itself, a connected computer fetches it with yt-dlp (installed by `install.sh`). Your playlists, likes and recommendations come
from your YouTube Music account, so they need the computer with the extension.

## The YouTube Music remote

1. In a Chromium-based browser (Chrome, Brave, Edge...) open its extensions page (for example `chrome://extensions`), turn on **Developer mode**, click **Load unpacked** and pick the [`extension/`](extension/) folder.
2. Keep a **music.youtube.com** tab open (pinning it is ideal).
3. Pocketfader's Settings → Status shows **YouTube Music: Connected**.

The same extension also improves YouTube Music itself (**Refine**): a ✦ button in its top bar switches on a Material 3
Expressive look with colours from the album art and a wavy progress bar, a richer speed menu (any speed, pitch, remembered
per song or playlist), live lyrics next to the player, a random-song button, and a playlist maker that builds on YouTube
Music's own radios. Each part has its own switch. See [docs/REFINE.md](docs/REFINE.md).

The extension only talks to Pocketfader on the same computer (`127.0.0.1`); it opens no network port of its own.
When the extension isn't connected, the app falls back to simple play/pause/next/seek for whatever
media player *is* running.

## Updates

Pocketfader looks for new releases on GitHub. The **computer** does the asking, so a phone never needs a GitHub login:

- the Android app shows a banner (and Settings → Updates) when a newer version exists and installs it through the computer;
- the desktop window has an **Updates** row with **Update now**; or run `pocketfader --check-update` and `pocketfader --update`
  (pip and `install.sh` installs; the packaged binaries link you to the download page).

For a private repository, sign in on the computer with `gh auth login` (or set `GITHUB_TOKEN`). Set `POCKETFADER_REPO=owner/name`
to follow a different repository. In-place Android updates need the app to be signed with the same key every time, see
[docs/RELEASING.md](docs/RELEASING.md).

## The desktop window

Run **Pocketfader** from your application menu (or `pocketfader-gui`, or `pocketfader --gui`). It shows whether the
server is running and lets you:

- turn it on or off,
- start it when you log in,
- allow your home Wi-Fi (turns on the access token) or set a Cloudflare hostname,
- copy the addresses to open on your phone, and see what's playing and which sound output is in use.

On Linux it uses GTK (the `python3-gi` package, installed on most desktops); elsewhere it uses Python's built-in
Tk. If neither is available you can still run `pocketfader` from a terminal and open the address it prints.

## Options

```text
pocketfader [--port 8777] [--lan] [--bind ADDR] [--token]
            [--audio auto|pulse|windows|voicemeeter|none]
            [--media auto|mpris|gsmtc|none] [--allow-host NAME] [--dry-run] [--gui]
```

`--dry-run` reads real state but never changes volume, mute or playback — handy for trying things
safely. Settings and the access token live in `~/.config/pocketfader/` (`%APPDATA%\pocketfader\` on Windows).

## Security

- **Default**: listens on `localhost` and your Tailscale address only. Your tailnet is the lock.
- **`--lan` / `--bind`**: also listens on that address and *requires* the access token (generated once, stored with `0600` permissions). Never expose it to the public internet.
- Every request's `Host` header is checked (anti DNS-rebinding) and every `POST`'s `Origin` (anti cross-site requests), so a web page you visit can't silently drive your speakers.
- It can only set the volume/mute of the *current default output* and send transport commands to players. It never switches outputs or moves streams.

## What's tested

- ✅ Unit tests (`python -m unittest discover -s tests -t .`): volume parsing/commands, MPRIS parsing/commands, the HTTP API, token auth, Host/Origin protections. They use fakes and cannot touch real audio.
- ✅ Live on Linux/PipeWire in `--dry-run`: real volume and player state are read correctly, the UI renders with no console errors, and no changes are made.
- ⚠️ A real volume/transport *write* is first exercised when you move a slider in the app. The commands it issues are the plain `pactl set-sink-volume @DEFAULT_SINK@ N%` / `busctl … PlayPause` calls covered by the tests.
- ⚠️ The Windows backends (and Voicemeeter) were refactored onto the new interface but not re-run since.
- ⚠️ LAN mode was verified on loopback; the private-address binding needs a machine that has one.

## More

[Cloudflare guide](docs/CLOUDFLARE.md) · [Discord presence](docs/BEACON.md) · [Refine: a better YouTube Music](docs/REFINE.md) · [Architecture](docs/ARCHITECTURE.md) · [Design language](docs/DESIGN.md) · [HTTP API](docs/API.md) · [Roadmap (native Android app, system stats)](docs/ROADMAP.md) · [Android plan](docs/ANDROID_PLAN.md) · [Voicemeeter](docs/VOICEMEETER.md) · [Android app](android/README.md)

MIT licensed, except the Android app (`android/`), which is **GPL-3.0-or-later** because it includes
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) to play YouTube Music on the phone itself.
