# Pocketfader

**Your computer's volume and music, in your pocket.**

Open a web page on your phone and get a proper remote for the computer that's playing your music:
a master volume fader, now-playing with cover art, play/pause/skip/seek, and — the favourite
feature — a **full YouTube Music remote** (search, queue, play next, mixes, playlists, likes) that
drives YouTube Music running in the browser on your computer.

- Works with the **normal system volume** (PipeWire / PulseAudio on Linux, the Windows volume on Windows). No special audio software.
- Phone side is just a web page — **any phone, tablet or laptop with a browser**. Add it to the home screen and it behaves like an app.
- Also controls **any media player** on the computer (Spotify, VLC, Firefox, phone sessions via KDE Connect…) through MPRIS on Linux.
- Private by design: it only listens where you tell it to, and exposed modes are protected by an access token.
- Zero dependencies on Linux — just Python's standard library.

> Status: **v0.1** — the Linux port of an earlier Windows-only project. See [What's tested](#whats-tested).

## Download

Ready-made builds are on the repository's **Releases** page: a Linux binary, a Windows `.zip`,
an **Android app**, and a Python package. The Android app is optional — any phone with a browser works
as the remote — but adds lock-screen controls and lets the phone's volume buttons control the computer.

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
| **Home Wi-Fi** | Simple, at home only | Install with `./install.sh --lan` (or run `pocketfader --lan`). It also listens on your home-network address and **requires an access token**. Open the full link it prints (it includes `?token=…`) once per device. |

Then use your browser's **Add to Home Screen** (Android Chrome: ⋮ → *Add to Home screen* / *Install app*;
iPhone Safari: Share → *Add to Home Screen*).

> Tip: with Tailscale you can get real HTTPS (and the full "installed app" experience) via
> `tailscale serve --bg 8777`; then open the `https://….ts.net` address instead.

## The YouTube Music remote

1. In Brave or Chrome open `brave://extensions` (or `chrome://extensions`), turn on **Developer mode**, click **Load unpacked** and pick the [`extension/`](extension/) folder.
2. Keep a **music.youtube.com** tab open (pinning it is ideal).
3. Pocketfader's Settings → Status shows **YT Music bridge: Connected**.

The extension only talks to Pocketfader on the same computer (`127.0.0.1`); it opens no network port of its own.
When the extension isn't connected, the app falls back to simple play/pause/next/seek for whatever
media player *is* running.

## Options

```text
pocketfader [--port 8777] [--lan] [--bind ADDR] [--token]
            [--audio auto|pulse|windows|voicemeeter|none]
            [--media auto|mpris|gsmtc|none] [--allow-host NAME] [--dry-run]
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

[Architecture](docs/ARCHITECTURE.md) · [HTTP API](docs/API.md) · [Roadmap (native Android app, system stats)](docs/ROADMAP.md) · [Android plan](docs/ANDROID_PLAN.md) · [Voicemeeter](docs/VOICEMEETER.md) · [Android app (WebView edition)](android/legacy-webview/README.md)

MIT licensed.
