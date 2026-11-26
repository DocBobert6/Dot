package app.dot

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — the control-layout editor (SHELL-SPEC §3.3).
//
// A full-screen editor over the paused game (charcoal at 0.92
// alpha, so a layout is judged against the real picture while
// edited). The editor holds its own draft: nothing reaches the
// play overlay until Done; Back discards it. The editor's back
// handler is composed after the player's own, so it wins while
// the editor is open; after it closes the pause screen is still
// showing, so a second Back resumes the game.
//
// Dragging selects the control first (so the size stepper appears),
// then moves it — and its whole cluster when Together is on —
// snapping the centre to the 8 dp grid when Snap is on, clamped to
// the screen and to the safe-area bounds. The preview is the real
// buttons shared with the play overlay, so the two can never drift
// apart; while editing the game is paused, so a press is inert
// (it sets nothing the session reads) and the haptics are the only
// live feedback. A 0% draft leaves no live buttons under the drag
// detector, but the controls stay draggable.
//
// The same component serves the Settings default editor (batch 5):
// the caller passes the base layout and the reset target — the
// player passes the resolved layout and the effective default, the
// Settings editor passes the default base and the factory layout —
// and commits the draft itself on Done.

@Composable
fun LayoutEditor(
    baseLayout: ControlLayout,
    resetTarget: ControlLayout,
    haptics: HapticsEngine,
    ffVisible: Boolean,
    slowVisible: Boolean,
    rewindVisible: Boolean,
    prefs: SharedPreferences,
    onDone: (ControlLayout?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(baseLayout) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var snapOn by remember { mutableStateOf(LayoutStore.isSnapOn(prefs)) }
    var togetherOn by remember { mutableStateOf(LayoutStore.isTogetherOn(prefs)) }
    var settingsCollapsed by remember { mutableStateOf(false) }

    BackHandler { onDone(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF141414).copy(alpha = 0.92f)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val canvasWidthPx = with(density) { maxWidth.toPx() }
            val canvasHeightPx = with(density) { maxHeight.toPx() }
            val canvasWidthDp = maxWidth
            val canvasHeightDp = maxHeight
            val layoutDirection = LocalLayoutDirection.current

            // Safe-area bounds, read once per composition: the
            // maximum inset per edge, as fractions — never tighter
            // than the 0.03 screen clamp. asPaddingValues() is a
            // @Composable call, so it runs in the composition body,
            // not inside the remember lambda.
            val cutout = WindowInsets.displayCutout.asPaddingValues()
            val nav = WindowInsets.navigationBars.asPaddingValues()
            val status = WindowInsets.statusBars.asPaddingValues()
            val insets = remember(cutout, nav, status, layoutDirection) {
                fun left(p: androidx.compose.foundation.layout.PaddingValues): Dp =
                    p.calculateLeftPadding(layoutDirection)
                fun right(p: androidx.compose.foundation.layout.PaddingValues): Dp =
                    p.calculateRightPadding(layoutDirection)
                fun top(p: androidx.compose.foundation.layout.PaddingValues): Dp =
                    p.calculateTopPadding()
                fun bottom(p: androidx.compose.foundation.layout.PaddingValues): Dp =
                    p.calculateBottomPadding()
                InsetsDp(
                    left = maxOf(left(cutout), left(nav), left(status)),
                    right = maxOf(right(cutout), right(nav), right(status)),
                    top = maxOf(top(cutout), top(nav), top(status)),
                    bottom = maxOf(bottom(cutout), bottom(nav), bottom(status)),
                )
            }
            val leftBound = maxOf(
                LayoutStore.EDGE_CLAMP,
                (insets.left / canvasWidthDp).coerceIn(0f, 0.5f),
            )
            val rightBound = minOf(
                1f - LayoutStore.EDGE_CLAMP,
                1f - (insets.right / canvasWidthDp).coerceIn(0f, 0.5f),
            )
            val topBound = maxOf(
                LayoutStore.EDGE_CLAMP,
                (insets.top / canvasHeightDp).coerceIn(0f, 0.5f),
            )
            val bottomBound = minOf(
                1f - LayoutStore.EDGE_CLAMP,
                1f - (insets.bottom / canvasHeightDp).coerceIn(0f, 0.5f),
            )

            fun clusterOf(id: String): List<String> {
                if (!togetherOn) return listOf(id)
                return LayoutStore.CLUSTERS.firstOrNull { id in it }
                    ?: listOf(id)
            }

            fun moveControl(id: String, dxPx: Float, dyPx: Float) {
                val dxFrac = dxPx / canvasWidthPx
                val dyFrac = dyPx / canvasHeightPx
                val next = draft.controls.toMutableMap()
                for (cid in clusterOf(id)) {
                    val spec = next[cid] ?: continue
                    var cx = spec.cx + dxFrac
                    var cy = spec.cy + dyFrac
                    if (snapOn) {
                        cx = LayoutStore.snapFraction(
                            cx, canvasWidthPx, density.density,
                        )
                        cy = LayoutStore.snapFraction(
                            cy, canvasHeightPx, density.density,
                        )
                    }
                    next[cid] = spec.copy(
                        cx = cx.coerceIn(leftBound, rightBound),
                        cy = cy.coerceIn(topBound, bottomBound),
                    )
                }
                draft = draft.copy(controls = next)
            }

            fun resizeSelected(deltaDp: Int) {
                val id = selectedId ?: return
                val next = draft.controls.toMutableMap()
                for (cid in clusterOf(id)) {
                    val spec = next[cid] ?: continue
                    next[cid] = spec.copy(
                        sizeDp = (spec.sizeDp + deltaDp).coerceIn(
                            LayoutStore.MIN_SIZE_DP, LayoutStore.MAX_SIZE_DP,
                        ),
                    )
                }
                draft = draft.copy(controls = next)
            }

            fun place(spec: ControlSpec): IntOffset {
                val sizePx = spec.sizeDp * density.density
                return IntOffset(
                    (spec.cx * canvasWidthPx - sizePx / 2f).roundToInt(),
                    (spec.cy * canvasHeightPx - sizePx / 2f).roundToInt(),
                )
            }

            val inputEnabled = draft.opacity > 0
            val alpha = draft.opacity / 100f
            val visibleIds = LayoutStore.CONTROL_IDS.filter { id ->
                when (id) {
                    "ff" -> ffVisible
                    "slow" -> slowVisible
                    "rw" -> rewindVisible
                    else -> true
                }
            }

            // The draggable previews.
            for (id in visibleIds) {
                val spec = draft.controls.getValue(id)
                val selected = selectedId == id
                Box(
                    modifier = Modifier
                        .offset { place(spec) }
                        .size(spec.sizeDp.dp)
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) Color(0xFF5B5BD6)
                            else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .pointerInput(id, snapOn, togetherOn) {
                            detectDragGestures(
                                onDragStart = { selectedId = id },
                                onDrag = { _, dragAmount ->
                                    moveControl(id, dragAmount.x, dragAmount.y)
                                },
                            )
                        },
                ) {
                    when (id) {
                        "dpad" -> DPadCross(
                            spec = spec,
                            haptics = haptics,
                            onDirections = { /* inert while paused */ },
                            enabled = inputEnabled,
                            alpha = alpha,
                        )
                        "a", "b" -> RoundControlButton(
                            label = id.uppercase(),
                            spec = spec,
                            alpha = alpha,
                            enabled = inputEnabled,
                            onHold = { down ->
                                if (down) haptics.pressClick()
                            },
                        )
                        "start", "select" -> PillControlButton(
                            label = id.uppercase(),
                            spec = spec,
                            alpha = alpha,
                            enabled = inputEnabled,
                            onHold = { down ->
                                if (down) haptics.lightClick()
                            },
                        )
                        "ff", "rw" -> RoundControlButton(
                            label = id.uppercase(),
                            spec = spec,
                            alpha = alpha,
                            enabled = inputEnabled,
                            labelSize = 14.sp,
                            utility = true,
                            onHold = { down ->
                                if (down) haptics.utilityClick()
                                else haptics.utilityReleaseTick()
                            },
                        )
                        "slow" -> RoundControlButton(
                            label = "½×",
                            spec = spec,
                            alpha = alpha,
                            enabled = inputEnabled,
                            labelSize = 14.sp,
                            utility = true,
                            onHold = { down ->
                                if (down) haptics.toggleClick()
                            },
                        )
                    }
                    // The selected control shows its exact size in
                    // dp inside itself, at its bottom edge.
                    if (selected) {
                        Text(
                            "${spec.sizeDp} dp",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 2.dp),
                        )
                    }
                }
            }

            // Top action row + settings panel.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorPill("Back") { onDone(null) }
                    EditorPill("Done") { onDone(draft) }
                    EditorPill("Reset") {
                        draft = resetTarget
                        selectedId = null
                    }
                    EditorPill(
                        "Snap",
                        active = snapOn,
                    ) {
                        snapOn = !snapOn
                        LayoutStore.setSnapOn(prefs, snapOn)
                    }
                    EditorPill(
                        "Together",
                        active = togetherOn,
                    ) {
                        togetherOn = !togetherOn
                        LayoutStore.setTogetherOn(prefs, togetherOn)
                    }
                }
                Row {
                    TextButton(
                        onClick = { settingsCollapsed = !settingsCollapsed },
                    ) {
                        Text(
                            if (settingsCollapsed) "Show settings"
                            else "Hide settings",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (!settingsCollapsed) {
                    // Opacity stepper: 0–100% in steps of 5.
                    EditorStepperRow(
                        label = "Opacity",
                        value = "${draft.opacity}%",
                        onMinus = {
                            draft = draft.copy(
                                opacity = (draft.opacity - 5).coerceIn(0, 100),
                            )
                        },
                        onPlus = {
                            draft = draft.copy(
                                opacity = (draft.opacity + 5).coerceIn(0, 100),
                            )
                        },
                    )
                    // Size stepper, only while a control is selected.
                    val selectedSpec = selectedId?.let { draft.controls[it] }
                    if (selectedSpec != null) {
                        EditorStepperRow(
                            label = "Size",
                            value = "${selectedSpec.sizeDp} dp",
                            onMinus = { resizeSelected(-LayoutStore.SIZE_STEP_DP) },
                            onPlus = { resizeSelected(LayoutStore.SIZE_STEP_DP) },
                        )
                    }
                    // Hidden utility buttons: only the visible ones
                    // can be positioned; name the hidden ones and
                    // where to turn them on.
                    val hidden = listOf(
                        "ff" to "FF",
                        "slow" to "0.5x",
                        "rw" to "RW",
                    ).filter { (id, _) ->
                        when (id) {
                            "ff" -> !ffVisible
                            "slow" -> !slowVisible
                            else -> !rewindVisible
                        }
                    }.map { (_, label) -> label }
                    if (hidden.isNotEmpty()) {
                        val verb = if (hidden.size == 1) "is" else "are"
                        val obj = if (hidden.size == 1) "it" else "them"
                        Text(
                            "${hidden.joinToString(" / ")} $verb hidden in " +
                                "play — turn $obj on in Settings → " +
                                "Controls to move $obj.",
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class InsetsDp(
    val left: Dp,
    val right: Dp,
    val top: Dp,
    val bottom: Dp,
)

@Composable
private fun EditorPill(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) Color(0xFF5B5BD6) else Color(0xFF2A2A2A),
            ),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun EditorStepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
        EditorPill("−") { onMinus() }
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
        EditorPill("+") { onPlus() }
    }
}
