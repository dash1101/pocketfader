# Discord presence (YTM Beacon)

[YTM Beacon](https://github.com/dash1101/ytm-beacon) shows the song playing in YouTube Music on your Discord profile
("Listening to …"). It runs on the computer, next to Pocketfader. Pocketfader doesn't replace it or feed it songs: it only
lets you **see whether it's working and switch it on or off from your phone**.

## What you get

- A **Discord presence** card in the Android app (Mixer tab) and in the web app (Settings):
  - a status line that says what's happening and what to do if something's wrong (YTM Beacon not running, Discord closed,
    the browser extension not set up or gone quiet, a song hidden on purpose…);
  - a **Show on Discord** switch;
  - **Hide for 30 min / 1 hour / 3 hours**: the card comes back by itself, with a countdown and **Show now**;
  - **What it shows**: private mode, show when paused (and for how long), progress bar, play/pause icon, Listen button,
    album art, and the name shown next to your username.
- **Install, start or update YTM Beacon** from the card: if it isn't installed, an **Install YTM Beacon** button has the
  computer download the **latest release** from GitHub, check its checksum and run Beacon's own installer; if it's installed
  but not running there's a **Start** button (Linux); and when a newer release exists an **Update** button appears. It always
  uses the newest release, never a bundled copy. Installing still needs one step in the browser (adding Beacon's extension):
  Beacon opens a setup page on the computer for that.
- To hide the card in the Android app: Settings → Discord presence.

## Requirements

YTM Beacon **0.1.4 or newer**, running on the same computer as Pocketfader. Older versions are reported as out of date.

## How it works, and why it's safe

```
phone  --(Pocketfader's own connection and login)-->  Pocketfader on the computer  -->  YTM Beacon (this computer only)
```

YTM Beacon only listens on the computer itself and has no login, so the phone never talks to it directly. Pocketfader asks
it on the phone's behalf, and only for the things above (status, show/hide, those settings, and install/update/start).
The install only ever fetches Beacon's own latest release, verifies its SHA-256 checksum before running anything, and the
phone can't choose what is downloaded or run. It checks every request,
never passes on file paths or Discord's application id, and can't reach Beacon's other controls (it can't stop it, clear
it, or send it songs).

Under `--dry-run` the switch and settings do nothing (the status still shows).

## Troubleshooting

| The card says | Do this |
|---|---|
| YTM Beacon isn't running | Start it on the computer (Linux: `systemctl --user start ytm-beacon.service`). |
| Can't reach Discord | Open the Discord app on the computer. YTM Beacon reconnects by itself. |
| The browser extension isn't set up / went quiet | Install the YTM Beacon extension in the browser that plays YouTube Music, and keep a music.youtube.com tab open. A paused tab in the background may report only about once a minute, so changes can take a little while to show. |
| Out of date | Update YTM Beacon on the computer. |
