# Pocketfader Android client (WebView edition)

A thin native wrapper around the Pocketfader web app that adds what a browser can't do on its own:

1. **Media notification** on the lock screen / status bar showing what's playing on the computer
   (title, artist, cover art) with **prev / play-pause / next**.
2. **Hardware volume keys**: while the app is open, the phone's volume buttons change the
   **computer's** volume instead of the phone's.

Everything else is the normal web app inside a WebView. A fully native app is planned; see
[`docs/ANDROID_PLAN.md`](../../docs/ANDROID_PLAN.md).

## Install

Download `pocketfader-android-….apk` from the repository's **Releases** page, allow "install unknown
apps" for your browser/file manager, and open it. On first launch enter the address of the computer
running Pocketfader, for example `http://my-computer:8777` (a Tailscale name or address). Tap the small
gear in the top-right corner any time to change it.

If the server was started with `--lan` (or `--token`) it asks for an access token: paste the **full
link** the server prints (it ends in `?token=…`) instead of just the address.

## Build it yourself

Needs JDK 17 and the Android SDK (Android Studio includes both).

```bash
cd android/legacy-webview
gradle :app:assembleRelease          # or open this folder in Android Studio and press Run
# APK: app/build/outputs/apk/release/app-release.apk
```

Release builds from CI are signed with a throwaway debug key; sign your own build with a
keystore of your choice if you want updates to install over each other.

## Notes
- Minimum Android 8.0 (API 26). Uses built-in system icons for the notification buttons.
- The notification polls `/api/ytm/state` every 2 s; transport buttons post to `/api/ytm`; volume keys
  post `{"delta": ±5}` to `/api/volume`.
- `spen/SpenRemoteManager.kt` is an optional Samsung S Pen air-actions helper. It needs Samsung's
  S Pen Remote SDK (`.aar`), is **not** compiled into the default build, and is kept for reference.
