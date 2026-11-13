# Refine: a better YouTube Music

Refine is the part of YTM Starcast that improves YouTube Music itself. The browser extension that makes the YouTube Music
remote work already runs inside music.youtube.com, which makes it the natural place for these improvements. This page
says what is built and what comes next. Everything here runs on **your** computer and your own account; nothing goes
through a server of ours.

## Status

The first part is built (extension 2.2, `extension/refine.js`, with `extension/stretch.js` for the clear slowed sound). It
lives in YouTube Music's own page rather than in a separate panel: a ✦ button in the top bar opens its settings, the speed
menu opens from the player's own speed button, the live lyrics fill YouTube Music's own Lyrics tab, and the "something you
might like" button sits beside the home page's first shelf (Listen again), where starting something new is a deliberate
choice rather than a slip next to the player.

| Done | Where | Switch |
|---|---|---|
| Rounder shapes (covers, list rows, queue, menus, dialogs, search box, chips) | style layer | Rounder shapes |
| Colours from the album art: tonal surfaces and an accent from the cover's most vivid colour | style layer + cover sampling | Colours from the album art |
| Expressive player controls: bigger play button that morphs circle ↔ rounded square, tinted hover and pressed buttons | style layer | Expressive player controls |
| Wavy progress bar: drawn over YouTube Music's own (which stays underneath, invisible, so seeking and dragging are unchanged) | canvas | Wavy progress bar |
| Glow behind the cover | style layer | Glow behind the cover |
| Animations: a new page's header, shelves and rows rise in one after another; cards, rows, chips and buttons answer a press; menus and dialogs drop in; a new song slides into the player bar and the big cover settles; the player page and the Up next / Lyrics / Related tabs move into place. None of it for anyone whose system asks for less motion | style layer | Animations |
| Speed menu: 0.25×–3×, presets, pitch on/off, a speed for any song (it stays from song to song and after a reload, even though the player puts its own back whenever a song loads), for this song or for this playlist | YouTube Music's speed button | Richer speed menu, Keep the pitch |
| Clear slowed sound: a slowed song is played the clear way (pitch lowered, as with a record) and its pitch is put back by a phase vocoder, instead of the browser's own time-stretching, which smears and warbles on music | speed menu | Clear slowed sound |
| Room: a soft reverb for a "slowed + reverb" sound, from off to 100% | speed menu | — |
| Times that follow the speed: at 0.8×, a 3:00 song shows 3:45 in the player bar (and on Discord, through the app) | player bar | Times follow the speed |
| Random-song button beside the home page's first shelf | home page | Something-you-might-like button |
| Playlist maker (item 3 below) | ✦ menu → Make a playlist | — |
| Live lyrics (item 1 below): word by word, in YouTube Music's own Lyrics tab, or in a window that can be moved, resized, pinned and kept above other windows | Lyrics tab, ✦ menu → Open lyrics in a window | Live lyrics |
| Speed from the keyboard: Shift+. faster, Shift+, slower | keyboard | Richer speed menu |
| YTM Starcast's switches: the YouTube Music remote, Discord status and Refine itself, with a line saying what Discord status is doing, and every Discord status option | ✦ menu → YTM Starcast | — |
| The computer's sound: volume, output, and the built-in equalizer (on/off, presets, bass, mid, treble), as in YTM Starcast's mixer | ✦ menu → Computer sound | Computer audio (in the apps) |

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
  playlists), the lyrics in the Lyrics tab and in their window, the times that follow the speed, the animations, and the
  clear slowed sound with real music. All of these were tried on a silent stand-in page; the clear slowed sound's
  processor was also checked on made-up tones (pitch, steady volume, no gaps), in Node and in the browser's audio engine,
  without playing anything.

## Live lyrics

- **Where:** YouTube Music's own Lyrics tab, next to Up next (Refine switches the tab on when YouTube Music has no lyrics
  of its own). The window button moves them into a window on the page: drag it by its title, resize it from its corner,
  pin it (it then stays where it is, can't be moved by accident and comes back after a reload), keep it above other
  windows (the browser's floating window for a page, where the browser has one), or send it back to the tab.
- **Word by word:** KuGou and NetEase time each word; LRCLIB, NetEase and YouTube Music otherwise give line timings, and
  then Refine estimates the words by their length (Options → Words → Lines only turns that off). The sub-title says which
  it is.
- **Options**, per song: the source (Best picks word by word when there is one; a dot marks the sources that have word
  timings) and the timing (Earlier and Later move the lyrics a quarter of a second). For all songs: text size, alignment,
  and the romanized and translated lines.
- **The clear slowed sound** comes out a little later than the song's clock; the lyrics wait for it.

## The clear slowed sound

The browser can slow a song down in two ways. With the pitch kept, it stretches the sound in time itself, which on music
sounds smeared and warbly. With the pitch let go, it simply plays the sound slower, which is smooth and clear but lower,
like a record at the wrong speed. "Clear slowed sound" takes the second and puts the pitch back: the page's sound runs
through Web Audio into `stretch.js`, a phase vocoder with identity phase locking (both channels turned by the same angle
at each frequency, so the stereo picture stays put, and the phases reset at drum hits so they stay sharp), followed by a
resampler. It runs in an AudioWorklet, or on the page itself where the worklet can't load.

- It works only while the speed is below 1× and "Keep the pitch" is on; above 1× the browser's own way is used.
- The browser lets a page take over a media element's sound only once, and only after a press on the page: it is set up
  the first time it's needed (or Room is turned up) and stays until the page reloads. With Clear off (or at 1×) the sound
  passes through untouched.
- It adds about 130 ms of delay, which the lyrics allow for.

## Next, in this order

1. ~~Lyrics in the tab~~ (done: in YouTube Music's own Lyrics tab or a window of their own; they need YTM Starcast running
   on the computer, whose lyrics service they use through the extension).
2. **A proper Material You look for the page.** The current theme only swaps colours. The next step is restyling the parts that
   look dated: the player bar and page (rounded shapes, tonal surfaces, the wavy seek bar and cover glow from the apps), and a
   dynamic accent taken from the album art. This is the biggest piece of work, and the most fragile: it depends on YouTube
   Music's markup, so it will be done as small, separate style layers that can each be switched off.
3. ~~Smarter playlist generation~~ (done: the playlist maker).
4. ~~Playback speed per song or per playlist, and pitch on/off~~ (done).
5. ~~A random-song button inside the page's own controls~~ (done).
6. **YTM Starcast's options, fully in the tab.** Mostly done: the ✦ menu switches the remote, Discord status and Refine,
   has every Discord status option and the computer's sound (volume, output, equalizer). Still to come: update status.
7. **Packaging.** The extension is now a release download (`ytm-starcast-extension.zip`) to load unpacked. A store listing and
   automatic updates come after the features settle.

## Principles

- Local first. The extension talks only to the page and to YTM Starcast on this computer.
- Personal data stays on this computer. Nothing is sent anywhere you didn't ask for.
- Every change to the page is optional and reversible from the panel.
- Honest about fragility: anything that depends on YouTube's internal markup is labelled experimental.
