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

### What's new in alpha.6

- **Readable on every theme (Android)**: text that didn't name a colour fell back to black, which vanished on the Dark and
  Black themes. Every screen now starts from the theme's own text colour. On the light theme, the line being sung is now
  your accent colour instead of a washed-out grey.
- **Accent colour (Android)**: Settings → Appearance. Pick Auto, one of ten colours, or any colour with sliders or a hex code.
  It can never disappear into the theme: a dark accent on the Black theme (or a pale one on Light) is shown a little
  lighter or darker, and the setting tells you when it did that. The cover's colour is now a separate, optional tint
  (**Cover colours in the background**, **Lyric highlight follows the cover**), and the tint is weakened whenever it
  would make the text hard to read.
- **Discord presence**: if you use [YTM Beacon](https://github.com/dash1101/ytm-beacon) (0.1.4+) on the computer, the
  Android app (Mixer) and the web app (Settings) show whether it's working, switch it on or off, hide it for 30 min, 1 h or
  3 h, and change what it shows. See [docs/BEACON.md](docs/BEACON.md).
- **Update installer**: if Android would refuse an update because it was signed with a different key (older test builds
  used a temporary key), the app now says so and points you to the download page, instead of ending in "App not installed".
  This version and later are signed with the permanent key and install over each other. If you're on alpha.4 or earlier,
  uninstall once, then install this one.
- **Shuffle and loop**: the extension (now **1.5**) searches more of the page for the buttons, falls back to the player's
  own shuffle and loop controls when a layout has none, no longer searches the whole page on every update, and reports
  its version. **Reload the extension in your browser and refresh the YouTube Music tab** (an old tab keeps running the old
  version until you do). The app now says that, in plain words, if the button still can't be found.

### Earlier

- alpha.5: jump to the current lyric line, lyrics layout options, steadier buttons, cover-tinted Now playing screen.
- alpha.4: word-by-word lyrics, faster lyrics, closer sync, a lighter Up next, and in-app updates.

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
- **Word-by-word lyrics and the update installer** compile and were checked against the live services and with tests, but have not been run on a real Android device.
- **Android settings and pixel shift** compile and are described above but haven't been run on a real device.
- **Equalizer and output switching**: verified against an isolated PipeWire instance (measured gain per band),
  not yet across many hardware setups.
- **Playlist editing**: the web app's editing screens were checked against a stand-in for the extension; the
  YouTube Music requests themselves have not yet been tried on a signed-in account.
- **Cloudflare setup**: the guide has not been run end to end against a real Cloudflare account.
- **macOS**: not supported yet.

Native playlists in the Android app, push updates, QR pairing and a computer-statistics view are planned; see
the roadmap in the repository.
