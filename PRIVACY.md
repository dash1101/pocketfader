# Privacy

**Effective 2026-11-14**

This page describes what YTM Starcast keeps, where it keeps it, and what it sends to other services. It covers every
part: the program on your computer (with its desktop window), the web app, the browser extension and the Android app.

## The short version

- There is no YTM Starcast account, and the developers run no server for it. You never sign up or sign in to anything
  run by the developers.
- No analytics, no ads, no tracking and no crash reporting. Nothing is sent to the developers.
- YTM Starcast talks to your own computer and to the services you use it with: YouTube Music, Discord, lyrics services,
  GitHub (for updates) and, if you set them up, Tailscale or Cloudflare. Each one is described below.
- What it keeps stays on your own devices, in files and app storage you can clear.

## What stays on your devices

### On the computer

The settings folder is `~/.config/ytm-starcast` (Windows: `%APPDATA%\ytm-starcast`). It holds:

| File | What's in it |
|---|---|
| `config.json` | The access token. On Linux, only your user can read this file. |
| `prefs.json` | Your favourites, the switches for each part and a few other choices made in the apps. |
| `discord.json` | Your Discord status settings, including the words that keep a song off Discord. |
| `server.log` | The program's messages, on Windows and whenever the desktop window starts the program. (With the Linux background service, they go to the system journal instead.) When the access token is on, the start-up lines include the link with the token in it. |
| `ytm-layout.json` | A description of how the YouTube Music page is built, sent by the extension after the page loads so that a control it can't find can be looked into. It includes the address of the YouTube Music page you are on and the title of the song playing. Each page load replaces it. |
| `updates` | Downloaded updates. |

A few other small files there hold the port, the equalizer and similar settings.

Songs you save with **Save to this computer** (in YouTube Music's song menu, with Refine) go to `Music/YTM Starcast` in
your Music folder, as ordinary audio files with the song's details and cover. They stay until you delete them.

Songs the computer fetches for the phone are kept in `~/.cache/ytm-starcast/audio` (Windows: `.cache\ytm-starcast\audio`
in your user folder), up to 1 GiB; the songs used least recently are removed first. Lyrics are kept in memory only.

Uninstalling keeps the settings folder. Delete it, and the cache folder, to remove everything.

### On the phone (Android app)

In the app's private storage, which other apps can't read and which is removed when you uninstall the app:

- your computer's address and access token, and the Cloudflare Access id and secret if you entered them. They are
  stored as they are, not encrypted by the app;
- your settings, and the id the app makes for itself to tell your computer which phone a song comes from;
- your YouTube Music library as synced from the computer (playlists, likes and similar);
- your listening history: the last 3,000 plays of YouTube songs, both on the phone and on the computer while the app is
  connected to it. It is used for recommendations and mixes. **Settings → Downloads & offline → Recommendations → Clear listening history**
  removes it;
- your last few searches;
- the queue, playlists kept for offline listening, a cache of playlists and a cache of images;
- an error log, readable in **Settings → About**. It is never sent anywhere; it only leaves the phone if you copy it.

The app turns off Android's cloud backup for all of this. Moving to a new phone with Android's own transfer
tool may still copy it.

Downloaded songs, with their covers and lyrics, are kept in the app's own music folder on the phone's storage, which is
also removed when you uninstall the app.

If you tap **Allow access to music**, the app reads the music files already on the phone so you can play them. That
list stays on the phone.

### In your browser

- **Web app**: its settings (such as the theme, and the last colour taken from a cover for the Album colours theme) in
  the browser's local storage, and, when an access token is used, a cookie holding the token, kept for a year. The
  Album colours theme reads the cover in the browser itself; nothing about it is sent anywhere.
- **Extension**: a few small values in the extension's own storage (such as colours taken from the album art).
- **Refine**: its settings in music.youtube.com's local storage, in your browser.

## What goes where

Every service below sees the IP address of the device that contacts it, as any website does.

### Your own computer

To reach your computer, the web app and the Android app use only the address you give them. They send it your
commands and setting changes, and ask it for what's playing, your library, lyrics and songs.

While music plays on the phone, the phone also tells your computer what it plays, so that your Discord status can show
it: the song's title, artist and album, its cover link and YouTube id (for songs from YouTube), its position, length and
playback speed, whether it is paused, and the id the phone makes for itself. Music files already on the phone are
included. This is on unless you switch off **Settings → Discord status → Show what this phone plays** in the Android
app, and with Discord status switched off on the computer, the computer keeps none of it.

Over plain `http://` on a home network, this traffic is **not encrypted**: someone who can watch that network could read
it, including the access token. Tailscale encrypts it, and so does an `https://` address (for example through
`tailscale serve` or a Cloudflare Tunnel).

### YouTube and YouTube Music

- **The extension** works inside your own YouTube Music tab, where you are signed in. What you do through the remote
  (playing, searching, liking, adding to playlists) happens in that tab as you, so it shows in your YouTube Music
  account as if you had done it in the browser. Your Google sign-in stays in that page: the extension uses it only for
  YouTube Music's own requests, and never sends it to the YTM Starcast program.
- **The Android app** talks to YouTube directly and anonymously through NewPipe Extractor, with no Google sign-in.
  When it plays on the phone, it uses that for search, the songs' audio, radio mixes built from songs in your listening
  history, and public playlists. It also uses it for search suggestions when YouTube Music isn't open on the computer,
  and for the comments that load by themselves on the Playing page a few seconds after a YouTube song starts, wherever
  that song plays. YouTube sees your IP address and which videos are asked for.
- **The computer** may fetch a song with yt-dlp when the phone can't get it itself, and when you choose **Save to this
  computer** in a song's menu. YouTube then sees the computer's IP address and which video is asked for.

### Discord

YTM Starcast reaches Discord only through the Discord desktop app on the same computer, over Discord's own local
connection. It never signs in to Discord and never sees your Discord password or token. It receives your Discord display
name, to show it in the apps.

When Discord status is on (it is unless you switch it off), your Discord profile shows what you play, in YouTube Music
on the computer and on the phone (see above), to anyone who can see your activity there: depending on your settings,
the song's title, artist and album, its cover, the progress, and a button linking to the song on YouTube Music. Private
mode, the words that keep a song off Discord, hiding it for a while, and the Discord status switch control what is
shown. Discord loads the covers itself from Google's image servers, and
YTM Starcast's own card images from GitHub.

### Lyrics services

To find lyrics, the song's title and artist, and sometimes its album and length, go to:

- **LRCLIB** (lrclib.net),
- **NetEase Cloud Music** (music.163.com) and **KuGou**, both based in China.

While YouTube Music plays, the computer looks up lyrics by itself for the song playing and the next two in the queue,
so they are ready when you open them. The Android app asks LRCLIB directly when no computer is reachable, and for a
song it downloads when the computer has no lyrics for it. Lyrics from YouTube Music itself come through your YouTube
Music tab.

### GitHub

- **Updates**: the computer asks GitHub for new versions when the apps or the desktop window check (answers are reused
  for 15 minutes) and downloads them from GitHub when you update. The Android app asks GitHub itself when it opens
  (unless **Settings → Updates → Check automatically** is off), and gets new versions from GitHub, or through your
  computer when GitHub can't be reached from the phone. GitHub publicly counts how many times each release file is
  downloaded, not who downloaded it.
- **A GitHub sign-in on the computer**: if the GitHub command-line tool (`gh`) is signed in on the computer, or
  `STARCAST_GITHUB_TOKEN`, `POCKETFADER_GITHUB_TOKEN`, `GITHUB_TOKEN` or `GH_TOKEN` is set, the computer's update check
  sends that token to GitHub (and only to GitHub). GitHub can then tie the check to your GitHub account.
- **Discord status images**: the small images on the Discord status are hosted on GitHub, and Discord fetches them
  from there.

### Images

Covers and thumbnails load from Google's image servers, as they do on YouTube Music itself. For other players on the
computer, covers load from whatever address that player gives.

### Tailscale

If you use Tailscale, the phone reaches the computer through your own Tailscale network. YTM Starcast only asks the
Tailscale program on your computer for that computer's Tailscale address and name.

### Cloudflare

Only if you set up a Cloudflare Tunnel yourself (see the [Cloudflare guide](docs/CLOUDFLARE.md)). The traffic between
your phone and your computer then passes through Cloudflare, which can see it, and the Android app sends Cloudflare the
Access id and secret you entered.

### PyPI

Only while installing or updating: the installers download what they need, such as yt-dlp on Linux, from the Python
Package Index.

## Who can reach the program on your computer

- **By default** it listens only on the computer itself and on its Tailscale address. Programs on the computer and the
  devices on your Tailscale network can use it without a token.
- **With `--lan` or `--bind`** it also listens on that address, and every request needs the access token. With
  `--token` (as set up for a reverse proxy or a Cloudflare Tunnel), every request needs it too.
- When the token is on, the browser extension's requests can skip it only when they come from the computer itself.
- Web pages you visit can't control it behind your back: every request is checked for the address it was sent to, and
  every change for the site it came from. The one site let through is YouTube Music itself, because the extension works
  inside it.

## Permissions

### Android app

| Permission | Why |
|---|---|
| Internet | To reach your computer, YouTube, LRCLIB and GitHub. |
| Foreground service | To keep playing, and to keep downloading, with a notification. |
| Notifications | For the playback and download notifications. |
| Network state | So automatic downloads can wait for Wi-Fi. |
| Install updates | To install a new version you chose (Android still asks you). |
| Wake lock | To keep playing with the screen off. |
| Music files | Only when you tap **Allow access to music**, to play the music already on the phone. |

It does not ask for the camera, microphone, location, contacts or accounts.

### Browser extension

- **storage**: its few small values;
- **alarms**: to check every 30 minutes whether YTM Starcast has a newer extension ready;
- access to **music.youtube.com** and to YTM Starcast on the same computer (`127.0.0.1:8777`, also written
  `localhost:8777`), and nothing else.

## Children

YTM Starcast isn't made for children. It collects nothing from anyone, children included.

## Changes

Changes to this page are listed in the repository's history, and the date at the top changes with them.

## Contact

Questions go to the [GitHub issues page](https://github.com/dash1101/ytm-starcast/issues). Issues are public, so
never post your access token or other private details there.
