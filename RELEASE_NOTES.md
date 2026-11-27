# Dot 0.1.0-phase1.5 — Phase 1: two experiments to pin the black screen

The picture still does not reach the screen: in 1.4 the test pattern drew twice through the graphics chain with no errors and stayed invisible, so the fault is in the surface path, not the game and not the chain. This build adds the two experiments that isolate it. The first draws the same magenta/cyan checkerboard straight to the screen, bypassing the whole graphics chain. The second puts the game surface above the window. Together they tell us whether the chain's output, the surface ordering, or the swap itself is at fault — and only the pinned cause gets fixed next.

## New features

- Direct-pattern diagnostic (Settings → Developer, default off): a bright magenta/cyan checkerboard drawn straight to the screen with a trivial shader, bypassing the whole graphics chain. Pattern visible here but invisible through the chain means the chain's output is the fault; invisible in both means the surface path is.
- Media-overlay diagnostic (Settings → Developer, default off): puts the game surface above the window. If the picture appears with this on, the surface was sitting below an opaque window.

## Improvements

- The frame path now logs what the screen is about to be given: the centre pixel of the presented buffer is read back after each draw, so a bug report shows whether the chain produced real pixels or black.

## Bug fixes

- Various bug fixes and stability improvements

## Maintenance

- Various bug fixes and stability improvements
