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

## Write

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/volume` | `{"percent": 0-100}` and/or `{"delta": ±n}` and/or `{"muted": bool}` | `503` if no output; `400` on bad values. |
| `POST /api/ytm` | `{"type": "transport", "action": "playpause\|next\|prev\|seek", "position"?: s}` | Goes to the extension if connected, else the host media player. |
| `POST /api/ytm` | `{"type": "search"\|"play"\|"queueAdd"\|"home"\|"playlists"\|"startPlaylist"\|…, …}` | Extension only (`{"error":"extension_offline"}` otherwise). See `extension/ytm_main.js`. |
| `POST /api/media` | `{"action": "playpause\|next\|prev"}` or `{"action":"seek","position":s}` | Host media player directly. |
| `POST /api/session` | `{"app": "<id>"\|null}` | Pin which player the server controls (`null` = auto). |
| `POST /api/eq` | `{"band": "bass\|mid\|high", "db": -12..12}` | `501` unless `capabilities.eq`. |
| `POST /api/engine` | `{"action": "<one of capabilities.engine_actions>"}` | Backend-specific maintenance. |
| `POST /api/prefs` | any JSON object (merged) | |

## Extension channel (loopback)

`GET /ext/poll` (long-poll, ≤25 s), `POST /ext/state`, `POST /ext/result` — used only by the browser extension.

## Planned for the native app (not implemented)

`GET /api/events` (Server-Sent Events replacing polling), `GET /api/stats`, mDNS advertisement, a
`/api/v1` prefix and a published OpenAPI document. See [ANDROID_PLAN.md](ANDROID_PLAN.md).
