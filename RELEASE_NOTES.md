# Dot 0.1.0-phase1.3 — Phase 1: the picture really draws now

The game area no longer freezes on charcoal: a frame that cleared
charcoal while the surface was still sizing kept its hash, and every
later frame — a static title screen, all with the identical hash —
was skipped forever. The hash is only consumed by a frame that
actually drew, and a real surface size arriving forces the frame to
draw. The benchmark's presented clock is wired into the real draw
path, so captures can complete and the FPS counter reads live.

## New features

- Draw counts in Settings → Graphics failure report: draws
  attempted, draws shown, charcoal draws by cause (latch, surface,
  area), last surface size, and latch state — so a black screen can
  be told apart from a failed chain without a benchmark run. The
  same counts travel with any failure report that is written.

## Improvements

- The FPS counter and the 600-frame benchmark now read the
  presented frames they were always meant to: the live FPS and the
  capture complete on a real draw, instead of sitting at zero and
  waiting forever.

## Bug fixes

- Fixed the game area freezing on charcoal: a frame that drew
  charcoal before the surface was sized no longer poisons the
  static-frame hash, and the same frame draws for real once the
  surface exists.
- Start and Select now read START and SELECT: the pills grow to
  fit their labels instead of wrapping and clipping them into
  unreadable fragments.

## Maintenance

- Various bug fixes and stability improvements
