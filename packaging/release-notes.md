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

### What's new in alpha.8

- **Ambient cover glow (Android)**: the glow is no longer one flat colour. It's a soft blur of the cover's own edges carried
  outward, so each side of the album art lights up in the colours that are actually there. It's drawn behind the list, so it
  spreads under the top bar instead of being cut off in a hard line.
- **Smoother seek wave**: drawn from many short steps and eased flat at both ends, so there are no rough edges.
- **Settings in pages**: Settings opens as an overview of categories (Sound & outputs, Lyrics, Controls, Appearance, Effects,
  Screen & always-on, Connection, Discord presence, Updates, Performance, About). The old **Mixer** (volume, output devices,
  equalizer) is now **Sound & outputs**, and Discord presence has its own page.
- **Settings in the bottom navigation** replaces Mixer. In Settings → Appearance you can also choose a **floating pill**
  navigation bar instead of the classic bar.
- **Scrolling on old tablets**: fixed a slowdown from alpha.7 where the whole screen was redrawn on every update (the sleep timer's
  clock changed each time). There's also a new **Pictures in the queue** switch (Settings → Performance) to turn the little
  cover pictures off if the queue is still slow on an old device. Not measured on a device, so tell us how it feels.
- **Like and autoplay (extension 1.7)**: newer YouTube Music builds use different player controls; the extension now looks for the
  like button there. Autoplay can't be changed while loop is on (YouTube Music hides and ignores it), so the app says so
  instead of silently failing. **Reload the extension and refresh the YouTube Music tab.**
- Friendlier messages in the app for a queue move, like or autoplay that didn't go through.

### Earlier

- alpha.7: install/start/update YTM Beacon from the app, effects (cover glow, wavy seek bar, cover shapes, transitions), a sleep timer, sturdier queue reordering.
- alpha.6: readable colours on every theme, accent colour picker, Discord presence card, safer app updates.
- alpha.5: jump to the current lyric line, lyrics layout options, steadier buttons.
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
