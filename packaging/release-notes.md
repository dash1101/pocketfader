### What's new in 0.2.1-alpha

- New version names: `0.2.1-alpha`, `0.2.2-alpha` and so on, instead of ever more numbers after "alpha".
- The Android app finds new versions on GitHub itself and downloads them from there (checked against the release's
  checksums), so updating the app no longer goes through the computer. When GitHub can't be reached from the phone, it
  still asks the computer.
- The Android app has a **Comments** tab beside Up next and Lyrics: the comments of the song or video that's playing,
  with replies.
- **Offline playlists** on the phone: "Download all" on a playlist or album keeps it together, in its order, and you can
  make your own from downloaded songs (⋮ → Add to an offline playlist). Every song is still in Downloaded.
- Automatic downloads can keep up to 1000 songs and use up to 100 GB (or any amount with Custom).
- Full screen can show the time and the battery in the app's top bar.
- Songs played on the phone show their covers sharp, in the player, the notification and on the lock screen.
- Music on the phone pauses when headphones, an AUX cable, or a Bluetooth or USB speaker disconnect (a setting, on by
  default).
- The volume sliders follow the phone's volume buttons at once, and glide.
- On tablets, the cover's glow no longer stops at a hard edge beside the tabs.
- The browser extension (2.4.0) updates itself: YTM Starcast copies each new version into the extension's folder, and the
  extension reloads into it (straight away with no YouTube Music tab open, otherwise from **Update now** in its toolbar
  button). This first time, reload it once by hand on the browser's extensions page.
- Release notes show only what's new. Earlier versions are in the changelog.

### Updating from 0.2.0-alpha.10 or older

- **Computer:** versions up to 0.2.0-alpha.10 don't recognize the new version names, so update this once by hand:
  `./install.sh` (Linux), `install-exe.ps1` (Windows) or Orbit.
- **Android app:** once the computer runs 0.2.1-alpha, the app offers this update through the computer one last time
  (Settings → Updates). From then on it updates from GitHub by itself.

[Install and downloads](https://github.com/dash1101/ytm-starcast#readme) ·
[Changelog](https://github.com/dash1101/ytm-starcast/blob/main/docs/CHANGELOG.md)
