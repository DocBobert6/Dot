# Dot — core record

- Core: mGBA, libretro/mgba fork — https://github.com/DocBobert6/mgba
- Branch: `dmg/phase-0` (Dot's own branch; never build DMG core
  changes on Atomic's `atomic/*` branches or the GBC app's `gbc/*`
  branches, and never bump Atomic's pin to pick them up)
- Pinned commit: f705b5d69e88116326124ead0fa6bd2ecbd2aa41
  (mGBA 0.11.0; "Atomic Phase 3 5.10: per-scanline BG0CNT/BG1CNT
  export (bgCntText)")
- Licence: MPL-2.0, no Exhibit B notice in src/include (checked
  in CI on every build)
- Build: `-DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON`, NDK
  27.2.12479018, arm64-v8a, android-21
- Fork-only core options this app depends on by name (checked in
  CI — they exist only in the libretro fork, not upstream
  mgba-emu): `mgba_gb_colors`, `mgba_gb_colors_preset`,
  `mgba_interframe_blending`, `mgba_color_correction`. Also
  checked: `mgba_gb_model` (must resolve to DMG, not CGB) and
  `mgba_sgb_borders` (OFF in v1).

Phase 0 carries no DMG core changes: the pin is Atomic's pin, and
the DMG layer-capture work (Phase 2) lands on `dmg/phase-0`'s
successor branch when it starts.
