# Pocketfader Refine: a better YouTube Music

Pocketfader started as a remote for the music playing on your computer. The extension that makes the remote work already
runs inside music.youtube.com, which makes it the natural place for improvements to YouTube Music itself. This is the plan.
Everything here is built to run on **your** computer and your own account; nothing goes through a Pocketfader server of ours.

## Status

The first part is built (extension 1.15, `extension/refine.js`). It lives in YouTube Music's own page rather than in a
separate panel: a ✦ button in the top bar opens its settings, the speed menu opens from the player's own speed button, and the
"something you might like" button sits among the player controls.

| Done | Where | Switch |
|---|---|---|
| Rounder shapes (covers, list rows, queue, menus, dialogs, search box, chips) | style layer | Rounder shapes |
| Colours from the album art: tonal surfaces and an accent from the cover's most vivid colour | style layer + cover sampling | Colours from the album art |
| Expressive player controls: bigger play button that morphs circle ↔ rounded square, tinted hover and pressed buttons | style layer | Expressive player controls |
| Wavy progress bar: drawn over YouTube Music's own (which stays underneath, invisible, so seeking and dragging are unchanged) | canvas | Wavy progress bar |
| Glow behind the cover | style layer | Glow behind the cover |
| Speed menu: 0.25×–3×, presets, pitch on/off, remember per song or per playlist | YouTube Music's speed button | Richer speed menu, Keep the pitch |
| Random-song button in the player controls | player controls | Something-you-might-like button |
| Playlist maker (item 3 below) | ✦ menu → Make a playlist | — |

`?pf-refine=off` on music.youtube.com starts a page with all of it off (the saved choices are kept). The extension's layout
snapshot (`~/.config/pocketfader/ytm-layout.json`, written a few seconds after each page load) now also records the progress
slider, the docked player, the top bar, the page's colour variable names and what Refine found (`refine`), so selectors can be
corrected from real data when YouTube Music changes.

Tested on a silent stand-in page with the same element names as the real one; not yet against the real YouTube Music.

## Next, in this order

1. **Lyrics in the tab.** The same synced lyrics as the apps, as a panel next to the player, with the layout options. The lyrics
   service already exists in the server; this is mostly interface.
2. **A proper Material You look for the page.** The current theme only swaps colours. The next step is restyling the parts that
   look dated: the player bar and page (rounded shapes, tonal surfaces, the wavy seek bar and cover glow from the apps), and a
   dynamic accent taken from the album art. This is the biggest piece of work, and the most fragile: it depends on YouTube
   Music's markup, so it will be done as small, separate style layers that can each be switched off.
3. ~~Smarter playlist generation~~ (done: the playlist maker).
4. ~~Playback speed per song or per playlist, and pitch on/off~~ (done).
5. ~~A random-song button inside the page's own controls~~ (done).
6. **Pocketfader options and Beacon, fully in the tab.** A settings page (not only the compact panel) mirroring the app:
   outputs, equalizer, update status.
7. **Packaging.** Right now it's an unpacked extension. A store listing, a packaged build and automatic updates come after the
   features settle.

## Principles

- Local first. The extension talks to the page, to the Pocketfader server on this computer, and (only if you use it) to Google's
  Gemini API with your own key.
- Keys and personal data stay in the browser. Nothing is sent anywhere you didn't ask for.
- Every change to the page is optional and reversible from the panel.
- Honest about fragility: anything that depends on YouTube's internal markup is labelled experimental.
