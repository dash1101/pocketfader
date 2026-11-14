### What's new in 0.2.5-alpha

- **The web app looks like Refine:** its colours now come from the cover of what plays, the same tones Refine gives
  YouTube Music (the new Album colours theme, now the default; the other themes are still in Settings → Appearance).
  The tabs sit in one pill bar beside YTM Starcast's name, with the chosen one tonal, as do the song playing in the
  queue and the chosen chips. The play button and switches take Refine's shapes.
- **Download in YouTube Music's song menu (Refine):** the ⋮ menu's Download opens to two choices: YouTube Music
  Premium's own download, or **Save to this computer**, which saves the song as an audio file, with its details and
  cover, in your Music folder (Music › YTM Starcast). Saving uses yt-dlp on the computer (and ffmpeg, when there, for
  the details and cover).
- **No more stutter when a song changes on the player page:** the Halo visualizer behind the cover is drawn far more
  cheaply, and looks the same.
- With the wavy bar off, the song's progress bar takes the album's colour instead of YouTube Music's red.
- A tab beside the player that YouTube Music can't open for a song (Comments, when they are turned off) is crossed out.
- Up next: every song in view glides in, one after another, when the tab or the player page opens (it used to be only
  the first twelve).
- YTM Starcast's logo no longer runs into the search box in a narrow window: only its tile shows there.

### Updating from 0.2.0-alpha.10 or older

- **Computer:** versions up to 0.2.0-alpha.10 don't recognize the new version names, so update this once by hand:
  `./install.sh` (Linux), `install-exe.ps1` (Windows) or Orbit.
- **Android app:** once the computer runs 0.2.1-alpha or later, the app offers this update through the computer one last
  time (Settings → Updates). From then on it updates from GitHub by itself.

[Install and downloads](https://github.com/dash1101/ytm-starcast#readme) ·
[Changelog](https://github.com/dash1101/ytm-starcast/blob/main/docs/CHANGELOG.md)
