# YouTube Music in-page control — technical notes (probed 2026-07-21)

Probed against a **real, logged-in Brave** tab at `music.youtube.com` (browser automation),
client `WEB_REMIX` v1.20260719.16.00. Goal: verify the control primitives an injected
browser extension would use. Everything below was **run against a live tab**, not inferred
from documentation.

## VERIFIED WORKING

### Transport — solid, standard embed API on `#movie_player`
`playVideo / pauseVideo / nextVideo / previousVideo / seekTo / seekBy / playVideoAt /
stopVideo / getPlayerState / getProgressState / getVideoData / getVideoUrl / getVolume /
setVolume / mute / unMute`.
- `getPlayerState()` → 1 = playing, 2 = paused, 5 = cued.
- `getVideoData()` → `{title, author, video_id, ...}` (now-playing metadata).
- `getVideoUrl()` → parse `v=` for current videoId; `list=` for current queue/mix id.
- Confirmed by actually playing tracks muted and watching state/title change.

### Search — same-origin InnerTube fetch, no manual auth
```js
const key = window.ytcfg.get('INNERTUBE_API_KEY');
const ctx = window.ytcfg.get('INNERTUBE_CONTEXT');
const r = await fetch(`/youtubei/v1/search?key=${key}&prettyPrint=false`, {
  method:'POST', headers:{'Content-Type':'application/json'},
  credentials:'include', body: JSON.stringify({ context: ctx, query })
});
```
- Returns 200 with real `videoId`s and `playlistId`s. `credentials:'include'` on a
  same-origin request means the browser cookie auths it — **no SAPISIDHASH header needed**.
- Each result item's menu carries **real command endpoint objects** (`watchEndpoint`,
  `watchPlaylistEndpoint`, `queueAddEndpoint`) — extract these, don't hand-build them.

### Play a song / start a mix or radio — via `ytmusic-app.resolveCommand`
`resolveCommand` is YT Music's universal command dispatcher — the same path every menu
click takes, networkless.
- `resolveCommand({ watchEndpoint: { videoId } })` → plays that song AND auto-starts a
  radio (`list=RDAMVM<videoId>`). Confirmed: base song played, then autoplayed the mix.
- `resolveCommand({ watchPlaylistEndpoint: { playlistId } })` → expected to start a
  playlist (NOT yet run, but same mechanism).
- `loadVideoById(id)` also plays but is a *lower-level* shortcut that bypasses the queue
  UI (`getPlaylist()` stays null) — prefer `watchEndpoint` for queue-integrated playback.

## Queue-add — SOLVED via UI-simulation (menu-item click), NOT resolveCommand

Verified by calibrating the measurement against a known-good action, per advisor guidance.

- **`resolveCommand({queueAddEndpoint:{...}})` returns `true` but does NOT insert.** Tried a
  hand-built endpoint AND the real one from search results (with the `commands` field);
  against a **valid** readout (all 52 live queue ids read via `item.data.videoId`, target
  id absent before & after) it changed nothing. `r:true` is a **false success** — the same
  class of lie this project already documents for GSMTC transport.
- **The real menu-item `.click()` DOES insert — confirmed.** Opening a search-result row's
  "⋮" menu and clicking **"Add to queue"** put the target videoId into the live queue
  (readout caught it: count 0→1 including the target). This proves both that the mechanism
  works AND that the readout is trustworthy (it detects real changes).
- **Production mechanism for the extension:** simulate the UI. The extension drives the real
  page, so it renders results and clicks the genuine menu item. Same path gives **"Play
  next"** and **"Start mix"** (sibling menu items on every row).

### Reliable live-queue readout (works on watch pages; queue panel isn't in DOM on /search)
```js
const readIds = () => { const items=[]; const scan=r=>r.querySelectorAll&&
  r.querySelectorAll('ytmusic-player-queue-item').forEach(i=>items.push(i));
  scan(document); document.querySelectorAll('*').forEach(el=>{if(el.shadowRoot)scan(el.shadowRoot);});
  return items.map(i=>{try{return i.data?.videoId;}catch(e){return null;}}).filter(Boolean); };
```

### Menu-item locator (after clicking the row's ⋮ button, ~600ms settle)
```js
const items=[...document.querySelectorAll('ytmusic-menu-service-item-renderer, ytmusic-menu-navigation-item-renderer')];
// items carry .data.serviceEndpoint.queueAddEndpoint for Play next / Add to queue;
// match by textContent: "Play next", "Add to queue", "Start mix".
```

### Note / open nicety (not a blocker)
- `resolveCommand` arity read as 0 (minified; inconclusive). The menu click likely calls it
  WITH an element/context arg the bare call lacks — a cleaner programmatic path *may* exist
  (dispatch queueAddEndpoint with proper context). Worth investigating further, but
  UI-simulation is already proven and sufficient.
- Redux aside: `state.playerPage.playerPageWatchNextResponse.…playlistPanelRenderer.contents`
  is the **static initial** watch-next response (doesn't track live position) — do not use it
  as the live queue. Use the DOM readout above.

## Home feed + Library playlists — VERIFIED

- **Home** (`browse` browseId `FEmusic_home`) → 200 with cookie-only auth. Returns
  `musicCarouselShelfRenderer` sections (Speed dial / Listen again / Quick picks-style;
  names are personalized, e.g. "That summer feeling", "Throwbacks"). Every item carries
  `thumbnail.thumbnails[]` URLs (cover art) — the `=w{N}` sizing suffix lets us request art
  at any resolution.
- **Playlists** (`browse` browseId `FEmusic_liked_playlists`) → **needs SAPISIDHASH**, else
  returns a `messageRenderer` ("sign in") with no content. With the header: real playlists
  (Liked Music, HWave 26, CH1LL V2025-*, 2024 Fall, …). Each `musicTwoRowItemRenderer` has
  title + browseId (`VL…`) + thumbnail.

### Auth tiers (important for the extension's fetch layer)
| Endpoint | Auth needed |
|---|---|
| `/youtubei/v1/search`, `browse FEmusic_home` | cookie only (`credentials:'include'`) |
| `browse FEmusic_liked_playlists` / library | **`Authorization: SAPISIDHASH <ts>_<sha1(ts+" "+SAPISID+" "+origin)>`** + `X-Origin`, `X-Goog-AuthUser:0` |
SAPISID is JS-readable via `document.cookie` (not HttpOnly); SHA-1 via `crypto.subtle`.

## Extension architecture decision — TWO-WORLD (required, not optional)
music.youtube.com ships a strict CSP. Consequences:
- **A MAIN-world script can call page objects** (`#movie_player`, `resolveCommand`,
  `ytcfg`) and do **same-origin** InnerTube fetches (search/home/browse) — all CSP-legal.
- **But a MAIN-world `fetch('http://localhost:8777')` is blocked by the page's connect-src
  CSP.** So the link to the Python server MUST live in an **ISOLATED** content script (or
  background), which fetches with extension privilege and is exempt from page CSP.
- Therefore: ISOLATED content script owns the server long-poll + `host_permissions` for
  loopback; MAIN-world script owns all YTM interaction; the two bridge via
  `window.postMessage`. No background service worker needed (avoids the ~30s-idle death).

## Implications for the extension design
- Playback, search, now-playing, and start-song/mix are all cleanly scriptable in-page.
- Queue-add depends on one of the fallbacks described above; it is the most fragile primitive.
  The core set (play now / start playlist / start radio / transport) is fully proven.
- Extension↔server link: content-script holds the connection (MV3 SW dies ~30s idle);
  server is already `ThreadingHTTPServer` so long-poll/SSE won't deadlock.
