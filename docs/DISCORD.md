# Discord status

YTM Starcast can show the song you're playing in YouTube Music on your Discord profile, as a "Listening to …" card with the
title, artist, cover, progress and a **Listen on YouTube Music** button. It is one of YTM Starcast's parts: switch it on or off
in **Settings → Parts**, or from the browser extension's toolbar button.

This used to be the separate YTM Beacon app. It is built into YTM Starcast now, so there is nothing else to install or keep
running. If you used YTM Beacon, see [Moving over from YTM Beacon](#moving-over-from-ytm-beacon).

## What you need

- **The Discord desktop app**, open and signed in on the same computer as YTM Starcast. Discord in a web browser can't be
  reached. YTM Starcast also looks for Discord installed as a Flatpak or Snap.
- **YTM Starcast's browser extension** in the browser that plays YouTube Music, with a music.youtube.com tab open (see
  [the browser extension](../README.md#the-browser-extension)). The extension already tells YTM Starcast what plays for the
  YouTube Music remote; Discord status uses the same report, so there is no second extension.
- **A Chromium-based browser (Chrome, Brave, Edge…) on a Linux or Windows computer.** The extension doesn't work in
  Firefox yet, and YTM Starcast doesn't run on macOS yet. YTM Beacon works in both, so keep using it there (see
  [Moving over from YTM Beacon](#moving-over-from-ytm-beacon)).

## Where to control it

- **Web app:** Settings → Discord status.
- **Android app:** Settings → Discord status, and the **Discord status** home-screen widget (show or hide it, hide for an
  hour).
- **Browser extension:** the toolbar button and the "YTM Starcast" section in Refine's ✦ menu have an on/off switch and say what
  the status is doing.

The card in the apps has:

- a status line that says what's happening, and what to do when something is wrong;
- **Show on Discord**: the on/off switch. It is the same switch as Discord status in Settings → Parts;
- **Hide for 30 min / 1 hour / 3 hours**: the card comes back by itself, with a countdown and **Show now**. A timed hide
  also ends when YTM Starcast restarts;
- **What it shows**:

| Setting | Does |
|---|---|
| Private mode | Shows only "Listening to music", without the song. |
| Show when paused | Keeps the card while the song is paused. |
| Hide a paused song after | Never, 1, 5 or 15 minutes, or an hour; it comes back when you press play. |
| Progress bar | Shows how far into the song you are (while it plays). At another speed the times follow it: a 3:00 song slowed to 0.8× shows 3:45, the time it really takes to hear. |
| Play/pause icon | A small play or pause badge on the cover. |
| Listen button | A **Listen on YouTube Music** button that opens the song (or a search for it). |
| Album art | The song's cover (otherwise YTM Starcast's logo). |
| Name next to your username | What Discord shows after your name: **YouTube Music**, the **Artist** or the **Song title**. |
| Never show songs matching | Words that keep a song off Discord: a song whose title, artist or album contains one of them is not shown. Up to 50 words of up to 80 characters; upper and lower case don't matter. |

## How it behaves

- While an **advert** plays, nothing is shown; the card comes back with the music.
- With several YouTube Music tabs open, a tab that is playing wins over a paused one.
- When you close the YouTube Music tab or leave the page, the card is cleared at once. A tab that stops reporting without
  saying goodbye (the browser crashed, the computer went to sleep) is dropped after about 90 seconds.
- When you quit YTM Starcast, it clears the card first.
- Settings are saved in `~/.config/ytm-starcast/discord.json` (`%APPDATA%\ytm-starcast\discord.json` on Windows; on Linux the file
  is readable only by you). It holds the same settings as YTM Beacon's `config.json`.
- `ytm-starcast --dry-run` never contacts Discord, and settings you change are kept in memory only (the status still shows).

## Moving over from YTM Beacon

**Only move over in a Chromium-based browser on Linux or Windows.** YTM Beacon in Firefox or on macOS has no replacement
in YTM Starcast yet, so keep it: on Linux or Windows, install YTM Starcast with `--keep-ytm-beacon` (Linux) or
`-KeepYtmBeacon` (Windows) and don't use **Move over**; on a Mac, nothing changes.

The easiest way is to run YTM Starcast's installer (see [Install](../README.md#install)): it removes the YTM Beacon app with its
own uninstaller, and YTM Starcast takes over Beacon's settings when it first starts (from `~/.config/ytm-beacon/config.json`,
or `%APPDATA%\ytm-beacon\config.json` on Windows). That happens only once, while YTM Starcast has no Discord settings of its
own yet.

**While the YTM Beacon app is still running, YTM Starcast leaves Discord to it:** two programs showing a status for the same
Discord account would fight over the card. YTM Starcast checks every 10 seconds, and its status line says that YTM Beacon is
still running. To finish the move from the apps, use **Settings → Discord status → Move over**. It:

1. copies YTM Beacon's settings into YTM Starcast (the ones Beacon uses right now, else its settings file);
2. runs YTM Beacon's own `ytm-beacon uninstall`, which stops it and removes its program and start-at-login entry, and
   keeps its settings file and log;
3. waits until it has stopped, then starts YTM Starcast's Discord status.

**One step is left for you:** remove the **YTM Beacon** extension on your browser's extensions page
(`chrome://extensions`, `brave://extensions` or `edge://extensions`). YTM Starcast's own extension does its job now.

If Move over can't finish, its message says why:

| Message | What to do |
|---|---|
| There is nothing to move over | YTM Beacon isn't on this computer. Nothing to do. |
| Its program isn't where its installer puts it | Run `ytm-beacon uninstall` yourself; YTM Starcast then shows your status by itself. |
| Couldn't take over YTM Beacon's settings | Nothing was changed. Check that YTM Beacon's settings file is readable, then try again. |
| YTM Beacon's own uninstall didn't finish | Your settings were already copied. Try again, or run `ytm-beacon uninstall` yourself. |
| YTM Beacon was removed but is still running | Close it (or restart the computer); YTM Starcast then shows your status by itself. |
| Already moving over | Wait for the first move to finish. |

## How it works, and why it's safe

```
YouTube Music tab --(YTM Starcast's extension, this computer only)--> YTM Starcast --(Discord's local connection)--> Discord app
```

The extension reports what plays to YTM Starcast on `127.0.0.1:8777`, as it does for the remote. YTM Starcast passes the song to
the Discord desktop app through Discord's own local connection (a socket file on Linux and macOS, a named pipe on
Windows). YTM Starcast opens no network port for this and never talks to Discord's servers itself; the Discord app shows the
card to your friends.

- The card's logo and play/pause badges are images in YTM Starcast's GitHub repository, which Discord loads itself. With
  **Album art** on, Discord also loads the song's cover from YouTube Music.
- The apps can change only the settings above. YTM Starcast reports to Discord as the same Discord application YTM Beacon
  used; that can't be changed from the apps.
- Nothing about your computer (file paths, programs) is passed to the apps, and the only program YTM Starcast ever runs for
  this is YTM Beacon's own uninstaller, during a move you asked for.

## Troubleshooting

| The status line says | Do this |
|---|---|
| Can't reach Discord | Open the Discord desktop app on the computer. YTM Starcast reconnects by itself. |
| The browser extension hasn't reported yet | Add YTM Starcast's extension to the browser on the computer, then open YouTube Music there. |
| The browser extension went quiet | Check that YouTube Music is still open in the browser; reload the tab if it is. |
| The YTM Beacon app is still running | Use **Move over** (see above). |
| An advert is playing / hidden while an advert plays | Nothing to do: the card comes back with the music. |
| Not shown: this song matches one of your never-show words | That is your "Never show songs matching" list at work. |
| Not shown while paused / hidden because the song was paused a while | Turn on **Show when paused**, or change **Hide a paused song after**. |
| Switched off / hidden for a while | Turn on **Show on Discord**, or tap **Show now**. |
| Showing on Discord, but your friends don't see it | In the Discord app, check **Settings → Activity Privacy**: sharing your activity must be on. |
