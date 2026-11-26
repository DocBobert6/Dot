package app.dot.gl

// Dot — the pure draw-decision state for the GL renderer (no GL
// calls, no Android imports, so plain JUnit can drive it).
//
// Owns lastHash, so a charcoal draw can never poison it: the
// Phase 1.2 black screen on device was a frame that drew charcoal
// on a 0-sized surface, had its hash consumed anyway, and then
// every later frame — a static title screen, all with the identical
// hash — was skipped forever, freezing the screen on charcoal.
// The rule this class enforces: only a frame that actually drew
// consumes its hash, and a real surface size arriving resets the
// hash so a frame that only ever drew charcoal draws for real.
// Copyright (c) 2026 Dot contributors. All rights reserved.

/** Why a frame cleared charcoal instead of drawing. */
enum class CharcoalReason {
    /** The surface has no size yet (startup, before it is laid out). */
    Surface,
    /** A game area computed to zero. */
    Area,
    /** The failure latch is set — the chain itself failed. */
    Latch,
}

internal class DrawDecision {
    private var lastHash: Long = -1L

    /** A fresh GL context means no frame has drawn yet. */
    fun reset() {
        lastHash = -1L
    }

    /**
     * A real size arriving resets the hash: a frame that only ever
     * drew charcoal (surface 0-sized at startup) must draw for
     * real once the surface exists, even though its hash is the
     * same as the charcoal frame's.
     */
    fun onSurfaceSized(width: Int, height: Int) {
        if (width > 0 && height > 0) lastHash = -1L
    }

    /**
     * The pure charcoal decision, from the surface size and the two
     * game areas. Null means the frame can draw.
     */
    fun charcoalReason(
        surfaceW: Int,
        surfaceH: Int,
        internalArea: Pair<Int, Int>,
        onScreenArea: Pair<Int, Int>,
    ): CharcoalReason? {
        if (surfaceW <= 0 || surfaceH <= 0) return CharcoalReason.Surface
        if (internalArea.first <= 0 || internalArea.second <= 0 ||
            onScreenArea.first <= 0 || onScreenArea.second <= 0
        ) {
            return CharcoalReason.Area
        }
        return null
    }

    /** True when this frame needs a real draw (its hash differs from the last drawn frame's). */
    fun needsDraw(frameHash: Long): Boolean = frameHash != lastHash

    /** Call only after the frame actually drew — never on a charcoal outcome. */
    fun markDrawn(frameHash: Long) {
        lastHash = frameHash
    }
}
