# Dot 0.1.0-phase0.1 — Phase 0 scaffold

First build. Scaffolding, foundations, measurement — no game
playback yet.

## New features

- New public repo (DocBobert6/Dot, package app.dot) with CI that
  builds a debug APK on every push and a signed release APK on
  every tag, attached to the GitHub release by the workflow.
- mGBA core integration: the libretro/mgba fork, branch
  dmg/phase-0, pinned commit f705b5d69e88116326124ead0fa6bd2ecbd2aa41
  (mGBA 0.11.0), built in CI with -DLIBMGBA_ONLY=ON
  -DBUILD_LIBRETRO=ON, NDK 27.2.12479018, arm64-v8a, android-21.
  Licence audit in CI on every build (MPL-2.0, no Exhibit B, the
  four fork-only core options present).
- Minimal JNI bridge (libdot-bridge.so) proving the core link:
  one function, retro_api_version, symbol-verified in CI.
- DMG header validation from the first build: Nintendo logo at
  0x104-0x133, CGB flag at 0x143 (0x00 and 0x80 accepted, 0x80 in
  DMG mode; 0xC0 refused plainly), header checksum at 0x14D, 8 MB
  import cap — with the failing bytes named in the message.
- Dex guard (no app method over 255 registers) and Robolectric
  startup gate from the first build. Manifest permissions pinned
  by test: VIBRATE, INTERNET, ACCESS_NETWORK_STATE.

## Improvements

- Portrait-first library, silver and black, dot-matrix dot icon.

## Bug fixes

- Various bug fixes and stability improvements.

## Maintenance

- General maintenance.
