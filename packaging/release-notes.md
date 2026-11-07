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

### What's new in alpha.4

- **Word-by-word lyrics** (Apple Music style): the line being sung fills in word by word, and letter by letter inside
  the current word, in the Android app and the web app. It uses KuGou's word timings when it has the song; other
  songs keep line-by-line sync. Instrumental breaks now show a large ♪ that lights up while the break lasts.
- **Faster lyrics**: all sources are asked at once, the answer comes back as soon as something synced is in (about
  2 s cold, instant when cached), and the computer looks lyrics up in the background when a track changes (and for the
  next two), so they are usually ready before you open them.
- **Closer sync**: the playback position the computer reports is now brought up to date, the extension reports it
  every quarter second, and the app measures it from the middle of the request. A **Sync** setting nudges the lyrics
  earlier or later (−2 s to +2 s) if your device still runs a little off.
- **Smoother Up next on slow tablets**: rows are lighter (small pictures with rounded corners baked in, one shared
  actions dialog instead of a menu per row).
- **Updates**: the computer checks GitHub for new releases (works for a private repository through `gh auth login` or
  `GITHUB_TOKEN`). The Android app shows a banner and has Settings → Updates to download and install through the
  computer; the desktop window has **Update now**; `pocketfader --update` does the same from a terminal.
- **Extension 1.3** (quarter-second position updates). Reload it after updating.

**Updating the Android app in place needs a signing key.** This is the first release prepared for it: Android only
installs an update over an installed app when both use the same key, and earlier builds used a throwaway key, so
alpha.3 has to be uninstalled once. See `docs/RELEASING.md`. If the repository has no signing secrets set up, this
release is still signed with a throwaway key and cannot be installed over an existing copy.

Earlier: alpha.3 (Settings screen, native Library, queue and lyrics on the Playing page, desktop window) and
alpha.2 (native Android app, queue control, playlist editing, outputs, equalizer, Cloudflare guide).

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
