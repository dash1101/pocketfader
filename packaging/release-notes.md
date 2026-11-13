### What's new in 0.2.3-alpha

- **The web app looks and works like the Android app:** Playing, Search and Settings. On phones the player is a page of
  its own, with a navigation bar (or the floating pill) and the mini player on every other page; on wide screens the
  player stays on the left with Queue, Lyrics, Search and Settings beside it. Search holds For you, Library and
  Favorites as tabs; Settings is a list of categories (the mixer is Sound & outputs).
- **The desktop window is redrawn in Material You** (on Windows): rounded cards, switches and buttons, dark or light as
  Windows is (it follows a change), a dark title bar, YTM Starcast's icon in the window and the taskbar, and sizes
  that follow the display's scaling. The Windows programs carry the icon too. On Linux the window follows the
  desktop's dark setting (KDE, GNOME and others).
- **Android downloads:** a Downloading card with the progress of everything (megabytes, speed, about how long is left)
  and of each song, and Cancel the rest; the notification says the same. The quality choices name their bitrates.
  Warnings when the space for automatic downloads (or the number of smart downloads) is more than the phone has room
  for, and the phone's free space shows by your downloads. Automatic downloads always leave 1 GB of the phone free.
- **Android:** Settings → Sound & outputs shows the phone itself while music plays on it: its volume, where the sound
  goes (speaker, headphones, Bluetooth, USB) and Android's output picker.
- The extension's toolbar popup no longer shows an empty update card.

### Updating from 0.2.0-alpha.10 or older

- **Computer:** versions up to 0.2.0-alpha.10 don't recognize the new version names, so update this once by hand:
  `./install.sh` (Linux), `install-exe.ps1` (Windows) or Orbit.
- **Android app:** once the computer runs 0.2.1-alpha or later, the app offers this update through the computer one last
  time (Settings → Updates). From then on it updates from GitHub by itself.

[Install and downloads](https://github.com/dash1101/ytm-starcast#readme) ·
[Changelog](https://github.com/dash1101/ytm-starcast/blob/main/docs/CHANGELOG.md)
