### What's new in 0.2.4-alpha

- **Discord status for music playing on the phone:** when the Android app plays music on the phone itself, your
  computer shows it as your Discord status, through the Discord app on the computer, just as it does for YouTube Music
  in the browser. It works wherever the phone can reach the computer (at home or over Tailscale), and the phone needs no
  Discord app. Switch it in the Android app: Settings → Discord status → Show what this phone plays (on by default).
  Whichever started playing last, the phone or the computer, is the one shown.
- **Privacy and terms:** [Privacy](https://github.com/dash1101/ytm-starcast/blob/main/PRIVACY.md) lists everything YTM
  Starcast keeps and what it sends where; [Terms of use](https://github.com/dash1101/ytm-starcast/blob/main/TERMS.md)
  covers the rest.
- The web app no longer tells Google or GitHub the address it was opened at when it loads covers and images.

### Updating from 0.2.0-alpha.10 or older
 0.2.0-alpha.10 or older

- **Computer:** versions up to 0.2.0-alpha.10 don't recognize the new version names, so update this once by hand:
  `./install.sh` (Linux), `install-exe.ps1` (Windows) or Orbit.
- **Android app:** once the computer runs 0.2.1-alpha or later, the app offers this update through the computer one last
  time (Settings → Updates). From then on it updates from GitHub by itself.

[Install and downloads](https://github.com/dash1101/ytm-starcast#readme) ·
[Changelog](https://github.com/dash1101/ytm-starcast/blob/main/docs/CHANGELOG.md)
