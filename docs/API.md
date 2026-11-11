# HTTP API (v1, as of 0.2.0)

Base URL `http://<host>:8777`. JSON in, JSON out. The web app, the Android app, the desktop window and the browser
extension all use this API.

**Auth.** If the server runs with a token (`--lan`, `--bind`, `--token`), send
`Authorization: Bearer <token>` (native clients) or the `pf_token` cookie (set by opening `/?token=…`).
Without one: `401`. Public: `/api/info`, `/manifest.json`, `/icon.svg`, `/icon-192.png`, `/icon-512.png`. The
extension's `/ext/*` routes need no token when they come from the computer itself (127.0.0.1, not through a proxy).

**Origin and Host.** Browsers must send a same-origin `Origin` on POSTs (non-browser clients send none, which is fine);
the only other origin accepted is `https://music.youtube.com`, for the extension, and it is also the only one that gets
CORS headers. Everything else: `403`. A `Host` header the server doesn't know: `421` (`--allow-host` adds one).

Some routes and fields keep the names they had before the product was called YTM Starcast (`/api/beacon*` for Discord
status, `capabilities.beacon`, the `pf_token` cookie), so apps already on phones keep working. See
[Legacy identifiers](ARCHITECTURE.md#legacy-identifiers).

## Read

| Endpoint | Returns |
|---|---|
| `GET /api/info` | `{name: "YTM Starcast", version, api, auth}`. Safe to call before authenticating (discovery / pairing). |
| `GET /api/state` | `{version, volume, media, engine, capabilities, limits, sleep, parts}` (below). |
| `GET /api/parts` | `{"parts": {"remote", "discord", "refine", "audio"}}`, each `true` or `false` (see [Parts](#parts)). |
| `GET /api/ytm/state` | Now-playing in YT-Music shape: `{connected, source: "ytm"\|"media"\|null, now: {title, artist, album, art, playing, position, duration, videoId?, liked?, volume?}, queue: []}`. `source` says whether it came from the extension or the host's media player. Keeps answering while the remote is switched off. |
| `GET /api/prefs` | Shared client prefs (favourites…), same on every device, plus the current `parts`. |
| `GET /api/outputs` | `{outputs: [{name, description, percent, muted, default, kind, form_factor, state}], default}`: every playback device with its own level. `501` when the audio backend can't switch outputs (`capabilities.outputs`). |
| `GET /api/update[?refresh=1]` | The newest release on GitHub and whether it's newer than this server: `{current, latest, available, notes, url, apk, wheel, self_update}`. The computer does the asking, so phones need no GitHub login. |
| `GET /api/update/apk` | The newest Android app (checked against the release's `SHA256SUMS.txt`) for the app to install. |
| `GET /api/lyrics?title=&artist=&album=&duration=&videoId=&source=&refresh=1` | Lyrics for a track (below). `title` is required; `source` is `lrclib`, `netease` or `ytm`; `refresh=1` asks every source again. |
| `GET /api/media/audio/<videoId>` | The song's audio, fetched with yt-dlp on the computer and cached (Range requests supported). The phone uses it when it can't get a song itself. `capabilities.audio` says whether yt-dlp is available. |
| `GET /api/beacon` | Discord status (see [Discord status](#discord-status)). |

```jsonc
// /api/state
{
  "version": "0.2.0a1",
  "volume": {"available": true, "percent": 70, "muted": false, "device": "Desk Speakers"},
  "media":  {"available": true, "app": "spotify", "name": "Spotify", "playing": true,
             "title": "…", "artist": "…", "album": "…", "art": "https://…",
             "position": 12.3, "duration": 200.0,
             "can": {"play": true, "pause": true, "next": true, "prev": true, "seek": true},
             "sessions": [{"app": "spotify", "name": "Spotify", "playing": true}], "pinned": null},
  "engine": {"connected": false, "level": 0.0, "eq": {}, "routing": {}},
  "capabilities": {"volume": true, "eq": false, "eq_toggle": false, "meter": false, "outputs": true,
                   "engine_actions": [], "engine_label": "", "media": true, "ytm_bridge": false,
                   "lyrics": true, "beacon": true, "discord": true, "sleep": true, "audio": true},
  "limits": {"eq_min": -12.0, "eq_max": 12.0},
  "sleep": {"remainingSec": 0},
  "parts": {"remote": true, "discord": true, "refine": true, "audio": true}
}
```

`capabilities.discord` and `parts` are new in 0.2.0: an app that sees neither is talking to an older server, where
every part counts as on. `capabilities.beacon` stays `true` because older apps show their Discord page only when it is set.

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
track and the next two in the background when the track changes. When the chosen source has no romanization or
translation, the lines borrow them from another source by timestamp. Only the title, artist, album and length are sent
to those services. Sources are third-party and best-effort. `capabilities.lyrics` is `true` on servers that have this
endpoint.

## Parts

YTM Starcast's four parts can each be switched off. The apps hide what belongs to a part that is off.

| Part | What it covers | When it is off |
|---|---|---|
| `remote` | The YouTube Music remote: commands from the apps to the browser tab. | `POST /api/ytm` answers `409 {"error": "part_off"}` and nothing reaches the tab. The extension still reports what plays (Discord status and lyrics need it), and `GET /api/ytm/state` keeps answering. |
| `discord` | Discord status. The same switch as the Discord status engine's own on/off setting (and as `POST /api/beacon/visibility` show/hide), not a second one. | The Discord card is cleared. |
| `refine` | Refine inside YouTube Music. The server passes it on to the extension; Refine keeps its own detailed switches in the page. | Refine removes everything it added to the page. |
| `audio` | The computer's volume, outputs and equalizer in the apps. | The apps hide those controls. (The server still answers those routes.) |

| Endpoint | Body | Notes |
|---|---|---|
| `GET /api/parts` | — | `{"parts": {...}}` with all four. |
| `POST /api/parts` | any subset, e.g. `{"remote": false}` | Booleans only, known names only (`400` otherwise, and nothing changes). Replies `{"parts": {...}}` with all four. Open YouTube Music tabs hear about a change at once. |

`/api/state` and `GET /api/prefs` include `parts` too. `POST /api/prefs` with a `parts` key is checked the same way.

## Write

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/volume` | `{"percent": 0-100}` and/or `{"delta": ±n}` and/or `{"muted": bool}` | `503` if no output; `400` on bad values. |
| `POST /api/ytm` | `{"type": "transport", "action": "playpause\|next\|prev\|seek", "position"?: s}` | Goes to the extension if connected, else the host media player. `409 {"error": "part_off"}` while the remote is switched off (for every `/api/ytm` command). |
| `POST /api/ytm` | `{"type": "search"\|"play"\|"queueAdd"\|"home"\|"playlists"\|"startPlaylist"\|"artist"\|"speed"\|"lucky"\|…, …}` | Extension only (`{"error":"extension_offline"}` otherwise). See `extension/ytm_main.js`. `artist` takes `browseId`, `videoId` (the artist of that song) or `name` and returns `{name, subscribers, description, thumb, play, radio, shelves}`; `speed` takes `rate` (0.25–2); `lucky` plays a random pick from your home page; `search` takes `query` and an optional `filter` (`songs`, `videos`, `albums`, `artists` or `playlists`; without it, everything) and returns `{items}`, each with a `kind` (`song`, `artist`, `album`, `playlist`). |
| `POST /api/ytm` | `{"type": "play", "videoId", "playlistId"?}`, `{"type": "startPlaylist", "playlistId"}` or `{"type": "open"}` **with no YouTube Music tab connected** | The computer opens music.youtube.com in its browser (the song, the playlist or the home page) and replies `{"ok": true, "opened": true}`. Only valid ids are accepted and only music.youtube.com is ever opened. |
| `POST /api/media` | `{"action": "playpause\|next\|prev"}` or `{"action":"seek","position":s}` | Host media player directly. |
| `POST /api/media/pause-others` | `{}` | Pauses every other media player that is playing on the computer (not the YouTube Music song playing); replies `{paused: [names], supported}`. The shared preference `pauseOthers` (set through `POST /api/prefs`) makes the computer do this by itself whenever YouTube Music starts playing. |
| `POST /api/session` | `{"app": "<id>"\|null}` | Pin which player the server controls (`null` = auto). |
| `POST /api/output` | `{"name": "<output name>"}` | Make that output the default (`404` for an unknown name, `501` without `capabilities.outputs`). With the built-in equalizer on, the equalizer moves in front of the new output. |
| `POST /api/output/volume` | `{"name", "percent"?: 0-100, "muted"?: bool}` | One output's own level, also when it isn't the default. |
| `POST /api/eq` | `{"band": "bass\|mid\|high", "db": -12..12}`, `{"enabled": bool}` or `{"preset": "<name>"}` | `501` unless `capabilities.eq`; `enabled` needs `capabilities.eq_toggle` (the built-in equalizer). |
| `POST /api/engine` | `{"action": "<one of capabilities.engine_actions>"}` | Backend-specific maintenance. |
| `POST /api/prefs` | any JSON object (merged) | Replies with all prefs and `parts`. |

## Discord status

Discord status is built into the server (see [docs/DISCORD.md](DISCORD.md)). Its routes keep the names they had when it
was the separate YTM Beacon app, and the same reply shapes, so older apps keep working. Nothing of the Discord
connection itself is reachable through them.

`GET /api/beacon`:

```jsonc
{
  "reachable": true,
  "version": "0.2.0a1",                     // YTM Starcast's version
  "headline": "showing",                    // see below
  "visible": true,                          // false while switched off or hidden for a while
  "snoozeSec": 0,                           // seconds left of a timed hide
  "discord": {"state": "connected", "user": "…"},   // state: connected, connecting, disconnected…; user only when connected
  "detail": "…",                            // optional: the last problem talking to Discord, in words
  "extension": {"lastSeenAgoSec": 2, "quietAfterSec": 90, "everConnected": true},
  "nowPlaying": {"title": "…", "artist": "…", "album": "…", "artwork": "https://…",
                 "state": "playing", "positionSec": 12.3, "durationSec": 200.0},     // or null
  "presenceActive": true,                   // a card is on Discord right now
  "settings": {"privateMode": false, "showPaused": true, "showProgress": true, "showStateIcon": true,
               "showButtons": true, "showAlbumArt": true, "pauseTimeoutMinutes": 5, "statusDisplay": "name",
               "hideMatching": ["…"],       // new in 0.2.0: the "never show" words
               "clientId": ""},             // new in 0.2.0, read-only: "" = the built-in Discord application
  "fetchedAt": 1762800000000,
  "beaconApp": {"running": false, "installed": false, "canMigrate": false, "version": null},  // new in 0.2.0
  "install": {"installed": true, "canInstall": false, "canStart": false, "latest": null,
              "updateAvailable": false, "job": {"state": "idle", "message": ""}},   // kept so older apps offer nothing
  "dryRun": true                            // only under --dry-run
}
```

`beaconApp` describes the separate YTM Beacon app on this computer: `running` (it answers on its local port, so YTM Starcast
leaves Discord to it), `installed` and `canMigrate` (its program is where its installer puts it, so
`POST /api/beacon/migrate` can remove it), and its `version` when it runs.

`headline` is one of:

| Value | Meaning |
|---|---|
| `showing` | A card is on Discord. |
| `waiting` | A song is playing and the card is about to be sent. |
| `idle` | Nothing is playing. |
| `ad` | An advert is playing; nothing is shown meanwhile. (New in 0.2.0.) |
| `off` | Switched off (`parts.discord` false). |
| `snoozed` | Hidden for a while (`snoozeSec` left). |
| `paused-hidden` | Paused, and "show when paused" is off. |
| `paused-timeout` | Paused longer than `pauseTimeoutMinutes`. |
| `filtered` | The song matches one of the never-show words. |
| `discord-down` | The Discord desktop app can't be reached. |
| `extension-missing` | The browser extension has never reported. |
| `extension-quiet` | The browser extension stopped reporting. |
| `beacon-running` | The separate YTM Beacon app still runs, so YTM Starcast leaves Discord to it. (New in 0.2.0.) |
| `unsupported`, `unreachable` | Kept for older apps. The built-in Discord status never says `unsupported`; `unreachable` means it couldn't answer. |

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/beacon/visibility` | `{"action":"show"}`, `{"action":"hide"}` or `{"action":"hide","minutes":60}` | `show` switches Discord status on (also `parts.discord`) and ends a timed hide. `hide` without minutes switches it off. `minutes` is a whole number from 1 to 1440 and only goes with `hide`: the card comes back by itself (a restart of YTM Starcast also ends it). Replies `{enabled, snoozeSec, visible}`. Both can safely be repeated. |
| `POST /api/beacon/settings` | any of `privateMode`, `showPaused`, `showProgress`, `showStateIcon`, `showButtons`, `showAlbumArt` (true/false), `pauseTimeoutMinutes` (0–1440, 0 = never), `statusDisplay` (`name`\|`state`\|`details`), `hideMatching` (a list of words) | Only the keys you send change. `hideMatching` replaces the whole list; it is kept as at most 50 words of up to 80 characters, lower-cased, without repeats. `clientId`, `enabled`, anything else or a wrong type: `400`. Replies with the new `settings`. |
| `POST /api/beacon/migrate` | `{}` | Moves over from the separate YTM Beacon app: takes over its settings (the ones it uses now, else its settings file), runs its own `ytm-beacon uninstall` (which keeps its settings file and log), waits up to 5 s for it to stop, then starts YTM Starcast's Discord status. Replies `{"ok": true, "message": "…"}`. Under `--dry-run` it changes nothing and replies `{"ok": true, "dryRun": true, "message": "…"}`. Errors below. |
| `POST /api/beacon/install` | anything | Always `409 {"error": "built_in", "message": "…"}`: there is nothing to install any more. |

`POST /api/beacon/migrate` errors, each with a `message` in plain words:

| HTTP | `error` | Meaning |
|---|---|---|
| 409 | `nothing_to_move` | YTM Beacon isn't on this computer. |
| 409 | `beacon_not_found` | It runs, but its program isn't where its installer puts it, so it can't be removed from here. |
| 409 | `busy` | A move is already under way. |
| 502 | `import_failed` | Its settings exist but couldn't be read. Nothing was changed. |
| 502 | `uninstall_failed` | Its uninstall didn't finish. Its settings were already copied. |
| 502 | `still_running` | It was removed but still answers. |

While the separate YTM Beacon app still runs, `POST /api/beacon/visibility` and `POST /api/beacon/settings` change
nothing and answer `409 {"error": "beacon_running", "message": "…"}`: move over first.

Other errors: `400` for a bad body, and `500 {"error": "<words>"}` when the settings can't be saved. Under `--dry-run`
Discord is never contacted, and visibility and settings changes are kept in memory only.

## Extension channel (loopback)

Used only by the browser extension. Each YouTube Music tab names itself (`i` on the poll, `instance` in its state);
with several tabs open the server follows one (the one playing) and only that tab receives commands. A command that
times out is dropped, not run later.

| Endpoint | Body | Notes |
|---|---|---|
| `GET /ext/poll?i=<tab>` | — | Long poll (≤ 25 s). Replies with a command `{id, type, …}`, or with no `id` when there is none; both carry `"cfg": {"remote", "discord", "refine"}`. A waiting poll is answered at once when one of those switches changes or another tab says goodbye. Each poll also tells Discord status the tab is still open (a paused tab sends nothing else). A tab that hasn't reported since the server started gets `"resend": true` once, at once, and answers by sending its whole state again. |
| `POST /ext/state` | `{instance, now: {title, artist, album, art, playing, paused, buffering, position, duration, videoId, ad, …}, queue, ts, …}` (or `partial: true` with only `now`) | What the tab plays. Feeds the remote, the lyrics and Discord status. `now.album` (a string or `null`) and `now.ad` (`true` while an advert plays) are new in extension 2.0. Replies `{"ok": true, "cfg": {...}}`. |
| `POST /ext/result` | `{id, data}` | The answer to a command. |
| `POST /ext/bye` | `{"instance": "<tab>"}` | The tab is closing or leaving the page (sent on `pagehide`, as `text/plain`). The server forgets that tab at once, and clears its Discord card. Replies `{"ok": true}`; `400` without `instance`. |
| `GET /ext/status` | — | For the extension's toolbar button and Refine's panel: `{"app": "YTM Starcast", "version", "parts": {...all four}, "discord": {"headline", "visible", "state"}}`. `headline` uses the values above; it is `off` while `parts.discord` is off. |
| `POST /ext/parts` | any subset of `remote`, `discord`, `refine` (booleans) | Same as `POST /api/parts`; replies `{"parts": {...}}`. The extension may not switch `audio` (`400`). |
| `GET /ext/lyrics` | — | `/api/lyrics` for the extension's live lyrics inside YouTube Music (same parameters and answer). |

POSTs must come from `https://music.youtube.com` (the extension's content script) or send no `Origin`; requests from the
extension's own pages are refused, so its toolbar button goes through an open YouTube Music tab.

Old and new mix safely: a server before 0.2.0 sends no `cfg` and answers `404` on `/ext/status`, `/ext/parts` and
`/ext/bye`, which the extension reads as "an older app, every part on"; extensions before 2.0 ignore `cfg`.

## Sleep timer

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/sleep` | `{"minutes": 1..720}`, `{"minutes": "track"}` or `{"minutes": 0}` | Pause playback after that long, when the current song ends, or cancel. Replies `{remainingSec}`; `/api/state` has `sleep.remainingSec` (0 = no timer) and `capabilities.sleep`. With the remote switched off, it pauses through the computer's media player. |

## Planned (not implemented)

`GET /api/events` (Server-Sent Events replacing polling), `GET /api/stats`, mDNS advertisement, a
`/api/v1` prefix and a published OpenAPI document. See [ANDROID_PLAN.md](ANDROID_PLAN.md).
