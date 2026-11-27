# Dot 0.1.0-phase1.4 — Phase 1: the flashing stops, and the picture can be proven

The game area no longer flashes black and charcoal on a static screen: an unchanged frame used to cause a render request and a buffer swap anyway, so the display cycled stale buffers. Now an unchanged frame causes no render and no swap at all. Two Developer diagnostics are included so the remaining picture question can be settled on the device in one test: a test pattern that replaces the game picture with a bright magenta/cyan checkerboard through the same graphics chain, and the live draw counts on the player screen. The frame path also logs to logcat now — frames delivered and drawn, the first-frame hash, the EGL config actually chosen, and how long the first shader programs take to build — so the next bug report shows what the app itself saw.

## New features

- Test-pattern diagnostic (Settings → Developer, default off): a bright magenta/cyan checkerboard drawn through the same graphics chain instead of the game frame. Pattern visible means the surface and swap path work; pattern invisible means the surface path itself is broken.
- Live draw stats on the player screen (Settings → Developer, default off): the draw counts — attempted, drawn, skipped, surface, latch — updating live while you play, so they can be read without opening Settings.

## Improvements

- The frame path now logs to logcat: frames delivered and drawn, the first-frame hash, the EGL config actually chosen, the surface format, and per-program shader build times.

## Bug fixes

- Fixed the game area flashing on static screens: an unchanged frame no longer causes a render request or a buffer swap.

## Maintenance

- Various bug fixes and stability improvements
