## Pocketfader — pre-release

Control your computer's volume and music from your phone, including a full YouTube Music remote.
This is an early build for testing; expect rough edges.

### Downloads

| File | For | How to use |
|---|---|---|
| `pocketfader-…-linux-x86_64.tar.gz` | Linux computer (server) | Extract and run `./pocketfader`. Needs PipeWire/PulseAudio (`pactl`); `busctl` (systemd) is used for media-player control. For a background service that starts at login, use `install.sh` from the source instead. |
| `pocketfader-…-windows-x64.zip` | Windows computer (server) | Extract, run `pocketfader.exe`, or run `install-exe.ps1` to start it at logon. **Experimental.** |
| `pocketfader-android-….apk` | Android 5.0+ phone or tablet (client) | Install the APK (allow "install unknown apps"), open it, enter your computer's address (e.g. `my-computer.tailnet.ts.net:8777`) and tap **Test**, then **Save**. A native app: now playing, queue, search, output devices and equalizer, lock-screen controls, and the volume buttons control the computer. |
| `pocketfader-0.1.0-py3-none-any.whl` / `.tar.gz` | Any OS with Python 3.9+ | `pipx install pocketfader-0.1.0-py3-none-any.whl` |
| `SHA256SUMS.txt` | | Verify downloads with `sha256sum -c SHA256SUMS.txt`. |

You don't need the Android app to use Pocketfader: any phone, tablet or laptop can open the web
page the server prints and add it to the home screen.

### What's new in alpha.2

- **Native Android app** (Android 5.0+, Material 3): now playing, queue, search, mixer, and a Library tab
  that shows the web app for Home and playlists. Media notification and hardware volume keys included.
  A modern connection dialog supports access tokens and **Cloudflare Access** service tokens.
- **Queue control** in the web app and Android app: autoplay suggestions are shown separately and marked
  *Won't play* when the queue is on loop (or autoplay is off); shuffle, loop and autoplay switches; jump to
  any track; move, remove and "play next"; save the queue as a playlist.
- **Playlists**: playing or shuffling a playlist now falls back to the same navigation method as queue jumps
  on newer YouTube Music builds; edit your own playlists (reorder, remove, rename, delete), add a song to a
  playlist, and create new ones.
- **Output devices**: see every output with its own level, set the level before switching to it, and switch.
- **Built-in equalizer** (PipeWire): bass / mid / treble with presets, no Voicemeeter or EasyEffects needed.
  It is off by default; turning it off restores the previous output.
- **Tablets**: landscape tablets get a two-pane layout; phone and tablet widths and the album-art fallback
  were fixed.
- **Cloudflare**: a step-by-step guide for putting the server behind a Cloudflare Tunnel with a login
  (see `docs/CLOUDFLARE.md`).
- **Extension 1.1**: cover art, queue sections and modes, reliable reordering. **Reload the extension and
  refresh the YouTube Music tab** after updating.

### Quick start

1. Run the server on your computer. It prints an address such as `http://100.x.y.z:8777/`.
2. On your phone, install [Tailscale](https://tailscale.com) on both devices (recommended), or start the
   server with `--lan` to use your home Wi-Fi (this turns on an access token).
3. Open the address on the phone (or enter it in the Android app).
4. For the YouTube Music remote, load the `extension/` folder from the source in Brave or Chrome
   (`brave://extensions` → Developer mode → Load unpacked) and keep a music.youtube.com tab open.

### Status of this build

- **Linux server**: built and smoke-tested automatically (starts and serves the web app). Volume and
  media control are covered by automated tests and were checked against a live PipeWire system.
- **Windows server**: built and smoke-tested automatically (starts and serves); the Windows audio and
  media code has not been run on a real Windows machine since a refactor. Voicemeeter support is optional.
- **Android app**: built automatically; it compiles and packages but has not yet been run on a physical
  device or emulator, so expect rough edges. The APK is signed with a throwaway debug key, so a later release
  signed with a different key will need an uninstall first.
- **Equalizer and output switching**: verified against an isolated PipeWire instance (measured gain per band),
  not yet across many hardware setups.
- **Playlist editing**: the web app's editing screens were checked against a stand-in for the extension; the
  YouTube Music requests themselves have not yet been tried on a signed-in account.
- **Cloudflare setup**: the guide has not been run end to end against a real Cloudflare account.
- **macOS**: not supported yet.

Native playlists in the Android app, push updates, QR pairing and a computer-statistics view are planned; see
the roadmap in the repository.
