# Dot GL Chain Specification — picture chain extracted from Atomic

**Purpose.** This file is the complete, clean-room specification of the
picture chain Dot (package `app.dot`) implements for its Game Boy
(160×144, 4 greyscale shades) frames. It is derived from reading
Atomic's renderer (`~/workspace/atomic/src/app/src/main/java/app/atomic/GlRenderer.kt`,
`DitherInversion.kt`, `Debanding.kt`, `LinearLight.kt`,
`CompositeRules.kt`, `FrameRenderGate.kt`, `GlFailureReport.kt`,
`GlFailureExport.kt`, `DisplayRenderer.kt`) and every shader asset
Atomic loads (`app/src/main/assets/shaders/`), plus the two Dot research
reports (`~/workspace/research_notes/dot-final-resample-20261126-1702/report.md`,
`~/workspace/research_notes/dot-final-audit-20261126-1714/report.md`).

**Rules of this document.**

- No Atomic code or shader source is quoted. Every algorithm is
  described in prose plus mathematics: inputs, outputs, uniforms,
  constants, per-pixel math. A competent implementer can rebuild the
  chain from this file alone.
- Dot reimplements from algorithm descriptions. Licence notes are
  given per pass: ScaleFX and rAA are MIT (Sp00kyFox); the JINC2
  family is GPL-2.0-or-later (Hyllian) or GPL in libretro form;
  xBR-Lv2, B-spline, deblur-fast are GPL/MIT mixed; sharp-bilinear
  (`sharp-bilinear-simple`) is public domain; Mitchell–Netravali,
  Bayer ordered dither, the sRGB EOTF, and the GBI deflicker rule are
  published mathematics / published algorithm descriptions — clean-room
  from the rule, never copied. Dot must write its own shader files
  from this spec. The JINC2 final resample is **not** reused by Dot
  (replaced by sharp-bilinear, §5); the hybrid xBR final stage is
  layer-chain only and **not** in Dot Phase 1 (§11).
- **Dot's numbers** (Atomic's GBA numbers do not apply): source frame
  160×144; 4 greyscale shades only; palette "Micro-hybrid C adjusted"
  (255 / 180 / 90 / 0); ScaleFX 3× gives 480×432; final resample is
  sharp-bilinear, **not** JINC2; 4K downscale is widened
  Mitchell–Netravali B=C=1/3 in linear light; flicker-transparency is
  the GBI deflicker rule (blend n with n−1 only where n==n−2, weight
  0.55, sustained ≥2 alternations, decay on stop).
- **Dot Phase 1 is the composited-frame chain only.** Per-layer
  framebuffers are Phase 2. Anything about Atomic's layer / OAM /
  affine / MP2K passes is GBA-only and is collected in §11 under
  "does NOT apply to Dot (Phase 1)".
- Where this spec adapts an Atomic rule to Dot's 4-shade composited
  frame, the paragraph is marked **[Dot adaptation]** — it is a design
  decision derived from the cited sources, not something Atomic does.
  Where Atomic has no equivalent at all, the paragraph is marked
  **[Dot-new]**.

**Atomic reference numbers** (for comparison only; GBA source
240×160): ScaleFX 3× gives 720×480; 1080p internal game area
1620×1080 (6.75×); 4K internal game area 3240×2160 (13.5×).

---

## 1. Composited-chain pass list, in order

### 1.1 Dot Phase 1 pass list

The chain runs once per emulated frame, on the GL thread, in this
order. Passes 1–2 run at 1× (160×144); passes 3–4 run at 3×
(480×432); pass 5 resamples to the internal game area; pass 6 runs
only when the internal area is larger than the on-screen rect; pass 7
is the screen pass; pass 8 is a per-pixel epilogue inside pass 7 (or
a trivially cheap final operation — see §8.6).

| # | Pass | Input size | Output size | FBO? | Notes |
|---|------|-----------|-------------|------|-------|
| 0 | Flicker-transparency gate (GBI deflicker, §7) | 160×144 | 160×144 | yes (1× target) | Needs frames n, n−1, n−2. Runs on the dither-inverted frame — see ordering note below. |
| 1 | Dither inversion, extended dictionary (§3) | 160×144 | 160×144 | yes (1× target) | Replaces checker/dither cells with their intended grey. |
| 2 | Debanding in shade space (§4.3) | 160×144 | 160×144 | no — CPU ramp + texture re-upload | Same shape as Atomic: detect on CPU, upload the ramped plane, no GL pass. |
| 3 | ScaleFX passes 0–4 (§2.2) | 160×144 | 480×432 | yes (5 passes; 0–3 at 1×, 4 at 3×) | Pass 4 reads the chain source as its extra input. |
| 4 | rAA passes 0–1 (§2.2) | 480×432 | 480×432 | yes (2 passes) | Horizontal, then vertical. No-ring clamp (§8.5). |
| 5 | Sharp-bilinear final resample (§5) | 480×432 | internal game area (§1.2) | yes (game target, LINEAR filter) | 1 hardware-bilinear tap per output pixel. |
| 6 | Mitchell downscale (§6) | internal area | on-screen game area | yes, **only when internal > screen** (4K forced on a smaller display) | Separable, widened by scale, linear light. Skipped otherwise. |
| 7 | RCAS (§2.5) | game area | screen | **no — renders to the default framebuffer** | Viewport = on-screen game-area rect. `flipV` per the parity rule (§1.3). |
| 8 | 1-LSB blue-noise output dither (§8.6) | — | — | no | Epilogue of pass 7 (or final op): ±½ LSB blue noise before 8-bit quantisation. **[Dot-new]** |

**Ordering note (passes 0–1).** The deflicker gate must run on
dither-inverted frames, not raw frames: blending frames n and n−1
first would create intermediate greys that the dither classifier
cannot recognise (it matches exact palette shades), so inversion
would silently stop working on exactly the flickering scenes. Order
is therefore: invert frame n (pass 1), invert frame n−1 (already
stored inverted from the previous frame), then blend (pass 0 in the
table above is listed first only because it is the temporal stage;
in execution order it is: **invert → deflicker → deband → ScaleFX**).
Debanding runs after inversion for the same reason Atomic orders it
that way: inversion and debanding are disjoint by construction
(a ramp's 2×2 blocks are never checkers; checker texels never lie
inside a band mask), and debanding first would leave raw checker
texels inside the ramp that break solid runs. In Dot the same
disjointness holds, so the order invert → deband → ScaleFX is kept.
Atomic's own order in its layer chain is deband (CPU ramp on the raw
plane) then dither-invert (GL pass reading the debanded texture);
the two orders are equivalent **only** because the regions are
disjoint — Dot keeps Atomic's proven order (deband first, then
invert) **except** that the deflicker blend must see inverted
frames. Concretely, Dot's per-frame execution order is:

1. Deband the raw 160×144 plane on CPU (pass 2 logic), upload it.
2. Dither-invert the debanded plane in GL (pass 1 logic). Store the
   inverted frame as this frame's history frame.
3. Deflicker-blend the inverted frame against the stored inverted
   frames n−1, n−2 (pass 0 logic).
4. ScaleFX → rAA → sharp-bilinear → [Mitchell] → RCAS → output dither.

**What Atomic's composited chain does instead (reference).**
Atomic's Phase 1 (composited) chain is: optional cheap de-dither
pre-pass at 1× (`dedither`, a 0.5-blend heuristic — see §3.6) →
ScaleFX 0–4 → rAA 0–1 → JINC2-sharper to the internal game area →
RCAS to screen. Dot replaces the cheap de-dither heuristic with the
exact extended inversion (§3), replaces JINC2 with sharp-bilinear
(§5), and adds the deflicker gate, the deband stage, the conditional
Mitchell downscale, and the output dither. Atomic's layer chain
(which Dot Phase 1 does **not** build) is: per-layer 1×
deband/dither-invert → per-layer ScaleFX 2× (ScaleFX 3× + bilinear
downsample 3×→2×) → one composite at the 2× grid → hybrid final
stage (xBR-Lv2 3× → B-spline-x → B-spline-y → JINC2-bilateral →
deblur-fast) → RCAS. See §2.5 and §11.

**Chain name (diagnostics).** Atomic labels its chains as strings,
e.g. `gl:dedither>scalefx3x+raa>jinc2>rcas` and
`gl:layers>dither>scalefx2x>composite2x>hybrid-jinc2-xbr>rcas`.
Dot should label its chain the same way, e.g.
`gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>rcas`
(with `>mitchell` inserted before `rcas` when the downscale runs).
The label is the string shown on the error screen, in benchmarks,
and in failure reports (§9); it must name the chain that actually
drew, not the chain that was configured.

### 1.2 Intermediate sizes

Framing rule (Atomic `Framing`, ported with Dot's 160×144 source):
scale to the limiting dimension, keep the source aspect exactly
(160:144 = 10:9), pillarbox the sides with the charcoal clear
colour, never stretch or crop. The internal render target and the
on-screen rect are separate: when the forced target is taller than
the display allows, the chain renders large and downsamples
(supersampling).

- Resolved target height: setting `1080p` locks 1080; `4k` locks
  2160; `auto` (and any unknown value) follows the display:
  `scale = min(displayWidth/160, displayHeight/144)`, clamped to
  ≥ 1, and `targetHeight = 144 × scale` (truncated to int).
- Internal game area (`targetGameArea`): `scale = targetHeight/144`
  (≥ 1); `width = 160 × scale`, `height = 144 × scale`, offset (0,0).
- On-screen game area (`gameArea`): `scale = min(displayWidth/160,
  displayHeight/144, targetHeight/144)`, clamped to ≥ 1;
  `width = 160 × scale`, `height = 144 × scale`, centred
  (`offsetX = (displayWidth − width)/2`,
  `offsetY = (displayHeight − height)/2`).

| Target | Internal game area (FBO) | ScaleFX output → final scale | On-screen rect, 1080p display (1920×1080) | On-screen rect, 4K display (3840×2160) |
|--------|--------------------------|------------------------------|--------------------------------------------|----------------------------------------|
| 1080p | 1200×1080 (7.5×) | 480×432 → 1200×1080 = **2.5×** | 1200×1080, offsetX 360, offsetY 0 | 1200×1080 (display-limited), centred |
| 4K | 2400×2160 (15×) | 480×432 → 2400×2160 = **5×** (integer) | 1200×1080, offsetX 360, offsetY 0 — internal 2400×2160 downscaled 2× by Mitchell (§6), then RCAS 1:1 | 2400×2160, offsetX 720, offsetY 0 |
| Auto on 1440p display (2560×1440) | 1600×1440 (10×) | 480×432 → 1600×1440 = 3.333× | — | — |

Intermediate FBO sizes along the chain (independent of target):
dither-invert 160×144; ScaleFX passes 0–3 at 160×144; ScaleFX
pass 4 and rAA 0–1 at 480×432; sharp-bilinear output at the
internal game area; Mitchell output at the on-screen game area;
RCAS output is the screen.

Pool sizes (Atomic `GlChainTest` pins these for its chain; Dot's
follow by the same counting rule — one target per pass per size):
with dither inversion on, Dot needs 2 targets at 160×144
(dither-invert + ScaleFX 0–3 share the size but must not overwrite
each other's live inputs — see §1.4) — in practice the pool holds
one target per pass per size: 160×144 × (1 invert + 4 ScaleFX 0–3)
= 5 in the worst case if nothing is reused, 480×432 × 3
(ScaleFX 4 + rAA 0–1), and 1 at the internal game area
(sharp-bilinear output). The pool reuses targets across frames;
per-frame the rule in §1.4 is what matters, not the count.

### 1.3 Vertical-flip parity rule

Every FBO pass toggles the image vertically (an FBO texture's
v=0 is the bottom row of what was rendered; a bitmap/source
texture's v=0 is the top row). The final screen pass must sample
its input flipped exactly when the number of FBO passes before it
is odd.

- Atomic's rule, as pure functions: `chainFboPassCount` =
  (1 if de-dither else 0) + (1 for MMPX, else 7 for ScaleFX 0–4 +
  rAA 0–1) + 1 (JINC2; RCAS renders to the screen, not an FBO);
  `chainNeedsFinalFlip` = count odd. Verified cases: ScaleFX with
  de-dither 9 (flip), without 8 (no flip); MMPX with de-dither 3
  (flip), without 2 (no flip); xBRZ falls back to ScaleFX, same
  counts.
- **Dot's count:** dither-invert 1 + ScaleFX 0–4 (5) + rAA 0–1 (2)
  + sharp-bilinear 1 = **9 FBO passes** (flip) when inversion is
  on; **8** (no flip) when it is off. The conditional Mitchell
  downscale is an FBO pass too: when it runs, add 1 (9 → 10, no
  flip; 8 → 9, flip). **Implement the parity as a computed
  function of the passes actually run this frame, not a constant** —
  Atomic's layer chain learned this the hard way: under temporal
  reuse the pass count varies per frame, so parity cannot decide
  orientation there; the layer chain instead keeps a per-texture
  invariant (each 2× texture holds a once-flipped image, written
  by exactly one FBO pass). Dot Phase 1 has no temporal reuse of
  intermediates, so the computed parity rule is sufficient — but
  compute it from the pass list every frame, and unit-test the
  table exactly as Atomic's `GlChainTest` does (§10).
- The flipped screen pass uses a second fullscreen quad whose
  texture coordinates are swapped (clip-space top samples v=1
  instead of v=0). All other passes use the unflipped quad.
  The unflipped quad (triangle strip BL, BR, TL, TR) is:
  positions (−1,−1), (1,−1), (−1,1), (1,1) with texcoords
  (0,1), (1,1), (0,0), (1,0) — clip-space top (y=+1) samples
  v=0, because bitmaps upload top-row-first. The flipped quad
  swaps the texcoords to (0,0), (1,0), (0,1), (1,1).

### 1.4 Target-pool rules (the speckle bug, and how to avoid it)

Atomic's pool is keyed by (width, height) only, persists across
frames, and hands targets out under three rules. Dot must copy
all three — the first is a proven regression (a real bug Robert
saw as speckles):

1. **Never hand out a texture twice in one frame.** A per-frame
   "used" set is cleared at the start of each frame
   (`beginFrame`); every target handed out or freshly created is
   recorded there. This matters because the chain re-reads some
   textures later in the same frame (ScaleFX pass 4 reads the
   chain source; pass 2 reads the pass-0 output). The old
   first-fit rule handed the chain-source texture to ScaleFX
   pass 1 when de-dither was on, so pass 4 read one texture as
   both its inputs — tag data as colours (speckles). The fix:
   a target is eligible only if it is neither an input of the
   current pass (main input **and** extra input) nor already
   handed out this frame. Unit test: with the old rule, pass 4's
   main and extra inputs are the same texture (the bug); with
   the fixed rule they are always different (§10).
2. **Filter-aware reuse.** The pool is keyed by size only, so
   filtering is part of the reuse decision, not the key: only a
   texture whose min/mag filtering matches the request
   (LINEAR vs NEAREST) may be reused. Without this, a LINEAR
   request (the game target, or the b-spline guide in the layer
   chain) could silently receive a NEAREST texture left over
   from another pass at the same size, turning a bilinear sample
   into a point sample (or vice versa). Dot: the sharp-bilinear
   game target is LINEAR (it is downsampled by the Mitchell
   pass / sampled by RCAS); every intermediate carrying tag or
   metric data is NEAREST.
3. **Format fallback.** Intermediate targets are allocated as
   RGBA16F (half float) first, because ScaleFX passes 0–3 pack
   metric/tag values that need more than 8 bits; if the FBO is
   incomplete at that format, fall back to RGBA8 and re-check;
   if still incomplete, throw loudly (§9) — never silently
   render into an incomplete FBO. All targets use
   CLAMP_TO_EDGE wrapping on both axes. The source texture is
   NEAREST, allocated once and updated in place afterwards
   (`texImage2D` first, `texSubImage2D` after).

### 1.5 Framing, clear colour, and the pillarbox

Before the chain runs, the whole surface is cleared to charcoal
(Atomic: RGB 0x1A, 0x19, 0x1E — i.e. (26, 25, 30)/255, alpha 1)
with the viewport set to the full surface. The final (RCAS) pass
sets the viewport to the on-screen game-area rect and draws only
inside it, so the sides stay charcoal. If any area computation
yields a non-positive width or height, clear charcoal and draw
nothing that frame (do not attempt a zero-size FBO). On a
resolution change (resolved height differs from the active one)
drop every pooled target and rebuild for the new size — a stale
pool across a change hands the chain a target of the old size
(Atomic alpha.6 pool corruption). `onSurfaceChanged` also drops
all targets for the same reason.

### 1.6 What the source texture holds

The chain source is the emulated frame uploaded as one RGBA8
texture at 160×144, NEAREST. The "chain source" for ScaleFX
pass 4 is the frame **as ScaleFX pass 0 saw it**: the
dither-inverted (and debanded) frame when inversion is on, else
the raw upload. Track it explicitly: after the inversion pass,
`chainSourceTex` is the inversion target; otherwise it is the
source texture. The pass-0 metric output texture must likewise
be tracked explicitly for pass 2. Never derive either by
assuming which pool texture is newest.

---

## 2. Each pass's algorithm

All shaders are GLES 3.00 (`#version 300 es`, `highp float` /
`highp int`). Every pass draws one fullscreen triangle strip
(§1.3) with an identity model-view-projection matrix. Uniforms
set for every pass (libretro-style, plus Atomic's own):

- Main input on texture unit 0, bound to the samplers named
  `Texture`, `s_p`, and `uTexture` (different shaders use
  different names; set all three — setting a uniform that a
  program does not declare is a no-op).
- Extra input (ScaleFX pass 2 / pass 4) on texture unit 1, bound
  to `PassPrev2Texture` and `PassPrev5Texture`.
- `TextureSize` = `InputSize` = main input size in pixels;
  `OutputSize` = output size in pixels;
  `PassPrev5TextureSize` = the **extra** texture's size when one
  is bound, else the input's size (getting this wrong steps the
  ±2 neighbour offsets by the wrong distance — Atomic alpha.59);
  `PassPrev5InputSize` = input size; `PassPrev2TextureSize` =
  extra size when bound.
- `FrameCount` = frames drawn so far (integer, increments once
  per drawn frame); `FrameDirection` = 1 always.
- `uTexel` = (1/inputWidth, 1/inputHeight) for Atomic's own
  passes (dedither, dither-invert, mmpx, downsample).
- Sampler locations that do not exist in a program are skipped
  (location < 0 → no-op); this is how one binding routine serves
  every pass.

Blending and depth testing are disabled for the whole renderer
(`glDisable(GL_BLEND)`, `glDisable(GL_DEPTH_TEST)`); every pass
writes colour unconditionally. Programs are compiled lazily on
first use from the asset files, cached by the
(vertex asset, fragment asset) pair, and **all program/target/
texture IDs are dropped on surface (re)creation** — a fresh GL
context means every old ID is dead; never delete dead IDs, just
drop them and rebuild. A failed compile or link throws with the
compiler log in the message (§9).

### 2.1 Pass 0/1 — dither inversion and debanding

See §3 (dither inversion, full dictionary) and §4 (debanding).
Both run at 1× before any scaler. Debanding is a CPU ramp plus a
texture re-upload, not a GL pass; dither inversion is one GL pass
with two inputs (the colour texture on unit 0, the palette plane
as an R8UI unsigned-integer texture on unit 1).

### 2.2 ScaleFX 3× (passes 0–4) + rAA — the detail stage

ScaleFX (Sp00kyFox, MIT) is an edge-interpolation algorithm for
pixel art. Its guarantee: **the output contains only colours
present in the input** — it never invents a colour, it only
chooses, per 3×3 sub-pixel, which neighbouring input texel's
colour that sub-pixel takes. It interpolates edges up to level 6
and makes smooth transitions between different slopes. The five
passes form a pipeline of metric → strength → junction resolution
→ tag → sub-pixel selection. All five passes sample NEAREST;
intermediate formats are RGBA16F (§1.4).

**Colour-distance metric (pass 0's core).** For two RGB colours
A, B (each channel in [0,1]), let r = 0.5·(A.r + B.r), d = A − B,
and weight vector c = (2 + r, 4, 3 − r). Then

    dist(A, B) = sqrt( (c·d)·d ) / 3
               = sqrt( (2+r)·d.r² + 4·d.g² + (3−r)·d.b² ) / 3.

This is the Compuphase redmean-style weighted distance: red and
blue are weighted by the mean redness, green always by 4. It is
used everywhere ScaleFX compares colours. **[Dot adaptation]**
Atomic's layer variant of pass 0 decodes its inputs to linear
light first (the layer chain is linear); the composited chain's
pass 0 works on the sRGB-encoded values as uploaded. Dot Phase 1
is composited, so Dot's pass 0 uses the encoded values — **but**
Dot's source has only 4 greys, so the distance reduces to a
function of the grey difference alone; the redmean weighting is
still implemented as written (it costs nothing and keeps the
behaviour identical if the palette ever gains colour).

**Pass 0 — metric data (1× → 1×).** For each input texel E
(centre) with neighbours A, B, C on the row above (left, centre,
right) and F to its right, output the four distances
`(dist(E,A), dist(E,B), dist(E,C), dist(E,F))` as an RGBA texel.
Neighbour coordinates are supplied by the vertex shader as
precomputed varying offsets: row above at (−1,−1), (0,−1),
(1,−1); row of E at (0,0), (1,0). The output is a 4-channel
metric field, one texel per input texel. (In the layer chain the
inputs are sRGB-decoded before the distance; in the composited
chain they are not.)

**Pass 1 — interpolation-candidate strength (1× → 1×).** Reads
the metric field over the 3×3 neighbourhood of E (A, B above;
D, E, F on the centre row; G, H, I below). For each of the four
corners of E it computes a corner strength. The strength helper,
for a metric distance d and two metric pairs a, b, is:

    diff  = a.x − a.y
    wght1 = max(SFX_CLR − d, 0) / SFX_CLR,   with SFX_CLR = 0.5
    wght2 = clamp( (1 − d) + (min(a.x,b.x) + a.x > min(a.y,b.y) + a.y ? diff : −diff), 0, 1 )
    str   = (SFX_SAA == 1  or  2·d < a.x + a.y) ? wght1 · wght2 · (a.x · a.y) : 0,
    with SFX_SAA = 1.0.

The four outputs are the strengths of the top-left, top-right,
bottom-left and bottom-right corners of E, packed as RGBA. A
strength is zero unless the two adjacent metric values are both
non-zero (the product a.x·a.y) and the distance is small enough
(the SFX_CLR falloff) — i.e. a corner exists only where two
different colours meet with a sharp metric step.

**Pass 2 — junction resolution (1× → 1×, extra input = pass-0
metric).** Reads the pass-1 strength field (main input) over the
3×3 neighbourhood and the pass-0 metric field (extra input) over
the same neighbourhood. **Orientation trap (Atomic alpha.45):**
the metric texture is flipped once relative to the strength
texture (it has passed through one more FBO), so the metric must
be sampled at vertically mirrored coordinates — the vertex shader
supplies a second set of varyings with negated y offsets for the
metric, while the strength keeps the unmirrored ones. Sampling
both at the same coordinates is a real, shipped bug: it mirrors
the junction decisions.

For each of the four junctions around E it computes a dominance
value: `dom(x,y,z,w) = 2·(x.y, y.y, z.y, w.y) − ((x.x,y.x,z.x,w.x)
+ (x.z,y.z,z.z,w.z))` (component-wise, on the strength vectors'
channels), then a majority vote over the four rotations to decide
which corner candidate wins at ambiguous junctions, then injects
the raw strength back in where it creates no contradiction, then
applies single-pixel and end-of-line detection (a corner that is
isolated in both directions is suppressed). Separately it tests
each corner for a "clear" orthogonal edge: `clear(crn, a, b)` is 1
iff `crn.x ≥ max(min(a.x,a.y), min(b.x,b.y))` and
`crn.y ≥ max(min(a.x,b.y), min(b.x,a.y))`, else 0. It also
computes horizontal/vertical minima `h`, `v` from the metric
field and an orientation bit `or = (h + shifted metric) ≥
(v + shifted metric)`. The four outputs pack, per corner,
`(corners + 2·horizontal + 4·vertical + 8·orientation) / 15`
— i.e. four boolean flags per corner packed into one float in
[0,1] in units of 1/15. That is why the intermediate must be
RGBA16F: the flags are extracted later by
`floor(mod(value·15 + 0.5, 2))` and its siblings (×7.5, ×3.75,
×1.875 for the second, third and fourth flags).

**Pass 3 — edge level and sub-pixel tags (1× → 1×).** Reads the
pass-2 packed flags over a 7×7 neighbourhood (centre E, ±1/±2/±3
in each axis). It unpacks the four flags per texel (corners,
horizontal edges, vertical edges, orientation) and then decides,
for each of the four corners and four edge-midpoints of E, which
edge level (1–6) is present, by AND-chains of flags reaching
progressively further along the edge:

- Level 1 corners: the corner flag at E and (a neighbouring
  corner flag, or the `SFX_SCN = 1.0` "single-pixel" allowance).
- Level 2 mids: level-1 corner + the edge flag at E + the
  neighbouring corner flag.
- Level 3 corners: level-2 mid + the next edge flags + the
  following corner flag.
- Level 4 corners: a six-flag conjunction reaching two texels
  out plus the corner/edge flags at distance 2.
- Level 5 mids: level-4 corner + the distance-2/3 edge flags.
- Level 6 corners: level-5 mid + the distance-3 edge flags.

Each corner and each midpoint then resolves to a sub-pixel code
0–8, where 0 = E itself, 1 = D (left), 2 = D0 (two left),
3 = F (right), 4 = F0 (two right), 5 = B (above), 6 = B0 (two
above), 7 = H (below), 8 = H0 (two below). The corner codes and
mid codes are packed into one float as
`(corner + 9·mid) / 80`, four corners/mids per RGBA texel.
Codes are chosen by priority: a diagonal (orientation-matched)
level is preferred over a straight one, then higher levels over
lower ones, with the level-1 corner as the fallback.

**Pass 4 — sub-pixel output (1× → 3×, extra input = chain
source).** For each output texel, compute which of the 3×3
sub-pixels of its source texel it is:
`fp = floor(3 · fract(vTexCoord · sourceSize))`, giving
(fp.x, fp.y) each in {0,1,2}. Read the pass-3 tag texel, unpack
corner = `floor(mod(tag·80 + 0.5, 9))` and mid =
`floor(mod(tag·80/9 + 0.0555…, 9))` (i.e. the two base-9 digits
of the packed value), select the code for this sub-pixel
position (corners at the four corners, mids at the edge centres,
0 = E at the centre), map the code to an offset (0→(0,0),
1→(−1,0), 2→(−2,0), 3→(1,0), 4→(2,0), 5→(0,−1), 6→(0,−2),
7→(0,1), 8→(0,2) in source-texel units), and output the chain
source's colour at `vTexCoord + offset/sourceSize`, sampled
NEAREST. The vertex shader supplies the tag texel's own
coordinates plus the ±1/±2/±3 neighbour coordinates used by
pass 3; the offset arithmetic uses `PassPrev5TextureSize` (the
chain source size) — see §2's uniform note. The output is
exactly one of the input colours per sub-pixel: no new colours,
no blending. **[Dot adaptation]** Atomic's layer variant of
pass 4 decodes the selected colour to linear light and overrides
alpha with the centre texel's flag byte (the composite reads
flags per texel). Dot Phase 1 has no flags and no composite, so
Dot's pass 4 outputs the selected colour with alpha 1.0; the
linear-light decode is **not** done here in Dot — Dot's
linear-light work happens in the stages that average (§3, §4,
§5, §6), not in the colour-selection stage.

**rAA passes 0–1 (3× → 3×, horizontal then vertical).**
Reverse anti-aliasing (Sp00kyFox, MIT), a generalised
continuation of Feck's rAA for already-upscaled images.
Constants (both passes): `scl = 3` (the ScaleFX factor),
`rad = 7` (search radius), `RAA_SHR = 2.0`, `RAA_SMT = 0.5`,
`RAA_DVT = 1.0`. Each pass reads a 15-tap line (centre ±7) along
its axis. Per output texel:

1. Candidate search: walk outward from the centre; maintain
   the two most recent inter-texel distances on each side
   (d1, d2, d3 as the walk advances); a texel is a candidate
   edge when its distance is a local maximum
   (`max(d1, d3) < d2`, tracked separately per side and per
   channel-pair). Record the first candidate index i1 and the
   second i2 on each side (i2 defaults to i1+1 when none is
   found).
2. Smoothness weight: `sw = |df1 − df2| / (|df1| + |df2|)`,
   raised to RAA_SMT (0.5); `sw = 1` when the denominator is 0.
   This protects smooth gradients — where neighbouring steps
   are equal, the weight collapses toward 0 and rAA does
   nothing.
3. Tilt (`res2x`): from the five samples (pre2, pre1, px, pos1,
   pos2) build the forward-difference matrix, take
   `m = RAA_SHR · min(distance-to-0.5-or-1.0 per channel,
   |df1|, |df2|)` as the magnitude cap, and the tilt
   `t = (7·(df1+df2) − 3·(df0+df3)) / 16` (a 4-point
   derivative estimate). Clamp per channel (`t1 = clamp(t, −m,
   m)`) and by length (`t2 = min(1, min over channels of
   m/|t|) · t`). Blend between the two by the colour-deviation
   weight `w = min(1, max(d1, d2)/0.8125)` raised to RAA_DVT,
   where d1, d2 are the distances from the candidate lines to
   the tilted point (cross-product distance divided by line
   length; 0 when the line has zero length).
4. Distance weight: `dw = 0` if either side found no candidate
   (i1 == 0 on either side); else
   `dw = 2·((i1.x − 1)/(i1.x + i1.y − 2)) − 1`, which is −1
   when the left edge is nearer and +1 when the right edge is
   nearer.
5. Result: `px + (scl−1)/scl · sw · dw · t`, i.e. the centre
   texel shifted along the estimated edge tilt by up to 2/3 of
   the tilt, weighted by smoothness and by which side the edge
   is on.
6. **No-ring clamp (§8.5):** clamp the result per channel to
   `[min, max]` of the three immediate neighbours along the
   axis (TX(−1), TX(0), TX(+1)), and output alpha 1.0. This
   clamp is what prevents rAA from ever ringing — no output
   value can leave the local 3-tap range.

Pass 0 does this horizontally, pass 1 vertically, with identical
constants and the same code transposed. rAA's cost is one
15-tap line read per output texel per pass at 480×432 — trivial
at Dot's sizes.

**MMPX 2× (the alternative Atomic ships; not Dot's default).**
For completeness: Atomic's alternate upscaler is MMPX 2×
(McGuire & Gagiu, MIT), one pass, 2× output, palette-exact.
Per output texel it reads the 3×3 neighbourhood plus an outer
ring (±2), and for each of the four 2×2 sub-pixels applies
exact-equality corner/slope rules (e.g. top-left takes the left
neighbour's colour when left == above, left ≠ below-right and
above ≠ right, or when the left colour continues diagonally with
its outer-ring witnesses). No blending, no new colours. Dot
keeps MMPX as a TEST candidate only (research audit §1.3: it
preserves 1-px features and text better than ScaleFX, but at 2×
it changes the chain shape and it keeps more raw checkerboard,
which fights dither inversion). It is not in the Phase 1 chain.

### 2.3 JINC2-sharper — Atomic's final resample (reference only)

**Dot does NOT use this pass; §5 replaces it with
sharp-bilinear.** It is documented here because it is the pass
Dot is replacing, and because its constants are the reference
for the "sharper" end of the trade-off.

Windowed-JINC 2-lobe resampler with anti-ringing (Hyllian,
GPL-2.0-or-later in this form). Constants:
`JINC2_WINDOW_SINC = 0.42`, `JINC2_SINC = 0.92`,
`JINC2_AR_STRENGTH = 0.8`. The kernel is **not** a true Bessel
function: for a distance x (in input-texel units) the weight is

    w(x) = sin(x·wa)·sin(x·wb) / x²,   wa = 0.42·π, wb = 0.92·π,
    w(0) = wa·wb (the limit).

Per output pixel: map to input coordinates `pc = texCoord ·
TextureSize`; the 4×4 tap grid is centred on
`tc = floor(pc − 0.5) + 0.5` with offsets (−1..+2) in both axes;
each tap's weight is `w(Euclidean distance from pc to the tap
centre)` — radial, not separable, so diagonals are treated the
same as horizontal/vertical edges. The 16 weighted colours are
summed and divided by the sum of the 16 weights (normalisation).
Anti-ringing: compute the per-channel min and max of the central
2×2 taps (c11, c21, c12, c22); clamp the result into that range;
then mix the clamped and unclamped results with strength 0.8
(`mix(aux, clamped, 0.8)`). Output alpha 1.0. The vertex shader
scales the texture coordinate by 1.0001 (a guard against
exact-edge sampling artefacts). Why Dot rejects it: its negative
lobes overshoot every hard black↔white edge (a halo that is a new
grey outside the 4-shade palette, plus a dark undershoot), and
the "sharper" tuning reintroduces the dithering that inversion
just removed; at the integer 5× target it collapses to nearest
anyway, so it buys nothing there.

### 2.4 RCAS — robust contrast-adaptive sharpening (screen pass)

FSR1 RCAS (AMD, MIT; Atomic's port from the Shadertoy
reference). Runs **last**, to the screen, on the sRGB-encoded
game-area image. Constants: `FSR_SHARPENING = 0.9` (Atomic:
gentle; note FSR's own default 0.2 is *sharper* — lower means
sharper), `FSR_RCAS_LIMIT = 0.25 − 1/16 = 0.1875`. Denoise is
**off** (the `FSR_RCAS_DENOISE` branch is not compiled in).

Per output pixel, with integer pixel position `ip` and the
5-tap cross (b above, d left, e centre, f right, h below),
sampled at `ip/OutputSize`:

1. Luma per tap: `L = g + 0.5·(b + r)` (green plus half the sum
   of blue and red — i.e. luma × 2, the constant factor cancels
   in the ratios below).
2. Noise detection: `nz = 0.25·(bL+dL+fL+hL) − eL`, then
   `nz = clamp(|nz| / (max(bL,dL,eL,fL,hL) − min(...)), 0, 1)`,
   then `nz = 1 − 0.5·nz`. (With denoise off, `nz` is computed
   but not applied — record this; a future denoise enablement
   would multiply the lobe by `nz`.)
3. Ring min/max: `mn4 = min(b, f, h)`, `mx4 = max(b, f, h)`
   (note: d, the left tap, is **not** in the ring — only b, f, h).
4. Lobe: `hitMin = mn4 / (4·mx4)`,
   `hitMax = (1 − mx4) / (4·mn4 − 4)` (peak constants (1, −4));
   `lobeRGB = max(−hitMin, hitMax)` per channel;
   `lobe = max(−0.1875, min(max(lobeRGB.r, .g, .b), 0)) · con`,
   where `con = 2^(−sharpness) = 2^(−0.9) ≈ 0.536`. So the lobe
   is always ≤ 0 (sharpening only, never blurring), bounded in
   magnitude by 0.1875·con ≈ 0.1005.
5. Resolve: `out = (lobe·(b + d + h + f) + e) / (4·lobe + 1)`,
   alpha 1.0.

RCAS runs at the on-screen game-area size (after the Mitchell
downscale when one runs — §6 — so that it sharpens the
downscaled image, never the pre-downscale one). Its 5 taps are
the cheapest pass in the chain.

### 2.5 The layer chain's hybrid final stage (Atomic only — Dot Phase 2 at most)

For reference, and because Dot Phase 2 (per-layer framebuffers)
will need a final stage: Atomic's layer chain composites at the
2× grid (480×320 for GBA) and then runs six passes to the game
area. **Dot Phase 1 does not build any of this.**

1. **xBR-Lv2 pass 0 (1× on the composite):** packs edge
   information. Constants: `SMALL_DETAILS = 1.0`,
   `XBR_WP4_PARAM = 0.8` (WP4 = 5·0.8 = 4.0),
   `XBR_KA_PARAM = 0.35` (KA = 6 − 5·0.35 = 4.25),
   `XBR_EQ_THRESHOLD = 0.26`, `XBR_FD_PARAM = 0.0` (FD = 1.0),
   corner mode C enabled (A and B disabled), `lv2_cf = 2.4`.
   Reads a 5×5 neighbourhood; colour distance is the luma-
   weighted absolute difference (`dot(|A−B|, Y)` with
   Y = (0.299, 0.587, 0.114) when SMALL_DETAILS ≥ 0.5, else
   Y = (0.2627, 0.6780, 0.0593)); two colours are "equal" when
   that distance ≤ 0.26. Weighted distance combines four
   directed distances with FD and the WP4 term. Output packs
   five flags per channel (values up to 79) — hence the RGBA16F
   intermediate. (Licence: GPL family via the libretro xBR
   lineage — Dot must reimplement from the algorithm
   description, not copy, if it ever adopts xBR.)
2. **xBR-Lv2 pass 1 (3×):** reads the pass-0 info (main input)
   and the composite itself (extra input, the colours). Blending
   strength `XBR_BLENDING = 1.0`. Unpacks the info bits
   (successive halving: each flag is `round(modf(i/2))`),
   evaluates the interpolation rules along the four diagonal
   directions with the fixed offset vectors
   (Ao/Bo/Co, Ax/Bx/Cx, Ay/By/Cy, Ci = 0.25), and blends the
   selected colours. Output 3× the composite.
3. **B-spline-x / B-spline-y (separable stretch to the game
   area):** cubic B-spline with B = 1.0, C = 0.0 (the
   Mitchell–Netravali family, §6 — B=1, C=0 is the all-positive,
   zero-ringing, approximating end). Per output texel: take the
   fractional position `fp = fract(texCoord · sourceSize)`,
   the four taps along the axis, the weight vector
   `(fp³, fp², fp, 1) · INV` where INV is the 4×4 inverse
   B-spline matrix built from B and C, and output the weighted
   sum. X first (to game-area width), then Y (to game-area
   height). The Y pass's target is LINEAR-filtered: the
   bilateral pass samples it bilinearly.
4. **JINC2-bilateral (1×, to the game area):** resamples the
   composite to the game area with the §2.3 JINC2 kernel in the
   space domain, range-weighted by the B-spline guide.
   Constants: `J2B_WA = 0.50`, `J2B_WB = 0.88`,
   `J2B_AR_STR = 1.0`, `J2B_STR = 1.0`. Space weights are
   `sin(x·wa)·sin(x·wb)/x²` with wa = 0.50π, wb = 0.88π over
   the same 4×4 radial footprint as §2.3 (kernel in
   composite-texel units, `OriginalSize` = composite size,
   carried by `PassPrev2TextureSize`). Range weights: for each
   of the 16 taps, `I = lanczos(luma(|guide − tap|) · 1.0, 2.0)`
   where `lanczos(x, a) = sinc(x)·sinc(x/a)`,
   `sinc(x) = sin(πx)/(πx)`, luma uses Y = (0.2126, 0.7152,
   0.0722), and a tiny epsilon (0.00001) keeps the distance
   function non-zero. Space and range weights are multiplied
   element-wise, the 16 colours summed and normalised by the
   weight sum, then anti-ringing-clamped to the central 2×2
   min/max with strength 1.0 (full clamp). Main input is the
   guide (LINEAR); extra input is the composite (NEAREST taps).
5. **deblur-fast (1×, into the game target):** the last
   linear-light pass — it sRGB-encodes its output (the encode
   the retired JINC2-resample pass used to do). Constants:
   `OFFSET_F = 0.5`, `DEBLUR_F = 5.0`, `SMART_F = 0.5`. Reads
   the 3×3 neighbourhood; per channel computes the 3×3 min and
   max, the distances from the centre to each
   (`Dmin = |E − Cmin| + 0.0001`, `Dmax = |E − Cmax| + 0.0001`),
   a per-channel exponent
   `vexp = clamp(5.0 · max(luma(Dmin), luma(Dmax)), 2.0 − 0.5,
   10.0)` with luma Y = (0.2627, 0.6780, 0.0593), raises both
   distances to that exponent, and outputs the power-weighted
   blend `(Dmin^vexp · Cmax + Dmax^vexp · Cmin) /
   (Dmin^vexp + Dmax^vexp)`, then sRGB-encodes. The exponent
   makes the blend favour whichever extreme the centre is
   nearer to in a contrast-dependent way — a smart deblur that
   sharpens without inventing values outside [Cmin, Cmax].

### 2.6 Composite (Atomic layer chain — GBA only, §11)

Atomic's composite decides, per 2× output texel, which layer
wins by mGBA's priority/word rules, in linear light, with
fades done on the encoded value (see §4.2). Dot Phase 1 has no
layers, no priorities, no windows, no mosaic, no blend modes —
the composited frame arrives already composited from the core.
The composite's portable ideas for Dot Phase 2 are: priority
is decided per output texel *before* any resample (never per
input texel, never after resampling); colour samples may be
bilinear but flag/priority samples must be exact texel fetches;
and scroll-phase de-shimmer (§2.7) belongs in the composite,
not in the resampler.

### 2.7 Scroll-phase de-shimmer (Atomic layer chain — GBA only, §11)

Atomic's composite snaps each text BG layer's colour sample to
the layer's tile-space phase so that resampled frames are exact
integer translates of each other under scrolling. For a scroll
value `s` (HOFS or VOFS) and a resample scale `s` (output px per
2× texel):

    φ(scroll, s)  = fract((−2·(scroll mod 8) + 0.5)·s − 0.5)
    φ0(s)         = fract(0.5·s − 0.5)
    Δ(scroll, s)  = fract(φ0 − φ)

The sample is taken at u − Δx and v + Δy — **opposite signs**,
because v increases upward in texture space while u increases
rightward. Flags are never snapped; OBJ is never snapped; a
mosaic'd layer does not snap. Gated per scanline on the display
mode and the layer's mosaic-enable bit. For Dot Phase 2 the same
formula ports with SCX/SCY as the scroll values; for Dot Phase 1
(composited frame) there is no per-layer scroll to snap, so
shimmer in the final resample is handled by the sharp-bilinear
stage's texel-anchored sampling instead (§5).

---

## 3. Dither inversion — classifier rules and the full extended pattern dictionary

### 3.1 What Atomic proves (the 2×2 checker core)

Atomic's exact dither inversion (`DitherInversion.kt`, mirrored
in `dither-invert.frag`) works on 2×2 even-parity blocks. A block
with corners TL = A, TR = B, BL = C, BR = D is inverted iff **all
four** conjuncts hold:

1. **Checker:** `A == D` and `B == C` and `A != B` (exact texel
   equality — hence NEAREST sampling and `texelFetch` everywhere;
   any filtering would destroy the test).
2. **Same palette:** the four texels' palette ids (from the
   palette plane, §3.4) are all equal and not the sentinel 255
   (uncovered / no tile). This is the guard that saves the
   alternating-palette brick wall: a brick pattern whose mortar
   lines alternate palettes is **not** inverted.
3. **Field:** at least 3 of the 8 neighbouring 2×2 blocks are
   checkers of the same unordered pair {A, B} (either phase).
   Neighbours are edge-clamped (a clamped neighbour is the centre
   block itself, which counts).
4. **Baseline density:** among the 8 sample blocks at ±8 and ±32
   blocks (±16 / ±64 texels) in x and y, edge-clamped, each sample
   votes its A-fraction (number of its four texels equal to A,
   0–4) iff all four of its texels are A or B; otherwise it
   abstains. Invert iff the votes take **≥ 3 distinct values
   spanning ≥ 2 quarters (≥ 0.5)**. This is the gradient test: a
   real dither field sits on a gradient, so distant samples show
   different densities; a flat intentional checkerboard shows one
   density everywhere and fails.

**Replacement:** every texel of an inverted block becomes the
linear-light average of A and B (§4.1): sRGB-decode both, average,
sRGB-encode, quantise back. The flag/alpha byte is the centre
texel's, untouched — inversion must never invent a flag. In
Atomic's shader the replacement is computed in float and the
alpha is the centre texel's; in the Kotlin oracle the average is
rounded half-to-even (`Math.rint`, matching NumPy) and the
reference check is `average(0xF800, 0x07E0) = 0xBDC0` exactly.

**Scope:** Atomic applies this only to eligible layers — mode 0
text BG layers / mode 1 BG0–1, mosaic-free, enabled, constant
mode (the dedup scope). Stamped (atlas-dedup) layers skip
inversion in v1. A missing tile frame means no inversion that
frame.

### 3.2 Dot's extended dictionary

The audit (§1.6) locks the dictionary for Dot as: **2×2, 2×3,
3×3, diagonal, vertical/horizontal lines, 25/50/75% densities,
Bayer 2×2/4×4, palette-aware per scanline**. Atomic implements
only the 2×2 checker row of this table; the rest is the
**[Dot adaptation]** extension, specified here precisely enough
to implement. The extension rule is: **every family reuses the
same four-gate structure as §3.1** (pattern match, palette
membership, field, baseline density), with the pattern-match and
replacement generalised per family. Per-game dictionaries are
explicitly rejected (audit §1.6): no ROM-hash database, no
per-game toggles; if "Report picture problem" exports cluster on
a game/pattern, add that pattern to the *global* dictionary
post-release.

Notation: the two shades of a pattern are A (darker) and B
(lighter), both members of the 4-shade palette. "Linear-light
average with weight f" means: decode A and B to linear light,
compute `f·lin(A) + (1−f)·lin(B)`, encode back, quantise to the
nearest palette shade **only if the result must be a palette
shade** — for the replacement value itself, keep the encoded
8-bit grey (do **not** snap to the 4 shades; the whole point is
to produce the intermediate grey the artist intended).

| Family | Cell | Pattern match (exact shade equality, NEAREST) | Density f (fraction of A) | Replacement (every texel of the cell) |
|--------|------|-----------------------------------------------|---------------------------|--------------------------------------|
| 2×2 checker, phase 0 | 2×2 | TL=BR=A, TR=BL=B | 0.5 | linear-light 50/50 average of A, B |
| 2×2 checker, phase 1 | 2×2 | TL=BR=B, TR=BL=A | 0.5 | same |
| 2×3 / 3×2 checker | 2×3 or 3×2 | alternating A/B in a checkerboard over the 6 texels | 0.5 (3 of each) | linear-light 50/50 average |
| 3×3 checker | 3×3 | alternating A/B in a checkerboard over the 9 texels (centre = majority shade) | 5/9 or 4/9 (≈0.556 / 0.444) | linear-light weighted average with f = count(A)/9 |
| Diagonal (staircase) | 2×2 blocks along a diagonal | a run of ≥3 consecutive 2×2 checker blocks whose phase flips every row (TL=A on even rows, TL=B on odd rows), forming a diagonal boundary | 0.5 | linear-light 50/50 average |
| Vertical lines | 1×2 (per column pair) | columns alternate A, B, A, B… over a run of ≥4 columns, each column solid over ≥2 rows | 0.5 | linear-light 50/50 average |
| Horizontal lines | 2×1 (per row pair) | rows alternate A, B, A, B… over a run of ≥4 rows, each row solid over ≥2 columns | 0.5 | linear-light 50/50 average |
| 25% density | 2×2 or 4×4 ordered cell | exactly 1 of 4 (2×2) or 4 of 16 (4×4) texels are A, the rest B, in the Bayer positions for that density (§3.3) | 0.25 | linear-light average with f = 0.25 |
| 50% density | 2×2 or 4×4 ordered cell | 2 of 4 / 8 of 16 texels are A in Bayer positions | 0.5 | linear-light 50/50 average |
| 75% density | 2×2 or 4×4 ordered cell | 3 of 4 / 12 of 16 texels are A in Bayer positions | 0.75 | linear-light average with f = 0.75 |
| Bayer 2×2 | 2×2 | the cell matches the Bayer 2×2 threshold pattern for some density (see §3.3) | per threshold | linear-light weighted average with f = count(A)/4 |
| Bayer 4×4 | 4×4 | the cell matches the Bayer 4×4 threshold pattern for some density | per threshold | linear-light weighted average with f = count(A)/16 |

**Field gate (all families):** at least 3 of the 8 neighbouring
cells (same cell size, edge-clamped) must match a pattern of the
*same family and same shade pair {A, B}* (any phase / any
density within the family). For the 3×3 and 4×4 families the
neighbourhood is the 8 adjacent cells of the same size.

**Baseline-density gate (all families):** as §3.1 conjunct 4,
with the cell size substituted: sample 8 cells at ±8 and ±32
cells in x and y (edge-clamped); a sample votes its A-fraction
(count of A texels / cell size) iff all its texels are A or B;
invert iff the votes take ≥ 3 distinct values spanning ≥ 0.5.
For the 25/50/75% families the vote is the density itself, so
the gate requires the local density to actually vary — a flat
25% stipple field (constant density everywhere) fails, which is
correct: a constant-density stipple *is* a texture, not a
gradient.

**Palette gate (all families) — [Dot adaptation].** Atomic's
conjunct 2 needs a per-texel palette plane built from tile keys
+ HOFS (§3.4). Dot Phase 1 (composited frame) has no per-texel
tile identity, so the conjunct cannot be evaluated as written.
Dot's replacement rule: (a) both shades must be members of the
4-shade palette (trivially true for any DMG frame — this only
excludes already-inverted intermediate greys, i.e. a cell is
never inverted twice); and (b) the field gate is tightened from
≥3 to **≥4 of 8 neighbours** as compensation for the missing
palette evidence. When Dot Phase 2 adds per-tile data, restore
Atomic's conjunct 2 verbatim (same palette id for all texels of
the cell, sentinel = uncovered) and relax the field gate back to
≥3. The brick-wall guard is preserved in Phase 1 by the
combination of the tightened field gate and the baseline-density
gate: a brick wall's mortar pattern is constant-density and
fails conjunct 4.

**Disjointness with debanding:** inversion and debanding are
disjoint by construction — a ramp's 2×2 blocks are never
checkers, and checker texels never lie inside a band mask
(Atomic E5a). Dot keeps the same composition: deband first on
the raw plane, invert second on the debanded texture (see §1.1
for the deflicker ordering, which is the one place Dot differs).

### 3.3 Bayer ordered-dither matrices

Ordered dither thresholds, standard (public-domain) matrices.
A cell of size N×N with threshold matrix T (values 0..N²−1) and
target density d (fraction of A) is "on" (shade A) at position
(i, j) iff `T[i][j] < d·N²`, and B otherwise. Detection is the
converse: a cell matches Bayer density d for pair {A, B} iff
every texel equals the shade the matrix predicts for that
position at density d. Test densities d ∈ {0.25, 0.5, 0.75} (and,
for the 4×4 matrix, the intermediate sixteenths when the
baseline gate needs finer votes — the replacement weight is
always the counted fraction, never the nominal density).

Bayer 2×2 (values 0..3):

    0  2
    3  1

Bayer 4×4 (values 0..15):

     0   8   2  10
    12   4  14   6
     3  11   1   9
    15   7  13   5

### 3.4 The palette plane (Atomic — layer chain only, §11)

For completeness, because Dot Phase 2 will need it: Atomic
builds a 240×160 plane of palette ids (one byte per texel, 255
= sentinel for uncovered texels) from the tile grid, the
coverage flags, and the per-scanline HOFS. The lattice column
of the tile covering screen texel x on a scanline with HOFS h
is `k = floorDiv((x − ((x + h) mod 8)) + 3, 8)` — note **floor**
division, not truncating division: with a large HOFS the
covering tile can start off-screen left and k is −1, not 0.
The palette of a tile key is: key 0 → 0; 8bpp → 16 (the whole
256-colour palette); 4bpp → bits 17–20 of the key. The plane is
uploaded once per frame per eligible layer as an R8UI texture
(240×160, `GL_RED_INTEGER` / `GL_UNSIGNED_BYTE`) and sampled
with `texelFetch`. Dot Phase 1 does not build this plane
(§3.2 palette gate).

### 3.5 The cheap heuristic (Atomic's composited pre-pass — NOT Dot's)

Atomic's composited chain also ships a cheap 1× de-dither
(`dedither.frag`): a pixel is dithered when its 2×2 block is a
checker (NW == SE, NE == SW, NW != NE), the centre pixel is one
of the two colours, **and** every orthogonal neighbour (N, S, E,
W) is also one of the two colours — the guard against false
positives on intentional checkerboard textures. A dithered pixel
is blended **halfway** toward the mean of the two colours
(`mix(e, 0.5·(nw+ne), 0.5)`) — deliberately 0.5, not a full
replace, so a misdetected texture pixel is only softened, never
flattened. Everything else passes through untouched. Dot does
**not** use this pass: the extended exact inversion (§3.2)
supersedes it, and a 0.5 blend would leave half the checkerboard
for ScaleFX to sharpen. It is documented here so a future
reader does not "rediscover" it as a missing stage.

### 3.6 Dither-inversion constants (summary)

- Block/cell sizes: 2×2 (Atomic core), 2×3, 3×2, 3×3, 4×4 (Dot extension).
- Field gate: ≥3 of 8 neighbours (Atomic) / ≥4 of 8 (Dot Phase 1, §3.2).
- Baseline samples: 8 cells at ±8 / ±32 cells in x and y; votes
  are A-fractions; require ≥3 distinct values spanning ≥0.5.
- Palette sentinel: 255. Replacement: linear-light average (§4.1).
- Neighbour/sample reads are edge-clamped, never wrapped.

---

## 4. Debanding in shade space + the linear-light pipeline

### 4.1 The sRGB EOTF pair (the 2.4 rule)

Every averaging operation in the chain — dither replacement,
deband ramp, sharp-bilinear blending, Mitchell downscale — is
done in **linear light**, never in sRGB code values. The exact
piecewise sRGB functions (Atomic `LinearLight.kt`, mirrored in
every shader that averages):

    decode(c) = c / 12.92                          if c ≤ 0.04045
              = ((c + 0.055) / 1.055)^2.4           otherwise
    encode(c) = 12.92·c                            if c ≤ 0.0031308
              = 1.055·c^(1/2.4) − 0.055             otherwise

with c in [0,1]. GLSL runs these in `highp float` (`pow`); the
CPU oracle runs them in double. They agree to float precision
(~1e-7 relative, < 0.001 LSB at 8-bit); the round-trip bound is
0.5 LSB at 8-bit. Rounding after encode is round-half-to-even
(`Math.rint` / NumPy `rint`), not truncation and not
round-half-up.

**The sRGB-vs-linear 50% grey fact.** sRGB code value 128
(50%) decodes to only ≈ 0.214 linear light — a 50% grey in code
values is 21% grey in light. Conversely linear 0.5 encodes to
≈ 0.735, i.e. code value ≈ 188, not 128. A 1-px black/white
checkerboard averaged in sRGB code values renders at 21%
luminance instead of the intended 50% (Lomont). Dot's picture
is built out of checkerboard dither and 4 greys, so **every**
averaging stage that used code values would render every 50%
dither darker than the artist intended. This is the single most
important numerical fact in this spec.

**Dot's palette in linear light** (decode of 255/180/90/0):

| Shade | sRGB code | Normalised | Linear light |
|-------|-----------|------------|--------------|
| 0 (lightest) | 255 | 1.0000 | 1.0000 |
| 1 | 180 | 0.7059 | 0.4566 |
| 2 | 90 | 0.3529 | 0.1022 |
| 3 (darkest) | 0 | 0.0000 | 0.0000 |

Worked examples (replacement values the dither stage produces):
50/50 of shades 0+3 → linear 0.5 → encodes to ≈ 188 (not 128).
50/50 of shades 1+2 → linear (0.4566+0.1022)/2 = 0.2794 →
encodes to ≈ 144 (not the code average 135). 25/75 of shades
0+3 → linear 0.25 → encodes to ≈ 137. These are the values the
chain must produce; a stage that returns 128 / 135 / 64 for
those cases is averaging in the wrong space and is a bug.

**Cost note:** the palette has 4 entries, so the decode is a
4-entry LUT and the encode of an averaged value is a small
lookup or a single `pow` — there is no performance reason to
average in code values anywhere in Dot's chain.

### 4.2 Blends and fades in linear light (Atomic's composite rules)

Atomic's composite (GBA-only, §11) blends in linear light with
the hardware weights: `mix = (a·wa + b·wb)/16` per channel,
with wa, wb the BLDCNT EVA/EVB values in [0,16]. **Fades are
the exception and the trap:** the hardware fades the *encoded*
value (mGBA's integer `_brighten`/`_darken` operate on the
5-bit channels: brighten moves each channel toward white by
y/16, darken toward black by y/16, in integer channel units).
Fading in linear light over-brightens fades — Atomic's host
proof measures integer fade vs linear-light fade at mean 23.53
LSB, max 77 LSB over 540 samples. So the fade rule is:
**encode → fade in encoded space → decode back**
(`fadeF`: brighten `c + (1−c)·(y/16)`, darken `c·(1−y/16)`,
applied to the sRGB-encoded colour, then decoded). The alpha
blend is deliberately **not** changed — fades only. Dot Phase 1
has no blend modes and no fades; the rule is recorded here
because Dot Phase 2 (if it ever composites layers) must get it
right, and because it is the general principle: *average in
linear, fade in encoded*.

### 4.3 Debanding in shade space (Atomic's detector, Dot-adapted)

Atomic's debanding (`Debanding.kt`) detects true gradients —
runs of solid bands stepping by at most 1 LSB per channel in a
consistent direction, coherent across many lines — and smooths
**only** those regions with a linear-light endpoint ramp;
everything else passes through byte-identical. The detector's
four conjuncts, with Dot's 4-shade adaptation in brackets:

1. **Solid runs.** A band is a maximal run of identical colour,
   ≥ 2 texels wide; 1-texel runs (text, detail, noise) break a
   sequence. Uncovered texels break runs. **[Dot]** runs of
   identical *shade*; minimum width 2 texels, unchanged.
2. **Step sequence.** ≥ 5 consecutive bands, per-channel
   monotone steps, each step |d| ≤ tol (Atomic 8-bit: 12 R/B,
   6 G), total span ≥ spanMin (32 R/B, 16 G) in at least one
   channel. A direction reversal restarts the sequence at the
   previous band. **[Dot]** steps are in *shade index* (0–3):
   each step |Δshade| ≤ 1, monotone in shade index, total span
   ≥ 2 shade steps (e.g. shade 0 → 1 → 2, or 3 → 2 → 1 → 0).
   With only 4 shades a 5-band sequence necessarily repeats
   shades (e.g. 0,0,1,1,2 is bands of shades 0,1,2 with widths
   ≥ 2) — the band is the unit, not the shade, so repetition
   across bands is expected and correct.
3. **Region coherence.** Adjacent lines carry the same colour
   sequence, band boundaries within ±2 texels of the first
   line's, ≥ 16 lines, area ≥ 1,000 texels; **plus the
   full-extent rule:** if the immediately neighbouring line
   carries a sequence with the same colour list (necessarily
   with drifted boundaries), the group is only a locally
   straight span of a longer drifting feature and is rejected.
   (This is what saves a sinusoidal feature whose boundaries
   hold ±2 for 40+ rows near a sine peak but continue past the
   group with drifted boundaries.) **[Dot]** unchanged, with
   "same colour list" meaning the same shade sequence.
4. **No checker overlap.** No texel of the region belongs to a
   2×2 exact checker block. A ramp's 2×2 blocks are never
   checkers, and checker texels are never inside a band mask,
   so debanding and dither inversion are disjoint by
   construction and compose sequentially: deband first on the
   raw plane, dither-invert second on the debanded texture.
   **[Dot]** unchanged (the checker test uses the §3 families'
   2×2 case).

**The ramp.** Every texel of a region becomes the linear-light
lerp between the region's first and last band colours at its
fractional position along the gradient axis:
`t = (y − y0)/(y1 − y0)` for vertical gradients,
`t = (x − x0)/(x1 − x0)` for horizontal; per channel,
`encode(decode(start)·(1−t) + decode(end)·t)`, rounded
half-to-even. Texels outside any region are copied exactly.
Vertical is detected first, then horizontal; a later region's
mask is masked out by already-claimed texels and dropped if
empty. **Documented misses:** diagonal gradients (their bands
drift past the coherence bound) and stacking after dither
inversion (a partially-inverted checker field still contains
raw checker texels that break solid runs, so the detector never
fires on it).

**Scope:** Atomic's scope is the dither scope (mode 0/1 text
layers, mosaic-free, enabled, constant mode); stamped layers
skip debanding in v1. **[Dot]** scope is the whole composited
frame — there are no layers in Phase 1 — but the detector still
only fires on regions meeting all four conjuncts, and the
1-texel-run breaker protects text: a text box's 1-px strokes
break every sequence they touch.

**Wiring (Atomic `drawDebandPass`, ported):** the layer plane
is read into a CPU scratch buffer, `rampPlaneInto` writes the
ramp in place (it only ever reads the alpha/flag byte of each
texel, which the ramp never modifies, so in-place output is
byte-identical to a fresh array), the buffer is uploaded into a
per-layer RGBA8 texture (`texImage2D` first, `texSubImage2D`
after), and that texture — not the layer texture — feeds the
dither pass / ScaleFX. Dot: one deband texture at 160×144,
same shape.

---

## 5. Sharp-bilinear final resample (Dot's final resample — replaces JINC2)

**Decision (research report, verified live in the public-domain
reference):** sharp-bilinear (`sharp-bilinear-simple`, rsn8887
after TheMaister, public domain) as the final resample, in a
single pass directly from the 480×432 ScaleFX output with
hardware bilinear sampling. Keep ScaleFX at 3×. Do **not** use
JINC/Lanczos, EASU, or a cubic as the default final stage. The
runner-up is Lanczos-2 with a hard anti-ringing clamp, clean-room
from the published sinc formula — sharpest true reconstruction,
but it creates intermediate greys and halo/overshoot on every
hard black↔white edge, risks resurrecting the dithering that
inversion just removed, and no permissive implementation exists
(every libretro implementation is GPLv2+). SameBoy 1.0.4 made
sharp-bilinear its default filter, which is the consensus this
decision follows: at fractional scales the consensus final stage
is sharp-bilinear/AANN; at integer scales it is nearest.

### 5.1 Algorithm

Sharp-bilinear is an integer-prescale filter combined with
hardware bilinear filtering. It needs **no separate prescale
render target** — the UV warp *is* the prescale. One
hardware-bilinear tap per output pixel; the texture is sampled
with `GL_LINEAR`.

Let the input be W×H (Dot: 480×432) and the output be the
internal game area (§1.2). Define the integer prescale per axis:

    s  = max(floor(outputSize / inputSize), 1)

and the region range:

    region_range  r = 0.5 − 0.5/s.

For each output pixel, compute its position in input-texel
coordinates `p = uv · inputSize` (uv is the fragment's
normalised coordinate). Let `d = fract(p) − 0.5`, the signed
distance from the texel centre, in [−0.5, 0.5] per axis. Warp it:

    f = (d − clamp(d, −r, r)) · s + 0.5

and sample the input (hardware bilinear) at

    sample_uv = (floor(p) + f) / inputSize.

Effect: inside a texel (|d| ≤ r) the warp maps the position to
the texel centre (f = 0.5), so the texel interior stays flat;
only where a texel boundary falls inside a screen pixel
(|d| > r) does the sample move off-centre, blending across the
boundary in exactly that one screen pixel. At integer scales the
warp returns texel centres exactly — identical to nearest.

### 5.2 Dot's numbers

| Target | Final scale (480×432 → area) | s = floor(scale) | r = 0.5 − 0.5/s | Behaviour |
|--------|------------------------------|------------------|-----------------|-----------|
| 1080p (1200×1080) | 2.5× | 2 | 0.25 | Flat texel interiors; blend confined to the 1 screen px where a texel edge falls; remainder 1.25× handled by the warp. |
| 4K (2400×2160) | 5× | 5 | 0.40 | Nearest-exact (output samples coincide with input samples). |
| Auto 1440p (1600×1440) | 3.333× | 3 | 0.3333 | Same shape as 1080p. |

Why ScaleFX 3× and not 2× or 4×: with ScaleFX 3× every final
scale is ≥ 2, so sharp-bilinear always has an integer prescale
≥ 2, and one target is an exact integer (5×), while keeping the
final magnification small. With ScaleFX 4× the final scales
drop to 1.6875×/1.875×/3.75×, where floor = 1, r = 0, and the
warp degenerates to plain bilinear — the worst final stage.
With ScaleFX 2× the final scales (3.375×/3.75×/7.5×) still
work, but ScaleFX at 2× has less resolution for its pattern
detection and rAA, and the final stage then magnifies any
ScaleFX artefact 3.375× instead of 2.25×. If a future target
had a final scale < 2 (e.g. a small landscape window), that
target — not the chain — should fall back to Lanczos-2-AR or
Mitchell.

### 5.3 Colour space

**[Dot adaptation — from the audit §1.1]** The sharp-bilinear
blend happens in linear light, not in code values: the single
hardware-bilinear tap is a blend of up to 4 texels, and a
bilinear blend of sRGB code values darkens exactly as §4.1
describes. Implementation: the ScaleFX/rAA output texture
holds linear-light values (Dot's ScaleFX pass 4 decodes the
selected colour to linear — the layer variant's behaviour,
adopted for Dot's composited chain because Dot has no separate
linear stage), the sharp-bilinear pass samples it bilinearly in
linear light, and the result is sRGB-encoded at the end of the
pass (the encode the JINC2 pass used to do in Atomic's chain).
RCAS then runs on sRGB, as it requires. The 4-shade LUT makes
the decode/encode free. **A/B note (research, low confidence):**
no published A/B test of a ScaleFX-3× DMG chain at 2.25×/2.5×
comparing sharp-bilinear vs Lanczos-2-AR exists; confirm the
final call by a screenshot A/B on the Pixel 10a.

---

## 6. Mitchell downscale (4K → smaller display)

**Decision (audit §1.1):** Mitchell–Netravali (B=C=1/3),
widened by the scale factor, separable, in linear light — with
Lanczos-2 as the A/B challenger. Do NOT use Lanczos-3 as the
default, and do NOT use a plain box/area-average alone. This
pass runs **only** when the internal game area is larger than
the on-screen rect (4K forced on a 1080p display: 2400×2160 →
1200×1080, a 2× downscale). On a real 4K display it does not
run at all. It runs **before** RCAS, so RCAS restores acutance
without re-introducing ringing.

### 6.1 The kernel

Mitchell–Netravali (1988), parameters (B, C), radius 2, 4×4
footprint, separable. With B = C = 1/3:

    for |x| < 1:   w(x) = ( 7|x|³ − 12|x|² + 16/3 ) / 6
    for 1 ≤ |x| < 2: w(x) = ( −(7/3)|x|³ + 12|x|² − 20|x| + 32/3 ) / 6
    for |x| ≥ 2:   w(x) = 0

Check values: w(0) = 16/18 ≈ 0.8889; w(1) = 1/18 ≈ 0.0556
(both branches agree); w(2) = 0. **Mitchell has no negative
lobes** (B=C=1/3 sits on the no-ringing boundary of the family),
so there is no ringing and no halo on hard black/white edges —
at the cost of a slightly softer top octave. (For comparison,
from the same family: Catmull-Rom (B=0, C=0.5) is interpolating
and sharpest with ~4.6% step-edge overshoot; B-spline (B=1,
C=0) is all-positive, zero ringing, but approximating — it does
not pass through the samples, 0.67 at centre, very soft. The
resample report's indicative benchmark: Mitchell 68.0% passband
retention / 1.8% overshoot; Catmull-Rom 78.5% / 4.6%; B-spline
51.0% / 0%.)

### 6.2 Widening, separability, normalisation

- **Widened by the scale factor:** for a downscale factor s
  (Dot: s = 2), the kernel is stretched: `w_s(x) = w(x/s)/s`,
  support radius 2s (Dot: 4 input texels each side). Two passes,
  horizontal then vertical (separable: 8+8 taps for s=2, not
  64). Weights are **normalised per output pixel** (divide by
  the sum of the weights actually used, after edge clamping) —
  this is Lomont's decision-table rule for downscaling by any
  factor.
- **Linear light:** decode the 4 shades to linear (LUT, §4.1),
  filter there, encode back at the end. A box prefilter alone
  leaves −11.7 dB of alias and 3 dB droop; a widened Mitchell
  loses >5 dB of the top octave but keeps the edges clean, and
  the RCAS that follows recovers acutance. On photo downscale
  tests Mitchell keeps 93% of ideal contrast with false detail
  5.0, vs Lanczos-3's 109% / 7.1 and linear's 87% / 4.5 — the
  extra "detail" Lanczos-3 keeps is largely ringing, which on
  4-shade hard edges reads as grey halos/fringes.
- **Why not box alone, why not Lanczos-3:** box is the correct
  anti-aliasing intuition for a >2× downscale but too soft on
  its own for pixel-art edges; Lanczos-3 keeps the most detail
  but with the most false detail/ringing among smooth kernels
  (~9.4% step-edge overshoot), which is exactly the artefact a
  best-looking DMG picture cannot have. Lanczos-2 sits between
  (102% detail, 6.8 false detail) and is the A/B challenger:
  A/B Mitchell vs Lanczos-2, both in linear light, on (a) a
  checkerboard/dither-heavy scene, (b) a hard diagonal, (c)
  small text; decide on ringing vs softness, not on PSNR.
- **Cost:** a separable 4-tap cubic (8 taps/axis at s=2) at
  2400×2160 → 1200×1080 is trivial next to the ScaleFX passes;
  measure it in the existing benchmark harness — it should not
  move the 59.5 fps gate.

---

## 7. Gated flicker-transparency (GBI deflicker rule)

**Decision (audit §1.2a):** promote from TEST to v1, gated,
always-on internally, no toggle. This is the only genuine
DMG-specific temporal effect, and the only temporal stage Dot
builds — full temporal super-resolution is explicitly rejected
(§11.4).

### 7.1 The problem

Some DMG games alternate two frames to fake transparency,
relying on the slow DMG LCD to average them (GBDev wiki: games
switch back and forth between two images every frame, relying
on the slow response of the DMG's LCD to produce something close
to the average intensity; worst on SGB where frame rates
mismatch; examples include a fullscreen parallax scene and a
status bar). On a fast modern OLED with no blending those games
flicker at 30 Hz — visibly worse than on real hardware. It is a
correctness gap, not nostalgia.

### 7.2 The rule (GBI `deflicker`, clean-room from the published rule)

GBI publishes three related modes; Dot adopts only `deflicker`:

- `deflicker`: blend frames n and n−1 **where n and n−2 are
  equal** (default weight 0.55).
- `blend` (rejected): blend n + n−1 unconditionally at 0.55.
- `accumulate` (rejected): accumulation buffer at 0.55.

Naive blending is rejected with evidence: SameBoy's optional
frame blending and Gearboy's ghosting (alpha 0.15 +
0.50×(1−intensity)) smear *all* motion. The gated form blends
only pixels that are alternating with period 2 and leaves
everything else untouched.

### 7.3 Detector design (Dot's, from the audit)

Per pixel, with frames n (current), n−1, n−2 (all
dither-inverted first — §1.1 ordering note):

1. **Candidate:** pixel is a flicker candidate iff
   `frame[n] == frame[n−2]` **and** `frame[n] != frame[n−1]`
   (exact shade equality — the frames hold 4 shades, so this is
   an exact test, not a threshold).
2. **Sustain:** the candidate must persist for **≥ 2
   alternations** (i.e. the n==n−2 / n!=n−1 pattern holds for
   two consecutive frames) before any blending is applied.
   A single alternation (e.g. a one-frame glitch) is left alone.
3. **Blend:** a sustained candidate pixel is blended
   `0.55/0.45 toward the previous frame`:
   `out = 0.55·frame[n] + 0.45·frame[n−1]`, computed in **linear
   light** (§4.1) and encoded back. (GBI's 0.55 is the weight on
   the current frame in this reading; the audit states "blend
   that pixel 0.55/0.45 toward the previous frame" — implement
   exactly that ratio and A/B the direction if the picture
   disagrees.)
4. **Decay:** when the alternation stops, the blend decays back
   to the unblended pixel over a short run (a few frames) so a
   sprite that *stops* alternating does not leave a ghost.
   Decay shape is an implementation choice (linear ramp over
   2–4 frames is the recommended default); record the choice in
   the code and A/B it.
5. **Motion disambiguation (free, from exact motion):** DMG
   motion is almost entirely integer-pixel (SCX/SCY scroll and
   OAM sprite positions are integer pixels per frame;
   sub-pixel sprite positioning is OFF in Dot's lock-in), so a
   pixel that changed because the background scrolled can be
   distinguished from a pixel that is alternating by comparing
   against the known scroll/sprite deltas — no optical flow, no
   depth, no ML. Use the scroll delta to shift frame n−2 into
   alignment before the n==n−2 test; without this, a scrolling
   background would false-positive wherever the scrolled pattern
   happens to repeat with period 2.

**Cost:** one extra framebuffer at 160×144 (plus the two history
frames, which are 160×144×1 byte each in shade-index form) and
a per-pixel n==n−2 test — negligible. **Failure mode to measure
in the A/B protocol:** false positives (a 2-frame walk cycle
that happens to alternate); the gate + decay bounds the damage.
**Scope:** always-on internally, no user toggle (per-game
toggles are removed before release).

---

## 8. The small rules — what each means concretely

### 8.1 1-px guard

Atomic has no pass literally named "1-px guard". The two
mechanisms that carry this meaning, and Dot's concrete rule for
each:

- **(a) Edge clamping on every halo read.** ScaleFX pass 0
  reads ±1, pass 3 reads ±3, rAA reads ±7 (at 3×), the
  dedither/dither-invert passes read ±1 and the ±8/±32-block
  baseline samples. **All of these read with CLAMP_TO_EDGE**
  (texture wrap S/T = CLAMP_TO_EDGE on every texture; neighbour
  and sample-block reads in the CPU/shader detectors are
  explicitly clamped to the frame bounds — a clamped neighbour
  is the centre block itself). Nothing in the chain ever wraps
  or samples outside the 160×144 frame, and the final resample
  never samples outside the 480×432 image. A 1-px feature at
  the frame edge is therefore read correctly, not smeared with a
  wrapped texel from the opposite edge. Unit test: a frame whose
  edge column is a solid shade must produce an identical edge
  column after ScaleFX (no wrap-around colour leaking in).
- **(b) Guard strips for atlas fills (Phase 2).** Atomic's
  tile-dedup sheet fills split each fill quad into up to 5
  parts: the in-frame centre, top/bottom guard strips (full
  block width, out-of-frame rows) and left/right guard strips,
  whose UVs are degenerate in the out-of-frame direction, so the
  ScaleFX halo reads at a tile's edge see the true neighbouring
  tile's texels. This is the "guard-ring" shape from the
  fill-cap proof (the guard-ring shape saves 72.2% of layer work
  at steady state; the older 75.8% figure belongs to the cheaper
  shape and must never be quoted). Dot Phase 1 has no atlas, so
  (b) is Phase 2 work — but the *principle* behind it is a
  Phase 1 rule too: **every 2×/3× producer samples the layer
  (frame) texture, never an isolated tile patch** (Atomic §2.3
  cross-tile sampling guard). A future change that fills a slot
  from anything other than the frame texture reintroduces the
  naive per-tile failure (Atomic's host proof: 35,504
  mismatching pixels) and is a bug by definition.

### 8.2 Text/UI bypass

There is **no in-chain text bypass in Atomic**, and Dot Phase 1
does not add one — the bypass is architectural: the GL chain
draws **only the game frame**. All UI (HUD, menus, pause
screen, overlays, buttons) is drawn by the UI layer *above*
the GLSurfaceView (Compose in Atomic), never passed through
ScaleFX, rAA, the resampler, or RCAS. Consequences that must
be preserved: (i) UI text is never upscaled, sharpened, or
dither-inverted — it stays pixel-crisp at panel resolution;
(ii) the game chain never sees UI pixels, so UI can never
corrupt the dither classifier or the deband detector; (iii) the
chain's output rect (§1.2) and the UI layout are independent —
the UI may overlay the pillarbox. The in-game text risk that
remains is *game* text (dialogue boxes, menus rendered by the
game itself): ScaleFX can melt 1-px game text, which is why
MMPX is kept as a TEST candidate (§2.2) and why the A/B set
includes text-heavy games (Pokémon menus, Zelda text boxes,
Tetris). If MMPX wins globally on that A/B it replaces ScaleFX;
if it only wins on text it cannot ship (no per-game toggles) —
so the TEST verdict must be global or nothing.

### 8.3 Per-sprite OBJ upscaling

Atomic's attempt at this is the alpha.63 OAM affine pass — and
it is **reverted** (alpha.66): `OAM_AFFINE_PASS_ENABLED =
false`, because on device the affine sprites rendered
upside-down and mirrored (Robert's 2026-11-21 device gate
FAILED on the picture: no black screen, crash fixed, 58.9 fps,
but the sprites wrong). The OBJ layer therefore renders
ScaleFX-only (the alpha.62 picture). The host proof that
shipped the pass ran only a Python copy of the shader, never
the real GLSL, so the flip slipped through; the proper fix
requires a host graphics harness running the real shader,
re-proven on real frames, before it ships again. **Dot Phase 1
does not attempt per-sprite upscaling** (composited frame only).
For Dot Phase 2, "per-sprite OBJ upscaling" means: evaluate
each sprite from VRAM/OAM at 3× (rather than upscaling the
composited sprite layer), with the affine transform applied per
sprite at 3× resolution — and the Atomic lesson is a
requirement, not a footnote: **re-prove on the real shader in a
host graphics harness with the device gate before shipping**,
and keep the temporary-fallback rule (§9.3) in mind: no silent
fallback in the final product.

### 8.4 Content-hash VRAM invalidation

Atomic's temporal reuse (`TemporalReuse.kt`, `GlRenderer.kt`)
reuses a layer's previous 2× output only when **all** of these
hold; Dot ports the same gate for any cached 3× output (Phase 2)
and for the deflicker history (Phase 1):

1. **Content hash:** FNV-1a-64 over the full layer-plane bytes.
   The hash function requires the buffer to be *exactly* the
   plane size (`TEMPORAL_PLANE_BYTES`) and fails loudly
   otherwise — never hash a prefix. FNV-1a-64's step is a
   bijection in state, so a single-word difference — any
   1-texel change, colour or flag — can never collide. (A
   multi-word difference could in principle collide at the
   64-bit birthday bound, but the gate's other conjuncts would
   have to fail silently too.) Hashing runs on a duplicate of
   the buffer so no position/limit is disturbed. Dot Phase 1:
   hash the 160×144 shade-index plane (160×144 bytes) per frame.
2. **Producer stability:** the *predicted* producer (which pass
   would render this layer) must equal the stored producer, and
   the *actual* producer is recorded after rendering — a
   predicted stamp that fell back to ScaleFX (fill-cap
   exhaustion) is recorded as ScaleFX, or the next frame would
   reuse a stamp-labelled ScaleFX texture. Inversion/deband
   state is part of the producer identity: a toggle flip or an
   eligibility flip changes the producer, so a cached texture
   can never be served under the other inversion state.
3. **Motion field:** the per-scanline motion field (diff of
   this frame's metadata + palette against the previous frame)
   must show no change for the layer.
4. **VRAM version + epoch (the invalidation):** the affine
   frame's VRAM version bumps on *every* VRAM write, and the
   epoch forces a re-upload on save-state load / renderer reset
   (content may have changed without the versions moving). The
   palette generations bump per 16-entry palette group. Any
   change in version, epoch, or palette generations invalidates
   the cached output. OAM has the same guard via the per-scanline
   OAM generations (the version is the generation at the last
   scanline). **Dot:** the core must expose a VRAM version
   counter (bump on any VRAM write) and an epoch counter (bump
   on state load / reset); the renderer records them per frame
   and invalidates on any change. If the core cannot provide
   versions, the content hash alone is the gate — but then a
   state load that restores identical bytes would (correctly)
   reuse, and a state load that changes bytes would (correctly)
   re-render, so the hash is sufficient and the versions are
   belt-and-braces. Never reuse across a toggle change: a
   preset toggle flip invalidates all cached outputs.
5. **No reuse without a valid target:** a persistent target only
   counts once it has actually been rendered into (the
   `reuseValid` flag per layer, cleared on layers-off, on
   toggle change, and on surface rebuild).

### 8.5 rAA no-ring clamp

§2.2 step 6, restated as a rule: **no rAA output value may
leave the local 3-tap range.** After computing the tilted
result, clamp per channel to `[min, max]` of the three
immediate neighbours along the pass axis (TX(−1), TX(0),
TX(+1)), and output alpha 1.0. This is the only anti-ringing
mechanism rAA has, and it is unconditional — it applies to
every texel, every frame, both passes. If a future rAA tuning
weakens or removes the clamp, the pass must be re-proven
against the same diagonal/text A/B set, because unclamped rAA
rings on hard edges exactly like unclamped JINC2.

### 8.6 1-LSB blue-noise output dither **[Dot-new]**

Atomic has no output dither. Dot adds one, because the chain's
output is 8-bit sRGB and the source has 4 shades: after RCAS,
smooth regions produced by the linear-light stages (inverted
dither fields, deband ramps, the Mitchell downscale) can sit
between two 8-bit code values and band. The rule:

- After RCAS (the last pass, on sRGB values in [0,1]), add a
  blue-noise value of **±½ LSB** (1 LSB peak-to-peak, i.e.
  ±0.5/255) per channel, then quantise to 8-bit. The noise comes
  from a small tiled blue-noise texture (e.g. 64×64 or 128×128,
  precomputed, values in [−0.5, +0.5] LSB), indexed by
  `(x mod N, y mod N)` **plus a per-frame offset** (e.g. add the
  frame counter to the tile index) so the pattern does not sit
  static on screen.
- Amplitude is exactly 1 LSB: enough to break banding, small
  enough to be invisible at panel PPI and to never create a new
  visible shade (a ½-LSB shift cannot push a flat shade into a
  neighbouring code value on its own — it only dithers values
  that are already between codes).
- Blue noise, not white noise and not ordered Bayer: blue noise
  has no low-frequency clumping, so it reads as uniform grain
  rather than patterning. Do **not** reuse the Bayer matrices
  of §3.3 for this — they are for dither *detection*, not for
  output dithering.
- This is the last operation before presentation; it must not
  run before RCAS (RCAS would sharpen the noise).

---

## 9. GLSurfaceView wiring, FrameRenderGate, GL failure latch/export

### 9.1 View wiring (Atomic `GameScreen.kt` + `DisplayRenderer.kt`)

- View creation: `GLSurfaceView(ctx)` with
  `setEGLContextClientVersion(3)` (GLES 3.0),
  `setEGLConfigChooser(8, 8, 8, 8, 0, 0)` (8-bit RGBA, no depth,
  no stencil), `preserveEGLContextOnPause = true`,
  `setRenderer(glRenderer)`,
  `renderMode = RENDERMODE_WHEN_DIRTY` (renders on demand, one
  `requestRender()` per frame — never continuous).
- If view creation throws (no GLES 3.0, or view creation
  fails): **no fallback picture** (Atomic alpha.38). The player
  shows an honest error instead of a picture. The same rule
  applies to Dot: a device without GLES 3.0 gets an error, not
  a bitmap path.
- The renderer is attached to the host (`attachGlView`); the
  host owns the pending-frame slots and the gate (§9.2).
- `onSurfaceCreated`: disable blending and depth testing;
  capture the GPU renderer string (`glGetString(GL_RENDERER)`,
  only valid on the GL thread) for failure reports; drop all
  programs, targets, and textures (fresh context — §2); rebuild
  source/layer/meta/VRAM/palette/OAM/dither/deband textures and
  the tile atlas (Phase 2); rebuild the quad buffers; **clear
  the failure latch last** (deliberately: if anything above
  throws, the latch stays set). `onSurfaceChanged`: record the
  new surface size and drop all intermediate targets (the game
  target depends on surface size).
- `onDrawFrame`: if the latch is set, clear charcoal and return
  (no draw attempts on a latched chain). Otherwise the whole
  frame body — uploads included — runs under one catch; any
  throw routes to `failGl` (§9.3). Per-frame timing is recorded
  (GL draw duration, entry to exit) for the benchmark harness;
  clock reads only, no behaviour change.

### 9.2 FrameRenderGate — exactly one render per emulated frame

The bug it fixes (Atomic alpha.22): with per-layer framebuffers
on, each emulated frame drew **twice** — once per chain —
alternating at ~59 Hz, which was the sky/HUD flicker Robert
reported. Root cause: the session makes four back-to-back
`requestRender()` calls per frame (setFrame, setLayers,
setTiles, setAffine) and the GL thread can wake between them.
The deliveries arrive on **two threads**: layer/tiles/affine
frames land on the session thread (directly in the emulation
loop); the composited frame lands later on the main thread
(Handler post + Compose state + LaunchedEffect → setFrame). So
neither side alone knows the frame is complete — the gate is
the meeting point.

The gate holds three booleans and no frame data; all methods
are synchronised so the check-and-set is atomic across the
session, main, and GL threads:

- `onFrameComplete()` (session thread, after the last delivery):
  sets frameComplete; returns true iff this call caused a render
  request.
- `onFrameLanded()` (main thread, when the frame lands):
  sets bitmapLanded; returns true iff this call caused a render
  request.
- `beginRender()` (GL thread, after reading the pending slots
  and before running the chain): re-arms the gate for the next
  frame (clears all three flags), so a frame completing
  mid-render still gets its own request.
- `maybeRequest()` (private): true iff frameComplete **and**
  bitmapLanded **and** no request is queued yet; then marks a
  request queued. A render is requested exactly once per frame,
  by whichever side completes the frame second. The other three
  deliveries never request a render on their own.

The gate does **not** decide which chain runs — the draw
branch (layer chain iff a layer frame is pending, else the
composited chain) is unchanged; it only guarantees the GL
thread wakes once per frame, after every delivery for that
frame has landed. Host proof: exhaustive enumeration of every
real interleaving of the two delivery threads (171 per scenario:
layers on / off / flaky) asserting: a completed+landed frame
always has a render queued; at most one request per render
epoch; no request before the frame is complete; each frame's
data rendered at most once. The same enumeration run against
the old behaviour (every delivery requests) double-renders one
frame in 13/171 interleavings — the proof is not vacuous.

**Dot Phase 1:** Dot has one delivery path (the composited
frame), so the two-thread rendezvous is simpler — but keep the
gate shape: the frame is complete when the core has delivered
it **and** the bitmap has landed; exactly one `requestRender()`
per frame; re-arm after reading the pending slots. If Dot later
adds a second delivery thread (e.g. a separate tile stream for
Phase 2), the gate extends without redesign. Pending slots are
cleared only if no newer frame has arrived since
(`clearPendingFrame` checks identity) — a newer frame must
never be dropped by a stale clear.

### 9.3 Failure latch, failure path, and what fails loudly

**The latch (`GlFailureLatch`).** One boolean, the only state
that decides whether the GL path is live. Two operations,
both idempotent:

- `markFailed()`: any throw in `onDrawFrame` latches the
  failure. The next frame early-returns to charcoal and the
  host is told (`onGlFailed`) so the player shows the error and
  stops the session — instead of audio and a black screen.
- `clearOnSurfaceRebuilt()`: a successful surface rebuild
  clears the latch (programs, targets, textures are all fresh
  on the new context, so a latch from the old context is
  stale). If the failure repeats on the new context the next
  frame re-latches, so a genuinely broken chain stays failed
  instead of flapping.

**The failure path (`failGl`, the single failure path for the
GL thread).** Latch the failure, tell the host (which shows
the player error and records `gl_failed` for the benchmark
harness, so a capture on a failed chain is flagged, never
passed), record the failure report (§9.4), and clear the
surface to charcoal so no half-drawn frame stays on screen.
`failGl` takes a `where` string: `"frame"` (outer catch),
`"chain"` (composited chain), `"layer chain"` (layer chain),
or the OAM branch's own value.

**What fails loudly (never silently degrades):**

- Shader compile failure or program link failure: throw with
  the compiler/info log verbatim in the message. (This is how
  a broken shader is caught — the log names the shader.)
- FBO incomplete at either format (RGBA16F, then RGBA8): throw
  `IllegalStateException("FBO incomplete at WxH: status")`.
- A hash/length precondition failure (e.g. a plane buffer that
  is not exactly the expected byte count): fail loudly with a
  `require` — never hash a prefix.
- Any throw from an upload (`uploadLayers` / `uploadSource` /
  VRAM / palette): routes to `failGl` exactly like a chain
  failure (before alpha.30 only the chain calls were wrapped,
  so an upload throw escaped on the GL thread: the session kept
  running with audio and no picture, and the error never
  showed).
- A silent GL error (no throw): `checkGl` after each chain logs
  it (`Log.w`, "GL error after <where>: <code>") but does **not**
  latch — and therefore leaves **no** failure report. This is
  a documented honest limit (§9.4).

**What is allowed to degrade (and only this):** the tile-dedup
atlas (Phase 2): if its FBO is incomplete on the device, dedup
stays off and the layer chain falls back to the full-screen
ScaleFX pass — the picture stays correct. The persistent 2×
targets (Phase 2): if creation fails, the layer chain falls
back to pool targets — the picture stays correct, temporal
reuse just never engages. **The temporary OAM fallback
(Atomic alpha.65) is NOT a pattern to copy:** when the
affine-sprite program lookup threw, the OBJ layer fell back to
ScaleFX-only and latched for the session so later frames
skipped the branch. It exists only because Robert's standing
rule is **no fallbacks in the final product** and the fallback
is removed once the exported evidence names the root cause.
Dot must not ship a silent fallback anywhere in Phase 1: a
failing shader fails loudly (§9.3) instead.

**Failure reporting — what is recorded (`GlFailureReport`).**
One file, latest failure only, overwritten on each failure, in
the app-private filesDir; local only, no send, no network, no
new permission. The write is a single direct `writeText`,
capped at 32 KiB (32,768 chars), and **never throws** — a
failing chain must never die twice on the report write. The
report format: header line `Atomic graphics failure report`
(Dot: `Dot graphics failure report`), then labelled fields —
Time, App (version), Device ("Manufacturer Model (Android
release)"), GPU (renderer string, or `unknown` if captured
before the first surface creation), Game (library title, or
`unknown`), Where, Program, Fallback engaged (yes/no) — then a
blank line and the exception type + full message + stack trace
verbatim (the shader compiler log verbatim, when that is what
failed). The reader (`parseReport`) demands the header, a
`Time:` second line, every labelled field, and non-blank
exception text; anything else (foreign file, torn write,
garbage, oversized file — load guard is cap×4 + 1024 bytes,
strict UTF-8 decoding) is treated as no report. Recorded on
**both** paths: the fallback catch site (a caught shader
failure is still a failure) and `failGl`. The program is
`unknown` in `failGl` — it sees the throw, not the lookup that
made it — so the exception message must carry the shader name
when the failure is a compile/link failure.

**Export (`GlFailureExport`).** One shared export used by both
access points (the "Export failure report" button on the player
error screen and the "Graphics failure report" section in
Settings), so the two can never disagree about where the file
landed. Android 10+: write to `Downloads/gl-failure` via
MediaStore (`Download/gl-failure`, MIME `text/plain`, filename
`atomic-gl-failure-<yyyyMMdd-HHmmss>.txt` — Dot: `dot-…`);
older Android (and any MediaStore failure): fall back to the
app's private `filesDir/gl-failure/`. The returned outcome
always names the true location; the caller shows the notice
verbatim ("Saved to Downloads/gl-failure/<name>" /
"Saved to app storage/gl-failure/<name>" / "Export failed").
A failed MediaStore insert or write deletes the partial entry
and returns null (falling back to private), never leaving a
partial file. The name is generated once and used for both
paths, so the fallback file never carries a different timestamp
than the attempted Downloads name.

**Honest limits (state them on the Settings page itself, as
Atomic does):** caught GL-thread failures only — a failure
that never throws (a silent GL error) leaves no report; the
GPU string is `unknown` if the failure precedes the first
surface creation.

---

## 10. Test list to port for the chain

Atomic's chain is pinned by pure (JVM, no-GL) tests plus host
proofs. Dot ports the same list, with Dot's numbers substituted.
"Pure" means the logic under test is factored into a pure
function/class so it runs without GL; the GL wiring itself is
verified by inspection (Atomic cites an INSPECTION.md for the
wiring that cannot be unit-tested).

### 10.1 Port verbatim (same test, Dot's numbers)

- **`GlChainTest` — pool rule (regression).** Simulate the
  chain's target acquisitions twice: once with the old first-fit
  rule, once with the fixed rule. Old rule: with dither
  inversion on, ScaleFX pass 4's main and extra inputs are the
  same texture (the bug — tag data read as colours). Fixed rule:
  for every upscaler × inversion combination, three frames each,
  no target is handed out twice in one frame, and pass 4 always
  reads colours and tags from different textures.
- **`GlChainTest` — pool sizes.** One target per pass per size:
  Dot's counts follow §1.2 (invert + ScaleFX 0–3 at 160×144;
  ScaleFX 4 + rAA 0–1 at 480×432; 1 at the internal game area).
  Assert the exact counts for each chain combination.
- **`GlChainTest` — FBO pass count and final flip.** The table
  for every combination (inversion on/off × Mitchell on/off):
  9/8 without Mitchell, 10/9 with it; flip iff odd. Assert the
  two combinations a user actually saw broken both need the
  flip and the working one does not (Atomic's form of this
  assertion, with Dot's cases).
- **`GlChainTest` — `selectReusableTarget`.** Skips excluded
  and already-used textures; null when nothing is free; first
  free texture otherwise. Exact cases: `([1,2,3], exclude {1},
  used {2}) → 3`; `([1,2], {1}, {2}) → null`;
  `([], {}, {}) → null`; `([1,2], {}, {}) → 1`.
- **`GlChainTest` — `FrameTargetPool`.** Hands a texture out
  once per frame then resets: select 1, select 2, select null;
  `recordCreated(3)` then select null; `beginFrame()` then
  select 1 again; select with an exclusion returns the first
  non-excluded free texture.
- **`GlChainTest` — filter-aware reuse.** A LINEAR request never
  receives a NEAREST texture at the same size, and vice versa
  (Atomic alpha.46: the hybrid guide case). Dot's instance: the
  sharp-bilinear game target (LINEAR) vs the rAA output
  (NEAREST) at the internal game-area size when they coincide.
- **`FrameRenderGateTest`.** Exhaustive interleaving enumeration
  (Atomic: 171 per scenario — layers on/off/flaky): a
  completed+landed frame always has a render queued; at most one
  request per render epoch; no request before the frame is
  complete; each frame's data rendered at most once. Plus the
  old-behaviour control: with every delivery requesting, the
  enumeration double-renders (Atomic: 13/171) — the test must
  be able to fail on the old behaviour, or it proves nothing.
- **`GlFailureLatchTest`.** `markFailed` latches (idempotent);
  `clearOnSurfaceRebuilt` unlatches (idempotent); a latched
  failure re-latches on the next frame if the failure repeats.
- **`GlFailureReportTest`.** Format/parse round-trip; the 32 KiB
  truncation cap; corrupt-file rejection (wrong header, missing
  field, blank exception text, oversized file, malformed UTF-8);
  export filename shape (`dot-gl-failure-<yyyyMMdd-HHmmss>.txt`).
- **`LinearLightTest`.** decode/encode against independently
  computed reference values (not against the shader); round-trip
  bound 0.5 LSB at 8-bit; the 50% fact: `decode(0.5) ≈ 0.214`,
  `encode(0.5) ≈ 0.735`; Dot palette spot values (§4.1 table).
- **`DitherInversionTest`.** `average565` reference check
  (Atomic: avg(0xF800, 0x07E0) = 0xBDC0 exactly — Dot's
  equivalent is the §4.1 worked examples: 50/50 of shades 0+3
  encodes to 188, 50/50 of 1+2 to 144); the 2×2 detector oracle
  against the host proof's vectors; the brick-wall guard (an
  alternating-palette brick pattern is **not** inverted — in
  Dot Phase 1, the constant-density stipple is not inverted,
  §3.2); the field and baseline gates each tested with a
  pattern that passes three conjuncts and fails the fourth.
- **`DebandingTest`.** Detector conjuncts individually (a
  4-band sequence fails; a 1-texel run breaks a sequence; a
  direction reversal restarts at the previous band; a region
  under 16 lines / under 1,000 texels is rejected; a
  same-colour continuation in the neighbouring line rejects
  the group); the ramp's endpoint lerp in linear light; the
  checker-overlap exclusion (a region containing a checker
  texel is dropped); the documented misses (a diagonal
  gradient is not detected).
- **`CompositeRulesTest` (Phase 2 only).** The per-pixel
  composite against the core's own dumps (integer mix/fade
  math, priority loop, postprocess (a)/(b)) — port when Dot
  builds layers, not before.
- **`HybridJinc2XbrTest` / `Jinc2ResampleTest` (reference).**
  Atomic pins the hybrid stage's pass order/scales and the
  JINC2 kernel weights. Dot does not port the JINC2 tests
  (JINC2 is replaced) but **does** port the shape: a test that
  the final-stage pass list and scales are exactly the §1.1
  list, in order, with the §1.2 sizes.
- **`IntegerFadeTest`.** Pins how far the retired linear-light
  fade sat from the hardware fade (Atomic: mean 23.53 LSB, max
  77 LSB over 540 samples) — the test that justifies §4.2's
  encode-fade-decode rule. Port as a documentation test when
  Dot implements fades (Phase 2).
- **`TemporalReuse` tests (Phase 2; Phase 1 ports the hash).**
  `hashLayerPlane`: a 1-texel change changes the hash; a wrong
  buffer length fails loudly; hashing does not disturb the
  buffer's position/limit. The reuse gate: hash change, producer
  change, motion-field change, VRAM-version change, epoch
  change, and OAM-generation change each independently force a
  re-render; a predicted producer that fell back is recorded as
  the actual producer.
- **`DefaultFlipTest` / `LayerFlipParityTest` (Phase 2).** The
  per-texture once-flipped invariant: a reused texture's
  orientation is identical to a freshly-rendered one's.

### 10.2 Dot-new tests (no Atomic equivalent)

- **Sharp-bilinear:** at integer 5× the output equals nearest
  exactly (every output sample lands on a texel centre);
  at 2.5× every texel interior is flat (two adjacent output
  pixels inside one texel are equal) and blending is confined
  to the single screen pixel where a texel edge falls; the warp
  never samples outside [0,1]².
- **Mitchell downscale:** kernel weights sum to 1 per output
  pixel after edge clamping; w(0) = 16/18, w(1) = 1/18, w(2) =
  0; no negative weights anywhere (no ringing by construction);
  a 50% checkerboard downscaled 2× in linear light yields
  linear 0.5 (≈188 encoded), not 128.
- **Deflicker gate:** a pixel alternating A,B,A,B for ≥2
  alternations is blended 0.55/0.45 in linear light; a pixel
  that alternates once is not; a pixel that stops alternating
  decays to unblended within the decay window; a scrolling
  background (integer shift) does not false-positive.
- **Output dither:** amplitude is exactly ±½ LSB; a flat shade
  never changes code value by more than 1 LSB; the pattern
  moves frame to frame (no static pattern).
- **Extended dither dictionary:** one fixture per family in
  §3.2 (2×3, 3×3, diagonal, vertical/horizontal lines, 25/50/75%,
  Bayer 2×2/4×4), each passing all four gates, plus one fixture
  per family that fails exactly one gate (field, baseline,
  palette/phase) and is **not** inverted.
- **Discrimination (applies to every test above):** each test
  must be run against the unfixed baseline first (or constructed
  so it fails without the fix) — a test that passes on both the
  fixed and unfixed code proves nothing. Atomic's gate test
  (§9.2) is the model: it ships the old-behaviour control
  (13/171 double-renders) alongside the new-behaviour assertion.

### 10.3 Device gates (not unit tests — the eye is the gate)

Atomic's standing rule, adopted verbatim: **device evidence
outranks test and sim evidence.** A fix is not proven from logs
or tests alone when the device check is still owed, and a repair
thread is never closed on code evidence alone. For Dot Phase 1
the device gates are: the dither-inversion A/B (inverted vs raw
on a dither-heavy game), the sharp-bilinear vs Lanczos-2-AR A/B
(§5.3), the Mitchell vs Lanczos-2 A/B (§6.2), the deflicker A/B
(false-positive hunt, §7.3), and the picture gate on the
release build (sprites the right way up, no upside-down or
mirrored output — the alpha.65 lesson: the host proof ran only
a Python copy of the shader, never the real GLSL, so the flip
slipped through; Dot's host proof must run the real shader).

---

## 11. GBA-only items that do NOT apply to Dot (Phase 1)

Everything in this section exists in Atomic and is **not** built
in Dot Phase 1. It is listed so a future reader does not mistake
an Atomic feature for a Dot requirement. Items marked "Phase 2"
are real future work; the rest are GBA-specific and never port.

### 11.1 The layer chain (Phase 2)

- Five captured layers (BG0–3 + OBJ) as RGBA8 textures with the
  core's flag byte in alpha (bit 7 covered, bits 0–1 OBJ
  priority, bit 2 target1, bit 3 target2, bit 4 semi-transparent,
  bit 5 reblend), plus a 36×160 metadata texture (144-byte
  per-scanline structs: DISPCNT, BLDCNT, BLDALPHA, BLDY,
  backdrop, MOSAIC, window controls, BG priorities, HOFS/VOFS,
  mosaic-enable, blend-enable bytes).
- Per-layer ScaleFX 2× (ScaleFX 3× verbatim + bilinear
  downsample 3×→2×; the downsample is drawn flipped because six
  FBO passes is even — §1.3's parity applied to a six-pass
  sequence; its RGB is a manual bilinear at the output texel
  centre in 3× texel coordinates, its alpha is the flag of the
  1× texel the output texel belongs to, read at its block-centre
  3× texel (3p+1, 3q+1)).
- The composite at the 2× grid (§2.6) with point priority
  (`uPointSample = 1`) and linear-light output
  (`uLinearOutput = 1`), mosaic re-application at output
  resolution (block origin `(x/mh)·mh, y − (y mod mv)`, sizes =
  register field + 1; OBJ behind the equality guard — the
  remapped texel is used only if it equals the un-remapped
  texel on the full RGBA value), scroll-phase snap (§2.7),
  window/OBJ-window segments, and the four `_composite`
  variants (blend/objwin combinations) with the unsigned word
  comparison (higher-priority pixel = larger 32-bit word).
- The hybrid final stage (§2.5): xBR-Lv2 3× → B-spline-x →
  B-spline-y → JINC2-bilateral → deblur-fast.
- Tile dedup / atlas stamping (tile keys, 8×8 cells, atlas FBO,
  fill cap, sheet fills, guard strips §8.1(b)), the palette
  plane (§3.4), affine supersampling (VRAM 256×384 R8UI +
  palette-history textures, version/epoch-gated uploads),
  bitmap supersampling, the OAM affine-sprite pass (reverted,
  §8.3), and temporal reuse of 2× outputs (§8.4).
- **GBA-only, never ports:** MP2K audio HLE (per-game switch,
  low-pass — audio is not in this spec); the GBA blend modes
  (alpha/brighten/darken, BLDCNT/BLDALPHA/BLDY, force-target-1,
  backdrop blend); windows and OBJ windows; mosaic; display
  modes 0–5 (bitmap modes 3/4/5); forced blank (white
  0xFFDF); stereo (STEREOCNT green-swap — not even implemented
  in Atomic's layer path); the 240×160 source size and 3:2
  aspect; RGB565 colour math (`mix5` with green treated as
  5-bit, mask 0x7C0, and the overflow-correction branches);
  the 59.73 fps GBA tick as a *game* rate (Dot's DMG tick is
  also 59.73 fps — see §11.5 — but the frame *delivery* path
  differs).

### 11.2 Atomic passes Dot replaces (not "does not apply" — superseded)

- JINC2-sharper final resample → sharp-bilinear (§5).
- Cheap de-dither heuristic (0.5 blend) → extended exact
  inversion (§3.5).
- JINC2-bilateral hybrid final stage → not built in Phase 1;
  Phase 2 will need a final stage and should re-evaluate
  (§2.5 documents the reference, not a decision).

### 11.3 Atomic features with no Dot equivalent

- **xBRZ upscaler option:** Atomic's xBRZ setting falls back to
  ScaleFX — no verified GPU xBRZ exists in the pipeline (a CPU
  xBRZ would burn the emulation thread), so it is left out
  rather than faked. Dot does not offer it either.
- **Colour correction (Off/2019/2026):** Atomic alpha.61 wired
  colour correction on for the first time; it had never run at
  runtime before and the 50% label was label only. Dot's
  palette is 4 fixed greys; there is no colour-correction stage
  in Phase 1.
- **Dedup / atlas / tile grid:** Phase 2 (Dot's tiles are 8×8
  like GBA's, but the composited frame carries no tile keys).
- **Cover art, library, thumbnails, achievements, haptics,
  benchmarks:** app features, not picture-chain items; not in
  this spec.

### 11.4 Rejected for Dot v1 (research verdicts, do not re-propose without new evidence)

- **Full temporal super-resolution (FSR2 / Arm ASR):** needs
  jittered sub-pixel sampling, depth, and motion vectors, plus
  a reactive mask. A DMG frame has no depth, and its motion is
  almost entirely integer-pixel — motion-compensated
  accumulation of successive frames contains **no new
  sub-pixel information**. The only new information temporal
  accumulation could add is alternating dither/flicker patterns
  (covered by §7) and sub-pixel phases if scroll ever moved
  sub-pixelly (it does not on DMG). Arm ASR is MIT, FSR2 is MIT
  — licence is not the blocker; the blocker is that the
  technique has nothing to reconstruct from. Exact motion *is*
  useful, but only to make the §7 detector cheap and exact.
- **EASU (FSR1) as final resample:** MIT, but a 12-tap
  directional Lanczos-2 tuned for 1.3–2.0×; at 2.25×/2.5× it
  misses 1-px features, misreads ScaleFX edges, creates new
  greys, and does not collapse to nearest at 5× — actively
  harmful there.
- **Anime4K / nnedi3-style CNNs:** trained on anime/photo
  content, hallucinate detail, heavy at 2400×2160 on Mali;
  no demonstrated gain on 2-bit art.
- **FXAA as rAA replacement:** blurs small text and 1-px
  features; a post-AA designed for 3D. **SMAA 1× (MIT) stays in
  TEST** as the one credible rAA challenger (single-pass
  morphological AA, better on long diagonals, less blur on text
  than FXAA; risk: its edge detection can mistake 1-px
  dither/text for an edge).
- **Per-game dither dictionaries:** rejected (§3.2).
- **Lanczos-3 as the downscale default:** rejected (§6).
- **B-spline as the final resample:** rejected — it blurs even
  at integer scale (0.67 at centre) and would undo the 5×
  target.
- **ScaleFX 4×:** a trap (§5.2).

### 11.5 Frame pacing (`setFrameRate`) — platform, not picture

The DMG tick is 59.73 fps, same as GBA. On a 120 Hz panel
59.73 is not a divisor and frames judder; on a 60 Hz panel a
59.73 fps source still drifts one frame every ~4 s. Android's
frame-rate API exists for exactly this: call
`Surface.setFrameRate(59.73…, FRAME_RATE_COMPATIBILITY_DEFAULT)`
on the game surface on entering a game (pass the exact rate,
not a rounded integer — Android docs: 29.97, not 30), and clear
it (0) on pause/exit. Verify in the benchmark harness that the
panel actually settles at 60 Hz (not 120 Hz with pulldown) and
that the avg ≥ 59.5 / p95 ≤ 18 ms gate still holds. Atomic does
**not** have this call (the audit flags it as missing from the
locked plan and unconfirmed in Atomic) — Dot implements it as
new platform work, not as a port. Whether the Pixel 10a panel
actually settles at 60 Hz vs 120 Hz pulldown needs on-device
measurement.

---

## Appendix A. Source map (where each rule was read from)

| Spec section | Atomic source |
|---|---|
| §1 pass list, chain names | `GlRenderer.kt` `buildPasses`, `chainName`, `layerChainName`, `runChain`, `runLayerChain` |
| §1.2 sizes, framing | `DisplayRenderer.kt` `Framing`, `resolveTargetHeight`, `OutputTarget` |
| §1.3 flip parity | `GlRenderer.kt` `chainFboPassCount`, `chainNeedsFinalFlip`, `makeQuad` |
| §1.4 pool rules | `GlRenderer.kt` `selectReusableTarget`, `selectReusableTargetFiltered`, `FrameTargetPool`, `getTarget` |
| §2 uniforms, program/target management | `GlRenderer.kt` `bindPassInputs`, `getProgram`, `compileShader`, `getTarget`, `drawPass`, `drawPassToScreen` |
| §2.2 ScaleFX | `scalefx-pass0..4.frag/.vert` (MIT, Sp00kyFox) |
| §2.2 rAA | `raa-pass0.frag`, `raa-pass1.frag` (MIT, Sp00kyFox) |
| §2.2 MMPX | `mmpx-2x.frag` (MIT, McGuire & Gagiu) |
| §2.3 JINC2 | `jinc2-sharper.frag/.vert` (GPL-2.0-or-later, Hyllian) |
| §2.4 RCAS | `rcas.frag/.vert` (MIT, AMD FSR1) |
| §2.5 hybrid stage | `xbr-lv2-pass0/1.frag`, `b-spline-x/y.frag`, `jinc2-bilateral.frag` (GPL-2.0-or-later, Hyllian), `deblur-fast.frag` (GPL-2.0-or-later, guest) |
| §2.6/2.7 composite, scroll phase | `composite.frag`, `CompositeRules.kt`, `ScrollPhase.kt` |
| §3 dither inversion | `DitherInversion.kt`, `dither-invert.frag` |
| §3.4 palette plane | `DitherInversion.kt` `palettePlane`, `latticeCol`, `palOfKey` |
| §3.5 cheap dedither | `dedither.frag` |
| §4 linear light | `LinearLight.kt`, `composite.frag` (`fadeF`, `mix5f`), `Debanding.kt` |
| §4.3 debanding | `Debanding.kt`, `GlRenderer.kt` `drawDebandPass` |
| §5 sharp-bilinear | dot-final-resample report (§5, §6) |
| §6 Mitchell downscale | dot-final-audit report (§1.1); B-spline shaders for the cubic family |
| §7 deflicker | dot-final-audit report (§1.2a) |
| §8.1 guard strips | `SheetQuads.kt`, `GlRenderer.kt` `drawSheetFills` |
| §8.3 OAM pass | `GlRenderer.kt` `OAM_AFFINE_PASS_ENABLED`, `decideOamFallback`, `drawOamAffinePass` |
| §8.4 content hash | `TemporalReuse.kt` `hashLayerPlane`, `TemporalReuseTracker`; `GlRenderer.kt` producer prediction |
| §9.1 view wiring | `GameScreen.kt` (GLSurfaceView block), `DisplayRenderer.kt` |
| §9.2 gate | `FrameRenderGate.kt`, `DisplayRenderer.kt` `setFrame`/`commitFrame`/`noteFrameLanded` |
| §9.3/9.4 latch, report, export | `GlRenderer.kt` `GlFailureLatch`, `failGl`, `onDrawFrame`; `GlFailureReport.kt`; `GlFailureExport.kt` |
| §10 tests | `GlChainTest.kt`, plus `FrameRenderGateTest`, `GlFailureLatchTest`, `GlFailureReportTest`, `DitherInversionTest`, `DebandingTest`, `LinearLightTest`, `CompositeRulesTest`, `HybridJinc2XbrTest`, `Jinc2ResampleTest`, `IntegerFadeTest` (test dir listing) |
| §11.4 rejections | dot-final-audit report (§1.2b, §1.3, §1.4, §1.6) |

## Appendix B. Open items (decisions this spec does not make)

1. **Deflicker blend direction** (§7.3 step 3): 0.55 on the
   current frame vs 0.55 on the previous frame — implement the
   stated ratio, A/B on a flickering game.
2. **Deflicker decay shape** (§7.3 step 4): linear ramp over
   2–4 frames is the recommended default; record the choice.
3. **Sharp-bilinear vs Lanczos-2-AR** (§5.3): no published A/B
   exists at 2.25×/2.5×; screenshot A/B on the Pixel 10a.
4. **Mitchell vs Lanczos-2 downscale** (§6.2): A/B on
   dither/diagonal/text scenes; decide on ringing vs softness.
5. **MMPX vs ScaleFX** (§2.2): global win or nothing; A/B on
   the text/HUD set.
6. **SMAA 1× vs rAA** (§11.4): TEST candidate, same A/B set.
7. **Palette-plane restoration** (§3.2): when Phase 2 adds
   per-tile data, restore Atomic's conjunct 2 and relax the
   field gate from ≥4 back to ≥3.
8. **`setFrameRate` panel behaviour** (§11.5): on-device
   measurement of whether the panel settles at 60 Hz.
9. **RCAS sharpening strength for Dot** (§2.4): Atomic ships
   0.9 (gentle); Dot's 4-shade picture may want a different
   value — A/B, do not assume.
