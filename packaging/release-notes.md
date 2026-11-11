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

### What's new in alpha.16

**Refine: a better YouTube Music, inside YouTube Music.** With the Pocketfader extension (1.15) loaded, music.youtube.com gets a
✦ button in its top bar. Everything below has its own switch there, and opening `music.youtube.com/?pf-refine=off` starts a page
without any of it. These changes depend on how YouTube Music's page is built, so treat them as experimental.

- **A Material 3 Expressive look**, in layers you can switch separately: rounder shapes (covers, lists, menus, search), colours
  taken from the album art, expressive player controls (a bigger play button that turns from a circle into a rounded square while
  playing, tinted pressed buttons), a **wavy progress bar** that ripples while a song plays and flattens when you pause, and a
  glow behind the cover.
- **A richer speed menu** on YouTube Music's own speed button: any speed from 0.25× to 3×, quick presets, keep or drop the
  pitch, and remember a speed for one song or a whole playlist (it comes back when that song or playlist plays, and doesn't spill
  over onto others).
- **Live lyrics** in YouTube Music: a lyrics button in the player opens time-synced lyrics (word by word where the source has
  it) from the Pocketfader app, following the song; tap a line to jump there.
- **Speed from the keyboard:** Shift+. faster, Shift+, slower.
- **A "play something you might like" button** among the player controls.
- **A playlist maker:** start from the song playing, your queue, one of your playlists or your liked songs. It asks YouTube Music
  for the radio of each starting song, ranks what they have in common, keeps any one artist from taking over, and shows the
  result to review: untick songs, rename it, try again for a different mix, then save it as a new private playlist or add it to
  one of yours, and play it.

### What's new in alpha.15

**Your library, without the computer.** The Android app now keeps a copy of your YouTube Music library and makes its own
recommendations, so playing on the phone feels the same whether or not YouTube Music is open on your computer.

- **Library sync:** your playlists (all their songs), liked songs and YouTube Music home page are copied to the phone whenever
  YouTube Music is open on the computer (Settings → Library & offline). Without it, Search → Playlists shows the synced copy and
  every playlist opens and plays from it. Likes and songs you add to your own playlists in the meantime are sent to your account
  at the next sync.
- **For you, made on the phone:** Quick picks, Listen again, Mixed for you (a radio per favourite artist), Forgotten favorites and
  Discover, worked out from what you play (on the phone and on the computer), skip and like, using YouTube Music's own song
  radios. "I'm feeling lucky" uses them too. Your listening history stays on the phone.
- **Better autoplay on the phone:** it continues with YouTube Music's radio for the song, not with YouTube's related videos (which
  could add hour-long compilations), and leaves out songs you just heard or keep skipping.
- **Automatic downloads:** keep your liked songs downloaded, keep any playlist on the phone ("Keep on this phone" on the
  playlist), and smart downloads (your most played songs and current quick picks). A space limit and a Wi-Fi only option;
  songs you downloaded yourself are never removed automatically.
- **Faster, sturdier downloads,** fetched in pieces at full speed, with a progress notification and a Stop button.
- **Playing on the phone:** shuffle keeps "Play next" working and shows the real order; a sleep timer (including "when this
  song ends"); the queue and position are kept when the app is closed; a song that won't load is retried once, and playback stops
  instead of racing through the queue when nothing loads (no connection).
- **Search:** suggestions as you type, recent searches, and results that stay when you switch screens. Song names found
  without the computer are tidied ("Bohemian Rhapsody" by Queen, not "Queen – Bohemian Rhapsody (Official Video)").
- **Fixes:** the widgets' lucky, sleep and mute buttons act on the phone when music plays there; likes work while playing on the
  phone; moving playback to the phone starts from the right song.
- **Computer and extension (1.14):** with several YouTube Music tabs open, Pocketfader follows the one that's playing instead of
  mixing them up; commands sent while the tab was reloading no longer run late (three taps on Next don't skip three songs a
  minute later). Reload the extension in your browser to get 1.14.

### Earlier

- alpha.14: new Android application ID `app.novalabs.pocketfader` (uninstall the old app once).
- alpha.13: widgets without a background, plus Discord presence and Up next widgets.
- alpha.12: widgets redesigned (clean card, rounded cover, live elapsed time); extension 1.13 quieter in the background.
- alpha.11: home-screen widgets and a new icon.
- alpha.10: play on the phone itself (YouTube Music and local files), downloads with lyrics, Search with Playlists and On this phone tabs.
- alpha.9: smoother cover glow, artist pages, search recommendations, soft edges, web app restyle.
- alpha.8: ambient cover glow, Settings in pages, Settings in the navigation, floating pill navigation, scrolling fixes.
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
