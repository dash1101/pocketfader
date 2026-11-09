# Pocketfader for Android

A native Android app (Kotlin, Jetpack Compose, Material 3) that controls the computer running
Pocketfader: volume, output devices, equalizer, and the YouTube Music remote.

Requires **Android 5.0 (API 21) or newer**. On Android 12 and newer it follows your wallpaper colours
(Material You); on older versions it uses the Pocketfader violet theme.

## What's in the app

| Tab | What it does |
|---|---|
| **Playing** | The player (cover, seek bar, shuffle / previous / play-pause / next / loop, like, volume) with **Up next** and **Lyrics** underneath. |
| **Search** | Search YouTube Music, and your library in tabs: **For you** (your home shelves), **Playlists** (play, shuffle, download, and edit your own) and **On this phone** (downloads and the phone's music files). The ⋮ menu on any song can play it, put it next, add it to your queue or a playlist, start a radio, or download it. |
| **Settings** | Split into pages: **Sound & outputs** (master volume, every output device with its **own level**, the built-in equalizer), Lyrics, Controls, Appearance (theme, accent colour, navigation style), Effects, Screen & always-on, Connection, **Discord presence**, Updates, Performance and About. |

**Up next** shows what's already played (collapsed), the current track, what's coming, and YouTube Music's
autoplay suggestions. Suggestions that won't play (because the queue is looping or autoplay is off) are dimmed
and say why, and you can still add any of them to your queue with the **+** button. A line under the
shuffle / loop / autoplay chips spells out what is being shuffled or looped.

**Lyrics** follow the song line by line (tap a line to jump to it). Pick a source (LRCLIB, NetEase, YouTube
Music), look again with ↻, and switch **Romanize** or **Translation** on when a source has them.

On tablets and in landscape the player stays on the left; Queue, Lyrics, Search and Library are tabs on the right (Settings is the gear in the top bar).

Outside the app, a **media notification** shows what's playing with previous / play-pause / next and works on
the lock screen and with Bluetooth headsets. While something is playing, the phone's **hardware volume keys
change the computer's volume**, even with the screen off.

### Settings (the gear at the top right)

| Section | Options |
|---|---|
| **Connection** | The computer's address, whether it's reachable, whether the YouTube Music extension is connected; edit the connection. |
| **Appearance** | Theme (System, Light, Dark, true Black), Material You colours (Android 12+), which screen opens first. |
| **Screen & always-on** | Keep the screen on (off, while playing, always), dim when untouched, full screen, and **pixel shift**: every few minutes the whole screen glides a few pixels so static parts don't burn into OLED displays (interval and distance are adjustable; best with Always on and the Black theme). |
| **Controls** | Media notification on/off, volume buttons on/off and the step per press, haptic feedback. |
| **Lyrics** | Romanize and translations on by default, text size, preferred source. |
| **Performance** | Update speed (Fast / Normal / Battery saver) and reduce animations. |

The YouTube Music features (queue, search, library, lyrics from YouTube Music, like, shuffle, loop, autoplay) need the
Pocketfader browser extension on the computer. Without it the app still shows and controls whatever the
computer's media player is doing, plus volume, outputs, the equalizer and lyrics from LRCLIB and NetEase.

## Playing on the phone

The button at the top switches between playing on the computer and on this phone. On the phone, YouTube Music songs stream
directly (through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)); if that fails, a connected computer
fetches the song instead. Downloads keep the audio, cover and lyrics in the app's own folder and play offline. Lyrics on the
phone come from the computer when it's there, else from LRCLIB.

**License:** because it includes NewPipe Extractor, the Android app is GPL-3.0-or-later (see `android/LICENSE`).

## Install

Download `pocketfader-android-….apk` from the repository's **Releases** page, allow "install unknown
apps" for your browser or file manager, and open it.

On first launch enter the computer's address and tap **Test**, then **Save**:

- A Tailscale name or address: `my-computer.tailnet-name.ts.net:8777`
- A home-network address: `192.168.1.20:8777` (the server must be started with `--lan`)
- A Cloudflare hostname: `https://music.example.com` (see the [Cloudflare guide](../docs/CLOUDFLARE.md))

If the server asks for an access token, paste the **full link** it prints (it ends in `?token=…`) into
the address field and the token is filled in for you.

### Behind Cloudflare Access

Open **Cloudflare Access** in the connection dialog and enter a service token's Client ID and Secret.
The app sends them with every request, including the Library and Lyrics.

## Notes and limits

- Settings (address, token) are kept in the app's private storage, not in a hardware-backed keystore.
- Release builds are signed with a throwaway debug key, so a build from another machine can't be
  installed over this one; uninstall first.
- Android 5.0 and 6.0 phones may not trust some newer HTTPS certificate roots. If an `https://` address
  fails on such a device, use the Tailscale or home-network address instead.
- The server address uses plain HTTP on Tailscale and home networks. The traffic stays inside your
  tailnet or network; for access from anywhere, use the Cloudflare setup.

## Build it yourself

Needs JDK 17 and the Android SDK (Android Studio includes both).

```bash
cd android
gradle :app:assembleRelease      # or open this folder in Android Studio and press Run
# APK: app/build/outputs/apk/release/app-release.apk
```

## How it works

The app polls the server's [HTTP API](../docs/API.md) (about once a second while open, every few
seconds in the background; slower with Battery saver) and posts commands to it. A foreground service publishes a media session so
the system shows controls and routes the volume keys. There is no networking library: it uses
`HttpURLConnection`, which also lets it send the Cloudflare Access headers.
