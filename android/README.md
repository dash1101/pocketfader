# Pocketfader for Android

A native Android app (Kotlin, Jetpack Compose, Material 3) that controls the computer running
Pocketfader: volume, output devices, equalizer, and the YouTube Music remote.

Requires **Android 5.0 (API 21) or newer**. On Android 12 and newer it follows your wallpaper colours
(Material You); on older versions it uses the Pocketfader violet theme.

## What's in the app

| Tab | What it does |
|---|---|
| **Playing** | Cover art, seek bar, previous / play-pause / next, like, shuffle, loop and autoplay, master volume. |
| **Queue** | Your queue and the autoplay suggestions, shown separately. Suggestions that won't play (because the queue is on loop, or autoplay is off) are dimmed and labelled. Tap a track to jump to it; the menu has play next, move up/down, and remove. |
| **Search** | Search YouTube Music; tap to play, **+** to add a song to the queue, shuffle-play for playlists. |
| **Mixer** | Master volume, every output device with its **own level** (set it before you switch to it), and the built-in equalizer with presets and bass / mid / high. |
| **Library** | The server's web app inside the app: Home shelves, your playlists, playlist editing. |

On tablets and in landscape the **Playing** view stays on the left and the other tabs sit on the right.

Outside the app, a **media notification** shows what's playing with previous / play-pause / next and
works on the lock screen and with Bluetooth headsets. While something is playing, the phone's
**hardware volume keys change the computer's volume**, even with the screen off.

The YouTube Music features (queue, search, like, shuffle, loop, autoplay) need the Pocketfader browser
extension on the computer. Without it the app still shows and controls whatever the computer's media
player is doing, plus volume, outputs and the equalizer.

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
The app sends them with every request. The **Library** tab loads the web app with the same token on its
first request and relies on the session cookie Access returns afterwards.

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
seconds in the background) and posts commands to it. A foreground service publishes a media session so
the system shows controls and routes the volume keys. There is no networking library: it uses
`HttpURLConnection`, which also lets it send the Cloudflare Access headers.
