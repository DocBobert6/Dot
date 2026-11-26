# Dot 0.1.0-phase1.2 — Phase 1: the picture fix + the DMG skin

The game now draws — and the app now looks like the handheld it
honours, not like Atomic.

## New features

- The dot-matrix boot: Dot's own mark in 5x7 dots when a game
  launches, short, gone within a second.
- The library is a tray of DMG carts now: silver cart bodies, the
  cover as the label, the full name on the silver, and the blurred
  cover wash behind the label.

## Improvements

- The skin, as decided: silver and black throughout — silver
  faceplate chrome, near-black charcoal base, white text. The
  D-pad is the DMG's own dark cross on a silver well, A/B are
  charcoal circles with silver rings, Start/Select are dark pills.
  Dot-matrix green appears only as the tiny power light.
- The launcher icon is a silver rounded square with one round
  dot-matrix dot.

## Bug fixes

- Fixed the black game screen: the frame render gate could wedge
  permanently on a one-frame slip between the emu, main, and GL
  threads, so no frame ever drew. The gate now requests a render
  from whichever side completes a frame, with a regression test
  that reproduces the wedge.
- A graphics failure that happens after the player opens now
  shows the honest error screen instead of sitting unseen.

## Maintenance

- Various bug fixes and stability improvements
