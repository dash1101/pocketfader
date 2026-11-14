# YTM Starcast for Android

A native Android app (Kotlin, Jetpack Compose, Material 3) for the computer running YTM Starcast, your YouTube Music
companion: the YouTube Music remote, your Discord status, and the computer's volume, output devices and equalizer.

Requires **Android 5.0 (API 21) or newer**. On Android 12 and newer it follows your wallpaper colours
(Material You); on older versions it uses YTM Starcast's violet theme.

## What's in the app

| Tab | What it does |
|---|---|
| **Playing** | The player (cover, seek bar, shuffle / previous / play-pause / next / loop, like, volume) with **Up next** and **Lyrics** underneath. |
| **Search** | Search YouTube Music (suggestions as you type, recent searches), and your library in tabs: **For you** (your home shelves, or recommendations made on the phone), **Playlists** (play, shuffle, download, keep on the phone, and edit your own) and **This phone** (downloads and the phone's music files). The ⋮ menu on any song can play it, put it next, add it to your queue or a playlist, start a radio, or download it. |
| **Settings** | Split into pages: **Sound & outputs** (master volume, every output device with its **own level**, the built-in equalizer), Downloads & playing on this phone, **Library & offline** (sync, automatic downloads, listening history), Lyrics, Controls, Appearance (theme, accent colour, navigation style), Effects, Screen & always-on, Connection, **Parts**, **Discord status**, Updates, Performance and About. |

**Up next** shows what's already played (collapsed), the current track, what's coming, and YouTube Music's
autoplay suggestions. Suggestions that won't play (because the queue is looping or autoplay is off) are dimmed
and say why, and you can still add any of them to your queue with the **+** button. A line under the
shuffle / loop / autoplay chips spells out what is being shuffled or looped.

**Lyrics** follow the song line by line (tap a line to jump to it). Pick a source (LRCLIB, NetEase, YouTube
Music), look again with ↻, and switch **Romanize** or **Translation** on when a source has them.

On tablets and in landscape the player stays on the left; Queue, Lyrics and Search are tabs on the right.

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
| **Parts** | Switch YTM Starcast's parts on or off on the computer: the YouTube Music remote, Discord status, Refine in YouTube Music, and computer audio. Shown when the computer's YTM Starcast has these switches. |
| **Discord status** | Whether your music is showing on Discord, a switch to show or hide it, hide for a while, what the card shows, and words that keep a song off Discord. If the old YTM Beacon app is still on the computer, **Move over** brings its settings into YTM Starcast and removes it. |

The YouTube Music features (queue, search, library, lyrics from YouTube Music, like, shuffle, loop, autoplay) need the
YTM Starcast browser extension on the computer. Without it the app still shows and controls whatever the
computer's media player is doing, plus volume, outputs, the equalizer and lyrics from LRCLIB and NetEase.

### When a part is switched off

With the **YouTube Music remote** off on the computer, the Playing tab shows a short note with a button to turn it back on
(and the computer's volume, if computer audio is on), and the Search tab is hidden. Music playing on this phone is not
affected. With **computer audio** off, the computer's volume, output devices and equalizer are hidden, and the phone's
volume keys no longer change the computer's volume.

## Home-screen widgets

Long-press the home screen → Widgets → YTM Starcast:
- **Now playing** (4×2): cover, title, progress and where it's playing, with like, previous / play-pause / next and volume.
- **Now playing (slim)** (4×1): cover, title and controls in one row.
- **Cover** (2×2): the album art with play / pause.
- **Volume** (3×1): the volume of wherever music is playing (computer or phone), with mute.
- **Discord status** (4×1): whether your music is showing on Discord, a switch to show or hide it, and hide for an hour.
- **Up next** (4×2): the next four songs; tap one to play it.
- **Quick actions** (2×2): I'm feeling lucky, a 30-minute sleep timer, pause other players, and switch between phone and computer.

They have no background of their own (your launcher draws one) and follow the app (the computer's state comes in while the app or its notification is running).

## Playing on the phone

The button at the top switches between playing on the computer and on this phone. On the phone, YouTube Music songs stream
directly (through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)); if that fails, a connected computer
fetches the song instead. Downloads keep the audio, cover and lyrics in the app's own folder and play offline. Lyrics on the
phone come from the computer when it's there, else from LRCLIB.

### Your library without the computer

While YouTube Music is open on the computer, the app copies your **playlists** (every song), **liked songs** and your
YouTube Music **home page** to the phone (Settings → Library & offline; automatically every few hours, or **Sync now**).
When the computer isn't there, Search → Playlists shows that copy, and playlists open and play from it. Likes, and songs
added to your own playlists, are kept and sent to your account at the next sync.

**For you** is then worked out on the phone: songs you play a lot, played lately or liked are used as seeds, YouTube
Music's radio for each seed is fetched, and everything they suggest is ranked (songs several seeds lead to, and songs by
artists you listen to, rank higher; songs you just heard or keep skipping drop). The shelves are Quick picks, Listen again,
Your playlists, Mixed for you (a radio per favourite artist), Forgotten favorites and Discover. Autoplay on the phone
continues with the song's YouTube Music radio, filtered the same way. What you listen to (on the phone, and on the computer
while the app is connected) is remembered on the phone only; you can clear it in Settings.

**Automatic downloads** (Settings → Library & offline): liked songs, playlists you mark "Keep on this phone", and smart
downloads (your most played songs plus the current quick picks), within a space limit you choose, on Wi-Fi only if you like.
Songs you download yourself are never removed automatically. Downloads show progress in a notification.

**License:** because it includes NewPipe Extractor, the Android app is GPL-3.0-or-later (see `android/LICENSE`).

## Install

Download `ytm-starcast-android.apk` from the repository's **Releases** page, allow "install unknown
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
- Released APKs are all signed with the same key, so each one installs over the last. A build you make yourself
  without that key is signed with a throwaway debug key and can't be installed over a released one; uninstall first.
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
