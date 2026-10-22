# Dual Earbuds design system — "instrument"

The app is a piece of audio equipment: it should read like a well-made mixer or a measurement tool, not a
landing page. Calm, dense, precise; colour means state. Applies to Windows (WPF) and Android (Compose).

## What to remove (the generic look)

- Big display page titles ("Home", "Phones") with a marketing sentence under them.
- ALL-CAPS, letter-spaced section labels ("PLAY ON", "SMART FEATURES").
- One floating rounded card per item; 16–24 px radii; gradients; glow; soft shadows.
- Accent colour on decorative chips, badges and every toggle; pill-shaped everything.
- Filler copy: "seamlessly", "effortlessly", "Let's get started", exclamation marks, emoji (UI and README).

## Structure

- **Header bar (48 px)**: page name at 15 px semibold on the left; on the right a live readout in mono, e.g.
  `LIVE  48 kHz  ·  P 92 ms  ·  4 ears  ·  sync ±3 ms` (muted text, accent dot only when live). No subtitle.
- **Sections**: a 12 px semibold sentence-case label, a muted count (`Outputs  2 of 3 on`), then a 1 px hairline
  running to the right edge. 24 px above a section, 8 px below its label.
- **Lists, not card piles**: items of one kind sit as rows inside one panel, separated by 1 px dividers.
  Row height 52 px (two lines 64 px). Panel radius 8 px (Android 8 dp), 1 px `Line` border, no shadow.
- **One primary action per view** (solid accent). Everything else is a quiet secondary (outline or text).
- Explanations live where they are needed: a one-line muted hint under a setting, or an (i) tooltip — never as
  a paragraph at the top of a page.

## Type

| Role | Size / weight | Notes |
|---|---|---|
| State headline | 20 / semibold | one per view at most ("Sharing to 4 ears") |
| Page name | 15 / semibold | header bar |
| Body / row title | 14 / regular–medium | |
| Section label, hint | 12 / semibold, 12 / regular muted | sentence case |
| Values (ms, dB, kHz, %, IP, PIN) | mono, tabular | Windows: Cascadia Mono→Consolas; Android: `FontFamily.Monospace` |

Fonts: Windows `Segoe UI Variable Text` (fallback Segoe UI) — drop Bahnschrift. Android: system sans.
Letter-spacing 0 everywhere except mono readouts (+0.2).

## Colour

Keep the palette tokens (Bg, Surface, Surface2, Line, Text, Muted, Accent, Good, Warn, Bad); change usage:
- Accent only for: live state, the primary action, the selected segment, focus ring, meters.
- Status = 6 px dot + word (Good live / Warn needs attention / Bad error). Never colour alone.
- Surfaces: Bg and Surface differ subtly; panels are separated by borders, not by brightness jumps.
- Contrast: text ≥ 4.5:1, muted ≥ 4.5:1 on its surface in both themes, focus ring ≥ 3:1.

## Controls

- Buttons 32 px high, radius 6. Segmented controls 28 px, radius 6, selected segment = Surface2 + accent text
  (not a solid accent block). Toggles: neutral track when off, accent only when on.
- Sliders: thin 2 px track, value shown in mono at the right, fixed width so rows align.
- Icons: 16 px, 1.5 px stroke, one set, aligned to text baseline.

## Signature element: the sync scope

Every ear (local output or remote listener) gets a compact row with a horizontal **sync scope**: a ±50 ms scale with
a tick at 0 and a marker for that ear's current offset from the common deadline (from clock sync), plus its
latency and buffer health in mono. It replaces decorative progress bars and is the thing people look at to
know "are we in sync?".

## Motion

120–160 ms ease-out for state changes; no bouncing, no shimmer, no animated gradients. Respect reduced-motion.

## Copy

Plain, specific, verbs first. "Calibrate", "Copy link", "Stop sharing". Numbers over adjectives
("92 ms behind the host", not "slight delay"). Errors say what happened and what to do, in one sentence.
