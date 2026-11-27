package app.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
// NOTE: awaitPointerEvent() is a member of the AwaitPointerEventScope
// receiver inside awaitEachGesture — it takes no import. Importing it
// from either gestures or ui.input.pointer fails the build.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.TextUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — the touch-controls layer (SHELL-SPEC §4).
//
// A full-screen layer over the player. Each control sits at its
// layout fraction (LayoutStore: centres as fractions of the full
// screen, sizes in dp), so a layout survives rotation and screen
// sizes, and a control may sit anywhere — including over the game.
// The layer itself takes no touches; only the controls do.
//
// D-pad ONLY: one cross, never a stick. The cross tracks every
// pointer on it at once (multi-pointer OR), the direction follows
// the §4.3 ratio rule (0.22 dead zone, 0.35 diagonal ratio), and a
// pointer that slides off the cross releases its direction. The
// mask clears the moment the direction goes empty; only the
// release haptic waits for lift-off.
//
// Opacity is the whole layout's: at 0 the controls are invisible
// AND non-interactive. Gamepad presses fire no haptics (SHELL-SPEC
// §5) — haptics come only from these touch controls.

/** The D-pad direction bits, for masking the shared input mask. */
private const val DIRS_MASK =
    Bridge.Input.UP or Bridge.Input.DOWN or Bridge.Input.LEFT or Bridge.Input.RIGHT

@Composable
fun OverlayControls(
    session: PlayerSession,
    layout: ControlLayout,
    haptics: HapticsEngine,
    ffVisible: Boolean,
    slowVisible: Boolean,
    rewindVisible: Boolean,
    /** The touch mask portion, owned by the player screen. */
    touchMask: Int,
    /** Fires when the touch mask portion changes; the player screen
     *  merges it with the gamepad mask before the session. */
    onTouchMaskChanged: (Int) -> Unit,
    rewindHolding: Boolean,
    onRewindStart: () -> Unit,
    onRewindEnd: () -> Unit,
    isForeground: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    // Each control owns its bits; the overlay merges them into the
    // touch mask portion and reports it up (the session's emu loop
    // is the single pusher to the core). The report fires only when
    // the mask actually changes (C10 #5).
    fun updateBits(bits: Int, value: Int) {
        val next = (touchMask and bits.inv()) or (value and bits)
        if (next != touchMask) onTouchMaskChanged(next)
    }

    val inputEnabled = layout.opacity > 0
    val alpha = layout.opacity / 100f

    // Rewind hold loop: while the hold is live, the emu loop is
    // paused and this steps one snapshot back every 150 ms. A pause
    // cancels it, and so does the app backgrounding (GameScreen
    // clears rewindHolding on ON_PAUSE); an empty ring ends the
    // hold. The first step waits 50 ms so a frame already in flight
    // finishes before the first unserialise.
    LaunchedEffect(rewindHolding, isForeground) {
        if (!rewindHolding) return@LaunchedEffect
        delay(50)
        while (rewindHolding && session.isPaused() && isForeground) {
            if (!session.rewindStep()) break
            delay(150)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        fun place(spec: ControlSpec): IntOffset {
            val sizePx = spec.sizeDp * density.density
            return IntOffset(
                (spec.cx * widthPx - sizePx / 2f).roundToInt(),
                (spec.cy * heightPx - sizePx / 2f).roundToInt(),
            )
        }

        val dpadSpec = layout.controls.getValue("dpad")
        Box(Modifier.offset { place(dpadSpec) }) {
            DPadCross(
                spec = dpadSpec,
                haptics = haptics,
                onDirections = { dirs -> updateBits(DIRS_MASK, dirs) },
                enabled = inputEnabled,
                alpha = alpha,
                // While paused the highlight is forced to 0 in
                // composition, so it clears the moment the pause
                // overlay appears instead of waiting on the
                // pointer-input restart (§4.1).
                highlightDirs = if (isPaused) 0 else null,
            )
        }

        val aSpec = layout.controls.getValue("a")
        Box(Modifier.offset { place(aSpec) }) {
            RoundControlButton(
                label = "A",
                spec = aSpec,
                alpha = alpha,
                enabled = inputEnabled,
                onHold = { down ->
                    if (down) haptics.pressClick()
                    updateBits(Bridge.Input.A, if (down) Bridge.Input.A else 0)
                },
            )
        }

        val bSpec = layout.controls.getValue("b")
        Box(Modifier.offset { place(bSpec) }) {
            RoundControlButton(
                label = "B",
                spec = bSpec,
                alpha = alpha,
                enabled = inputEnabled,
                onHold = { down ->
                    if (down) haptics.pressClick()
                    updateBits(Bridge.Input.B, if (down) Bridge.Input.B else 0)
                },
            )
        }

        val startSpec = layout.controls.getValue("start")
        Box(Modifier.offset { place(startSpec) }) {
            PillControlButton(
                label = "START",
                spec = startSpec,
                alpha = alpha,
                enabled = inputEnabled,
                onHold = { down ->
                    if (down) haptics.lightClick()
                    updateBits(
                        Bridge.Input.START,
                        if (down) Bridge.Input.START else 0,
                    )
                },
            )
        }

        val selectSpec = layout.controls.getValue("select")
        Box(Modifier.offset { place(selectSpec) }) {
            PillControlButton(
                label = "SELECT",
                spec = selectSpec,
                alpha = alpha,
                enabled = inputEnabled,
                onHold = { down ->
                    if (down) haptics.lightClick()
                    updateBits(
                        Bridge.Input.SELECT,
                        if (down) Bridge.Input.SELECT else 0,
                    )
                },
            )
        }

        if (ffVisible) {
            val ffSpec = layout.controls.getValue("ff")
            Box(Modifier.offset { place(ffSpec) }) {
                RoundControlButton(
                    label = "FF",
                    spec = ffSpec,
                    alpha = alpha,
                    enabled = inputEnabled,
                    labelSize = 14.sp,
                    utility = true,
                    onHold = { down ->
                        if (down) haptics.utilityClick()
                        else haptics.utilityReleaseTick()
                        session.ffHeld = down
                    },
                )
            }
        }

        if (slowVisible) {
            val slowSpec = layout.controls.getValue("slow")
            Box(Modifier.offset { place(slowSpec) }) {
                var slowOn by remember { mutableStateOf(session.slowOn) }
                RoundControlButton(
                    label = "½×",
                    spec = slowSpec,
                    alpha = alpha,
                    enabled = inputEnabled,
                    labelSize = 14.sp,
                    utility = true,
                    active = slowOn,
                    onHold = { down ->
                        // A toggle, not a hold: the press flips it.
                        if (down) {
                            slowOn = !slowOn
                            session.slowOn = slowOn
                            haptics.toggleClick()
                        }
                    },
                )
            }
        }

        if (rewindVisible) {
            val rwSpec = layout.controls.getValue("rw")
            Box(Modifier.offset { place(rwSpec) }) {
                RoundControlButton(
                    label = "RW",
                    spec = rwSpec,
                    alpha = alpha,
                    enabled = inputEnabled,
                    labelSize = 14.sp,
                    utility = true,
                    active = rewindHolding,
                    onHold = { down ->
                        if (down) {
                            haptics.utilityClick()
                            onRewindStart()
                        } else {
                            haptics.utilityReleaseTick()
                            onRewindEnd()
                        }
                    },
                )
            }
        }
    }
}

// -- the single-cross D-pad ------------------------------------------

@Composable
internal fun DPadCross(
    spec: ControlSpec,
    haptics: HapticsEngine,
    onDirections: (Int) -> Unit,
    enabled: Boolean,
    alpha: Float,
    /** When non-null, the highlight is forced to this mask instead
     *  of the live gesture state (the pause overlay forces 0). */
    highlightDirs: Int? = null,
) {
    var activeDirs by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val sizePx = with(density) { spec.sizeDp.dp.toPx() }
    val half = sizePx / 2f
    val hysteresisPx = with(density) { 8.dp.toPx() }
    Box(
        modifier = Modifier
            .size(spec.sizeDp.dp)
            .alpha(alpha)
            .pointerInput(enabled, sizePx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val pointers = LinkedHashMap<PointerId, Offset>()
                    val first = awaitFirstDown()
                    pointers[first.id] = first.position
                    var lastDirs = mergedDirs(pointers, half)
                    if (lastDirs != 0) {
                        onDirections(lastDirs)
                        activeDirs = lastDirs
                        haptics.directionTick(0, lastDirs)
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (!change.pressed) {
                                    pointers.remove(change.id)
                                } else {
                                    // Slide-off release: a pointer
                                    // that leaves the cross (plus
                                    // 8 dp of hysteresis) stops
                                    // counting, without lifting.
                                    val off = change.position - Offset(half, half)
                                    if (abs(off.x) > half + hysteresisPx ||
                                        abs(off.y) > half + hysteresisPx
                                    ) {
                                        pointers.remove(change.id)
                                    } else {
                                        pointers[change.id] = change.position
                                    }
                                }
                            }
                            val dirs = mergedDirs(pointers, half)
                            if (dirs != lastDirs) {
                                onDirections(dirs)
                                activeDirs = dirs
                                if (dirs != 0) {
                                    haptics.directionTick(lastDirs, dirs)
                                }
                                lastDirs = dirs
                            }
                            // The gesture ends when no pointer is
                            // left on the cross — not when one event
                            // happens to list no pressed change (a
                            // move event may only carry the other
                            // finger while this one still holds).
                            if (pointers.isEmpty()) break
                        }
                    } finally {
                        // Lift-off: the mask clears here, and the
                        // release haptic fires once, here — never on
                        // a slide back to neutral while still held.
                        pointers.clear()
                        if (lastDirs != 0) onDirections(0)
                        activeDirs = 0
                        haptics.releaseTick()
                    }
                }
            },
    ) {
        CrossCanvas(
            activeDirs = highlightDirs ?: activeDirs,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun mergedDirs(pointers: Map<PointerId, Offset>, half: Float): Int {
    var mask = 0
    for (pos in pointers.values) {
        mask = mask or DpadMath.directionMask(pos.x - half, pos.y - half, half)
    }
    return mask
}

/** The cross itself (§4.1): a silver well with a charcoal cross —
 *  the DMG's own D-pad, dark on the silver faceplate; a held arm
 *  brightens to near-white. */
@Composable
private fun CrossCanvas(activeDirs: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val arm = s * 0.36f
        val r = s * 0.10f
        val well = DotSkin.Silver
        val armRest = DotSkin.CharcoalDeep
        val armLit = DotSkin.SilverBright
        fun armColor(bit: Int) = if (activeDirs and bit != 0) armLit else armRest
        // The well behind the cross.
        drawRoundRect(
            color = well,
            topLeft = Offset.Zero,
            size = Size(s, s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 2f, r * 2f),
        )
        // Centre square.
        drawRoundRect(
            color = armRest,
            topLeft = Offset((s - arm) / 2f, (s - arm) / 2f),
            size = Size(arm, arm),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        // Up / down arms.
        drawRoundRect(
            color = armColor(Bridge.Input.UP),
            topLeft = Offset((s - arm) / 2f, 0f),
            size = Size(arm, (s - arm) / 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        drawRoundRect(
            color = armColor(Bridge.Input.DOWN),
            topLeft = Offset((s - arm) / 2f, (s + arm) / 2f),
            size = Size(arm, (s - arm) / 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        // Left / right arms.
        drawRoundRect(
            color = armColor(Bridge.Input.LEFT),
            topLeft = Offset(0f, (s - arm) / 2f),
            size = Size((s - arm) / 2f, arm),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        drawRoundRect(
            color = armColor(Bridge.Input.RIGHT),
            topLeft = Offset((s + arm) / 2f, (s - arm) / 2f),
            size = Size((s - arm) / 2f, arm),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        // Direction triangles, one at the end of each arm —
        // charcoal on the silver well.
        val tri = s * 0.10f
        val cx = s / 2f
        fun triangle(tip: Offset, left: Offset, right: Offset) {
            drawPath(
                path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                },
                color = DotSkin.CharcoalDeep,
            )
        }
        val upY = s * 0.07f
        triangle(
            Offset(cx, upY),
            Offset(cx - tri, upY + tri * 1.4f),
            Offset(cx + tri, upY + tri * 1.4f),
        )
        val downY = s * 0.93f
        triangle(
            Offset(cx, downY),
            Offset(cx - tri, downY - tri * 1.4f),
            Offset(cx + tri, downY - tri * 1.4f),
        )
        val leftX = s * 0.07f
        triangle(
            Offset(leftX, cx),
            Offset(leftX + tri * 1.4f, cx - tri),
            Offset(leftX + tri * 1.4f, cx + tri),
        )
        val rightX = s * 0.93f
        triangle(
            Offset(rightX, cx),
            Offset(rightX - tri * 1.4f, cx - tri),
            Offset(rightX - tri * 1.4f, cx + tri),
        )
    }
}

// -- round / pill control buttons ------------------------------------

@Composable
internal fun RoundControlButton(
    label: String,
    spec: ControlSpec,
    alpha: Float,
    enabled: Boolean,
    labelSize: TextUnit = 22.sp,
    active: Boolean = false,
    /** FF / 0.5x / RW render as muted steel-dark circles (§4.1). */
    utility: Boolean = false,
    onHold: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    // Silver fill when pressed or active, charcoal otherwise;
    // utility (FF / 0.5x / RW) renders as a muted steel-dark
    // circle (§4.1). The label is dark on silver, white on
    // charcoal.
    val silver = DotSkin.Silver
    val fill = when {
        pressed || active -> silver
        utility -> Color(0xFF3A3A3A)
        else -> DotSkin.ControlDark
    }
    Box(
        modifier = Modifier
            .size(spec.sizeDp.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(fill)
            // A/B carry a thin silver ring; pressed is solid silver.
            .then(
                if (!utility && !pressed && !active) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = silver.copy(alpha = 0.7f),
                        shape = CircleShape,
                    )
                } else {
                    Modifier
                },
            )
            .holdButton(
                enabled = enabled,
                onDown = { pressed = true; onHold(true) },
                onUp = { pressed = false; onHold(false) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed || active) DotSkin.TextOnSilver else Color.White,
            fontSize = labelSize,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun PillControlButton(
    label: String,
    spec: ControlSpec,
    alpha: Float,
    enabled: Boolean,
    onHold: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    // The placement square is spec.sizeDp on a side, and the pill
    // is centred on it, so its centre lands exactly on the layout
    // fraction. The square is only the anchor: the pill's width
    // grows to fit the label (min the placement size), measured
    // against the overlay's own constraints — a plain Box would cap
    // the pill at the square's width, and the old fixed 64 dp pill
    // with 20 dp of horizontal padding left 24 dp for a 13 sp
    // label, so START / SELECT wrapped and clipped into unreadable
    // fragments ("CT" / "CEI" on device, Phase 1.2). A wider pill
    // overflows the square equally on both sides, centre unchanged.
    // (No size modifier on the Layout: it must see the overlay's
    // constraints, and it reports the square as its own size.)
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .height((spec.sizeDp / 2).dp)
                    .widthIn(min = spec.sizeDp.dp)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (pressed) DotSkin.Silver else DotSkin.ControlDark,
                    )
                    .holdButton(
                        enabled = enabled,
                        onDown = { pressed = true; onHold(true) },
                        onUp = { pressed = false; onHold(false) },
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Label in muted silver-grey; pressed is silver with a
                // dark label (§4.1). Single line, never wrapped: the
                // pill is wide enough to hold it whole.
                Text(
                    label,
                    color = if (pressed) DotSkin.TextOnSilver else DotSkin.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    ) { measurables, constraints ->
        // Measure the pill against the overlay's width, not the
        // square's: it may grow wider than the square.
        val pill = measurables.first().measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        val squarePx = with(density) { spec.sizeDp.dp.roundToPx() }
        layout(squarePx, squarePx) {
            pill.place(
                x = (squarePx - pill.width) / 2,
                y = (squarePx - pill.height) / 2,
            )
        }
    }
}

/**
 * Press-and-hold input (C10 #2, #3). Each button tracks its own
 * down pointer's id and releases when it lifts OR its position
 * exits the button bounds, past 8 dp of hysteresis — a finger
 * sliding off no longer stays held forever. The `finally` is
 * load-bearing: without it a gesture cancelled mid-press (e.g. a
 * pointer-input restart on pause) leaves the button stuck down.
 * The down itself is inside the try, so a restart with no finger
 * down fires nothing.
 */
fun Modifier.holdButton(
    enabled: Boolean = true,
    onDown: () -> Unit,
    onUp: () -> Unit,
): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            val id = down.id
            try {
                onDown()
                while (true) {
                    val event = awaitPointerEvent()
                    // Only the tracked pointer decides: an event
                    // carrying only the other finger must not end
                    // this hold.
                    val change = event.changes.firstOrNull { it.id == id }
                    if (change != null) {
                        if (!change.pressed) break
                        val hyst = 8.dp.toPx()
                        val pos = change.position
                        if (pos.x < -hyst || pos.x > size.width + hyst ||
                            pos.y < -hyst || pos.y > size.height + hyst
                        ) {
                            break
                        }
                    }
                }
            } finally {
                onUp()
            }
        }
    }
