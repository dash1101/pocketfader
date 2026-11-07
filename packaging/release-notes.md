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

### What's new in alpha.5

- **Shuffle and loop errors fixed (needs the extension reloaded)**: some YouTube Music layouts hide the player bar in a
  shadow tree, so the extension couldn't find the shuffle and loop buttons. It now finds them there, or by their label,
  and a failure says what it saw. **Reload extension 1.4 and refresh the YouTube Music tab.**
- **Buttons no longer flicker**: shuffle, loop, autoplay, like and play/pause keep what you just tapped for a few seconds,
  so a poll that arrives before the computer has applied the change can't flip them back and forth. Lyrics no longer reload
  when the track length arrives a moment after the title, and the source chips respond at once.
- **Jump to the current line**: a button appears when the line being sung is off screen.
- **Lyrics layout** (Settings → Lyrics, or **Aa Layout** on the lyrics screen): alignment (left, centered, right), text size,
  line spacing and how faint the other lines are, with a live sample.
- **A new look** for the Android app: the Now playing screen takes its colour from the cover and the current lyric uses
  it too, a bigger cover that eases back when paused, bolder titles, playlists as a cover grid, icons on the Settings sections.
- Earlier in alpha.4: word-by-word lyrics, faster lyrics, closer sync, a lighter Up next, and updates.

**In-place Android updates still need the signing key uploaded to the repository** (see `docs/RELEASING.md`). Until then this
release is signed with a throwaway key and must be installed after uninstalling the previous one.

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
