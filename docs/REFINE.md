# Refine: a better YouTube Music

Refine is the part of YTM Starcast that improves YouTube Music itself. The browser extension that makes the YouTube Music
remote work already runs inside music.youtube.com, which makes it the natural place for these improvements. This page
says what is built and what comes next. Everything here runs on **your** computer and your own account; nothing goes
through a server of ours.

## Status

The first part is built (extension 2.0, `extension/refine.js`). It lives in YouTube Music's own page rather than in a
separate panel: a ✦ button in the top bar opens its settings, the speed menu opens from the player's own speed button, and the
"something you might like" button sits beside the home page's first shelf (Listen again), where starting something new is
a deliberate choice rather than a slip next to the player.

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
| Live lyrics (item 1 below): time-synced, word by word where available, tap a line to jump, follows the song | lyrics button in the player controls | Live lyrics button |
| Speed from the keyboard: Shift+. faster, Shift+, slower | keyboard | Richer speed menu |
| YTM Starcast's switches: the YouTube Music remote, Discord status and Refine itself, with a line saying what Discord status is doing | ✦ menu → YTM Starcast | — |

**Refine as a whole has one switch in YTM Starcast**: Settings → Parts in the apps, the extension's toolbar button, or the
Refine switch in the ✦ menu. Switched off, Refine takes everything it added out of the page, including the ✦ button (a
speed remembered for a song or playlist goes back to 1×); switch it back on from the toolbar button or the apps. Your
detailed choices are kept for when it comes back.

`?pf-refine=off` on music.youtube.com starts a page with all of it off, remembered speeds and pitch included (the saved
choices are kept). The extension's layout snapshot (`~/.config/ytm-starcast/ytm-layout.json`, written a few seconds after each
page load) also records the progress slider, the docked player, the top bar, the page's colour variable names and what
Refine found (`refine`), so selectors can be corrected from real data when YouTube Music changes.

YouTube Music has two layouts of the player bar, and Refine supports both. What has been tried, and where:

- **The classic bar, on the real YouTube Music** (signed out, in a muted browser, with the scripts loaded by hand): the ✦ menu
  and every switch, the style layers, the wavy bar, the speed menu, the random-song and lyrics buttons, and the play button's
  two shapes while playing and paused.
- **The newer bar** (the one signed-in accounts usually get): only on a silent stand-in page with the same element names.
- **YTM Starcast's switches** in the ✦ menu and Refine's own on/off switch: only on a silent stand-in page.
- **Not tried on the real page:** anything that needs an account (the playlist maker saving a playlist, liked songs, your
  playlists), and the lyrics panel filled by YTM Starcast.

## Next, in this order

1. ~~Lyrics in the tab~~ (done: the live lyrics panel; it needs YTM Starcast running on the computer, whose lyrics service it
   uses through the extension). Its options (text size, alignment, romanized line and translation on or off) open from
   the panel's own button.
2. **A proper Material You look for the page.** The current theme only swaps colours. The next step is restyling the parts that
   look dated: the player bar and page (rounded shapes, tonal surfaces, the wavy seek bar and cover glow from the apps), and a
   dynamic accent taken from the album art. This is the biggest piece of work, and the most fragile: it depends on YouTube
   Music's markup, so it will be done as small, separate style layers that can each be switched off.
3. ~~Smarter playlist generation~~ (done: the playlist maker).
4. ~~Playback speed per song or per playlist, and pitch on/off~~ (done).
5. ~~A random-song button inside the page's own controls~~ (done).
6. **YTM Starcast's options, fully in the tab.** Started: the ✦ menu switches the remote, Discord status and Refine. Next, a
   settings page (not only the compact panel) mirroring the app: outputs, equalizer, Discord status settings, update
   status.
7. **Packaging.** The extension is now a release download (`ytm-starcast-extension.zip`) to load unpacked. A store listing and
   automatic updates come after the features settle.

## Principles

- Local first. The extension talks only to the page and to YTM Starcast on this computer.
- Personal data stays on this computer. Nothing is sent anywhere you didn't ask for.
- Every change to the page is optional and reversible from the panel.
- Honest about fragility: anything that depends on YouTube's internal markup is labelled experimental.
