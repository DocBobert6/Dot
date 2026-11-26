package app.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Dot — the decided skin (GB-PLAN §2, §7.8): silver and black,
// bright and sharp. Silver faceplate chrome, near-black charcoal
// base, white text, dot-matrix green ONLY as a tiny power-light
// accent. No indigo, no blue anywhere — that is Atomic's skin.
// Copyright (c) 2026 Dot contributors. All rights reserved.

object DotSkin {
    /** Near-black charcoal base (player, error screens). */
    val Charcoal = Color(0xFF141414)

    /** Charcoal, one step up (library, dialogs, wells). */
    val CharcoalDeep = Color(0xFF1A1A1A)

    /** Dark control bodies on the charcoal base. */
    val ControlDark = Color(0xFF2A2A2A)

    /** Muted steel (utility controls, secondary text on silver). */
    val Steel = Color(0xFF8A8A8A)

    /** Silver faceplate chrome. */
    val Silver = Color(0xFFC0C0C0)

    /** Silver, one step up (pressed chrome, containers). */
    val SilverLight = Color(0xFFD8D8D8)

    /** Silver highlight (pressed D-pad arm). */
    val SilverBright = Color(0xFFE8E8E8)

    /** White text on charcoal. */
    val TextWhite = Color(0xFFFFFFFF)

    /** Muted text on charcoal. */
    val TextMuted = Color(0xFFCCCCCC)

    /** Dark text on silver. */
    val TextOnSilver = Color(0xFF1A1A1A)

    /** Muted text on silver. */
    val TextMutedOnSilver = Color(0xFF4A4A4A)

    /**
     * Dot-matrix green — the DMG LCD green — used ONLY as a tiny
     * power-light accent. Never a fill, never a button, never
     * text. The green belongs to the old LCD; the app is the
     * bright screen that replaced it.
     */
    val PowerGreen = Color(0xFF8BAC0F)
}

/**
 * The power light: one tiny dot-matrix-green dot. The only green
 * in the app.
 */
@Composable
fun PowerLight(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(DotSkin.PowerGreen),
    )
}

/**
 * Dot's own mark: "DOT" in a 5x7 dot-matrix face, drawn as round
 * dots — the boot gesture reimagined, no Nintendo trademark.
 * [dotPx] is the dot diameter in pixels on the canvas; the mark
 * is 3 letters plus spacing, 17 dots wide, 7 dots tall.
 */
@Composable
fun DotMatrixMark(
    modifier: Modifier = Modifier,
    dotPx: Float = 14f,
    gapPx: Float = 6f,
    color: Color = DotSkin.Silver,
) {
    Canvas(modifier) {
        val step = dotPx + gapPx
        val w = 17 * step - gapPx
        val h = 7 * step - gapPx
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f
        for (row in 0 until 7) {
            for (col in 0 until 17) {
                if (!DOT_MATRIX[row][col]) continue
                drawCircle(
                    color = color,
                    radius = dotPx / 2f,
                    center = Offset(
                        ox + col * step + dotPx / 2f,
                        oy + row * step + dotPx / 2f,
                    ),
                )
            }
        }
    }
}

/** 5x7 D, O, T with one blank column between letters (17 wide). */
private val DOT_MATRIX: Array<BooleanArray> = arrayOf(
    booleanArrayOf(
        true, true, true, true, false, false,
        false, true, true, true, false, false,
        true, true, true, true, true,
    ),
    booleanArrayOf(
        true, false, false, false, true, false,
        true, false, false, false, true, false,
        false, false, true, false, false,
    ),
    booleanArrayOf(
        true, false, false, false, true, false,
        true, false, false, false, true, false,
        false, false, true, false, false,
    ),
    booleanArrayOf(
        true, false, false, false, true, false,
        true, false, false, false, true, false,
        false, false, true, false, false,
    ),
    booleanArrayOf(
        true, false, false, false, true, false,
        true, false, false, false, true, false,
        false, false, true, false, false,
    ),
    booleanArrayOf(
        true, false, false, false, true, false,
        true, false, false, false, true, false,
        false, false, true, false, false,
    ),
    booleanArrayOf(
        true, true, true, true, false, false,
        false, true, true, true, false, false,
        false, false, true, false, false,
    ),
)
