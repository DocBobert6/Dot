# Dot — standalone Original Game Boy (DMG) remaster app for Android

**Goal:** the best picture anyone's ever seen on an original Game Boy
game that wasn't officially remastered — completely outdoing every
other standalone DMG emulator, and trying to outdo RetroArch too.
DMG only. Emulator route. **No texture packs. No AI in any form.**

- Package: `app.dot`
- Core: mGBA, libretro/mgba fork, pinned commit (see CORE.md)
- Grayscale default: Micro-hybrid — paper white #FFFFFF, mids 180 /
  90, Vantablack #000000 (picked 2026-11-22 by blind A/B)
- No colour. No HDR. Portrait-first, like the real DMG.
- Dual-mode (0x80) games run in DMG mode; colour-only (0xC0) games
  are refused plainly and pointed at the GBC app.

## Status

Phase 0 (scaffolding, foundations, measurement). The plan is FINAL
(2026-11-26): `~/workspace/gb-remaster-plan/GB-PLAN.md`.

## Build

CI builds a debug APK on every push to `main` and a signed release
APK on every `v*` tag, attached to a GitHub release by the workflow.
The release keystore is never in this repo; CI restores it from the
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` secrets.

## Licence

Dot itself is closed-source (see LICENSE). The mGBA core is
MPL-2.0 (see NOTICE). No GPL-licensed code ships in Dot: the
ScaleFX / rAA / RCAS shaders are MIT, the core is MPL-2.0, and
Dot's own code is proprietary.
