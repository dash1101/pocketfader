# YTM Starcast browser extension

The part of YTM Starcast that lives in the browser playing YouTube Music. It:

- gives the apps real control over YouTube Music: search, play, start mixes and playlists, the queue and play next,
  now playing and the upcoming queue, cover art, likes, artist pages and your library (the **YouTube Music remote**);
- tells YTM Starcast what is playing, which YTM Starcast uses for the remote, lyrics and your **Discord status**;
- improves YouTube Music itself (**Refine**, below);
- adds a **toolbar button** with YTM Starcast's switches.

It works in Chromium-based browsers (Chrome, Brave, Edge…). It talks only to YTM Starcast on this computer at
`http://127.0.0.1:8777` and opens no network port of its own.

## Load it (one time)

1. Download `ytm-starcast-extension.zip` from the release and unzip it into a folder you will keep (the browser loads the
   extension from that folder). In a copy of the source, this `extension/` folder works too.
2. Open the extensions page: **`chrome://extensions`** (or `brave://extensions`, `edge://extensions`).
3. Turn on **Developer mode** (top-right toggle).
4. Click **Load unpacked** and pick the folder that holds `manifest.json`.
5. Keep a **`https://music.youtube.com`** tab open (pinned is ideal). The extension starts in that tab.

The extension stays loaded after a browser update or a reboot. **To update it**, replace the folder's files with those of
the new `ytm-starcast-extension.zip` (or update your copy of the source), click **Reload** on the extension's card, and reload
the YouTube Music tab.

**Coming from Pocketfader or YTM Beacon:** keep only one copy of this extension. If the Pocketfader extension was loaded
from another folder, remove it. Remove the **YTM Beacon** extension too: YTM Starcast's extension does its job now. (YTM
Beacon in Firefox has no replacement here yet: keep it there.)

## The toolbar button

Click YTM Starcast's button in the browser's toolbar to see whether YTM Starcast is running (and its version), switch the YouTube
Music remote, Discord status and Refine on or off, see what Discord status is doing, and open YTM Starcast's web app.

**Discord status options** (in the toolbar button and in Refine's ✦ menu) have everything YTM Beacon had: show or hide, hide
for 30 minutes, an hour or three hours, private mode, album art, the progress bar, the play/pause icon, the "Listen on YouTube
Music" button, paused songs (and how long they stay), what the member list shows after "Listening to", never-show words and a
30-second test song, with a live preview of the card. **Alt+Shift+M** shows or stops showing your music on Discord from
anywhere in the browser (change it on the browser's keyboard shortcuts page).

The button reaches YTM Starcast through an open YouTube Music tab, so it can show:

- **Open YouTube Music**, when no tab is open;
- **Reload the tab**, for a tab opened before the extension was added or reloaded (the song starts again);
- **Allow**, when the browser hasn't given the extension access to music.youtube.com;
- that an older version of the app is running on the computer, which has no switches (they show as on).

## Refine (improvements to YouTube Music itself)

`refine.js` adds a ✦ button to YouTube Music's top bar. From there you can switch each part on or off:
a Material 3 Expressive look (rounder shapes, colours from the album art, expressive player controls, a wavy progress bar,
a glow behind the cover, animations), a richer speed menu on the player's own speed button (any speed, which stays from
song to song; pitch on/off; a clear slowed sound; a "room" reverb; a speed remembered per song or playlist; Shift+. and
Shift+, from the keyboard; the player bar's times follow the speed), a "play something you might like" button on the home
page, live lyrics in YouTube Music's own Lyrics tab or in a window of their own, the computer's sound (volume, output,
equalizer), and a playlist maker. Live lyrics come from YTM Starcast on this computer (route `/ext/lyrics`), and so does the
computer's sound (`/ext/sound`). Settings stay in this browser.

The clear slowed sound runs `stretch.js` (the page loads it as an audio worklet, which is why the extension lists it as a
web-accessible file for music.youtube.com). The toolbar button takes on Refine's colours, which the extension keeps in its
own storage (the `storage` permission).

The ✦ menu's **YTM Starcast** section has the same three switches as the toolbar button. Switching Refine off there (or in
YTM Starcast's Settings → Parts) takes everything Refine added out of the page, the ✦ button included; switch it back on from
the toolbar button or the apps.

If the page ever looks wrong, open `https://music.youtube.com/?pf-refine=off` to load it without Refine, then switch off the
part that misbehaves. See [docs/REFINE.md](../docs/REFINE.md).

## How it works

- `ytm_main.js` runs in the **page** (MAIN world) and drives music.youtube.com internals
  (`#movie_player`, `ytmusic-app.resolveCommand`, same-origin `/youtubei/v1/*` fetches). It reads what plays, including
  the album and whether an advert is playing.
- `bridge_isolated.js` runs as an **isolated** content script and owns the link to YTM Starcast (the page's own rules block
  the page itself from reaching localhost). It long-polls `GET /ext/poll` for commands, pushes state with
  `POST /ext/state`, says goodbye with `POST /ext/bye` when the tab closes, and passes YTM Starcast's switches (`cfg`) on to the
  page. The two scripts talk over `window.postMessage`.
- `refine.js` runs in the page next to `ytm_main.js`.
- `popup.html`, `popup.js` and `popup.css` are the toolbar button's popup. It never calls YTM Starcast itself: it asks the
  content script in a YouTube Music tab, so every request carries music.youtube.com's origin, the only one YTM Starcast accepts.
- No background service worker: the always-open YouTube Music tab is the anchor.

Permissions: `http://127.0.0.1:8777/*` and `http://localhost:8777/*` (YTM Starcast on this computer) and
`https://music.youtube.com/*` (so the toolbar button can find YouTube Music tabs). The routes are described in
[docs/API.md](../docs/API.md#extension-channel-loopback).

## Check it

Open the YouTube Music tab's DevTools console: it shows `[YTM Starcast] YouTube Music bridge → http://127.0.0.1:8777`,
`[YTM Starcast] YouTube Music page bridge ready` and `[YTM Starcast] Refine ready`. With YTM Starcast running and a YouTube Music tab open:

```
curl http://127.0.0.1:8777/api/ytm/state
```

should show `"connected": true` within a second or two.
