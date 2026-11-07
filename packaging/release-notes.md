## Pocketfader — pre-release

Control your computer's volume and music from your phone, including a full YouTube Music remote.
This is an early build for testing; expect rough edges.

### Downloads

| File | For | How to use |
|---|---|---|
| `pocketfader-…-linux-x86_64.tar.gz` | Linux computer (server) | Extract and run `./pocketfader`. Needs PipeWire/PulseAudio (`pactl`); `busctl` (systemd) is used for media-player control. For a background service that starts at login, use `install.sh` from the source instead. |
| `pocketfader-…-windows-x64.zip` | Windows computer (server) | Extract and open `pocketfader-gui.exe` for a small control window, or run `pocketfader.exe` / `install-exe.ps1` directly. **Experimental.** |
| `pocketfader-android-….apk` | Android 5.0+ phone or tablet (client) | Install the APK (allow "install unknown apps"), open it, enter your computer's address (e.g. `my-computer.tailnet.ts.net:8777`) and tap **Test**, then **Save**. A native app: now playing, queue, search, output devices and equalizer, lock-screen controls, and the volume buttons control the computer. |
| `pocketfader-<version>-py3-none-any.whl` / `.tar.gz` | Any OS with Python 3.9+ | `pipx install pocketfader-<version>-py3-none-any.whl` |
| `SHA256SUMS.txt` | | Verify downloads with `sha256sum -c SHA256SUMS.txt`. |

You don't need the Android app to use Pocketfader: any phone, tablet or laptop can open the web
page the server prints and add it to the home screen.

### What's new in alpha.3

- **Live lyrics** in the Android app and the web app: lines follow the song (tap one to jump), with sources you
  can switch between (LRCLIB, NetEase, YouTube Music), a "look again" button, **romanization** (Korean,
  Japanese kana, Cyrillic, Greek, and real romaji where NetEase has it) and **translations** where available.
- **Android: a real Settings screen** behind the gear: theme (including true Black), Material You, keep the screen
  on (while playing / always) with dimming, full screen, **pixel shift** for OLED screens, volume-button step,
  notification on/off, lyrics options, update speed and reduced animations.
- **Android: the Library is native** now (Home shelves, playlists, playlist editing), not an embedded web page.
- **Android: queue and lyrics live on the Playing page**; the separate Queue tab is gone. Shuffle and loop sit next
  to the transport buttons, a caption says exactly what is being shuffled or looped, the tracks already played are
  listed, and autoplay suggestions can be added to your queue with one tap even when they won't play.
- **Faster on older phones**: the screen redraws only what changed, the app polls less, and the release build is
  shrunk (about 1.5 MB). An unshrunk `…-unshrunk-….apk` is attached too, in case the shrunk one misbehaves.
- **A desktop window for the server**: GTK on Linux (also in the application menu after `install.sh`), Tk on
  Windows (`pocketfader-gui.exe`). Start or stop the server, start at login, allow your home Wi-Fi, set a Cloudflare
  hostname, and copy the phone addresses.
- **Web app**: a Lyrics tab next to Up Next, and *Keep screen on* and *Pixel shift* in Settings.
- **Extension 1.2** adds the YouTube Music lyrics command. **Reload the extension** after updating.

Earlier in alpha.2: the native Android app, queue control with loop-aware autoplay separation, playlist editing,
output devices with per-device levels, the built-in equalizer, a two-pane tablet layout and the Cloudflare guide.

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
- **Lyrics**: the sources were checked live from a Linux machine for a few songs (including Japanese and
  Korean); they are third-party services and may change or have nothing for a song.
- **Desktop window**: the Linux (GTK) window was rendered and its controller tested, but not driven through every
  button on many desktops; the Windows (Tk) window and its start-at-login entry have not been run on Windows.
- **Android settings and pixel shift** compile and are described above but haven't been run on a real device.
- **Equalizer and output switching**: verified against an isolated PipeWire instance (measured gain per band),
  not yet across many hardware setups.
- **Playlist editing**: the web app's editing screens were checked against a stand-in for the extension; the
  YouTube Music requests themselves have not yet been tried on a signed-in account.
- **Cloudflare setup**: the guide has not been run end to end against a real Cloudflare account.
- **macOS**: not supported yet.

Native playlists in the Android app, push updates, QR pairing and a computer-statistics view are planned; see
the roadmap in the repository.
