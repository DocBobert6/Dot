# Dot 0.1.0-phase1.6 — Phase 1: bisect the picture chain

The surface path is exonerated: in 1.5 the direct pattern (the same checkerboard drawn straight to the screen, bypassing the chain) showed crisp and true-coloured, and the media-overlay test showed a uniform charcoal rectangle — the charcoal clear presents, but the game quad never lands in it. So the fault is inside the graphics chain: some stage turns the picture black, and every later stage only propagates it. This build adds the bisection that names it. Run the chain only up to a chosen stage and draw that stage straight to the screen, or read the centre pixel after every pass — either way, the first stage that outputs black is the one to fix next, and only the pinned cause gets fixed.

## New features

- Chain-stage selector (Settings → Developer, default off): runs the graphics chain only up to the chosen stage — Upload, Invert, DeFlicker, ScaleFX, rAA, Sharp, Mitchell, or the full chain (RCAS) — then draws that stage's output straight to the screen. Use with the test pattern on: a stage that shows the pattern is fine, the first stage that shows black is the fault.
- Stage probes (Settings → Developer, default off): reads the centre pixel after every chain pass and logs it, and shows the line in the live draw stats — so the log names the first pass that outputs black without needing a bug report.

## Improvements

- The chain label now records the bisection stage, so a report can never mistake a bisect draw for the full chain.

## Bug fixes

- Various bug fixes and stability improvements

## Maintenance

- Various bug fixes and stability improvements
