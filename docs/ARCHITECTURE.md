# Architecture

```
 phone / tablet / laptop (browser PWA, later: native Android app)
        │  HTTP + JSON  (Tailscale, or LAN + token)
        ▼
 ┌──────────────────────────── pocketfader (this repo) ────────────────────────────┐
 │ server.py  – routing, auth token, Host/Origin checks, merged /api/state          │
 │   │                                                                              │
 │   ├── audio backend   pulse.py (pactl)  | windows/winaudio.py (pycaw) | none     │
 │   ├── engine backend  (optional)  windows/voicemeeter.py   – EQ, meter, actions  │
 │   ├── media backend   mpris.py (busctl) | windows/gsmtc.py (WinRT) | none        │
 │   └── bridge.py       long-poll relay ◄──────────────┐                           │
 └──────────────────────────────────────────────────────┼───────────────────────────┘
                                                        │ 127.0.0.1 only
                        browser extension (extension/) ─┘  drives music.youtube.com
```

## Principles

1. **The UI is capability-driven.** `/api/state.capabilities` says what the host can do (`volume`, `eq`,
   `meter`, `media`, `ytm_bridge`, engine actions). The web app hides what's missing, so a new backend
   (or a native client) never needs to special-case platforms.
2. **Backends are small, duck-typed and replaceable** — contracts are documented in
   [`backends/base.py`](../pocketfader/backends/base.py). Windows modules load lazily, so Linux never
   imports them.
3. **Reads are always safe; writes are explicit.** Polling only reads. Every write goes through
   `runner.Runner.write`, which `--dry-run` turns into a log entry. Tests use fakes.
4. **Never change the user's audio topology.** Volume/mute apply to `@DEFAULT_SINK@` only; the app never
   sets the default output or moves streams.
5. **Secure by default.** Loopback + Tailscale unless the user opts in to more, and then a token is mandatory.

## Request flow for YouTube Music

The extension is the *client*: a content script long-polls `GET /ext/poll` for commands and pushes
state with `POST /ext/state`. The phone calls `POST /api/ytm`; the server enqueues it, waits for the
extension's `POST /ext/result`, and answers. If the extension is offline, `transport` commands (and
`/api/ytm/state`) fall back to the media backend, shaped identically.

## Where to add things

| I want to… | Touch |
|---|---|
| Support another OS's volume | new `backends/<name>.py` + a branch in `backends/__init__.py` |
| Add EQ on Linux (e.g. EasyEffects / PipeWire filter-chain) | an *engine* backend with `capabilities = {"eq": True}` |
| Show computer statistics | a `stats` provider + `/api/stats` + `capabilities.stats` (see [ROADMAP](ROADMAP.md)) |
| Build a native client | [API.md](API.md) + [ANDROID_PLAN.md](ANDROID_PLAN.md) |
