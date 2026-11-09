# Pocketfader Refine: a better YouTube Music

Pocketfader started as a remote for the music playing on your computer. The extension that makes the remote work already
runs inside music.youtube.com, which makes it the natural place for improvements to YouTube Music itself. This is the plan.
Everything here is built to run on **your** computer and your own account; nothing goes through a Pocketfader server of ours.

## Status

Parked for now. A first experiment (a floating panel inside the tab) was removed: the goal is for these features to feel like
part of YouTube Music itself (its own menus, settings and player controls), not a separate panel. What remains in the extension
are the building blocks the apps already use: playback speed, "I'm feeling lucky", and artist pages.

## Next, in this order

1. **Lyrics in the tab.** The same synced lyrics as the apps, as a panel next to the player, with the layout options. The lyrics
   service already exists in the server; this is mostly interface.
2. **A proper Material You look for the page.** The current theme only swaps colours. The next step is restyling the parts that
   look dated: the player bar and page (rounded shapes, tonal surfaces, the wavy seek bar and cover glow from the apps), and a
   dynamic accent taken from the album art. This is the biggest piece of work, and the most fragile: it depends on YouTube
   Music's markup, so it will be done as small, separate style layers that can each be switched off.
3. **Smarter playlist generation.** Seeds from what you are listening to now, from a liked playlist, or from "more like this
   queue"; regenerate; add to an existing playlist; a clearer review step before saving.
4. **Playback speed per song or per playlist**, remembered, and pitch correction on/off.
5. **A random-song button inside the page's own controls**, not only in the panel.
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
