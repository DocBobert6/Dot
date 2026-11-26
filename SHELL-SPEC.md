# Dot — App-Shell Specification (extracted from Atomic, alpha.66 tree)

Source: Atomic (`app.atomic`, current tree `~/workspace/atomic/src/app/src/main/java/app/atomic/`,
app version 0.1.0-alpha.66). This spec describes behaviour, state machines, file formats and
exact constants in prose so Dot can be implemented clean-room. No Atomic code is quoted.
Where Atomic's value is GBA-specific, the Dot adaptation is stated inline.
Dot package: `app.dot`. Dot preferences file: `dot-settings` (Atomic used `atomic-settings`).
Dot is portrait-first with follow-device rotation; Atomic was landscape-locked, so every
factory position in this spec must be re-derived for Dot's portrait canvas — the fraction
mechanism carries over, the numbers do not.

Conventions used below: "key" means a SharedPreferences key unless stated otherwise;
"filesDir" means the app-private files directory; "SHA" means the lower-case hex SHA-256
of the imported ROM bytes, which is the stable per-game identity everywhere (file names,
presets, states, saves, covers, layouts).

---

## 1. Settings structure — every section, row, key and default

Settings is one scrolling screen with a top bar ("Settings" + back). Values are snapshotted
when the screen opens; a change writes through to the store immediately. Most display /
control changes apply on the next game launch, not live (exceptions are noted). A single
transient notice line at the bottom of the screen reports the result of Reset / Clear /
layout actions. The screen uses status-bar padding (bars visible — see §11).

### 1.1 Display

| Row | Key | Type / values | Default |
|---|---|---|---|
| Render resolution | `render_resolution` | string: `auto` / `1080p` / `4k` | `auto` |
| FPS counter | `fps_counter` | boolean | `false` (OFF) |

- Render resolution: `auto` follows the display; `1080p` locks the internal path;
  `4k` forces the high internal render downsampled to the panel (sharper, heavier on GPU).
  Help text in Atomic: "Auto follows the display. 4K renders at 3240x2160 and downsamples
  to the panel — sharper on a 1080p screen, heavier on the GPU. Applies on the next launch."
  Dot keeps Auto / 1080p / 4K with 4K downscaled to panel size (decided 2026-11-26).
- FPS counter: shows the live delivered-fps readout while playing (see §7). Help text:
  "Shows a live frame-rate counter on screen while you play. Off by default. The benchmark
  line shows instead during a capture."
- The following Atomic Display rows are GBA remaster picture settings and do NOT exist in
  Dot as settings (see §13): upscaler choice, colour-correction strength, de-dither,
  per-layer framebuffers, temporal stabilisation, dither inversion, debanding, tile dedup,
  colour profile. Atomic locks those to constants; Dot's picture chain is its own Phase 1
  work and is out of scope for this shell spec.

### 1.2 Cover art

| Row | Key | Default |
|---|---|---|
| Fetch cover art | `cover_art_enabled` | `true` |
| Download over mobile data | `cover_art_mobile_data` | `false` |
| Index info line (read-only) | — | shows entry count, snapshot date, "(bundled)" or "(refreshed copy)", and a "Last refresh: …" line |
| Gate line (read-only) | — | "Fetching over Wi-Fi" / "Fetching over mobile data" / "Fetching paused: <reason>" |
| Refresh index button | — | manual index refresh (bypasses the weekly rule) |

- Help text: fetch toggle — "Shows each game's real box art on its library tile, fetched
  from the libretro-thumbnails collection. Off stops fetching; tiles keep the art already
  on disk." Mobile-data toggle — "Off by default: box art downloads on unmetered Wi-Fi
  only. The art comes from raw.githubusercontent.com; the index is a snapshot bundled
  with the app." (Dot: same, with the GB repo — see §8.)
- The fetch toggle closes the fetch gate when off; the library re-reads both toggles live
  on entry (never freeze them in a remembered snapshot — Atomic pins this as a bug class).

### 1.3 Audio

| Row | Key | Default |
|---|---|---|
| Volume | `volume` | `80` (percent, 0–100, clamped on read and write) |
| Low-pass strength | `low_pass_range` | `60` (percent, 5–95 in 5% steps) |

- Volume applies on the next game launch; the session maps it to an audio-track gain of
  percent/100, clamped to 0–1.
- Low-pass: there is NO on/off toggle. The filter is always on; only the strength slider
  exists. Rule (decided by Robert 2026-11-19): always on, strength slider only.
  Stored values outside 5–95 or off the 5% steps are coerced on read (snap to nearest
  5% step, clamp into range). Help text: "The low-pass filter is always on — it softens
  the harsh top end the sound core's own authors identified. This slider sets how
  strongly: a higher value softens more. 60% is the core's own default. Applies on the
  next launch." The session applies it once per game launch, after the core is
  initialised and before the first frame runs. The DSP itself is post-v1 work for Dot;
  this section records the decision only. Atomic's core option keys
  (`mgba_audio_low_pass_filter` / `mgba_audio_low_pass_range`) are mGBA-core keys, not
  shell behaviour; Dot's DSP wiring re-derives them when DSP lands. An old Atomic
  on/off key (`low_pass_filter`) may exist in stored prefs and must be ignored.

### 1.4 Controls

All keys live under one group. Defaults are listed in the table; a "Reset controls" action
restores exactly this set from a defaults map and then re-reads every field from the store
into the screen, so no field can stay stale after a reset. Notice on reset:
"Controls reset to defaults".

| Row | Key | Default |
|---|---|---|
| Haptics (toggle) | `haptics_enabled` | `true` |
| Haptic strength (slider, 0–100, snaps to 5% steps on release) | `haptic_strength` | `100` |
| Test haptics probe (six buttons + live phone facts) | — | see §2.4 |
| Fast-forward button (toggle) | `ff_visible` | `false` |
| Fast-forward speed (choice 2x / 3x / 4x) | `ff_speed` | `2` |
| Slow-motion button (toggle) | `slow_visible` | `false` |
| Rewind button (toggle) | `rewind_visible` | `false` |
| Default layout (Edit default layout button + Factory default button + status line) | files, not prefs — see §3 | factory layout |

- Help texts (carry the substance, rewording allowed): haptics — "A short click for every
  button — A/B full, Start/Select lighter, D-pad and stick tick on direction change, L/R
  click plus a low thud. 0 is silent, like the toggle off. Applies on the next launch."
  (Dot: drop the L/R and stick clauses.) FF speed — "Hold the FF button to run at the
  chosen speed; audio is muted while it runs. Hidden by default. Applies on the next
  launch." Slow — "A separate toggle from fast-forward: the 0.5x button runs the game at
  half speed. Hidden by default. Applies on the next launch." Rewind — "Hold the RW
  button to step back through the last 30 seconds, one second at a time. Hidden by
  default. Applies on the next launch."
- FF, slow-motion and rewind have SEPARATE visibility flags. Never gate two of them on
  one toggle (Atomic audit bug: the predecessor gated FF and slow-motion on one toggle,
  so slow motion could not be shown without fast-forward).
- Dot drops the following Atomic Controls rows entirely (§13): Left control (D-pad /
  Joystick choice), Joystick dead zone slider, Proportional steering toggle, Racing mode
  (all games) toggle. D-pad only is decided for Dot; there is no stick, no dead zone, no
  steering, no racing mode.
- Default layout section (see §3.5 for the full rule): heading "Default layout", explanation
  "The layout for games with no saved layout of their own. Arrange it however you like;
  per-game adjustments stay in the pause menu's Move controls.", status line "In effect:
  your custom default layout." or "In effect: the factory layout.", buttons "Edit default
  layout" and "Factory default". The Factory-default action clears the customised default
  (layout file AND its sidecars) and shows the notice "Factory layout restored — games
  with no saved layout will use it." The Edit action opens the layout editor over Settings
  (see §3); on Done it shows "Default layout saved — games with no saved layout will use
  it."

### 1.5 Storage

- Row: "Games folder" — read-only info showing the total size of the games directory,
  human-formatted.
- Row: "Saved states" with a "Clear saved states" button. It deletes every file in the
  states directory (manual slots AND auto states). Notice: "Saved states cleared" on
  success, "Could not clear saved states" on failure.

### 1.6 Crash report

- Shows the latest uncaught-exception report if one was written on a previous run:
  "Last crash: <time>" + a "Clear" button + the full report text in mono, plus the
  honest-limit line: "Only uncaught app crashes are recorded — a native crash in the
  game core leaves no report."
- When there is none: "No crash recorded" plus "A force-stop or swipe-away kill leaves
  no report." Never say "no crash happened". Clearing shows "Crash report cleared".
  Reading the file must never crash the screen (missing / corrupt / unreadable → the
  "No crash recorded" state). See §9 for the format.

### 1.7 Graphics failure report

- Shows the latest caught graphics-layer failure if one was recorded: "Last failure:
  <time>" + "Clear" + the full report text in mono + an "Export failure report" button.
- When there is none: "No graphics failure recorded" plus "A failure is recorded the
  moment the graphics layer fails — including one the temporary fallback caught."
- Explanatory line: "Covers caught graphics-layer failures — including ones the temporary
  fallback caught, so the game kept playing. NOT the same as the crash report above: a
  caught GL exception never reaches the crash handler, so the crash report correctly
  shows nothing for it." See §10 for the format and export.

### 1.8 Developer

- Row "Benchmark" with an "Open" button → Developer screen (benchmark harness, §7).
- Row "Import trace" with an "Open" button → Trace screen (timestamped log of every
  Add game / Add folder step, kept for screenshotting after a failed import).

### 1.9 About

- Lines: app name + what it is ("Dot — a standalone Game Boy emulator."), core line
  ("Built on the mGBA core (MPL-2.0)." — adapt to Dot's actual core attribution),
  "Not affiliated with or endorsed by Nintendo.", "Version <version>" in mono.
- A "Licences" button opens the Licences screen (open-source notices bundled in the app).

### 1.10 Navigation / back rules (Settings and siblings)

- Screens: Library, Player, Settings, Licences, Developer, Trace. Navigation is an
  explicit back stack (plain list, no navigation library). A Player entry is keyed by
  the game's SHA, not title or file name. The stack survives activity recreation via
  string keys: `library`, `player:<sha>`, `settings`, `licences`, `developer`, `trace`.
  On restore, a Player key whose game is gone drops that entry and the stack falls back
  to Library; unknown keys are dropped; an empty result is Library.
- System Back on Settings / Licences / Developer / Trace always pops to the previous
  screen; only the Library can exit the app, on a second Back press within 2000 ms
  (monotonic uptime clock). First press arms and shows "Press back again to exit" in the
  slot the import message uses; the prompt clears itself when the 2000 ms window lapses;
  a press after the window re-arms instead of exiting. Leaving the Library drops the
  armed state. The prompt takes precedence over a stale import message while showing.
- The session itself is NOT preserved across recreation: the old composition's dispose
  still stops it and a fresh session starts (battery save is written on stop, so progress
  is safe; the game restarts). Only the stack persistence is in scope.

---

## 2. Haptics system

### 2.1 Construction and the single guard

- The engine is constructed once per game from the system vibrator service (nullable —
  a device without one is a silent no-op, never a crash), the Settings toggle
  (`haptics_enabled`, default on) and the Settings slider (`haptic_strength`, 0–100,
  default 100). The probe screen rebuilds the identical construction from the same
  vibrator and the same live settings, so a probe fire is the game's fire.
- Every fire goes through one guard, in this order: if haptics are disabled or strength
  is 0, the fire never reaches the vibrator; otherwise the effect is built lazily
  (after the guard — building first would throw at strength 0, since a one-shot rejects
  amplitude 0); then the vibrator is asked. The outcome of every fire is one of three
  values: PLAYED (the vibrator took the effect), REJECTED (the guard stopped the fire
  before it reached the vibrator: off, strength 0, or no vibrator), THREW (the vibrator
  was asked and refused). A refusal is logged with its exception (log tag
  `AtomicHaptics` in Atomic; Dot uses its own tag) — a missing VIBRATE permission once
  threw here for a month with nothing in the log. The game ignores the returned outcome
  and stays silent and crash-proof; only the probe screen reports it.
- Strength mapping: the 0–100 strength becomes a 0.0–1.0 primitive scale (strength/100),
  except strength 0 is 0.0 (off) and any strength above 0 lands at least at the amplitude
  floor 0.05 — so the weakest setting is still felt. The floor never binds at strength
  5 or above (5/100 = 0.05). The one-shot path maps strength to amplitude 1–255
  (strength×255/100, floored at 1, 0 means off).

### 2.2 Effect selection (fallback-first)

- Press click (A/B): API 30+ with primitives supported → scaled click primitive;
  API 29+ at full strength only → the system's prebaked click (it has no amplitude, so
  it is used only at full strength); otherwise a 12 ms one-shot at the scaled amplitude.
  Each primitive is probed at runtime; an unsupported primitive falls back to the plain
  click — never to a longer one-shot or a square waveform (50 ms+ ringing reads as a
  notification buzz).
- Envelope click (API 36+, `BasicEnvelopeBuilder`): instant attack, ~15 ms total
  (attack = the device's fastest envelope step, minimum 1 ms; decay takes the rest of
  the 15 ms, never shorter than the attack), sharpness placed at the actuator's resonant
  frequency from the frequency profile (resonant frequency's place in the profile's
  supported range, clamped 0–1; no profile, or a profile that cannot answer, takes full
  sharpness 1.0). The envelope path is fallback-first: an envelope the device claims to
  support but will not build falls back to the plain click — unless the fallback itself
  fails, in which case the button stays silent and the fire reports THREW. The envelope
  is a refinement of the press click only, and it is unproven on the Pixel 10a; nothing
  here claims it feels better — that is a device verdict, not something tests settle.
- Which buttons take the envelope: Start/Select take the envelope press click;
  A/B stay on the plain click (device verdict: the envelope is worse on A/B); the D-pad
  takes the envelope only on a press from neutral or a roll to a cardinal — a roll to a
  diagonal takes the plain tick (see §2.3). L/R are GBA-only and do not apply to Dot.

### 2.3 Per-button character

- A/B: the full press click.
- Start/Select: the same click, lighter — strength scaled by 0.6 before the effect is
  built (a smaller pill, still clearly a click).
- D-pad direction change while held: a lighter tick. Holding one direction steady fires
  nothing; only a change ticks. Routing (the D-pad split): a press from neutral (previous
  mask empty) takes the direction envelope whatever the new direction is; a roll to a
  cardinal (new mask holds exactly one direction bit) takes the direction envelope; a
  roll to a diagonal takes the plain tick at 0.6× strength. Where the device has no
  envelopes, or a claimed envelope will not build, the envelope branch falls back to the
  same 0.6× tick, so the choice is only about character, never about how hard the roll
  lands. The direction envelope uses strength × 0.6 and sharpness × 0.75 (resonant
  sharpness pulled down a quarter). The plain tick is the tick primitive at 0.6× where
  supported, else an 8 ms one-shot at the tick's reduced amplitude. Never the predefined
  click for a tick — it has no amplitude, so it would land at press strength.
- Release: a soft envelope settle, not another tick — the dome springing back. The
  release envelope is ~25 ms total (longer than the 15 ms click, so it reads as a
  settle), at strength × 0.5 with sharpness × 0.5 (half the resonant sharpness), gentle
  decay. Where there are no envelopes, or a claimed envelope will not build, the fallback
  is the tick at 0.5× — the same amplitude the envelope lands at. A release is never at
  press strength. The release fires only on lift-off: a lift-off gate remembers whether
  a direction was held during the gesture and fires exactly once, at the moment the last
  finger leaves. A thumb sliding back to neutral (D-pad centre) while still touching
  fires nothing; a gesture that never held a direction (a tap on the centre) fires
  nothing; a pause restart drops the gate, so it never fires on pause. The input still
  clears the moment the direction goes empty; only the haptic waits for lift-off.
- FF / RW (hold buttons) and the 0.5x toggle: NOT silent. Decided explicitly — a light
  click on press, a release tick on lift; the 0.5x toggle clicks lightly on toggle.
- Gamepad presses fire NO haptics (see §5). Haptics come only from the touch controls.
- Shoulder thud (L/R): GBA-only, does not apply to Dot. The mechanism is recorded here
  because its token rule is the pattern for any delayed effect Dot ever adds: one
  pending token only; a new press cancels the previous pending thud before posting its
  own, so rapid mashing never stacks thuds; the session-end cancel drops the token AND
  cancels any vibration already in flight, so no thud fires after pause/quit. Constants
  for reference: thud scale 0.3×, thud delay 20 ms after the click starts (click ~15 ms,
  so it has finished), composition route click+thud at 20 ms offset on API 34+ (relative
  start offset) and the 3-argument form below that.

### 2.4 The "Test haptics" probe (Settings → Controls)

- Six buttons, each firing exactly one engine method on the real vibrator and showing the
  outcome of the last fire next to the button as one word: "Played", "Rejected", "Threw".
  Labels: "Press click (A/B)", "Light click (Start/Select)", "Roll tick (D-pad / stick)",
  "Release tick", "Shoulder click + thud (L/R)", "Envelope click". Dot keeps the first
  four and the envelope button, and drops the shoulder button (no L/R). The roll-tick
  label drops "/ stick" for Dot.
- Below the buttons, the phone's live facts, one line each, in this order:
  "Android API <n>", "Vibrator: present/absent", "Envelope effects: Supported/Not
  supported", "CLICK primitive: Supported/Not supported", "TICK primitive: Supported/Not
  supported", "THUD primitive: Supported/Not supported", "Haptics enabled: on/off",
  "Haptic strength: <n>%". Every fact read is guarded: a vibrator that cannot answer
  reports "not supported", never a crash; a null vibrator reports no vibrator and no
  support. Below API 36 the envelope line honestly reports "not supported" (the probe
  method does not exist there).
- Probe explanatory text: "Tap a button to fire that effect on the real vibrator, through
  the haptics settings above. The word next to each button is what happened the last time
  it fired: Played, Rejected (haptics off, strength 0, or no vibrator), or Threw (the
  phone refused the effect). The lines below are what the phone itself reports."
- The probe is diagnostic, not a fix. The game itself stays silent — only this screen
  reports outcomes.

### 2.5 Manifest pin

- The manifest must declare `android.permission.VIBRATE`. Without it every vibrate call
  throws SecurityException and every button press fails silently (Atomic's alpha.39
  failure: all six probe effects Threw, nothing felt). Cover art needs `INTERNET` and
  `ACCESS_NETWORK_STATE` (see §8). All three are normal permissions: auto-granted at
  install, no runtime request. ROM import uses the system document picker (no storage
  permission), the custom-cover picker is the system photo picker (no storage permission),
  and there is no in-app updater (no install-packages permission). A permission is added
  later only deliberately, never by default — Atomic pins the exact permission set in a
  manifest test, and Dot must do the same.

---

## 3. Layout editor + default layout

### 3.1 The layout model

- Every control is one spec: its centre as a FRACTION of the full screen (so a layout
  survives rotation and screen sizes) plus its size in dp. Fractions are of the FULL
  screen, not the player area. Controls may sit anywhere, including over the game.
- Control ids and their stored keys: `dpad`, `a`, `b`, `start`, `select`, `ff`, `slow`,
  `rw`. (Atomic also has `l`, `r` — Dot drops them; DMG has no shoulders.) The layout
  file lists controls in id order; load/save round-trips are order-sensitive.
- Clamps: a centre is never closer than 0.03 to any edge (fractions 0.03–0.97); size is
  clamped to 36–220 dp. Every loaded control is clamped, so a hand-edited file can never
  strand a control off screen. An unparseable or non-finite value falls back to the
  default for that field only.
- Opacity: whole-layout, 0–100, default 100. At 0% the controls are invisible AND
  untouchable (see §4).
- Size stepper: ±4 dp (not ±1 — 36 taps to grow across the range was the predecessor's
  bug). Snap grid: 8 dp, on by default. Snap and Together are persisted settings (see
  §3.4), not reset on every open.
- Clusters (move/resize together by default): the D-pad cross (a cluster of one), the
  A/B pair, the Start/Select pair, the FF / 0.5x / RW trio. (Atomic also: the L/R pair.)
  Moving one control moves its whole cluster by the same delta; resizing one resizes its
  whole cluster to the new size. A "Together" switch moves/resizes buttons separately.
- Visibility filter: FF, slow-motion and rewind render only when their separate Settings
  flags are on. Hidden buttons render nowhere, including the editor.
- Factory defaults (Atomic, landscape reference canvas 923×411 dp — Pixel 10a landscape;
  Dot MUST re-derive these for its portrait canvas, same mechanism, new numbers):
  D-pad centre (88, 205.5) dp, size 144; A centre (875, 205.5), size 64; B centre
  (799, 205.5), size 64; Start centre (423.5, 379), size 64 (rendered height 32);
  Select centre (499.5, 379), size 64 (height 32); FF centre (883, 371), size 48;
  Slow centre (823, 371), size 48; RW centre (763, 371), size 48. As fractions these are
  centre ÷ reference canvas (e.g. D-pad 88/923 ≈ 0.0953, 205.5/411 = 0.5). Shoulder pills
  in Atomic sat at the top corners (96 dp wide, 48 high, 16 dp in, 60 dp down) — not
  applicable to Dot. The FF trio sits in the bottom-right corner (48 dp circles, 16 dp
  in, 16 dp up, 12 dp apart); Start/Select are side-by-side pills at the bottom centre
  (64 dp wide, 32 high, 16 dp up).

### 3.2 Storage and resolution

- One `.properties` file per game in `filesDir/control-layouts/`, named by the game's
  SHA stem (`<sha>.properties`). Keys: one triple per control — `<id>.cx` / `<id>.cy`
  (fractions) / `<id>.size` (dp) — plus `opacity` (0–100, default 100). A missing file
  means the defaults; a corrupt file means the factory default.
- The single customised default layout lives in the reserved file `default` in the same
  directory (base name `default`).
- Resolution order: (1) the game's own file, if it exists; (2) the effective default;
  (3) the factory layout. The effective default is the customised default file when one
  exists, else the factory layout. A game SHA used as a default key is read from its file
  at call time — so a later edit to the source game's layout flows through to every game
  using it as the default instead of being shadowed by a stale copy. A missing file
  degrades to the factory layout.
- Commit rule (Done): a draft that lands exactly on the effective default layout CLEARS
  the per-game override instead of saving a stale copy, so a later edit of the default
  flows through. Otherwise the draft is saved as the game's override.
- Clearing a game's override deletes its layout file. (Atomic also deletes three per-game
  sidecar override files — left-control, stick dead zone, proportional steering — beside
  the layout file, deliberately kept out of the layout file so a layout save/clear cannot
  wipe them, and a clear must delete them too or a "forget this game's layout" leaves a
  stale choice behind. None of the three sidecars applies to Dot — no stick, no steering
  — so Dot has no sidecar files; the rule to carry is: whatever per-game overrides Dot
  adds later must live beside the layout file and must be deleted by clearOverride.)
- Failed writes leave the previous file in place; the store never throws.

### 3.3 The editor

- Full-screen editor over the paused game — the game shows dimly behind it (background
  charcoal at 0.92 alpha), so a layout is judged against the real picture while edited.
  The editor holds its own draft: nothing reaches the play overlay until Done. Back
  discards the draft (the editor's back handler is composed after the player's own, so
  it wins while the editor is open; after it closes — discard or Done — the pause screen
  is still showing, so a second Back resumes the game).
- Top action row (pills): Back, Done, Reset, Snap (toggle), Together (toggle). Below it
  a collapse toggle ("Hide settings" / "Show settings") — collapsed, the settings rows
  hide and only the slim bar stays, so the panel stops covering the screen while the
  controls stay draggable; the action row never hides. Collapsed state is session-only,
  never persisted — the editor always opens expanded.
- Dragging: a drag selects the control first (so the size stepper appears), then moves
  it — and its whole cluster when Together is on — snapping the centre to the 8 dp grid
  when Snap is on, clamped to the screen and to the safe-area bounds (see below). A
  selected control shows its exact size in dp inside itself (small mono label at its
  bottom edge) and a border (indigo). The editor previews the real buttons (shared with
  the play overlay, so the two can never drift apart); while editing the game is paused,
  so a press that also lands on a button is inert — it sets a held bit the paused session
  never reads, and the button clears it on lift. The button never consumes the down, so
  the drag detector sees the same gesture. The preview follows the same opacity-0 rule
  as play (a 0% draft leaves no live buttons under the drag detector).
- Opacity row: stepper over the whole layout, 0–100% in steps of 5.
- Size row (only while a control is selected): ±4 dp stepper, 36–220 dp range, grouped
  resize when Together is on.
- Safe-area handling: the drag clamp honours the display cutout and the navigation /
  status bars. Insets are read once per composition (display cutout, navigation bars,
  status bars — the maximum per edge) and turned into fraction bounds for the drag
  (inset dp ÷ canvas dp, never tighter than the 0.03 screen clamp), so a dragged control
  cannot land under a cutout or gesture bar.
- Hidden utility buttons: the editor shows only the FF / 0.5x / RW buttons that are
  visible in play — a user can no longer carefully position buttons that never appear
  in-game. When any of them is hidden, a note names them and where to turn them on:
  "FF / 0.5x / RW are hidden in play — turn them on in Settings → Controls to move them."
- Reset: resets the whole per-game control setup — positions AND every per-game draft
  (in Atomic: left-control / dead-zone / steering drafts too) — not positions only.
  Reset restores the game's effective default layout (the "Reset restores" layout passed
  in), clears the drafts, and clears the selection. In the player the reset target is
  the effective default; in Settings' default editor it is the factory layout.
- Snap / Together persistence: keys `editor_snap` and `editor_together`, both default
  `true`. The editor's initial values come from Settings and every change is reported
  back, so the next open keeps the user's preference.
- Haptics in the editor: the preview fires the same per-button character as play
  (press click, release tick, roll tick, light click; the D-pad preview uses the routed
  direction callback so it feels the same as in play). The editor preview is inert while
  paused; the haptics are the only live feedback.
- Editor entry points: the pause menu's "Move controls" action, and the library
  long-press menu's "Move controls" (which launches the game with the editor open; this
  is transient UI state, not a destination — a restored navigation stack reopens the
  game without the editor).

### 3.4 Default layout — the "chosen default" (read this before building a picker)

Atomic's alpha.66 tree has NO picker UI that lets the user choose one game's layout as
the default for others. What exists, and what Dot must implement, is a single customised
default: the `default` file in `control-layouts/`, edited directly from Settings ("Edit
default layout") and restorable with "Factory default". The resolution order is
per-game file → customised default file → factory layout, with the commit rule in §3.2.
If Dot later adds a picker that points the default at a game's layout, it must reuse
this same resolution (per-game → chosen default → factory) and the same commit rule
(draft equal to the effective default clears the override), and the picker must give
feedback that something happened plus the explanation that the default "applies only to
games without their own layout" — that explanation is the Settings text quoted in §1.4
and the Done notice "Default layout saved — games with no saved layout will use it."
Do not invent a second default mechanism; there is exactly one default file.

- Settings default editor: reuses the same editor component with the default base as
  the base. Done compares the draft against the factory layout: equal → clear the
  customised default (so the factory applies again); otherwise save it as the default.
  Sidecar writes (none for Dot) would run after the clear, so drafted overrides survive
  either branch. Back discards; the editor's back handler wins while open, so device
  Back closes it instead of leaving Settings.
- In-game editor Done: compares the draft against the EFFECTIVE default (the chosen
  default layout), not the factory layout, so a draft landing exactly on it clears the
  per-game override and a later edit of the default flows through.

---

## 4. Overlay controls + C10 acceptance criteria

### 4.1 What renders

- The overlay is always on screen — no auto-hide timer, no tap-to-show, no fade. The
  game area has no tap handler at all, so a missed button paints no ripple and pops
  nothing in. Controls are placed by centre fraction at their own size from the resolved
  layout (§3). Shoulder pills and Start/Select pills render at half height (size/2);
  all other controls render square at their size.
- D-pad: flat charcoal well with indigo arms; the pressed arm (or arms, for a diagonal)
  brightens to full indigo. The highlight reads the same direction state the gesture
  loop maintains, so it can never disagree with the game; while paused the highlight is
  forced to 0 in composition, so it clears the moment the pause overlay appears instead
  of waiting on the pointer-input restart.
- A/B: charcoal fill with a thin indigo ring; pressed = solid indigo. Start/Select:
  small pills, charcoal, label in muted indigo-grey; pressed = indigo. FF / 0.5x / RW:
  muted indigo-grey 48 dp circles (at their layout size). Labels are modern sans
  everywhere; mono only for numbers (the editor's size labels).
- Opacity: the layout opacity is applied as alpha to every control, and the flag
  `inputEnabled = alpha > 0` rides into every button. At opacity 0 the controls draw
  (invisibly) but take no touches at all — the pointer input is not attached. This is
  the opacity-0-still-touchable fix: invisible must mean untouchable.

### 4.2 Input behaviour (the C10 acceptance criteria)

These are the acceptance criteria for the overlay. Each is a behaviour Dot must exhibit,
with the Atomic defect it fixes named so the test can be written against the defect.

1. **setInput is store-only; the emu loop is the single pusher.** The touch overlay (and
   the gamepad callback) only store the merged mask. The emulation loop pushes the mask
   to the core once per frame, before the frame runs, and only when it changed — a
   steady hold costs no native call at all. The old direct push let a UI-thread push
   interleave between the loop's push and the frame, so a frame could see a stale or
   double-applied value. At most one frame (~17 ms) of latency is added by waiting.
2. **HoldButton try/finally.** Every hold button clears its held bit in a `finally`
   block, so a pointer-input restart (e.g. on pause) clears the bit — not only a clean
   lift. The visual pressed state resets with the loop, and so does the input. The down
   itself is inside the try, so a restart with no finger down fires nothing. The release
   haptic hook fires only when a press actually started; a pause restart abandons the
   loop, so it never fires on pause.
3. **Slide-off releases.** Each button tracks its own down pointer's id and releases
   when it lifts OR its position exits the button bounds, past a hysteresis of 8 dp.
   (Pre-fix defect: a finger sliding off stayed held forever, because the captured
   pointer keeps routing to the button.) Multi-touch across buttons works: each button
   tracks its own pointer, so holding B while tapping A, or holding a D-pad direction
   while pressing Start, all work.
4. **Opacity 0 is not touchable** — see §4.1.
5. **Mask-change dedupe.** The "input changed" callback fires only when the mask
   actually changes. No native push per pointer-move event during a drag.
6. **D-pad multi-pointer OR.** Every active pointer's direction is OR-ed together, so a
   second thumb's direction adds to the first's. Direction comes from the ratio rule
   (§4.3): past the dead zone, a push counts as a diagonal when the smaller axis is at
   least 0.35 of the larger. A 45° push registers as soon as it clears the dead zone,
   exactly like a cardinal. The D-pad gesture loop is keyed on `paused`: pausing
   restarts the loop and drops its active-pointer map, so a direction held when the
   pause overlay appears cannot stay stuck (the lift under the overlay never reaches
   the loop); the `finally` clears the input with it.
7. **Rumble gate — not applicable to Dot, and not applicable to Atomic either.** Atomic
   has no cartridge rumble and no native rumble callback, so there is no rumble-gated
   haptics path to race. (The audit item about a `@Volatile` rumble-gate race belongs
   to the predecessor app, which had rumble; Atomic's haptics lambdas need no rumble
   gate.) Dot has no rumble either. Do not build one.
8. **Shoulder-thud coalescing** — GBA-only (see §2.3). No Dot equivalent; the one-pending-
   token rule is the pattern for any delayed effect Dot adds later.
9. **Editor shows hidden FF/RW only via the note** — see §3.3: hidden utility buttons are
   not on the canvas; the note names them and where to turn them on. (The C10 item is
   that the editor must surface hidden buttons somehow — the note is Atomic's answer.)
10. **Reset omits nothing it should restore, and restores no racing state** — the editor
    Reset restores positions AND every per-game draft (§3.3); Settings "Reset controls"
    restores every Controls key from the defaults map (§1.4). Dot has no racing settings
    to omit; the rule is that a reset must cover the whole per-game control setup, and
    the layout files are never touched by "Reset controls".
11. **clearOverride deletes sidecars too** — see §3.2. (Dot has no sidecars; the rule
    stands for any future per-game override files.)
12. **Keyless-remember staleness.** Every per-game remembered value in the player is
    keyed on the game entry (layout, left control, dead zone, steering, held mask,
    haptics engine, session, renderer, all overlay callbacks). A keyless remember would
    go stale if the composition ever survived a game change. The gamepad callback and
    enabled flag are likewise set/cleared in a dispose effect keyed on the entry, so a
    game change never leaves a stale pad behind.

### 4.3 D-pad / direction math

- D-pad direction gate: normalised offset from the pad centre, each axis clamped to
  −1…1. Radial dead zone 0.22 (shared constant): inside it, no direction. Past it, the
  ratio rule: a push counts as a diagonal when the smaller axis is at least 0.35 of the
  larger (diagonal band about 19°–71° off horizontal, ~51°; each cardinal keeps a
  ~19° band). Sign gives the directions (negative y = Up, positive y = Down, negative
  x = Left, positive x = Right). The 0.22 dead zone and 0.35 diagonal ratio are shared
  constants, defined once — the predecessor had 0.22 in two files and 0.35 meaning three
  different things in three files, and a radial dead zone plus per-axis thresholds left
  a wedge (magnitude 0.22–0.495 at 45°) reporting nothing, so diagonals needed ~41%
  harder push than cardinals.
- Direction-change haptic fires only when the direction set changes to something
  non-empty — once per press and once per direction change while held, never on release
  and never on a pointer event that leaves the directions unchanged. The callback
  carries both masks (previous, new) so the caller can tell a press from neutral from a
  roll, and a cardinal from a diagonal (see §2.3).
- Stick (Atomic only): fixed in place, never follows the thumb; touch down inside the
  base plus an 8 dp grab margin and drag; the nub follows the clamped direction offset
  (direction offset × (base radius − knob radius)), so at full deflection the nub edge
  sits flush with the base edge; release snaps the nub back to centre and clears the
  direction; a cancelled gesture clears it too, in the finally block. Only one pointer
  is tracked. A drag off the edge keeps its direction, clamped. Input travel is
  (base radius − 0.38 × base radius); the nub radius is 0.55 × base radius. The stick
  click follows the actuation rule: it fires only when the stick crosses an actuation
  threshold (neutral → a direction, or one direction → another); a release back to
  neutral stays silent (the release hook fires instead, once, on lift-off only — never
  when sliding into the dead zone while still touching). Button bits are ignored — only
  the direction set matters, so a button press while holding a direction never
  re-clicks. Dot does not build the stick.

---

## 5. Gamepad (DMG mapping)

- Player 1 only. Buttons and the D-pad arrive as key events; the left stick and the
  D-pad hat arrive as motion events. Both are folded into one mask and OR-ed with the
  touch mask by the player screen before it reaches the session — the session's
  `setInput` stores the merged mask, and the emu loop stays the single pusher to the
  core, once per frame (see §4.2 item 1). The gamepad's mask-change callback re-pushes
  the same merged mask (re-store, not a direct core push).
- Enabled gate: a volatile `enabled` flag, set by the player screen on entry and cleared
  on exit, so the pad never hijacks keys on the library screen. Only events from a
  gamepad/joystick source are ever consumed; everything else (keyboard keys, unmapped
  keys, all events while the player is not showing) falls through to default dispatch.
  The Back key is never swallowed.
- Mapping (DMG — L/R dropped; the DMG has no shoulders, no X/Y, no second stick):

  | Physical input | DMG mask bit |
  |---|---|
  | D-pad Up / Down / Left / Right (key events) | Up / Down / Left / Right |
  | Button A | A |
  | Button B | B |
  | Start | Start |
  | Select | Select |
  | Left stick / hat (motion axes) | directions per §5 axis rule |

  Unmapped, decided not overlooked: Button X / Y (the predecessor mapped these to
  turbo A/B; Dot has no turbo and the DMG has no X/Y), L2/R2, right stick, L3/R3
  (thumb buttons).
- Axis rule: fixed threshold 0.5, no setting. For a stick/hat deflection (x right,
  y down): x > 0.5 → Right; x < −0.5 → Left; y > 0.5 → Down; y < −0.5 → Up. Stick and
  hat masks are OR-ed. Motion events are consumed only when the source is joystick and
  the action is ACTION_MOVE.
- Key handling: repeat downs are idempotent (the button is already in the pressed set).
  Only ACTION_DOWN / ACTION_UP are consumed; other actions are not.
- Pause clears pad state: every pause path clears the held touch mask, the pad state,
  the stick state, the stored session mask (set to 0) and any pending shoulder thud, so
  a button held when the game pauses can never stay stuck. On player dispose the pad
  callback is cleared, the pad is disabled, and any held pad button is released.
- Gamepad silent: a gamepad press fires no haptic. Haptics come only from the touch
  controls.
- Input mask bits (libretro joypad-id order; the bridge interprets the mask in exactly
  this order): B = bit 0, Select = bit 2, Start = bit 3, Up = bit 4, Down = bit 5,
  Left = bit 6, Right = bit 7, A = bit 8. (Atomic also: L = bit 10, R = bit 11 — Dot
  drops them. Bits 1 and 9 are Y and X in libretro order and are unused.)

---

## 6. Fast-forward / 0.5x / rewind

- Play speeds: SLOW 0.5x ("0.5x"), NORMAL 1.0x ("1x"), FF 2x / 3x / 4x. The Settings
  screen offers FF speeds 2, 3, 4; stored speed 2 by default; any stored value outside
  2–4 reads as NORMAL. The emulation loop paces itself to the core's frame rate: one
  frame per interval; a speed multiplier divides that interval — 2x runs two frames in
  the time of one, 0.5x runs one frame in the time of two. Frame interval at fps f and
  speed s is 1,000,000,000 / f / s nanoseconds; a non-positive fps or speed returns 0
  and the caller must not sleep on it.
- Priority: fast-forward while the FF button is held (at the stored FF speed), else slow
  motion while the 0.5x toggle is on, else normal. FF wins over slow motion — the two
  cannot both drive the pace.
- Audio is muted whenever the speed is not normal: the core still produces samples at
  the multiplied rate, and playing them at the output rate would sound fast or slow —
  the session tells the core to discard them instead. The discard flag is applied by the
  emu loop exactly once per speed change (alongside the mask push), never by the UI.
  A benchmark capture taken while the speed is not normal measures the speed, not the
  device — its numbers are meaningless for the gate.
- FF button: hold-to-run. 0.5x button: toggle (checked = solid indigo with white
  lettering, unchecked = charcoal with muted label). RW button: hold-to-step-back.
  All three are hidden by default on separate flags (§1.4) and click lightly (§2.3).
- Rewind: a ring buffer of the last 30 serialized states (30 snapshots at one a second:
  30 seconds of rewind). The session captures one snapshot a second while the game runs
  AND the rewind control is visible (with the control hidden the snapshots could never
  be used, so nothing is captured), on the emu thread between frames — no frame in
  flight there, so the serialize is safe. Capture cadence is every 60th frame. Holding
  the rewind control pops snapshots newest first, stepping back one snapshot every
  150 ms (30 seconds of game in ~4.5 seconds of holding). The loop ends when the ring
  is empty or the hold ends; a pause cancels it. Starting rewind fails (does nothing)
  when the ring is empty. The ring is emptied on a state load and on a new game (a new
  timeline starts). Snapshots are plain byte arrays; the buffer never touches the bridge
  itself (the session serializes and unserializes). If a state outgrew the cached size,
  the size is re-measured once and the serialize retried.

---

## 7. FPS counter + benchmark harness

### 7.1 FPS counter

- Settings toggle `fps_counter`, default OFF. When on, a small delivered-fps readout
  shows at the top centre of the player screen while playing, 13sp mono, format
  "59.7 fps" (one decimal). It polls twice a second (500 ms) from the same live summary
  the benchmark line uses, and re-reads the setting each tick, so it applies live, not
  only on the next launch. While a benchmark capture is running the benchmark live line
  takes precedence — the counter hides, so the two never show together. It shows nothing
  until a live summary has landed.

### 7.2 Benchmark harness

- Two clocks, kept separately, and any pass judgement requires BOTH to hold: **delivered**
  (the emu loop's frame pacing — "can the core + chain keep up with the native frame
  tick"; renderer-independent) and **presented** (the main thread receiving the frame —
  "did the frames actually reach the player on pace"; includes the handler post,
  recomposition, bitmap copy and frame-gate wait; does NOT include the GL draw itself or
  vsync — it fires when the posted runnable executes, so a main-thread stall followed by
  a catch-up burst records near-zero intervals and biases presented fps upward; the
  presented leg is therefore the lenient one and delivered stays the strict one).
  Hooks: delivered is recorded by the emu loop once per delivered frame; presented is
  recorded on the main thread when the frame reaches the player. A third per-frame
  record exists for diagnosis: GL draw duration (recorded by the GL thread at the end
  of every draw; only the latest value is kept) and per-frame phases — emu work, sleep
  requested, sleep overshoot, GL draw — recorded by the emu thread once per delivered
  frame after the pacing sleep, into the running capture's ring. The GL draw attached
  to a phase record is the most recent completed draw (normally this frame's — it runs
  during the sleep).
- Native frame interval: 16,741,000 ns (59.73 fps). A frame interval longer than
  25,111,500 ns (1.5× nominal) counts as dropped. Statistics are computed over a
  fixed-size ring buffer of intervals (capacity 1200 for the live clocks; capture rings
  are sized to the capture target). A summary holds: frame count, average fps (from the
  mean interval), p50 / p95 / p99 in ms (nearest-rank percentile), worst in ms, 1% low
  fps (average of the slowest 1%, at least one frame), dropped count and drop percent.
  The first timestamp stores no interval (there is no previous timestamp); a
  non-positive interval is ignored. Sorting happens only in the snapshot, which the UI
  calls at most a couple of times a second — never per frame. The hot path writes one
  long into the array and moves an index: no allocation, no logging, no sorting.
- Capture: 600 delivered frames (~10 s at 59.73 fps). Rolling live window: the last 120
  frames (~2 s). A manual stop keeps the partial result only with at least 60 frames
  (marked incomplete); anything shorter is discarded as noise — and the previously
  displayed result is cleared with it, so the Developer screen does not keep showing an
  older capture as if this stop had produced it. Stop outcomes: FINISHED (kept),
  DISCARDED (under 60 frames), NOT_RUNNING. Cancelling a capture abandons it without
  keeping a result. Starting a capture while one is already running returns false.
  Stopping the game cancels any running capture (a stale partial capture and stale game
  title must not survive into the next launch).
- Priming: a capture's first frame normally has no interval, so the capture is primed
  from the live clock when the live clock recorded a frame within the last ~33 ms
  (prime window = 2 × 16,741,000 ns). Arming before a game starts primes nothing, and
  the capture finishes one frame late instead, with the same number of intervals. Either
  way it always terminates. The old 1 s prime window could inject a stale ~1,016 ms
  first interval when arming right after a pause, stop or game switch; the residual
  bound with the 2-interval window is ~50 ms.
- Labels: the chain string is snapshotted at capture start and written into the result
  at finish, so a preset change between capture and export cannot mislabel it. The game
  title is re-read at finish (arming before launch would otherwise record the previous
  game's title), the draw path is the value live at finish, and the upscaler segment of
  the chain label is corrected at finish against the finish draw path (the draw path
  proves what actually ran). The result records a `gl_failed` flag: true when the GL
  chain failed during the capture, or when the draw path at finish reports failure
  (which also catches a failure from before the capture started). Phase records hold
  every delivered frame except the closing one (its sleep completes after the capture
  closes).
- Live lines: the benchmark live line shows only while a capture is running, polling
  twice a second: "BENCH <progress>/<target> · <avg> fps · p95 <ms> · <n> drops". The
  pause-screen benchmark panel starts a capture mid-game (with the picture path already
  up, so the capture sits on one picture — starting before boot used to straddle two
  draw paths with a ~106 ms presented stall where the picture path switched). The
  capture records once play resumes (frames are only delivered while the game runs); it
  finishes by itself after 600 frames, and the result can be exported from the pause
  screen without leaving the game. No auto-start, no auto-export. Pause-panel button
  rules: Start is enabled only when no capture is running (label "Capturing…
  <progress>/<target>" while running); Export is enabled only once a completed capture
  exists AND no capture is running — exporting while one runs would hand over the
  previous capture while the new one is still recording. The hint explains a disabled
  Export button ("No capture yet" / "Capture running — export when it finishes").
- Developer screen: shows the live rolling readout ("<avg> fps avg · p95 <ms> · worst
  <ms> · <n> drops", or "No frames yet — start a game first."), Start capture / Stop
  buttons, a "Capturing… <progress>/<target> frames" line while running, the last
  capture's result lines (Frames [+ " (stopped early — partial)"], Duration, Avg, 1% low,
  p95, p99, Worst, Dropped with percent, Presented avg, Presented p95, Presented dropped
  with percent, Presented frames, Draw path, plus "GL chain failed (gate cannot pass)"
  when flagged), an "Export result (JSON)" button, an Import-trace entry, a "Chain under
  test" block (draw path, upscaler, colour correction, de-dither, render resolution,
  output target), and a "Build info" block (version, core, core pin, core source).
  The Developer screen polls the harness twice a second. The stop-discard notice is
  "Capture discarded — under 60 frames".
- Export (JSON): filename `atomic-benchmark-<yyyyMMdd-HHmmss>.json` (Dot: `dot-benchmark-…`).
  On Android 10+ the JSON goes to Downloads/benchmarks via MediaStore (no permission
  needed for the app's own files); on older Android (and on any MediaStore failure) it
  falls back to the app's private `filesDir/benchmarks/` — and the notice says so. The
  name is generated once and used for both paths, so the fallback file never carries a
  different timestamp than the attempted Downloads name. A throwing MediaStore must fall
  back to the private directory, never propagate out of the Export button and crash the
  app; a failed MediaStore insert or write deletes the partial entry, leaving nothing
  behind. The outcome always names the true location; the caller shows the notice
  verbatim: "Saved to Downloads/benchmarks/<name>", "Saved to app storage/benchmarks/
  <name>", or "Export failed". The export file is small (~10 KB for 600 frames) and is
  written atomically in the fallback path.
- Export JSON fields (fixed shape): app, core, core pin, device (manufacturer + model),
  game, chain (upscaler segment corrected against the draw path), draw path, output
  target height px, capture frames, capture target frames, capture completed, duration
  ms, avg fps, p95 ms, p99 ms, worst ms, 1% low fps, dropped frames, drop percent,
  presented avg fps, presented p95 ms, presented p99 ms, presented worst ms, presented
  1% low fps, presented dropped frames, presented drop percent, presented frames,
  gl failed, phase frames (+ phase summary means/p95s and tail counts), delivered
  intervals ms (array, 3 decimals), presented intervals ms (array), emu work ms,
  sleep requested ms, sleep overshoot ms, gl draw ms (nulls where no draw completed).
  Numbers are formatted to 3 decimals; strings are escaped; nulls stay null.
- Dot adaptation: the native interval stays 16,741,000 ns unless Dot's core reports a
  different frame rate (the DMG native rate is also 59.73 fps, so the same value
  applies to first order — confirm against the core's reported rate at Phase 1 and
  record the decision). The chain label is Dot's own picture-chain description; the
  draw-path values (`gl:pending`, `gl:failed`, …) are Dot's renderer states.

---

## 8. Cover-art mechanics (adapted to DMG / GB thumbnails)

### 8.1 Index source (Dot change)

- Atomic reads the libretro-thumbnails repo `Nintendo_-_Game_Boy_Advance`. Dot reads
  `Nintendo_-_Game_Boy` — the GB (DMG) repo, NOT the GBA index. Everything below is
  otherwise the same mechanism, with the repo name and the asset/file names swapped:
  bundled snapshot asset `art-index-gb.txt` (Atomic: `art-index-gba.txt`), refreshed
  index file `art-index-gb.txt`, meta file `art-index-meta.txt`. The bundled snapshot
  date is shown in Settings (Atomic's was `2026-11-19`, fetched live from the GBA repo
  when the asset was built — Dot records its own snapshot date the same way).
- Index load is two steps, never a download to show the first tile: (a) the top-level
  tree, non-recursive (~1.7 KB), whose `Named_Boxarts` entry carries the subtree SHA,
  fetched fresh on every index load, never hardcoded; (b) the `Named_Boxarts` subtree
  itself, downloaded only when the SHA changed. URLs: top tree
  `https://api.github.com/repos/libretro-thumbnails/<REPO>/git/trees/master`, subtree
  `https://api.github.com/repos/libretro-thumbnails/<REPO>/git/trees/<sha>`, one art
  file `https://raw.githubusercontent.com/libretro-thumbnails/<REPO>/master/Named_Boxarts/<url-encoded-name>`
  (spaces encoded as `%20`, not `+`). The `Accept: application/vnd.github+json` header
  is sent only to `api.github.com`, not to `raw.githubusercontent.com`. User-Agent is
  the app name (Atomic: `Atomic`; Dot: `Dot`).
- The subtree SHA is parsed from the top-tree response: the entry whose path is exactly
  `Named_Boxarts` and whose type is `tree`; the entry is sliced to its closing brace so
  a later entry's SHA can never be mistaken for it; null when absent — the caller fails
  the index with a reason instead of guessing a SHA. Filenames are parsed from a
  git-trees response in either form: `Named_Boxarts/<name>.png` (prefix stripped) or
  bare `<name>.png`; anything else (other directories, non-PNG files, the directory
  entry itself) is not box art. A response whose `truncated` flag is true is a failure,
  not a short index; an empty index is a failure.
- Index precedence: memory → disk cache (a refresh result) → bundled snapshot. A failed
  refresh leaves the bundled snapshot (or the disk cache) in place and reports the
  reason; it never blanks tiles. The index state is one of NotLoaded / Loading /
  Ready (entries, fromCache, snapshotDate) / Failed (reason).

### 8.2 Matcher (stem-first)

- Match inputs per game: the outer filename stem (the picked file's name), then the name
  inside the zip when the outer name is not the game's own (a zip named after the set
  still matches by the game inside it; null when there is none or it equals the outer
  stem), then the ROM header title (abbreviated by the hardware). Order: outer stem
  first, inner stem second, header last. The stems are preserved at import in the
  game's preset (`sourceName` = original filename, `innerName` = .gba entry name inside
  the zip, "" when not a zip or predating the build).
- Normalisation of a title for matching: `$` reads as `s` (a libretro quirk in one
  title); `, The` dropped; parenthetical and bracket groups stripped (edition/region
  tags, never the title); lower-cased on words; non-letter-or-digit characters become
  spaces; a leading `the` dropped when more than one word remains; trailing region /
  language / edition tags stripped while more than one word remains. Region words
  include japan, usa, us, europe, eu, world, asia, korea, brazil, france, germany,
  spain, italy, uk, australia, canada, netherlands, china, taiwan, mexico, argentina,
  portugal; edition words include rev, beta, proto, prototype, demo, kiosk, sample;
  language/region-code words are 2/4/6-letter strings made of 2-letter codes (en, ja,
  fr, de, es, it, pt, nl, ko, zh, ru, sv, da, fi, no, pl, tr, ar, he, cs, hu, el, id,
  ms, th, vi, jp, kr, cn, tw, au, ca, mx, br, us, uk, eu).
- Match steps, in order: (1) exact title; (2) title prefix ("Pokemon Red" → "Pokemon
  Red Version") — a real title only (≥ 2 words): a bare series name must never
  prefix-match a different game in the series; (3) bare prefix (truncated header titles:
  "POKEMON EMER" → "Pokemon Emerald") — the art must continue the SAME word (a new word
  after the match is a different game); (4) reverse prefix: the title starts with the
  art title; (5) contiguous phrase (both ≥ 2 words, so a bare "zelda" or "kirby" header
  title never matches the wrong game).
- Ranking among candidates: non-variant first (a re-release / repack / demo file — beta,
  proto, bootleg, demo, kiosk, sample, virtual console, collection — ranks last, never
  excluded), then shortest normalized title, then USA/World first, then Europe, then any
  other single region, then fewest parenthetical groups, then shortest filename.
- Denylist: Atomic carries a two-entry denylist measured against the live GBA index —
  header-only matches landing on the pinned wrong games are treated as NoMatch
  (CASTLEVANIA exact-matching the NES-classic port instead of Aria of Sorrow; SUPER
  MARIO prefix-matching Super Mario Ball instead of Super Mario Advance 4). The
  denylist fires only on the header path; with a filename stem present the stem wins
  and it never fires. **This denylist is GBA-specific and does NOT carry to Dot.**
  Dot starts with an empty denylist and adds entries only from measured header-only
  mismatches against the live GB index — a wrong cover on the tile is worse than a
  miss, so the mechanism (header-path-only denylist, pinned by test) carries, the
  entries do not.
- The tile's full name uses the SAME match the art pipeline makes: the index title is
  the matched art filename without `.png`, so the name a tile shows and the art it
  shows can never disagree about which game this is. A game with no match is simply
  absent from the title map — the tile falls through to the filename stem (see §12).

### 8.3 States, gate, fetch pipeline

- Per-game cover states (the tile reads exactly this): Missing (never attempted, or no
  file yet), Loading (download in flight), Ready (a decodable file exists on disk — the
  file is the authority; no bitmap is retained for the session, the tile decodes on
  demand from this file), NoMatch (the index has no art for this title; re-checked
  whenever the index refreshes), Failed (reason string, e.g. "timed out", "HTTP 403",
  "undecodable" — auto-retried on a later open while the attempt budget lasts, then
  manual-only).
- Network gate — OPEN if and only if the network validated AND (unmetered Wi-Fi OR the
  mobile-data opt-in is on). No network never qualifies. Gate values: OPEN, NO_NETWORK,
  METERED_NO_OPT_IN, UNVALIDATED. Labels: OPEN → "Wi-Fi OK" or "Mobile data OK"
  (naming the actual transport — never "Wi-Fi OK" when on mobile data); NO_NETWORK →
  "no network"; METERED_NO_OPT_IN → "metered connection — mobile data off";
  UNVALIDATED → "network not validated (captive portal?)". The Android reader resolves
  the facts from ConnectivityManager and never throws (any failure to read is
  NO_NETWORK). VPN-aware: VPN over Wi-Fi counts as Wi-Fi — the active network is the
  VPN transport when a VPN is up, so the reader inspects the other currently-available
  networks for a validated Wi-Fi network underneath it (there is no public API to list
  a VPN's underlying networks; a remembered-but-disconnected Wi-Fi never appears in the
  available list, and the candidate must be validated too, so captive-portal Wi-Fi
  under a VPN that actually rides cellular is never claimed as Wi-Fi). A network that
  has not validated (captive portal) never opens the gate, so it cannot burn the index
  budget failing.
- Gate watcher: calls back when the gate CHANGES (open→closed included), debounced 3 s
  (a burst of network events collapses to one gate check) and transition-only (a flap
  that leaves the gate where it was is not a retry). Start/stop are the caller's
  lifecycle; stop cancels a pending debounced check and tolerates an already-
  unregistered callback.
- Fetch triggers: new games only + manual refresh + Wi-Fi reconnect with pending work.
  A second open with no new games makes no network call. The pipeline runs up to 3
  concurrent downloads over the queue in library (tray) order, so visible tiles fetch
  first; one stall never blocks the queue. Per-game failure is independent. The LRU cap
  is enforced once the queue drains, never mid-run (evicting a Ready game to Missing
  mid-run would re-queue it into the same run).
- Fetch planner (which games need a fetch right now): Missing always; Failed only inside
  its auto-retry budget; Ready and NoMatch never (NoMatch re-checks only on index
  refresh). The gate and the fetch toggle close the whole list. The game list changing
  classifies only new games — states a game already holds (Loading, Ready) are never
  reset by a later list change, and removed games leave the map. Games with a verified
  file on disk reach Ready at once, with no network; games with a recorded failure
  reach Failed (or Missing again, inside their auto-retry budget) with no network
  either; NoMatch games re-check only when the index changed; the rest become Missing
  and the pipeline starts when the gate is open.
- Gate closing parks the pipeline: the running job is cancelled, and a game that was
  Loading returns to Missing, not Failed — a cancel is not a failure. Opening the gate
  starts (or resumes) the pipeline for games with work pending. Turning the fetch
  toggle off parks the pipeline like a closed gate.
- One game, start to finish, in one worker step: match → download → decode in memory
  → write file → publish Ready. The write and the publish are adjacent and in order,
  so there is no window in which the status says the cover landed and the tile has
  nothing; a decode failure writes nothing at all. Writes go through a temp file +
  rename (falling back to a direct write when the rename fails).
- HTTP: one capped GET per file with a total budget enforced by a race (not a timeout
  around a blocking call — a timeout cannot unwind the thread stuck on it): the call
  runs on its own IO thread and the pipeline awaits the deferred with the budget; on
  timeout the thread is abandoned and the pipeline moves on. Connect timeout 10 s, read
  timeout 15 s. Caps and budgets: one art file max 2 MB, one index/tree response max
  4 MB (~2× the largest legitimate subtree response); total budget 60 s for an index
  download, 20 s for one art file; a read past the cap fails with "over <cap>B cap".
  A 403 with `X-RateLimit-Remaining: 0` is reported as "HTTP 403 — rate limit — resets
  HH:mm:ss" (display reason only — never persisted). Transport failures are named by
  kind: "timed out" for a socket timeout, "cancelled" for a closed socket, else the
  exception's simple class name. Only PNG (8-byte magic) or JPEG (2-byte SOI) bytes are
  accepted, and acceptance requires a real decode, never a magic-bytes-only check — the
  decode function is injected with no default, so the device can never silently fall
  back to a magic check.

### 8.4 Retry / TTL rules (no poisoned terminal states)

- Failed and NoMatch are NEVER permanent. Attempt records hold ONLY a count and
  timestamps — never a failure reason string, never a wall-clock reset time. Persisted
  format, one record per line, in `cover-attempts.txt`:
  `F <sha> <attempts> <lastAttemptMs>` and `N <sha> <indexSha> <checkedAtMs>`. A corrupt
  line is skipped, never fatal.
- Failed: 1-hour cooldown before an automatic retry; at most 3 automatic attempts per
  record lifetime; then manual-only until the record expires. A failure record older
  than 24 hours is dropped — the game gets a fresh budget. An immediate re-open is
  inside the cooldown and fetches nothing; a genuinely later open retries.
- NoMatch: re-checked when the index changed since it was checked, or when its record
  is older than 7 days (TTL). A manual index Refresh re-queues NoMatch games even when
  the index has not changed (the user asked for a re-check, not just an index update);
  automatic refreshes keep the TTL-gated behaviour. A new index re-queues NoMatch games
  (their records clear). A re-import clears that game's attempt record (Failed or
  NoMatch) so the matcher runs again with the new stem — only that game's record, never
  all of them; a Ready game is untouched (its file is on disk); a Loading game is left
  to finish.
- Manual retry: "Retry" on the status line re-enqueues exactly the failed games — and
  only them. A Ready game is untouched; a NoMatch game stays NoMatch. The attempt budget
  for each retried game resets, so a manual retry is never refused for an exhausted
  budget. A tile's own retry (long-press menu, Failed tiles only) re-enqueues that one
  game the same way.
- Index SHA check: at most weekly (7 days) when nobody asked manually; manual refresh
  always checks. A manual refresh when checked recently reports "checked recently — up
  to date"; a manual refresh on an unchanged index reports "up to date (<n> entries) —
  re-checked <m> game(s)".

### 8.5 Cache, custom covers, bitmaps

- Total cover-cache cap: 50 MB, LRU eviction, least-recently-shown first. Eviction
  considers non-custom files only, sorted by last-shown order, until the total fits;
  custom covers are never evicted (they live in their own directory and are never
  counted). Per-file download cap 2 MB (same as §8.3). Evicted games go back to Missing.
- **B2 (over-cap churn) — do not repeat:** an evicted game must NOT be re-fetched in
  the same repository lifetime. Atomic keeps an in-memory set of SHAs evicted this
  session; they stay Missing and are not re-fetched until the next repository lifetime.
  Without this, eviction → refetch → eviction churns the cap forever on a library
  larger than 50 MB.
- Custom covers: the user picks an image (system photo picker; picked bytes capped at
  16 MB on read — a picked photo is a few MB at most, and an uncapped read would trust
  the provider). The picked image is decoded and re-encoded as PNG at most 512 px on a
  side into `custom-covers/<sha>.png`, never into `covers/` — so a re-fetch can never
  replace it. A custom cover wins over fetched art. Removing it deletes the file and
  the tile falls back to fetched art. A custom cover that is not a usable image, or
  whose write fails, writes nothing and the previous custom cover (if any) stays.
  Setting a custom cover, removing one, or evicting a fetched cover deletes that
  game's cached blurred wash (see §12), so the tile regenerates it from the new art
  instead of showing a stale blur.
- Startup scan: every file in `covers/` and `custom-covers/` is decoded in memory
  before it is trusted. A file that decodes is kept; a file that does not is deleted,
  and its game re-queues on the next game-list change (the file is gone, so the game is
  Missing again). No bitmap is retained by the scan — it records only WHICH SHAs
  verified. A failed scan must not take the repository down: the games simply fetch
  from the network instead.
- Decode: cover bytes/files are decoded downsampled to at most 512 px on a side
  (downsample factor is a power of two). Null when the bytes are not a decodable image.
  The tile bitmap cache holds at most 8 decoded covers, LRU, keyed by absolute path,
  so a fetched cover and a custom cover for the same game never collide; an entry is
  re-decoded when its file's last-modified time changed (a re-import, a re-fetch, a new
  custom cover), so no caller has to remember to invalidate anything.
- **B1 (remembered-null decode) — do not repeat:** a decode that returns null must
  never be memoised on the file path alone. If the tile remembers "this path decoded
  to nothing", a later replacement of the file at the same path (re-download, new custom
  cover) keeps showing nothing until something else forces a recomposition. Key any
  remembered decode on path + last-modified time (the same invalidation the bitmap
  cache uses), or do not remember a null at all — re-query on every state change.
- The tile showed a fetched cover: touch the file (update its last-modified time) so
  the LRU eviction order follows what was actually shown. Custom covers are not touched
  (they are exempt from eviction).
- Status line: one line for the library, derived from the same map the tiles read, never
  a second truth. While fetching: "Cover art: fetching art for <up to 3 titles, then
  'and N more'>… <n> landed[, <m> failed][ · <first failure>]". While idle: "Cover art:
  <ready>/<total> covers[, <m> failed] · <gate label>[ · gba index <n> / cached (<n>) /
  failed (<reason>) / —][ · <first failure>][ · still missing: <titles>]". The first
  failure is deterministic: the index first, then game failures in library order. The
  line auto-hides after 4 s UNLESS fetching, a failure, or a Missing game — the states
  where the user still needs to see it. A "Retry" button sits beside it whenever there
  is a failure (index or game).
- Settings → Cover art shows the index state (see §1.2). The repository is app-scoped
  (created once at boot, closed when the root composition leaves), so pipelines survive
  navigation to Settings or the player.

---

## 9. Crash report

- Installed in `MainActivity.onCreate`, first, next to the trace init: a JVM
  `UncaughtExceptionHandler` that synchronously writes the report, then chains to the
  previously installed default handler, so the system still handles the crash normally
  — the handler never swallows the crash or keeps the process alive. Installing twice
  (activity recreated in-process, e.g. on a font-scale change) just wraps the previous
  handler, so a crash writes the same report twice — harmless, same content. The write
  itself never throws.
- Store: one file, `crash-report.txt`, in filesDir — latest crash only, overwritten on
  each crash, local only, no send, no network, no new permission.
- Format: first line the header (`Atomic crash report` in Atomic; Dot uses
  `Dot crash report`), second line `Time: yyyy-MM-dd HH:mm:ss z`, blank line, then the
  exception type + message + full stack trace including any "Caused by" chain, truncated
  to 32,768 characters. The file is tiny by design (a small file for a human to read on
  a phone, not a full dump).
- Write choice: a single direct write, NOT temp-file + rename. The handler runs while
  the process is dying: temp + rename is two filesystem operations, and a kill between
  them leaves NO report at all. A direct write puts the header, the time and the
  exception type first, so even a partial write is still a useful report. A torn write
  can only happen if the process dies mid-write, and the reader treats a file without
  the intact header as no report, so a torn file never shows garbage as a report.
- Read/validation: load returns null when there is no file, the file is unreadable, the
  file is oversized (larger than 32,768 × 4 + 1024 bytes — a report written by this code
  is at most 32,768 chars, at most 4 bytes per char in UTF-8, plus slack), the UTF-8 is
  malformed, or the text fails validation (first line must be the header, second line
  must be a non-blank `Time: …` line). Load and clear never throw — reading the file
  must never crash the Settings screen.
- Honest limits (stated on the Settings page itself, see §1.6): uncaught JVM exceptions
  only — a native crash in the game core does not go through this handler and leaves no
  report. A task-removal kill (swiping the app away in Recents, or a force-stop) leaves
  NO in-app trace — no report can retroactively explain it.

---

## 10. Failure export (graphics failure)

- What is recorded: the exception type + full message (the shader compiler log verbatim,
  when that is what failed) + stack trace, which program/shader failed, where in the
  chain it threw ("frame" / "layer chain" / "chain", or the fallback's own where value),
  timestamp, app version, device, GPU renderer string, the game running, and whether
  the temporary fallback engaged. Recorded on BOTH paths: the fallback catch site (a
  caught shader failure is still a failure — the fallback only hides it) and the
  fail-everything path (every uncaught chain failure). Dot's renderer records the
  equivalent: program/shader, where, fallback-engaged flag (Dot has no fallback — see
  §13 — so the flag is always "no" until Dot adds one; keep the field).
- Store mirrors the crash report: one file, `gl-failure-report.txt` in filesDir, latest
  failure only, overwritten on each failure, local only, no send, no network, no new
  permission. Single direct write, capped at 32,768 characters, never throws. The GPU
  renderer string is captured on the GL thread in surface creation (it is only valid
  there) and held in a volatile slot for the record call; a failure before the first
  surface creation reports it as "unknown". The running game is held in a volatile slot
  set at game start (alongside the benchmark's game) and read at record time.
- Report format (header block, then a blank line, then the exception text): first line
  the header (`Atomic graphics failure report` in Atomic; Dot uses
  `Dot graphics failure report`), then labelled lines `Time:`, `App:`, `Device:`,
  `GPU:`, `Game:`, `Where:`, `Program:`, `Fallback engaged: yes/no`, then a blank line
  and the exception type + full message + stack trace verbatim, truncated to 32,768
  characters. Validation on load: first line the header, second a `Time: …` line, the
  header block carries every labelled field, then a blank line and non-blank exception
  text; `Fallback engaged` must be exactly `yes` or `no`. Anything else is not a
  report. "unknown" GPU/game parse back to null.
- Export filename: `atomic-gl-failure-<yyyyMMdd-HHmmss>.txt` (Dot: `dot-gl-failure-…`).
  On Android 10+ the text goes to Downloads/gl-failure via MediaStore (relative path
  `Download/gl-failure`, MIME `text/plain`); older Android (and any MediaStore failure)
  falls back to the app's private `filesDir/gl-failure/` — and the notice says so.
  The name is generated once and used for both paths. The outcome names the true
  location, shown verbatim: "Saved to Downloads/gl-failure/<name>", "Saved to app
  storage/gl-failure/<name>", or "Export failed". The fallback write is atomic.
- Two access points, one shared export function, so the two can never disagree about
  where the file landed: (a) the "Export failure report" button on the player error
  screen — shown ONLY when a report is actually recorded (a view-creation failure shows
  the error screen too and may have no GL failure on file, since the view never started,
  so the button is hidden then, never a dead button; the report is re-read when the
  error state changes, so a failure recorded on the GL thread just before the error
  posted is already on file when the screen composes); (b) the "Graphics failure
  report" section in Settings (§1.7), with its own "Export failure report" button.
  The export notice goes through the player's transient message line (auto-hide 3 s).
- Honest limits (stated on the Settings page): caught GL-thread failures only. A
  failure that never throws (a silent GL error) leaves no report — only logged. The
  crash report correctly shows nothing for a GL failure: a GL-thread failure is a
  caught exception, not an uncaught one, so it never reaches the crash handler.

---

## 11. Immersive mode + session lifecycle + audio-teardown gate

### 11.1 Immersive mode

- Decision (pure): only the Player screen hides the system bars — in every state it can
  be in (running, paused, layout editor open over the paused game). Library, Settings,
  Licences, Developer and Trace keep the bars exactly as before. Pausing the game and
  opening the layout editor do not change the screen (both are states inside the Player
  composition), so the bars stay hidden through both.
- Wiring: in a game the window lays out edge-to-edge (decor does NOT fit system windows)
  and both system bars are hidden, so the content view keeps the full screen size
  whether the bars are up or down — the transient message line (top 64 dp) and the
  benchmark live line (top 16 dp) sit in the same place with the bars hidden as with
  them shown, and nothing jumps at the transition. Behaviour is
  show-transient-bars-by-swipe: a swipe from the screen edge brings the bars back
  transiently and they re-hide on their own afterwards; transient bars float over the
  content and never resize it. Leaving the game restores the default: the content is
  inset by the system bars again and the bars are shown. Applied from a side effect on
  the first frame the screen composes, and re-applied after a recreation restores a
  Player stack. In a game both bars are hidden completely — there is no battery icon in
  a game (drawing one was declined).
- Settings and other non-player screens use status-bar padding.

### 11.2 Screen structure (player)

- The app launches straight into the Library with NO native code loaded — the bridge is
  only touched when a game starts. There is no splash screen beyond the system default.
  A 600 ms logo sweep runs when a game launches, inside the player: the wordmark fades
  in over 600 ms with an underline sweep. The game must not start during the sweep —
  start lives behind the booted flag, not in the dispose effect. If the user backgrounds
  the app during the sweep, pause while not running is a no-op, and when booted fires
  start, the loop reports Paused instead of Running when paused. A start error lands
  while the player (not the boot sweep) is showing, so the error screen is visible.
  Never start after a GL view-creation failure — the error is already showing, and
  starting would play audio behind it with no picture.
- No persistent top bar in the game (removed: it sat on top of the picture, white text
  washing out against bright game art). Nothing sits on the picture while playing.
- Pause menu — opened ONLY by the device back gesture; there is no on-screen button
  that opens it. System Back in the player toggles pause (paused → back resumes;
  running → back pauses); it never leaves the app from a game. The overlay's
  Pause/Resume behaviour and the Back handler share this one decision. Back during the
  logo sweep does nothing (pause no-ops while the game is not running). While the layout
  editor is open its own back handler wins and discards the draft; closing it leaves
  the pause screen showing, so a second Back resumes. While the slot picker is open, a
  back handler composed after the main one closes the picker and returns to the menu
  instead of resuming.
- Pause menu contents, in display order: Resume (first — the primary action), Save
  state, Load state, Move controls, Exit to library. The game name heads the menu (the
  full name, same as the tile — a rename shows here too). Save/Load open a slot picker
  in place of the menu's action buttons: 4 manual slots (labelled "Slot 1"–"Slot 4",
  showing each slot's save time formatted "19 Nov 10:32" so the four are distinguishable
  by when each was saved) plus, on Load only and only when an auto file exists, an
  "Auto" entry. An empty slot is shown but disabled on Load — there is nothing to load
  from it. A tap saves/loads and returns to the menu. Messages name the slot: "State
  saved to slot N" / "Could not save state to slot N"; "State loaded from slot N" /
  "Saved state in slot N is corrupt" / "No saved state in slot N yet" (a failed load
  with a state file present means the file is corrupt; with no file means there is
  nothing to load — the session's load returns only a Boolean, so the caller checks
  file existence to separate the two); Auto: "State loaded from Auto" / "Auto state is
  corrupt" / "No auto state yet". All transient messages auto-hide after 3 s; a newer
  message cancels the old hide and starts a fresh 3 s window, and a stale hide can never
  clear a newer message (the slot holds the message plus the moment it was set, and the
  hide checks identity). The error screen is a different path with its own message and
  lifetime, untouched by transient-message hiding.
- Colour profile (Atomic only): three choices in the pause menu, applied instantly
  through the session and saved globally. Does not apply to Dot (no colour profiles).
- Session states: Idle, Loading, Running, Paused, Error(message). A shown GL error is
  never overwritten by the session's later Idle — stop (called from the error path)
  reports Idle when its background teardown finishes, and that post must not clear the
  error screen. No legitimate transition leaves Error: the only way out is Back to the
  library. On a GL chain failure the player shows the honest error and stops the session,
  so there is no sound without a picture. Error messages: view-creation failure —
  "Could not start the graphics layer on this device."; chain failure — "The graphics
  layer stopped working."; core could not be loaded — "The emulator core could not be
  loaded."; game would not start — "Could not start that game." (or the core's own
  last-error text when non-blank); previous game still stopping — "The previous game is
  still stopping — try again in a moment."
- The frame path: the session posts each delivered frame to a view-level holder (the
  player screen itself does not recompose per frame); the view drives the frame into
  the renderer from its effect. The per-frame picture lives in the holder, not in the
  player scope.

### 11.3 Session lifecycle hardening

- Threads: the emulation thread runs one core frame per tick, copies the frame into a
  double-buffered bitmap, and paces itself to the core's frame rate; the audio thread
  drains the core's audio into an AudioTrack. Every native call is guarded by the
  core-loaded flag: if the core failed to load, the session reports an error and never
  touches native code.
- Start refusal (dual-loop guard): start refuses while a previous session's threads are
  still alive after a timed-out stop — starting a new loop on top would run two
  emulation loops at once. The refusal is transient and reports the "still stopping"
  error above. Each loop captures a monotonically increasing generation at entry (bumped
  synchronously in stop, before the joins) and exits when it no longer matches, so a
  thread wedged in a native frame after a timed-out stop cannot keep delivering frames
  once a new session starts.
- Core ownership: the core is a single global native object but sessions are per-game.
  Each start claims a fresh token; the background teardown unloads only while its token
  is still the latest. A skipped unload is safe: the new session's init is a clean
  restart (it unloads and deinits any loaded game first). Without this, an old teardown
  could unload the core under a new session's just-loaded game.
- stop() returns immediately: it flips running off, bumps the generation, cancels any
  running benchmark capture, and starts a background teardown thread. Everything else
  (joins, save, audio release, unload, Idle) happens there. The teardown steps run in
  this pinned order, each individually guarded so a failure in one cannot skip the
  rest, and the session reports Idle at the end either way: (1) join emu thread
  (bounded 2000 ms), (2) join audio thread (bounded 2000 ms), (3) write battery save,
  (4) write auto state, (5) release audio track, (6) unload core. The battery save is
  written only after both joins, so the final in-flight frame's save is never lost; the
  auto state is written after the joins and before the unload (the core must still be
  loaded for the serialize to read anything), synchronously on the teardown thread, so
  it completes before the core is torn down. It runs on every stop (clean exit and the
  GL-error stop too — the core still holds the last playable moment then). A stop before
  start never reaches teardown, so no auto state is written for a game that never ran.
  The core is unloaded only once the emulation thread has actually exited; when the
  thread is still alive after the join, the core is left loaded instead — a leak, not a
  crash — and the next start re-inits. References to still-alive threads are kept (not
  nulled) so a later start can see them and refuse. `coreReady` is reset at the end of
  teardown unconditionally, even when a thread is still alive — fail-closed: the guards
  then refuse while the core is in an indeterminate state.
- Pause: sets paused synchronously and reports Paused; pauses + flushes the audio track
  so no stale buffered audio can play on resume (null-guarded — no track yet when
  paused while loading — and caught: a track error must never break the pause itself);
  the battery save moves onto the background state-op thread (pause must still set
  paused + state synchronously, but the save takes the core mutex and must never block
  the UI thread). Resume restarts the paused track (same guards) and reports Running.
  Pause is safe to call any time: it no-ops when the session is not running, and its
  save write no-ops when the core never became ready. If the session was paused while
  still loading, the loop reports the true state instead of overwriting Paused with
  Running when it finishes loading.
- Backgrounding (Home, lock, incoming call, recents): pause on the ON_PAUSE lifecycle
  event ONLY — never resume (the user resumes deliberately with Resume), and every
  other event (including ON_STOP, which always follows ON_PAUSE) does nothing. A
  backgrounding pause also clears the held inputs, drops any rewind hold, and writes
  the auto state asynchronously on the state-op thread, silently (no transient message;
  the Load picker shows the entry when the game is next opened). This is the ONLY pause
  that writes the auto state — the pause toggle (Back / menu) does not: the pause menu
  opens and closes constantly, and serializing on every back press is waste. The exit
  write (the teardown step) is the guarantee; the backgrounding write covers an app the
  system later kills. The lifecycle observer is removed on dispose so a disposed player
  never pauses a later session.
- Auto state: one dedicated file per game, `<sha>-auto.state` in the states directory,
  separate from the manual slots (`<sha>-slot<0..3>.state`) — the names can never
  collide, so an auto write can neither overwrite a manual slot nor be overwritten by
  one. Written on clean exit and on backgrounding; loaded from the Load picker, never
  automatically. State files are written atomically (a crash mid-write leaves the
  previous state intact). Save/load run on the session's single background state-op
  executor, with the Boolean result delivered back on the main thread.
- Battery save: save RAM + RTC are read from the core and written to `<sha>.sav` /
  `<sha>.rtc` in the saves directory. Any failure — a read that returns false, or a
  write that throws — is logged, never swallowed silently.
- Emu loop pacing: the frame interval comes from the core's reported rate (fps in
  milli-fps; non-positive → the 16,741,000 ns fallback). Each iteration: push the input
  mask (store-only rule, §4.2), run one frame, fetch the frame, deliver it, record the
  delivered benchmark timestamp, fetch layer/tile data when wanted (Dot: its own
  picture path), capture a rewind snapshot every 60th frame, then sleep the remainder
  of the interval (sleep requested vs overshoot recorded for phase timing). While
  paused the loop does not run frames. The loop exits when running flips false or the
  generation mismatches.
- Audio: the audio thread starts after the game loads (sample rate from the core's
  reported info, else 48,000 Hz; streaming mode). It checks paused at the top of each
  iteration before writing, so once paused is set no new writes start; a write already
  in flight when pause runs is at most one buffer of current audio. On audio failure
  the game keeps running; only the audio stops (logged). Volume is applied as gain at
  track build (see §1.3).

### 11.4 The alpha.11 audio-teardown gate

- The audio thread OWNS the track and releases it in its finally block on exit.
  stop() must never call stop() on a track that thread may already have released —
  calling stop on a released track throws IllegalStateException ("stop() called on
  uninitialized AudioTrack"), which was a real crash (three times in one day, all at
  stop). The rule: stop() may release the track only once the audio thread has actually
  exited AND the track has not already been released. Enforcement is a release-once
  gate (an atomic boolean, first caller wins): exactly one caller ever runs
  stop()+release() — the audio thread's finally block, or stop() as a defensive fallback
  when the audio thread has already exited without releasing (defensive — with the
  finally block that cannot happen, but a thread that dies by an uncaught exception must
  never leave a second stop possible). The gate is the track's own: a stale thread
  claims only its own gate, never the new track's; the gate is re-armed when audio
  restarts. If the audio thread is still alive after the bounded join, do not touch the
  track: it will release it on exit, exactly as the core is left loaded when the
  emulation thread is still alive. Cross-thread fields (emu thread, audio thread,
  audio track) are volatile — a stale read could skip a join and release the track while
  the audio thread is still using it. The gate makes a second stop structurally
  impossible, not merely caught.

---

## 12. Library tiles + import cap

### 12.1 Import

- Import cap: Atomic 64 MB (`MAX_ROM_BYTES = 64 * 1024 * 1024` — a real GBA ROM is at
  most 32 MB; the cap is 64 MB so a corrupt or lying stream is refused instead of being
  loaded whole into memory). **Dot uses 8 MB** (decided; DMG ROMs are at most 8 MB).
  Reads are capped: the over-cap chunk is never buffered, so a lying stream cannot grow
  memory without bound. A stream holding more than the cap is rejected TOO_LARGE.
- Single-file pick (system document picker, `*/*`): a `.gba` directly, or a `.zip`
  holding a `.gba` (the zip's first `.gba` entry is used; a zip with no `.gba` entry is
  rejected EMPTY_ZIP). A null pick (picker dismissed) is reported, never swallowed.
  Folder pick (system document tree): every `.gba`/`.zip` under the tree, reported with
  a count — never a silent zero. A tree that cannot be listed at all is UNREADABLE, but
  games added take precedence over a partially unreadable tree (five added plus one
  unreadable subdirectory is "added some", not "could not read that folder"; UNREADABLE
  is only reported when nothing was added at all). Folder summary precedence: added
  all (added > 0, added == examined, not unreadable) → added some (added > 0) →
  unreadable → none found (examined == 0) → none added.
- Validation, in order: stream available (else STREAM_UNAVAILABLE); bytes ≥ header size
  (Atomic: 192 bytes, `0xC0`; else TOO_SMALL); header valid (else BAD_HEADER, with a
  per-check diagnosis — which check failed and the bytes involved — written to the
  import trace); cap (TOO_LARGE); write (WRITE_ERROR). Every failure path reports one
  specific reason, so a failed import can always say why. Import never throws; every
  step is a trace line.
- The stored copy is named by the ROM's SHA-256 (`<sha>.gba` in `filesDir/games/`) —
  two different ROMs sharing a header title no longer overwrite each other, and a
  re-import of the same ROM lands on the same name and replaces the copy. The write is
  atomic (a crash mid-write leaves the previous copy intact, never a truncated file
  that lists and then fails at load). A re-import keeps the user's rename and other
  preset choices (the existing preset is loaded first and only refreshed) and clears
  that game's cover-attempt record (§8.4). States and saves are already keyed by SHA,
  so the file name and the save identity agree.
- Header validation (Atomic GBA — Dot's DMG validation is Phase 0 work, already
  decided): 192-byte header, 156-byte Nintendo logo at 0x04–0x9F, fixed byte 0x96 at
  0xB2, header checksum at 0xBD = −(0x19 + sum of bytes 0xA0–0xBC) mod 256, title at
  0xA0–0xAB (12 uppercase ASCII chars, NUL-padded; empty → fall back to the filename
  without extension). Dot validates the DMG header instead: logo at 0x104–0x133,
  CGB flag at 0x143, checksum at 0x14D, 8 MB cap (Phase 0, already implemented — this
  spec does not re-specify it).
- Library list: files in `games/` with the ROM extension, one entry per file (a file
  that cannot be read or hashed is skipped, never taking down the whole list; the skip
  is traced). Entries are sorted by title, lower-cased. Each entry carries: SHA, header
  title, file name, last-played time (0 when never played), the preserved source
  filename, the inner zip name, and the user's rename. Playing a game updates its
  last-played time.
- Import launchers are registered in `onCreate` with the Activity Result API — never in
  a composable. A composable-owned launcher is only re-registered if that composable
  recomposes after the activity is recreated while the picker is open; activity-level
  registration is the recreation-safe pattern, and every outcome reports a message.
  Folder picks take a persistable read grant (best effort — the import reads the tree
  right now regardless).

### 12.2 Tiles

- The tray is a horizontal scrolling row (`LazyRow`, 16 dp spacing), items keyed by SHA.
  One tile per game, 216 dp wide: a 3:2 art area (216 × 144 dp, 8 dp rounded corners,
  charcoal background) above the name, the last-played label, and a "Remove" button.
- Art area: when a cover is Ready (or a custom cover is set), the art area shows the
  real box art with Fit scaling on the charcoal background — letterboxed, never cropped,
  never a taller box. The generated label stays as the placeholder while Loading /
  Missing / NoMatch / Failed (a 48 × 6 dp indigo bar, the full name in bold 16sp centred
  on two lines, and the system wordmark in muted 10sp with 2sp letter spacing — Atomic
  shows "GAME BOY ADVANCE"; Dot shows "GAME BOY"). Custom cover wins over fetched art;
  both are decoded on demand from disk (the disk file is the authority). The remember
  key is the path string (a new File instance every recomposition would re-decode every
  time) — subject to the B1 rule in §8.5 (key on path + last-modified, never memoise a
  null on path alone).
- Blurred wash (alpha.64 tile redesign): the art area's background is a blurred,
  darkened copy of the same art, so the tile no longer reads as blank where the art is
  narrower than the tile. Generation: the cover downscaled hard (24×16 — the blur is
  the filtered upscale back to the tile's 216×144, which smears every edge) and darkened
  ~45% black (draw colour `0x73000000`), written atomically to `cover-blurs/<sha>.png`.
  Generated once from the cover and cached on disk, never per frame; the generation runs
  on IO, the cached file just decodes. A wash is fresh only when it is newer than the
  cover it was made from — a replaced cover regenerates it instead of showing the old
  wash. The wash is cropped to fill; the art itself is still Fit, letterboxed, never
  cropped. No-art tiles keep the charcoal fallback. The wash file is deleted when the
  cover is replaced, the game is removed, or the cover is evicted.
- Full name (alpha.64 tile redesign): the name under the art is the FULL name, on two
  lines max with ellipsis as the last resort, in this priority order: (1) the user's
  rename, (2) the cover-art index title the matcher already found (the matched art
  filename without `.png`), (3) the filename stem — the outer name, then the name inside
  the zip — (4) the 12-character header title. A blank value at any step falls through
  to the next; the header title is never blank (the list falls back to the filename when
  the header has none). Font size: 15sp up to 28 characters, 13sp past that, so a long
  full name still fits its two lines. The same full name heads the pause menu (§11.2).
- Last-played label under the name, 12sp mono ("Never played" when 0 — Atomic formats
  the label from the stored time; Dot keeps the same shape).
- Gestures: a tap anywhere on the tile launches the game; a long-press pops the menu.
  The "Remove" button below keeps its own tap target and opens a remove confirmation
  dialog (removing deletes the game file and its preset/states/saves/covers — Atomic's
  remove deletes the game entry; Dot follows the same shape).
- Long-press menu items: "Set custom cover" (always); "Remove custom cover" (only when
  one exists); "Retry cover download" (only on a Failed tile); "Move controls"
  (launches the game with the layout editor open); "Rename" (opens the rename dialog
  pre-filled with the name the tile shows now; saving stores the name in the preset —
  empty clears it — and re-lists, so the tile and the pause-menu header show the new
  name at once). Atomic also shows an informational line on MP2K-detected games and a
  music-note mark on the tile — GBA-only, does not apply to Dot.
- The status line and its Retry button sit above the tray (see §8.5); the import
  message sits under the library header. The empty state shows when there are no games.
  The library header holds Add game, Add folder, Settings, Licences.

### 12.3 Store layout (filesDir)

- `games/` — imported ROMs as `<sha>.gba` (Dot: `<sha>.gb`).
- `presets/` — per-game JSON presets, `<sha>.json` (Atomic: picture preset + MP2K hook
  + source/inner names + rename; Dot: source/inner names + rename + whatever Dot's
  Phase 1 preset holds. A missing or corrupt preset reads as the default preset, never
  a crash; a preset write failure never fails the import).
- `states/` — manual slots `<sha>-slot<0..3>.state`, auto state `<sha>-auto.state`.
- `saves/` — battery saves `<sha>.sav`, RTC `<sha>.rtc`.
- `covers/` — fetched art `<sha>.png`; `custom-covers/` — user covers `<sha>.png`;
  `cover-blurs/` — blurred washes `<sha>.png`.
- `control-layouts/` — layouts `<sha>.properties` + `default.properties` (see §3.2).
- `cover-attempts.txt` — attempt records (§8.4). `art-index-gb.txt` + `art-index-meta.txt`
  (Dot names) — refreshed index + its meta (`sha=<sha>`, `checkedAtMs=<ms>`).
- `crash-report.txt`, `gl-failure-report.txt` — see §9, §10.
- Atomic also keeps a games-directory size readout (sum of file lengths) for the
  Storage row.

---

## 13. GBA-only items that do NOT apply to Dot

| Atomic item | Why it does not apply |
|---|---|
| L / R shoulder buttons (controls, haptics shoulder click + thud, layout cluster, gamepad L1/R1) | The DMG has no shoulders. DMG mapping drops L/R everywhere: controls, layout ids, haptics, gamepad. |
| Joystick / stick (left-control choice, dead-zone slider 0–50% default 22%, stick control, stick nub geometry, proportional steering, racing mode + racing stick 160 dp / dead zone 30%) | D-pad only, decided. No stick, no steering, no stick dead-zone. The stick's actuation rule and nub geometry are not built. |
| MP2K HLE (per-game "cleaner music mix" switch, pause-menu row, tile music-note mark, import-time engine detection) | GBA-only audio engine. No MP2K detection, switch, mark or menu row in Dot. |
| RetroAchievements | Not in Atomic's shell spec scope at all; not built for Dot. |
| Colour profiles (Off / 2019 / 2026) and colour correction | GBA remaster picture work. No colour profiles in Dot (decided: DMG is grayscale, no colour). |
| Affine fix / affine sprites / OAM sprite pass / layer chain / tile dedup / de-dither / dither inversion / debanding / temporal stabilisation / upscaler choice | GBA remaster picture chain. Dot's picture chain is its own Phase 1 work (sharp-bilinear upscaler, Mitchell downscale in linear light, linear-light pipeline, 59.73 fps — decided separately). |
| Dual-mode 0x80 games in DMG mode / GBC mosaic / JINC | Picture-chain decisions, recorded in Dot's plan, not shell behaviour. |
| GBA header validation (192-byte header, 156-byte logo, fixed byte 0x96, checksum at 0xBD), 64 MB import cap, `.gba` / `.zip` with `.gba` entry | Replaced by Dot's DMG validation (logo 0x104–0x133, CGB 0x143, checksum 0x14D, 8 MB cap, `.gb`). |
| GBA cover index (`Nintendo_-_Game_Boy_Advance`, `art-index-gba.txt`) and the two-entry header denylist (CASTLEVANIA, SUPER MARIO) | Replaced by the GB repo `Nintendo_-_Game_Boy` and an empty Dot denylist (§8.1–8.2). |
| Landscape-locked orientation | Dot follows device rotation, portrait by default (decided). Factory layout positions must be re-derived for portrait. |
| "GAME BOY ADVANCE" tile wordmark, "Atomic — a standalone Game Boy Advance emulator", mGBA-specific About/core lines | Reworded for Dot / Game Boy. |
| Temporary OAM fallback in the graphics chain and its "Fallback engaged" semantics | Dot has no fallback; the failure-report field is kept with the value always "no" until Dot adds one (see §10). |
| Soft-clip | DSP work, post-v1 — decision recorded only (§1.3): no soft-clip control ships in v1. |
| Rumble / cartridge rumble gate | Neither Atomic nor Dot has rumble; no gate is built (§4.2 item 7). |

### Notes for the Dot builder (things the spec deliberately leaves open)

- The envelope haptics path (API 36+) is unproven on the Pixel 10a and untestable under
  a Robolectric SDK-35 harness (the envelope builder does not exist there). Dot keeps
  the fallback-first structure so the envelope can only ever refine the click, never
  silence a button — and the probe screen is how the device verdict is taken.
- The factory layout numbers in §3.1 are Atomic's landscape numbers. Dot re-derives
  them for portrait; the mechanism (fractions, clamps, clusters, snap, opacity) is
  the spec, the numbers are not.
- The benchmark gate ("both clocks must hold 60 fps") is a judgement applied to the
  harness output, not a threshold in the harness code. Dot's gate thresholds are set
  when the harness lands, against the DMG native rate.
- The GB index entry count and snapshot date are Dot's own, recorded when the asset is
  built. The matcher ships with an empty denylist until Dot measures header-only
  mismatches against the live GB index.
