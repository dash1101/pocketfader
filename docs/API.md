# HTTP API (v1, as of 0.1.0)

Base URL `http://<host>:8777`. JSON in, JSON out. This is the contract the web app uses today and the
future native app will build on.

**Auth.** If the server runs with a token (`--lan`, `--bind`, `--token`), send
`Authorization: Bearer <token>` (native clients) or the `pf_token` cookie (set by opening `/?token=…`).
Without one: `401`. Public: `/api/info`, `/manifest.json`, `/icon.svg`. Browsers must also send a
same-origin `Origin` on POSTs (non-browser clients send none — that's fine).

## Read

| Endpoint | Returns |
|---|---|
| `GET /api/info` | `{name, version, api, auth}` — safe to call before authenticating (discovery / pairing). |
| `GET /api/state` | `{version, volume, media, engine, capabilities, limits}` (below). |
| `GET /api/ytm/state` | Now-playing in YT-Music shape: `{connected, source: "ytm"\|"media"\|null, now: {title, artist, album, art, playing, position, duration, videoId?, liked?, volume?}, queue: []}`. `source` says whether it came from the extension or the host's media player. |
| `GET /api/prefs` | Shared client prefs (favourites…), same on every device. |
| `GET /api/update[?refresh=1]` | The newest release on GitHub and whether it's newer than this server: `{current, latest, available, notes, url, apk, wheel, self_update}`. The computer does the asking, so phones need no GitHub login. |
| `GET /api/update/apk` | The newest Android app (checked against the release's SHA256SUMS) for the app to install. |
| `GET /api/lyrics?title=&artist=&album=&duration=&videoId=&source=&refresh=1` | Lyrics for a track (below). `title` is required; `source` is `lrclib`, `netease` or `ytm`; `refresh=1` asks every source again. |
| `GET /api/beacon` | Discord presence through [YTM Beacon](https://github.com/dash1101/ytm-beacon), if it runs on this computer: `{reachable, version, headline, visible, snoozeSec, discord, extension, nowPlaying, presenceActive, settings, fetchedAt}`. `headline` is one of `unreachable`, `unsupported`, `off`, `snoozed`, `discord-down`, `extension-missing`, `extension-quiet`, `idle`, `filtered`, `paused-hidden`, `paused-timeout`, `showing`, `waiting`. Never fails when Beacon isn't running (`reachable: false`). See [docs/BEACON.md](BEACON.md). |

```jsonc
// /api/state
{
  "volume": {"available": true, "percent": 70, "muted": false, "device": "Desk Speakers"},
  "media":  {"available": true, "app": "spotify", "name": "Spotify", "playing": true,
             "title": "…", "artist": "…", "album": "…", "art": "https://…",
             "position": 12.3, "duration": 200.0,
             "can": {"play": true, "pause": true, "next": true, "prev": true, "seek": true},
             "sessions": [{"app": "spotify", "name": "Spotify", "playing": true}], "pinned": null},
  "engine": {"connected": false, "level": 0.0, "eq": {}, "routing": {}},
  "capabilities": {"volume": true, "eq": false, "meter": false, "engine_actions": [],
                   "engine_label": "", "media": true, "ytm_bridge": false},
  "limits": {"eq_min": -12.0, "eq_max": 12.0}
}
```

```jsonc
// /api/lyrics
{
  "found": true,
  "source": "lrclib",                       // the one returned in `lyrics`
  "sources": [{"id": "lrclib", "label": "LRCLIB", "found": true, "synced": true},
              {"id": "netease", "label": "NetEase", "found": true, "synced": true},
              {"id": "ytm", "label": "YouTube Music", "found": false, "synced": false}],
  "lyrics": {
    "source": "lrclib", "label": "LRCLIB", "synced": true, "credit": "Source: LyricFind",   // credit is optional
    "lines": [{"t": 12.4, "text": "…", "rom": "…romanized…", "tr": "…translation…",
               "w": [[12.4, 12.9, "word "], [12.9, 13.6, "word"]]}]              // t is null when not synced; w only with word-by-word sources
  },
  "pending": false                          // true while slower sources are still being asked (ask again in a few seconds)
}
```

The server asks the sources at the same time (`kugou` gives word-by-word timing), answers as soon as something
time-synced is in, and keeps everything for a few hours, so switching `source` is instant. It also looks up the current
track and the next two in the background when the track changes. When the chosen source has no romanization or translation, the lines borrow them from another
source by timestamp. Only the title, artist, album and length are sent to those services. Sources are
third-party and best-effort. `capabilities.lyrics` is `true` on servers that have this endpoint.

## Write

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/volume` | `{"percent": 0-100}` and/or `{"delta": ±n}` and/or `{"muted": bool}` | `503` if no output; `400` on bad values. |
| `POST /api/ytm` | `{"type": "transport", "action": "playpause\|next\|prev\|seek", "position"?: s}` | Goes to the extension if connected, else the host media player. |
| `POST /api/ytm` | `{"type": "search"\|"play"\|"queueAdd"\|"home"\|"playlists"\|"startPlaylist"\|"artist"\|"speed"\|"lucky"\|…, …}` | Extension only (`{"error":"extension_offline"}` otherwise). See `extension/ytm_main.js`. `artist` takes `browseId`, `videoId` (the artist of that song) or `name` and returns `{name, subscribers, description, thumb, play, radio, shelves}`; `speed` takes `rate` (0.25–2); `lucky` plays a random pick from your home page. |
| `POST /api/ytm` | `{"type": "play", "videoId", "playlistId"?}`, `{"type": "startPlaylist", "playlistId"}` or `{"type": "open"}` **with no YouTube Music tab connected** | The computer opens music.youtube.com in its browser (the song, the playlist or the home page) and replies `{"ok": true, "opened": true}`. Only valid ids are accepted and only music.youtube.com is ever opened. |
| `GET /api/media/audio/<videoId>` | — | The song's audio, fetched with yt-dlp on the computer and cached (Range requests supported). The phone uses it when it can't get a song itself. `capabilities.audio` says whether yt-dlp is available. |
| `POST /api/media/pause-others` | `{}` | Pauses every other media player that is playing on the computer (not the YouTube Music song playing); replies `{paused: [names], supported}`. The shared preference `pauseOthers` (set through `POST /api/prefs`) makes the computer do this by itself whenever YouTube Music starts playing. |
| `POST /api/media` | `{"action": "playpause\|next\|prev"}` or `{"action":"seek","position":s}` | Host media player directly. |
| `POST /api/session` | `{"app": "<id>"\|null}` | Pin which player the server controls (`null` = auto). |
| `POST /api/eq` | `{"band": "bass\|mid\|high", "db": -12..12}` | `501` unless `capabilities.eq`. |
| `POST /api/engine` | `{"action": "<one of capabilities.engine_actions>"}` | Backend-specific maintenance. |
| `POST /api/prefs` | any JSON object (merged) | |

## Extension channel (loopback)

`GET /ext/poll?i=<tab>` (long-poll, ≤25 s), `POST /ext/state`, `POST /ext/result` — used only by the browser extension. Each
YouTube Music tab names itself (`i` on the poll, `instance` in its state); with several tabs open the server follows one (the one
playing) and only that tab receives commands. A command that times out is dropped, not run later.

## Planned for the native app (not implemented)

`GET /api/events` (Server-Sent Events replacing polling), `GET /api/stats`, mDNS advertisement, a
`/api/v1` prefix and a published OpenAPI document. See [ANDROID_PLAN.md](ANDROID_PLAN.md).

## Discord presence (YTM Beacon)

Pocketfader relays a small, fixed set of YTM Beacon's controls; nothing else of Beacon's API is reachable through it.

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/beacon/visibility` | `{"action":"show"}` or `{"action":"hide"}` or `{"action":"hide","minutes":60}` | `minutes` is a whole number from 1 to 1440 and only goes with `hide`; the card comes back by itself. Replies `{enabled, snoozeSec, visible}`. `show` and `hide` can safely be repeated. |
| `POST /api/beacon/settings` | any of `privateMode`, `showPaused`, `showProgress`, `showStateIcon`, `showButtons`, `showAlbumArt` (true/false), `pauseTimeoutMinutes` (0–1440, 0 = never), `statusDisplay` (`name`\|`state`\|`details`) | Only the keys you send change. Anything else, or a wrong type, is rejected with `400`. Replies with the new settings. |

| `POST /api/beacon/install` | `{"action":"install"\|"update"\|"start"}` (or `{}` to pick automatically) | Runs in the background and returns at once; follow it in `GET /api/beacon` → `install.job` (`state`: `idle`, `running`, `done`, `failed`, and a `message`). `install` downloads the newest YTM Beacon release, verifies its checksum and runs its installer; `update` runs `ytm-beacon update`; `start` starts its user service (Linux). |

`503` means Beacon isn't running; `502` means Beacon refused the change (the message says why).
`GET /api/beacon` also carries `install: {installed, canInstall, canStart, latest, updateAvailable, job}`.
`/api/state` has `capabilities.beacon: true` when this version of the server can do this.

## Sleep timer

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/sleep` | `{"minutes": 1..720}`, `{"minutes": "track"}` or `{"minutes": 0}` | Pause playback after that long, when the current song ends, or cancel. Replies `{remainingSec}`; `/api/state` has `sleep.remainingSec` (0 = no timer) and `capabilities.sleep`. |
