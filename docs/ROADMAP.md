# Roadmap

Ordered by value. Nothing below the "Done" line is implemented yet.

## Done (0.1)
- Linux host: PipeWire/PulseAudio volume + MPRIS media control, no extra software.
- Voicemeeter demoted to an opt-in Windows extra; UI adapts to the host's capabilities.
- YouTube Music extension bridge kept first-class; MPRIS fallback when it's offline.
- Access token, Host/Origin protection, `--lan`, `--dry-run`, installers, tests, CI.

## 0.2 — Make it effortless
- [ ] **QR pairing**: print/show a QR code of the connect link (token included) in the terminal and on a `/pair` page.
- [ ] **mDNS advertising** (`_pocketfader._tcp`) so apps can find the computer without typing an address.
- [ ] **Server-Sent Events** (`GET /api/events`) replacing 0.5–1 s polling → lower latency and battery use.
- [ ] Versioned `/api/v1/*` and a published OpenAPI file.
- [ ] Package for `pipx`/AUR/`.deb`; Windows `.exe` installer; macOS host (CoreAudio via `osascript`, `Now Playing`).
- [ ] Linux EQ through an *engine* backend (EasyEffects or a PipeWire filter-chain), enabling the existing EQ UI.

## 0.3 — Native Android app
See [ANDROID_PLAN.md](ANDROID_PLAN.md). Goal: replace the WebView shell with a real Kotlin/Compose app —
lock-screen controls, hardware volume keys that work with the screen off, quick-settings tile, widgets.

## 0.4 — Computer statistics
A "System" card (and later a native screen) showing the host's health.

- **Server**: a `stats` provider interface mirroring the other backends, exposed as `GET /api/stats` and
  streamed on `/api/events`; advertised as `capabilities.stats` so clients show it only when present.
- **Linux provider** (stdlib only): CPU % and per-core from `/proc/stat`, load, RAM/swap from
  `/proc/meminfo`, temperatures from `/sys/class/hwmon`, uptime, disk use via `os.statvfs`,
  network throughput from `/proc/net/dev`, battery via `/sys/class/power_supply`, GPU via
  `amdgpu` sysfs / `nvidia-smi` when present.
- **Windows provider**: `psutil` (optional extra).
- **UI**: sparklines over the last few minutes (keep a small ring buffer server-side), thresholds that
  tint values, and a toggle to disable stats entirely (privacy).
- Read-only by design: no process killing or power actions unless added later behind an explicit setting.
