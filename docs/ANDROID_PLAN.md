# Plan: from WebView wrapper to a real Android app

Today's [legacy shell](../android/legacy-webview/README.md) is a `WebView` that loads the server's web
app plus a notification service. It works, but everything is a web page: no true background behaviour,
WebView timers freeze with the screen off, and native integrations are bolted on through a JS bridge.

## Goals
1. **Feels native**: Material 3 / Material You, instant start, no browser chrome, smooth sliders.
2. **Works with the screen off**: lock-screen/notification media controls, hardware volume keys control the *computer*.
3. **Efficient**: push updates instead of polling; negligible battery use.
4. **Easy to set up**: find the computer automatically, pair by QR, no typing addresses.
5. **Keeps the YouTube Music remote** as the flagship (search, queue, mixes, likes) — now in native UI.

## Architecture
- **Kotlin + Jetpack Compose**, single-activity, MVVM. Min SDK 26.
- **Networking**: OkHttp + kotlinx.serialization against the documented [HTTP API](API.md);
  `Authorization: Bearer <token>`; SSE client for live state (falls back to short polling on older servers).
- **Discovery/pairing**: `NsdManager` for `_pocketfader._tcp`; scan a QR code from the server to get address + token.
  Token kept in `EncryptedSharedPreferences`.
- **Playback surface**: a foreground `MediaSessionService` publishing a `MediaSession` whose metadata/position/state
  mirror the computer's now-playing, so lock screen, Bluetooth headsets, Wear OS and Android Auto "just work".
- **Hardware volume keys**: a `VolumeProviderCompat` (remote playback volume) attached to that session, so the phone's
  volume buttons adjust the computer's volume even with the screen off — the main thing a WebView can't do.
- **Extras**: Quick Settings tile (mute / play-pause), Glance home-screen widget, share-sheet "Play on computer"
  for YouTube Music links, deep links.
- **System stats screen** once the server offers `/api/stats` ([ROADMAP](ROADMAP.md)).

## Server work that unblocks it (in this repo, first)
1. `GET /api/events` (SSE) with the same payload as `/api/state` + `/api/ytm/state`.
2. mDNS advertisement + `/pair` page with QR (link includes the token).
3. `/api/v1/*` prefix, OpenAPI spec, and an `api` field in `/api/info` for compatibility checks.
4. Token scopes or per-device tokens (revocable) so a lost phone can be cut off.

## Milestones (rough effort, one developer)
| # | Deliverable | Effort |
|---|---|---|
| M0 | Server prerequisites above | 2–3 days |
| M1 | Skeleton app: pairing, volume fader, transport, now-playing | 3–4 days |
| M2 | MediaSession service + hardware volume keys + notification | 3–4 days |
| M3 | Native YouTube Music: search, queue, playlists, likes | 5–7 days |
| M4 | Tile, widget, share target, settings, theming polish | 3–4 days |
| M5 | Signed release APK on GitHub Releases (+ F-Droid metadata); Play Store optional | 2–3 days |

Testing needs a physical device or emulator and the Android SDK; none of M1–M5 can be verified on this
repo's CI alone, so they should land behind an instrumented-test job once an emulator is available.
