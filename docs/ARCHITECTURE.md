# Architecture

```
 phone / tablet / laptop (web app, Android app)     desktop window (ytm-starcast-gui)
        │  HTTP + JSON  (Tailscale, or LAN + token)         │  same API, on 127.0.0.1
        ▼                                                   ▼
 ┌───────────────────────────── ytm-starcast (this repo) ──────────────────────────────┐
 │ server.py  – routing, auth token, Host/Origin checks, merged /api/state, parts      │
 │   │                                                                                 │
 │   ├── audio backend   pulse.py (pactl) | windows/winaudio.py (pycaw) | none         │
 │   ├── engine backend  eq_pipewire.py (built-in EQ) | windows/voicemeeter.py | none  │
 │   ├── media backend   mpris.py (busctl) | windows/gsmtc.py (WinRT) | none           │
 │   ├── bridge.py       long-poll relay ◄──────────────────┐                          │
 │   └── beacon.py ─► discord/  Discord status engine ──────┼──► Discord desktop app   │
 └──────────────────────────────────────────────────────────┼──────────────────────────┘
                                                            │ 127.0.0.1 only
             browser extension (extension/) ────────────────┘  inside music.youtube.com:
             the remote's bridge, Refine, the toolbar button
```

Everything is one Python package, `starcast/`, using only the standard library on Linux (Windows adds `pycaw` and the
WinRT media projections).

## Principles

1. **The UI is capability-driven.** `/api/state.capabilities` says what the host can do (`volume`, `eq`,
   `meter`, `media`, `outputs`, `ytm_bridge`, `discord`, engine actions). The apps hide what's missing, so a new backend
   (or a native client) never needs to special-case platforms.
2. **Every part can be switched off.** `parts` (`remote`, `discord`, `refine`, `audio`) is stored with the server's
   prefs (Discord status's switch lives in its own settings) and sent to every app and to the extension. See
   [API.md](API.md#parts).
3. **Backends are small, duck-typed and replaceable**: contracts are documented in
   [`backends/base.py`](../starcast/backends/base.py). Windows modules load lazily, so Linux never
   imports them.
4. **Reads are always safe; writes are explicit.** Polling only reads. Every write goes through
   `runner.Runner.write`, which `--dry-run` turns into a log entry; under `--dry-run` Discord status never connects to
   Discord either. Tests use fakes.
5. **Never change the user's audio topology unasked.** The master volume and mute apply to the current default output
   (`@DEFAULT_SINK@`); another output's level, or which output is the default, changes only when the user picks it.
6. **Secure by default.** Loopback + Tailscale unless the user opts in to more, and then a token is mandatory. The only
   other site allowed to talk to the server is `https://music.youtube.com`, for the extension.

## Request flow for YouTube Music

The extension is the *client*: a content script long-polls `GET /ext/poll` for commands and pushes
state with `POST /ext/state`. The phone calls `POST /api/ytm`; the server enqueues it, waits for the
extension's `POST /ext/result`, and answers. If the extension is offline, `transport` commands (and
`/api/ytm/state`) fall back to the media backend, shaped identically. The replies to the extension carry
`cfg` (the remote, Discord status and Refine switches), and a waiting poll is answered at once when one changes.

## Discord status (`starcast/discord/`)

A standard-library port of YTM Beacon's presence engine and Discord IPC client. It opens no network port.

| Module | Does |
|---|---|
| `presence.py` | `Presence`, the one object the server uses: start/stop, feed from the extension, status, settings, visibility. |
| `tracker.py` | Which tab's song to show: a playing tab beats a paused one; a tab not heard from for 90 s is dropped. |
| `build.py` | Turns the song and the settings into Discord's activity (the "Listening to" card), including private mode, the never-show words and the pause timeout. |
| `engine.py` | Keeps Discord up to date: connects, sends at most every 2 s, notices seeks, reconnects with back-off, clears the card on stop. |
| `ipc.py` | Discord's local IPC protocol: the socket search on Linux and macOS (including Flatpak and Snap folders) and the named pipe on Windows. |
| `settings.py` | `discord.json` (YTM Beacon's `config.json` keys), and taking over YTM Beacon's settings on first start. |

The extension's existing bridge feeds it: every `POST /ext/state` (`now`, with `album` and `ad`), every
`GET /ext/poll` (the tab is still open; a paused tab pushes nothing else) and `POST /ext/bye` (the tab is gone). There
is no second reporter in the page.

`starcast/beacon.py` sits between the engine and the apps. It serves `/api/beacon*` in the same shape as when YTM Beacon
was a separate app, adds the `beacon-running` and `ad` headlines, and watches for that old app: every 10 s it asks
`GET /v1/ping` on `127.0.0.1:41713-41717`, and while the old app answers, the engine stays stopped (two programs on one
Discord card would fight). `POST /api/beacon/migrate` imports the old app's settings, runs its own uninstall, waits for
its ports to close and starts the engine.

The card's logo and play/pause badges are fetched by Discord from
`https://raw.githubusercontent.com/dash1101/ytm-starcast/<tag>/assets/discord/`, where `<tag>` comes from the running
version (`0.2.0a1` → `v0.2.0-alpha.1`, a development version → `main`). So `assets/discord/` must be part of every
release tag.

## Settings on disk

| | Linux (and macOS) | Windows |
|---|---|---|
| Settings | `~/.config/ytm-starcast/` | `%APPDATA%\ytm-starcast\` |
| Song cache | `~/.cache/ytm-starcast/audio` | `%USERPROFILE%\.cache\ytm-starcast\audio` |

The settings folder holds `config.json` (access token and server settings), `prefs.json` (shared app prefs and
`parts`), `discord.json` (Discord status), `gui.json`, `eq.json` and the built-in equalizer's `eq-chain.*` files,
`ytm-layout.json` (the extension's layout snapshot), `server.pid`/`server.log` when the desktop window starts the
server, and `updates/`.

The first time YTM Starcast starts, it moves an existing `pocketfader` settings folder to `ytm-starcast` in one rename. On Linux and
macOS the old path is left as a link to the new one; Windows gets no link. Nothing is moved under `--dry-run`, while unit
tests run, or with `STARCAST_NO_SETTINGS_MOVE=1`; if the move fails, the old folder keeps being used. The song cache moves
the same way. Removing the old program, service and start-at-login entries is the installers' job, never the running
server's.

## Where to add things

| I want to… | Touch |
|---|---|
| Support another OS's volume | new `backends/<name>.py` + a branch in `backends/__init__.py` |
| Add another Linux EQ (e.g. EasyEffects) | an *engine* backend with `capabilities = {"eq": True}` |
| Add a part with its own switch | `PARTS` in `server.py`, then the Parts pages of the web app, the Android app and the desktop window |
| Show computer statistics | a `stats` provider + `/api/stats` + `capabilities.stats` (see [ROADMAP](ROADMAP.md)) |
| Build a native client | [API.md](API.md) + [ANDROID_PLAN.md](ANDROID_PLAN.md) |

## Legacy identifiers

YTM Starcast used to be called Pocketfader, and its Discord status was the separate YTM Beacon app. These names stay as they
are **on purpose**: each one holds people's data, or apps and extensions already installed depend on it. Users never see
them. Don't rename them.

| Identifier | Why it keeps its name |
|---|---|
| Android `applicationId` `app.novalabs.pocketfader` | A new id is a different app: phones would lose their settings, library, downloads and widgets, and couldn't update in place. |
| Kotlin package and namespace `app.pocketfader.client`, the widget provider classes (`NowWideWidget` … `UpNextWidget`, `BeaconWidget`) | Renaming them removes the widgets people have placed. |
| Widget and notification intent actions (`app.pocketfader.widget.*`, `app.pocketfader.downloads.CANCEL`) | Stored inside placed widgets until they redraw. |
| SharedPreferences `pocketfader` and `pocketfader_settings`; notification channel id `pocketfader_playback` | They hold the server address, token and settings, and the user's notification choices. |
| Android signing key alias `pocketfader`; CI secrets `PF_KEYSTORE_B64`, `PF_KEYSTORE_PASSWORD`, `PF_KEY_ALIAS`; Gradle properties `pfVersionName`, `pfVersionCode` | Updates install over the old app only with the same key (see [RELEASING.md](RELEASING.md)). |
| Internal Android names `PocketfaderApp`, `PocketfaderTheme`, `Theme.Pocketfader`, `PfShape`, `PfApi` | Never shown; renaming is churn. |
| HTTP routes `/api/beacon`, `/api/beacon/visibility`, `/api/beacon/settings`, `/api/beacon/install` and their reply shapes; `capabilities.beacon` | Apps already on phones show and control Discord status through them. |
| The `pf_token` cookie | Renaming it would sign every browser out. |
| The web app's `vmr-*` localStorage keys | Each device's theme, pins, layout and other choices. |
| Port 8777 | The extension, saved addresses and the web app's storage (it belongs to the address) depend on it. |
| Extension page-world names: `Symbol.for("pocketfader.api")`, `Symbol.for("pocketfader.refine")`, `__pfRefineLoaded`, the `__vmr` message tag, `pf-` / `--pf-` CSS names and `data-pf-*` attributes | Shared by scripts that ship together; `__pfRefineLoaded` also stops Refine being added twice when an old and a new copy of the extension are loaded at once. |
| `pocketfader.refine.*` localStorage keys on music.youtube.com (`.v1`, `.speeds`, `.color`, `.lyrics`, `.part`) and the `?pf-refine=off` switch | Refine's settings in each browser; people have the switch written down. |
| PipeWire node names `pocketfader_eq` and `pocketfader_eq_out`, and the hidden-output prefix `pocketfader` | An equalizer chain left behind by an earlier version stays out of the output list and is still recognised. |
| Discord application id `1567329407901634670` | YTM Beacon's application: what Discord shows next to the card. Never change it. |
| Environment variables `POCKETFADER_REPO` and `POCKETFADER_GITHUB_TOKEN` (read after `STARCAST_REPO` and `STARCAST_GITHUB_TOKEN`), `YTM_BEACON_CLIENT_ID` (after `STARCAST_DISCORD_CLIENT_ID`), `YTM_BEACON_HOME`; the installer's `PF_APP_DIR`, `PF_BIN_DIR`, `PF_UNIT_DIR`, `PF_NO_SYSTEMD`, `PF_APPS_DIR`, `PF_ICON_DIR` | Existing setups keep working. |
| `dash1101/pocketfader` in the updater | Asked only when `dash1101/ytm-starcast` has no releases (GitHub forwards the old name to the new one). |
| Old paths and names the installers and the desktop window look for: `~/.config/pocketfader`, `~/.local/share/pocketfader`, `pocketfader.service`, `pocketfader.desktop`, the `Pocketfader` scheduled task and Run value, `Programs\Pocketfader`, YTM Beacon's program, service and settings | Only to move an old installation over and clean it up. |
| Component names in the code: `starcast/beacon.py`, `tests/test_beacon.py`, Kotlin `BeaconPanel`, `BeaconModel`, `parseBeacon`, the `widget_beacon` layout | They name the Discord status component, not the product. |
