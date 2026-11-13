### What's new in 0.2.2-alpha

- The Android app's notification shows the song again when music plays on the phone (with its cover and progress, on
  the lock screen too): the phone's player never handed its session to the notification.
- The now-playing card stays at the bottom of every page on phones (the pages make room for it); tap it for the player.
- Play on a playlist, album, offline playlist or your downloads takes you to the player.
- Download quality: Best (about 1.2 MB a minute), Standard or Data saver (about 0.4 MB a minute), in Settings → Downloads
  & offline. Space estimates now go by each song's length and the quality (and by your own downloads, once there are a
  few), and say how many songs fit.
- Smart downloads can **Fill the space**: as many songs as fit in the space you set (most played and quick picks first,
  then liked songs, what you play most and your playlists).
- Playlists and albums open at once when you've opened them before, and the first page shows while the rest loads.
- The Comments tab only shows for songs that have comments.
- A new battery in full screen (on its side, filled to the charge, with a bolt while charging).
- Settings: Library & offline is part of Downloads & offline now.

### Updating from 0.2.0-alpha.10 or older

- **Computer:** versions up to 0.2.0-alpha.10 don't recognize the new version names, so update this once by hand:
  `./install.sh` (Linux), `install-exe.ps1` (Windows) or Orbit.
- **Android app:** once the computer runs 0.2.1-alpha or later, the app offers this update through the computer one last
  time (Settings → Updates). From then on it updates from GitHub by itself.

[Install and downloads](https://github.com/dash1101/ytm-starcast#readme) ·
[Changelog](https://github.com/dash1101/ytm-starcast/blob/main/docs/CHANGELOG.md)
