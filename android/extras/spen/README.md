# S Pen air actions (optional, not built)

`SpenRemoteManager.kt` is a helper that turns Samsung S Pen air actions into remote commands:
a single button press toggles play/pause, holding the button and moving up/down changes the volume, and
moving left/right skips to the previous/next track.

It needs Samsung's S Pen Remote SDK (`.aar`), which is not redistributed here, so it is **not** part of the
default build, and it was written for the previous WebView app, so its package and calls need adapting
(the native app's equivalents are `Remote.transport("next")`, `Remote.adjustVolume(5)` and so on).
It is kept as a starting point for anyone who wants to add S Pen support.
