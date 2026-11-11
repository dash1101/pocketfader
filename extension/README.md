# Pocketfader — YT Music Bridge (browser extension)

Gives the Pocketfader app real control over YouTube Music playing in Brave: search, play,
start mixes/playlists, queue / play-next, now-playing + upcoming queue, cover art.

It runs entirely on this PC and talks only to the local server at `http://127.0.0.1:8777`
(loopback) — it does **not** open any network port and does not change the Tailscale /
public-IP posture.

## How it works
- `ytm_main.js` runs in the **page** (MAIN world) and drives music.youtube.com internals
  (`#movie_player`, `ytmusic-app.resolveCommand`, same-origin `/youtubei/v1/*` fetches).
- `bridge_isolated.js` runs as an **isolated** content script and owns the link to the
  local server (the page's CSP blocks the page itself from reaching localhost). The two
  talk over `window.postMessage`.
- No background service worker — the always-open YT Music tab is the anchor.

## Refine (improvements to YouTube Music itself)
`refine.js` adds a ✦ button to YouTube Music's top bar. From there you can switch each part on or off:
a Material 3 Expressive look (rounder shapes, colours from the album art, expressive player controls, a wavy progress bar,
a glow behind the cover), a richer speed menu on the player's own speed button (any speed, pitch on/off, remembered per song
or playlist; Shift+. and Shift+, from the keyboard), a "play something you might like" button and live lyrics in the player,
and a playlist maker. Live lyrics come from the Pocketfader app on this computer (route `/ext/lyrics`). Settings stay in this browser.
If the page ever looks wrong, open `https://music.youtube.com/?pf-refine=off` to load it without Refine, then switch off the
part that misbehaves. See `docs/REFINE.md`.

## Load it (one time)
1. In Brave, open **`brave://extensions`** (or `chrome://extensions`).
2. Turn on **Developer mode** (top-right toggle).
3. Click **Load unpacked**.
4. Select this folder: the `extension/` folder of this repository
5. Keep a **`https://music.youtube.com`** tab open (pinned is ideal). The bridge activates
   on that tab. Open its DevTools console to see `"[Pocketfader] YTM ... bridge ready"`.

After a Brave update or reboot the extension stays loaded, but if you ever remove it just
repeat the steps above. If you edit the extension files, hit **Reload** on its card.

## Verify
With the server running and a YT Music tab open:
```
curl http://127.0.0.1:8777/api/ytm/state
```
should show `"connected": true` within a second or two.
