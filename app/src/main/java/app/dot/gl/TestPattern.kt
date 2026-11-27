package app.dot.gl

// Dot — the test-pattern diagnostic (Phase 1.4). A fixed, bright,
// unmistakable picture for the GL surface: a magenta/cyan
// checkerboard at 8-texel cells over the 160x144 source frame.
//
// This is a DIAGNOSTIC, never a fallback picture: it is only drawn
// when the Developer "Test pattern" toggle is on (default off), and
// it must never ship as a substitute for the game picture. It
// enters the chain at uploadSource — the same uploadSource ->
// drawChain -> screen path as a real frame — so one on-device test
// discriminates: pattern visible means the surface, swap, and
// composition path work and the fault is the chain's output for
// real frames; pattern invisible means the surface path itself is
// broken. The CPU deband step is skipped for the pattern (it would
// re-interpret the pattern's colours as a gradient); the deflicker
// mask still comes from the game frame, as in the real path.
// Copyright (c) 2026 Dot contributors. All rights reserved.

object TestPattern {
    const val WIDTH = 160
    const val HEIGHT = 144

    /** One checkerboard cell, in source texels. */
    const val CELL = 8

    /** Opaque magenta (A=255, R=255, G=0, B=255). */
    val MAGENTA: Int = 0xFFFF00FF.toInt()

    /** Opaque cyan (A=255, R=0, G=255, B=255). */
    val CYAN: Int = 0xFF00FFFF.toInt()

    /**
     * The pattern pixels, row-major, top row first. Shared and
     * read-only: uploadSource only reads it, so no copy is needed
     * per frame.
     */
    val ARGB: IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        if ((x / CELL + y / CELL) % 2 == 0) MAGENTA else CYAN
    }
}
