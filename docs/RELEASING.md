# Releasing

Before the first YTM Starcast release, the repository has to move once: see
[Moving the repository](#moving-the-repository-once).

1. Bump `version` in `pyproject.toml` and `__version__` in `starcast/__init__.py` (a test checks they agree). Versions
   are named `0.2.1-alpha`, `0.2.2-alpha` …: the middle number for bigger steps, the last one for each release, and no
   number after "alpha".
2. Move the previous version's section from `packaging/release-notes.md` to the top of `docs/CHANGELOG.md` (as
   `## <version>`), then write the new version's `### What's new in <version>` in `packaging/release-notes.md`. That file
   becomes the release's description, so it holds only this version (a test checks). The Android app shows the bullet
   points of its `### What's new …` section as the update's notes, so keep the news in `- ` bullets there (a new `###`
   heading ends the list).
3. Make sure `assets/discord/` (`logo.png`, `play.png`, `pause.png`) is committed: Discord loads the card's images from
   the release tag (`https://raw.githubusercontent.com/dash1101/ytm-starcast/<tag>/assets/discord/`).
4. Tag and push: `git tag -a v0.2.1-alpha -m "YTM Starcast v0.2.1-alpha" && git push origin v0.2.1-alpha`.
   The release workflow (`.github/workflows/release.yml`) builds everything, checks the Linux and Windows programs
   start, and publishes a pre-release. "Run workflow" from the Actions tab builds the same files without publishing.

## What a release contains

| File | Built from |
|---|---|
| `ytm_starcast-<version>-py3-none-any.whl` and `ytm_starcast-<version>.tar.gz` | `python -m build` (the Python package and its source). The in-app updater installs the `.whl` with pip. |
| `ytm-starcast-<tag>-windows-x64.zip` | PyInstaller: `ytm-starcast.exe` (the server), `ytm-starcast-gui.exe` (the desktop window) and `packaging/install-exe.ps1`, with `README.md` and `LICENSE`. |
| `ytm-starcast-<tag>-linux-x86_64.tar.gz` | PyInstaller: the single `ytm-starcast` program, with `README.md` and `LICENSE`. |
| `ytm-starcast-android.apk`, `ytm-starcast-android-unshrunk.apk` | The Android app, shrunk and unshrunk, signed with the release key. No version in the names, so links to the latest release keep working. |
| `ytm-starcast-extension.zip` | The `extension/` folder, to load unpacked (the files sit at the top of the zip). |
| `SHA256SUMS.txt` | SHA-256 of every file above. The updater checks downloads against it. |

`<tag>` is the release tag, so the Windows file of `v0.2.1-alpha` is `ytm-starcast-v0.2.1-alpha-windows-x64.zip`, and
`<version>` is the Python version as pip writes it (`0.2.1a0` for `0.2.1-alpha`).

The Android `versionCode` is the workflow's run number, so it keeps rising as long as the workflow file keeps its name.
Android refuses an update whose `versionCode` is lower than the installed one: don't rename `release.yml`, and if the
releases ever move to a new repository, add an offset above the last run number (`-PpfVersionCode=$((GITHUB_RUN_NUMBER + 100))`).

## Moving the repository (once)

YTM Starcast used to be Pocketfader, and its Discord status used to be the separate YTM Beacon app. The two old
repositories are handled differently: `dash1101/pocketfader` **becomes** `dash1101/ytm-starcast`, and only
`dash1101/ytm-beacon` is retired.

1. **Rename `dash1101/pocketfader` to `dash1101/ytm-starcast`** (Settings → General → Repository name), before the first
   YTM Starcast tag. Never archive, delete or re-create it. The renamed repository keeps its signing secrets, its Actions
   run numbers (so the Android `versionCode` keeps rising), its releases and its issues, and GitHub forwards the old
   name to the new one. A fresh repository would have none of the signing secrets, would start the run numbers at 1 (so
   phones refuse the update), and would lack Pocketfader `v0.1.0-alpha.15`, which Orbit's checks download from YTM
   Starcast's releases. Then point the local copy at the new name:
   `git remote set-url origin https://github.com/dash1101/ytm-starcast.git`.
2. **Update the descriptions** of `dash1101/ytm-starcast` and `dash1101/orbit`, which still name the old products, for
   example:

   ```bash
   gh repo edit dash1101/ytm-starcast --description "Your YouTube Music companion: a remote for your phone, your music as your Discord status, and a better YouTube Music."
   gh repo edit dash1101/orbit --description "One place for YTM Starcast, Nova and other apps: status, settings, installers, themes"
   ```
3. **Archive only `dash1101/ytm-beacon`**, after its README notice (that it is now part of YTM Starcast) is committed and
   pushed. Archive it (Settings → Danger Zone → Archive this repository); never make it private or delete it. Orbit's
   checks and existing YTM Beacon installs download its `v0.1.4` release without signing in, and Firefox and macOS
   users still depend on it, because YTM Starcast doesn't support either yet.
4. Release Orbit's version for YTM Starcast right after YTM Starcast's first release (see Orbit's `RELEASING.md`).

**What existing installs see.** Pocketfader `v0.1.0-alpha.15` and older can't follow GitHub's forwarding: after the
rename, their Updates row reports "GitHub answered 301", and the Android app, which updates through the computer, offers
nothing. They move to YTM Starcast only by running its installer by hand (or through Orbit), as the README and the
release notes say. YTM Starcast's own updater follows the forwarding and also asks `dash1101/pocketfader` while
`dash1101/ytm-starcast` has no releases.

## Signing the Android app

Android only installs an update over an installed app when both are signed with the **same key**. Create one key, once,
and keep it safe (if it is lost, existing installs must be uninstalled before a new key's app can be installed). Keep the
keystore **outside the repository folder**, so it can never be committed by accident:

```bash
mkdir -p ~/keys
keytool -genkeypair -keystore ~/keys/ytm-starcast-release.jks -storetype PKCS12 -alias pocketfader \
        -keyalg RSA -keysize 4096 -validity 36500
```

The alias `pocketfader` is the key's name inside the keystore. It is kept from before the product was called YTM Starcast: an
existing key keeps its alias, and the workflow reads it from the `PF_KEY_ALIAS` secret.

Give the key to the release workflow as repository secrets (Settings → Secrets and variables → Actions, or with the
GitHub CLI):

```bash
base64 -w0 ~/keys/ytm-starcast-release.jks | gh secret set PF_KEYSTORE_B64
printf '%s' 'the-keystore-password' | gh secret set PF_KEYSTORE_PASSWORD
printf '%s' 'pocketfader' | gh secret set PF_KEY_ALIAS
```

Without the secrets the workflow still builds, but signs with a throwaway key and prints a warning; those APKs can't be
installed over an earlier build.
