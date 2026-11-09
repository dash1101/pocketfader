# Pocketfader Refine: a better YouTube Music

Pocketfader started as a remote for the music playing on your computer. The extension that makes the remote work already
runs inside music.youtube.com, which makes it the natural place for improvements to YouTube Music itself. This is the plan.
Everything here is built to run on **your** computer and your own account; nothing goes through a Pocketfader server of ours.

## What works today (extension 1.10, the ✦ panel in the tab)

| | |
|---|---|
| Playback speed | 0.75× to 2×, set on the page's real player. |
| I'm feeling lucky | A random pick from your home page, or a random mix if there's no song to pick. |
| Theme (experimental) | One accent colour becomes a tonal dark theme, or AMOLED black. It overrides YouTube Music's colour variables, so it may need small fixes when YouTube changes them. |
| Playlist from a description | Bring a Gemini API key (kept in the browser). Describe a mood; Gemini suggests songs; each is looked up on YouTube Music; the result is saved as a playlist, and can be played right away. |
| Discord presence | YTM Beacon's status and switch, inside the tab. |
| Pocketfader options | Sleep timer, pause other players, from the tab. |
| Live lyrics | Already in the Pocketfader apps (word-by-word, several sources). Not yet shown inside the tab. |

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
