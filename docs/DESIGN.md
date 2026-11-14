# Design language

YTM Starcast has three faces: the web app, the Android app, and Refine (the part of the extension that lives inside
YouTube Music). They are built with different tools, so nothing forces them to look alike. This page is the one scale
they all draw from. When you add or change something, take the value from here rather than inventing a new one.
The extension's toolbar popup (`extension/popup.css`) uses the same values, under the web app's names.

| | Web app | Android app | Refine |
|---|---|---|---|
| Where it lives | `starcast/static/index.html` (`:root`) | `Theme.kt` (`PfShape`, `Motion`), `Fx.kt` | `extension/refine.js` (`CSS_UI`) |
| Corners | `--r-xs` … `--r-full` | `PfShape.Xs` … `PfShape.Full` | `--pf-r-xs` … `--pf-r-full` |
| Timings | `--t-quick` … `--t-long` | `Motion.Quick` … `Motion.Long` | `--pf-t-quick` … `--pf-t-long` |
| Curves | `--ease`, `--spring` | `Motion.Ease`, Compose springs | `--pf-ease`, `--pf-spring` |

## Corners

The bigger the surface, the rounder it is.

| Step | Size | Used for |
|---|---|---|
| xs | 8 | Small covers in lists, chips that are not chosen |
| sm | 12 | The highlight behind a row or an icon button, toasts, compact text fields |
| md | 16 | Menus, list rows, tiles, tall text fields |
| lg | 24 | Cards, widgets, settings groups, the small player |
| xl | 28 | Sheets, dialogs, popovers, the big cover |
| full | pill | Buttons, switches, the search box, chips that are chosen |

Two things to know:

- **A corner that changes shape uses a real number, not "999".** A chip that turns from 8 into a pill animates to half
  its height (16 or 17), because going from 8 to 999 looks like a jump.
- **Text fields** are `md` in the Android app, where they are tall, and `sm` in the web app and Refine, where they are
  compact. The proportion is the same.

## Motion

One curve for almost everything: `cubic-bezier(.2, 0, 0, 1)` (fast out, long settle). The springy one,
`cubic-bezier(.34, 1.3, .64, 1)`, is kept for things that should feel alive: the play button changing shape, a sheet
arriving. The Android app uses Compose's own springs for the same moments.

| Step | Time | Used for |
|---|---|---|
| quick | 120 ms | A press, something leaving |
| short | 200 ms | A fade, a colour, a chip changing shape |
| medium | 300 ms | Something moving or opening |
| long | 450 ms | A whole screen, the cover growing or shrinking |

**Arriving rows:** when a list's content arrives (a tab opens, a page or results load), the rows in view glide in from the
right one after another, 26 ms apart, along Material's emphasized curve (`cubic-bezier(.05, .7, .1, 1)`, about half a
second). Rows scrolled into view later just appear. Refine does this for Up next, the Android app for its lists.

Slow, endless movements (the breathing glow, the wavy progress bar, the turning round cover, the Halo and Orbit
visualizers) have their own timings.
They stop with "Reduce animations" in the Android app, and with the system's reduced-motion setting in Refine and for
the web app's wavy bar.

## Pressing

Things shrink a little while they are held: buttons and cards to 96 %, icon buttons to 90 %. List rows change colour
instead of size.

## Shapes with a meaning

- **Play button:** round while music plays, a soft square (30 % corners) while it is paused.
- **Round buttons:** round at rest; a squircle while pressed, and on a tonal squircle while they are on (shuffle, loop).
- **Tabs:** one pill-shaped bar, the chosen tab on a tonal pill that slides to it. A tab that can't open for what plays
  (Comments when they are turned off) stays in its place, crossed out.
- **Chips:** softly squared at rest, a pill once chosen.
- **The big cover:** rounded by default; the other cover shapes (soft, round, cut) are a setting. The light behind it
  follows the cover's outline, whatever the shape.

## Colour

**Album colours** (the default in all three): the colours come from the cover of what plays. Its most vivid hue (in
10-degree bins, each pixel weighted by how colourful and bright it is) gives one set of tones: page `hsl(h 14% 7%)`,
cards and bars 10 to 21 %, the accent `hsl(h 78% 76%)` with text on it at 15 %, the tonal container `hsl(h 42% 27%)` with
text on it at 90 %. A grey cover gives nearly neutral tones. The Android app makes light and true-black versions of the
same hue and keeps every text colour readable on them (checked in its tests).

Violet is the brand colour, used where no cover is involved. Filled accents use `#8b5cf6`; on a dark background the bright accent tone is `#b69cff`. Dark
surfaces step up from near black (`#0a0a0c` page, `#131318` panel, `#1a1a21` card). The web app's themes and the Android
app's dynamic colours replace these values, never the scale above.

## Type

The system font everywhere. Secondary text uses the dim tone of the theme.
