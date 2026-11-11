# Roadmap

Ordered by value. Nothing below the "Done" lists is implemented yet.

## Done (0.1)
- Linux host: PipeWire/PulseAudio volume + MPRIS media control, no extra software.
- Voicemeeter demoted to an opt-in Windows extra; UI adapts to the host's capabilities.
- YouTube Music extension bridge kept first-class; MPRIS fallback when it's offline.
- Access token, Host/Origin protection, `--lan`, `--dry-run`, installers, tests, CI.
- Native Android app (see [ANDROID_PLAN.md](ANDROID_PLAN.md)): lock-screen controls, hardware volume keys, widgets,
  playing on the phone itself.
- Refine: improvements inside YouTube Music (see [REFINE.md](REFINE.md)).

## Done (0.2)
- One product: Discord status is built in (it used to be the separate YTM Beacon app), with a move-over from Pocketfader
  and YTM Beacon in the installers and the apps.
- Every part has a switch (YouTube Music remote, Discord status, Refine, computer audio); the extension has a toolbar
  button with the same switches.
- The extension is a release download.

## Next: make it effortless
- [ ] **QR pairing**: print/show a QR code of the connect link (token included) in the terminal and on a `/pair` page.
- [ ] **mDNS advertising** (`_ytm-starcast._tcp`) so apps can find the computer without typing an address.
- [ ] **Server-Sent Events** (`GET /api/events`) replacing 0.5–1 s polling → lower latency and battery use.
- [ ] Versioned `/api/v1/*` and a published OpenAPI file.
- [ ] Package for `pipx`/AUR/`.deb`; a Windows installer; a macOS host (CoreAudio via `osascript`, `Now Playing`).
- [ ] The extension in a store, with automatic updates, and for Firefox.

## Later: computer statistics
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
