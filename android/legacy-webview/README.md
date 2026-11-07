# Legacy Android WebView wrapper

> **Superseded.** This is the original thin WebView shell, kept for reference. The plan for a real native
> app is in [`docs/ANDROID_PLAN.md`](../../docs/ANDROID_PLAN.md).


A thin native wrapper around the Pocketfader web app that adds the two things a browser can't do:

1. **Media notification** in the status bar / lock screen showing what's playing on the PC's
   YT Music (title, artist, cover art) with **prev / play-pause / next** — looks and behaves
   like native playback on the device, but controls the computer.
2. **Hardware volume keys** — while the app is focused, the device's volume buttons bump the
   **host computer's** volume (via `/api/volume {delta}`) instead of the device's own volume.

Everything else (search, queue, widgets, themes…) is the existing web app loaded in a WebView.

---

## Set it up in Android Studio (~10 min)

1. **New Project → Empty Views Activity** (Kotlin).
   - Name: `Pocketfader`  · Package: `app.pocketfader.legacy`  · Language: **Kotlin**
   - Minimum SDK: **API 26 (Android 8.0)** or higher.
2. Replace the generated `MainActivity.kt` with [`MainActivity.kt`](MainActivity.kt) from here,
   and add [`PlaybackService.kt`](PlaybackService.kt) in the same package folder
   (`app/src/main/java/app/pocketfader/legacy/`).
3. **Module `build.gradle.kts`** → add to `dependencies`:
   ```kotlin
   implementation("androidx.media:media:1.7.0")
   implementation("androidx.appcompat:appcompat:1.7.0")
   ```
4. **`AndroidManifest.xml`** — inside `<manifest>` (above `<application>`):
   ```xml
   <uses-permission android:name="android.permission.INTERNET"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
   ```
   On the `<application …>` tag add:  `android:usesCleartextTraffic="true"`
   (the server is plain HTTP over Tailscale).
   Inside `<application>` add the service:
   ```xml
   <service
       android:name=".PlaybackService"
       android:exported="false"
       android:foregroundServiceType="mediaPlayback"/>
   ```
   And on the existing `<activity android:name=".MainActivity">` add:
   `android:launchMode="singleTask"`  and  `android:configChanges="orientation|screenSize|keyboardHidden"`
   (so rotating / volume presses don't recreate the WebView).
5. Run it on your device. **First launch asks for the server address** — it defaults to
   `http://your-computer:8777`. Long-press the little ⚙ (top-right) any time to change it.

## Notes
- Uses built-in `android.R.drawable.ic_media_*` icons, so there are **no drawable files to add**.
- The media notification polls `/api/ytm/state` every 2 s; transport buttons POST
  `/api/ytm {type:"transport",…}`; volume keys POST `/api/volume {delta:±5}`.
- To make it installable to the app drawer, just **Build → Generate Signed APK** (or run once from
  Android Studio and it stays installed).
- Later ideas: lock-screen seek bar, tasker-style quick tiles, Wear OS remote.
