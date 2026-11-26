# Dot 0.1.0-phase1.1 — Phase 1: the player

The first build that plays games. The full picture chain from the
plan (sharp-bilinear upscaling, dither inversion, the Micro-hybrid
grayscale palette), the player with haptics, the layout editor, the
gamepad, the benchmark harness, and cover art.

## New features

- Game playback: the mGBA core (libretro/mgba fork, pinned
  f705b5d6, mGBA 0.11.0) behind the GL picture chain — sharp-bilinear
  upscaling, dither inversion, Micro-hybrid C adjusted grayscale
  (paper white #FFFFFF, mids 180 and 90, Vantablack #000000) — at
  Auto / 1080p / 4K render resolution, portrait-first like the real
  DMG.
- Haptics with per-button character: full clicks on A/B, lighter
  envelope clicks on Start/Select, direction ticks on the D-pad,
  release ticks on lift-off, light clicks on FF/RW — On/Off plus a
  strength slider, and a Test haptics probe in Settings that reports
  Played / Rejected / Threw for each effect with the phone's facts.
  Game rumble wins over UI clicks while a rumble-pak game rumbles.
- Layout editor: drag any control to move it, snap to an 8 dp grid,
  move the cluster together, resize in 4 dp steps, reset to factory
  — and a default layout that applies to every game without its own.
- D-pad as a single cross, with slide-off release and diagonal
  handling. Fast-forward (2x/3x/4x), half-speed, and rewind with a
  30-second ring — all hidden by default, each with its own switch.
- Gamepad support (DMG mapping; L/R have no DMG function).
- Cover art: boxarts from the libretro-thumbnails Game Boy set,
  matched stem-first against the filename and header title, fetched
  over Wi-Fi (mobile data only if you opt in), with custom covers
  from the photo picker, a 50 MB cap, and a status line that names
  what's still missing.
- Settings rebuilt: Display, Audio, Controls, Cover art, Default
  layout, Storage, Crash report, Graphics failure report, Developer,
  About. The Developer screen arms and reads the 600-frame benchmark
  (avg fps, 1% low, p95, p99, worst, drops) with the Phase 1 gate.
- Explicit back stack with double-back-to-exit on the Library,
  long-press Rename / Move controls / Remove on library games, and
  a crash report and import trace in Settings.

## Improvements

- The player session was reworked: the emulator loop is the single
  input pusher, saves and states are written asynchronously, and
  stopping always joins the emulator thread, then the audio thread,
  then writes, then unloads — in that order.
- The FPS counter (off by default) now re-reads its switch live and
  hides while a benchmark capture runs.

## Bug fixes

- Various bug fixes and stability improvements

## Maintenance

- Various bug fixes and stability improvements
