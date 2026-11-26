package app.dot

import kotlin.math.abs

// Dot — the single-cross D-pad direction math (SHELL-SPEC §4.3).
// Pure: the overlay feeds pointer offsets, this returns the
// direction mask. The rule: a pointer counts from the cross centre;
// the centre 22% of the side is dead; the direction is the dominant
// axis, and a diagonal counts when the minor component is at least
// 35% of the major component. Multi-pointer is an OR of the
// per-pointer directions.
// Copyright (c) 2026 Dot contributors. All rights reserved.

object DpadMath {

    /** The centre 22% of the side is dead (fraction of the half-side). */
    const val DEAD_ZONE = 0.22f

    /** A diagonal counts when the minor component is at least 35% of the major. */
    const val DIAGONAL_RATIO = 0.35f

    /**
     * The direction mask for one pointer at [dx]/[dy] from the cross
     * centre, in px, with [halfSidePx] the cross's half-side in px
     * (so the pointer is inside the cross when |dx|,|dy| <= halfSidePx).
     * Y grows downwards on screen: dy < 0 is Up.
     */
    fun directionMask(dx: Float, dy: Float, halfSidePx: Float): Int {
        if (halfSidePx <= 0f) return 0
        val nx = dx / halfSidePx
        val ny = dy / halfSidePx
        val ax = abs(nx)
        val ay = abs(ny)
        val major = maxOf(ax, ay)
        if (major <= DEAD_ZONE) return 0
        val minor = minOf(ax, ay)
        var mask = 0
        if (minor >= DIAGONAL_RATIO * major) {
            // Diagonal: both axes count.
            if (ny < 0f) mask = mask or Bridge.Input.UP else mask = mask or Bridge.Input.DOWN
            if (nx < 0f) mask = mask or Bridge.Input.LEFT else mask = mask or Bridge.Input.RIGHT
        } else if (ax >= ay) {
            if (nx < 0f) mask = mask or Bridge.Input.LEFT else mask = mask or Bridge.Input.RIGHT
        } else {
            if (ny < 0f) mask = mask or Bridge.Input.UP else mask = mask or Bridge.Input.DOWN
        }
        return mask
    }

    /** The merged direction mask for several pointers (an OR). */
    fun mergedMask(pointers: Iterable<Triple<Float, Float, Float>>): Int {
        var mask = 0
        for ((dx, dy, half) in pointers) {
            mask = mask or directionMask(dx, dy, half)
        }
        return mask
    }
}
