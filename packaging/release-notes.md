### What's new in 0.2.1-alpha

- New version names: `0.2.1-alpha`, `0.2.2-alpha` and so on, instead of ever more numbers after "alpha".
- The Android app finds new versions on GitHub itself and downloads them from there (checked against the release's
  checksums), so updating the app no longer goes through the computer. When GitHub can't be reached from the phone, it
  still asks the computer.
- Release notes show only what's new. Earlier versions are in the changelog.

### Updating from 0.2.0-alpha.10 or older

- **Computer:** versions up to 0.2.0-alpha.10 don't recognize the new version names, so update this once by hand:
  `./install.sh` (Linux), `install-exe.ps1` (Windows) or Orbit.
- **Android app:** once the computer runs 0.2.1-alpha, the app offers this update through the computer one last time
  (Settings → Updates). From then on it updates from GitHub by itself.

[Install and downloads](https://github.com/dash1101/ytm-starcast#readme) ·
[Changelog](https://github.com/dash1101/ytm-starcast/blob/main/docs/CHANGELOG.md)
