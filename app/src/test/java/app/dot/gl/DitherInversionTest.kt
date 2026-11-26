package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GL-CHAIN-SPEC §10.1: DitherInversionTest — the extended-dictionary
 * classifier (the Kotlin oracle mirroring dither-invert.frag).
 *
 * One fixture per family passing all four gates, plus one per family
 * failing exactly one gate; the brick-wall / stipple guard; and the
 * 50/50 worked examples (0+3 -> 188, 1+2 -> 144).
 *
 * Fixture conventions: the test cell is at (72,72) (interior, so all
 * +/-16/+/-64 baseline samples are in bounds). Shades: A is the
 * darker (higher index), B the lighter. The palette greens are
 * 251/170/81/0 for shades 0/1/2/3.
 */
class DitherInversionTest {

    private val G = intArrayOf(251, 170, 81, 0)
    private val W = 160

    private fun blank(shade: Int): IntArray = IntArray(DitherClassifier.SIZE) { G[shade] }

    private fun setShade(p: IntArray, x: Int, y: Int, shade: Int) {
        p[y * W + x] = G[shade]
    }

    private fun setGreen(p: IntArray, x: Int, y: Int, green: Int) {
        p[y * W + x] = green
    }

    /** A 2x2 cell, row-major (s00 top-left, s11 bottom-right). */
    private fun setCell2x2(p: IntArray, x: Int, y: Int, s00: Int, s10: Int, s01: Int, s11: Int) {
        setShade(p, x, y, s00)
        setShade(p, x + 1, y, s10)
        setShade(p, x, y + 1, s01)
        setShade(p, x + 1, y + 1, s11)
    }

    /** A global checker (alternating by (x+y)%2) of shades a/b over a rect. */
    private fun stampChecker(p: IntArray, x0: Int, y0: Int, w: Int, h: Int, a: Int, b: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                setShade(p, x, y, if ((x + y) % 2 == 0) a else b)
            }
        }
    }

    /** A repeating 2x3 checker tile (period 2 in x, 3 in y). */
    private fun stampTile2x3(p: IntArray, x0: Int, y0: Int, w: Int, h: Int, a: Int, b: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val dx = (x - x0) % 2
                val dy = (y - y0) % 3
                setShade(p, x, y, if ((dx + dy) % 2 == 0) a else b)
            }
        }
    }

    /** A repeating 3x2 checker tile (period 3 in x, 2 in y). */
    private fun stampTile3x2(p: IntArray, x0: Int, y0: Int, w: Int, h: Int, a: Int, b: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val dx = (x - x0) % 3
                val dy = (y - y0) % 2
                setShade(p, x, y, if ((dx + dy) % 2 == 0) a else b)
            }
        }
    }

    /** A repeating 3x3 checker tile (period 3 in both axes). */
    private fun stampTile3x3(p: IntArray, x0: Int, y0: Int, w: Int, h: Int, a: Int, b: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val dx = (x - x0) % 3
                val dy = (y - y0) % 3
                setShade(p, x, y, if ((dx + dy) % 2 == 0) a else b)
            }
        }
    }

    /**
     * A repeating Bayer 4x4 tile at density k/16 (k = 4, 8, or 12),
     * dark shade a, light shade b. The dark positions are the
     * bitmask indices (row-major i = y*4+x).
     */
    private fun stampBayer4x4(p: IntArray, x0: Int, y0: Int, w: Int, h: Int, a: Int, b: Int, k: Int) {
        val mask = when (k) {
            4 -> DitherClassifier.BAYER4_MASK_25
            8 -> DitherClassifier.BAYER4_MASK_50
            else -> DitherClassifier.BAYER4_MASK_75
        }
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val dx = (x - x0) % 4
                val dy = (y - y0) % 4
                val i = dy * 4 + dx
                val dark = (mask and (1 shl i)) != 0
                setShade(p, x, y, if (dark) a else b)
            }
        }
    }

    /**
     * Baseline samples that PASS the gate: the 8 sample cells vote
     * densities {1.0, 0.0, 0.5} (3 distinct, span 1.0).
     */
    private fun passingBaseline(p: IntArray, ox: Int, oy: Int, a: Int, b: Int) {
        setCell2x2(p, ox + 16, oy, a, a, a, a) // 1.0
        setCell2x2(p, ox - 16, oy, b, b, b, b) // 0.0
        setCell2x2(p, ox, oy + 16, a, b, b, a) // 0.5
        setCell2x2(p, ox, oy - 16, b, b, b, b) // 0.0
        setCell2x2(p, ox + 64, oy, a, a, a, a) // 1.0
        setCell2x2(p, ox - 64, oy, b, b, b, b) // 0.0
        setCell2x2(p, ox, oy + 64, a, a, a, a) // 1.0
        setCell2x2(p, ox, oy - 64, b, b, b, b) // 0.0
    }

    /** Baseline samples that FAIL the gate: every sample votes 1.0. */
    private fun failingBaseline(p: IntArray, ox: Int, oy: Int, a: Int, b: Int) {
        for ((dx, dy) in listOf(
            16 to 0, -16 to 0, 0 to 16, 0 to -16,
            64 to 0, -64 to 0, 0 to 64, 0 to -64
        )) {
            setCell2x2(p, ox + dx, oy + dy, a, a, a, a)
        }
    }

    // ---- 2x2 checker ----

    @Test
    fun checker2x2PassesAllGates() {
        val p = blank(0)
        stampChecker(p, 70, 70, 6, 6, 3, 0)
        passingBaseline(p, 72, 72, 3, 0)
        // 50/50 of 0+3 -> 188.
        assertEquals(188, DitherClassifier.classify(p, 72, 72))
        assertEquals(188, DitherClassifier.classify(p, 73, 73))
    }

    @Test
    fun checker2x2Shades1And2() {
        val p = blank(0)
        stampChecker(p, 70, 70, 6, 6, 2, 1)
        passingBaseline(p, 72, 72, 2, 1)
        // 50/50 of 1+2 -> 144.
        assertEquals(144, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun checker2x2FailsFieldGate() {
        val p = blank(0)
        // Only the centre cell is a checker — no neighbouring checkers.
        setCell2x2(p, 72, 72, 0, 3, 3, 0)
        passingBaseline(p, 72, 72, 3, 0)
        // Unchanged (the test texel is shade 0 = green 251).
        assertEquals(251, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun checker2x2FailsBaselineGate() {
        val p = blank(0)
        stampChecker(p, 70, 70, 6, 6, 3, 0)
        failingBaseline(p, 72, 72, 3, 0)
        // Unchanged: the test texel (73,72) is shade 0 = green 251.
        assertEquals(251, DitherClassifier.classify(p, 73, 72))
    }

    @Test
    fun solidBlockIsNotInverted() {
        // Brick-wall / stipple guard: a solid block is not a dither field.
        val p = blank(0)
        for (y in 70 until 76) for (x in 70 until 76) setShade(p, x, y, 3)
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(0, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun intermediateGreyIsNotInverted() {
        // Palette gate: an already-inverted grey is never inverted twice.
        val p = blank(0)
        for (y in 70 until 76) {
            for (x in 70 until 76) {
                setGreen(p, x, y, if ((x + y) % 2 == 0) 188 else 144)
            }
        }
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(188, DitherClassifier.classify(p, 72, 72))
        assertEquals(144, DitherClassifier.classify(p, 73, 72))
    }

    // ---- Bayer 2x2 ----

    @Test
    fun bayer2x2TwentyFivePercent() {
        val p = blank(0)
        // A 3x3 block of 2x2 cells, each with exactly 1 dark texel
        // (top-left), i.e. 25% dark.
        for (cy in 0 until 3) {
            for (cx in 0 until 3) {
                setCell2x2(p, 70 + cx * 2, 70 + cy * 2, 3, 0, 0, 0)
            }
        }
        passingBaseline(p, 72, 72, 3, 0)
        // 25% dark + 75% light -> 225.
        assertEquals(225, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun bayer2x2SeventyFivePercent() {
        val p = blank(0)
        for (cy in 0 until 3) {
            for (cx in 0 until 3) {
                setCell2x2(p, 70 + cx * 2, 70 + cy * 2, 3, 3, 3, 0)
            }
        }
        passingBaseline(p, 72, 72, 3, 0)
        // 75% dark + 25% light -> 137.
        assertEquals(137, DitherClassifier.classify(p, 72, 72))
    }

    // ---- checker 2x3 / 3x2 (tested at the seam, where 2x2 fails) ----

    @Test
    fun checker2x3PassesAtSeam() {
        val p = blank(0)
        // A 2x3 field (period 2 in x, 3 in y). The test texel sits in
        // the last row of a 2x3 tile (row 74 of the tile at y=72),
        // where the overlapping 2x2 cell spans the tile seam and is
        // not a checker — so the 2x3 branch is the one that fires.
        stampTile2x3(p, 66, 69, 12, 12, 3, 0)
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(188, DitherClassifier.classify(p, 72, 74))
    }

    @Test
    fun checker3x2PassesAtSeam() {
        val p = blank(0)
        // Transposed: the test texel sits in the last column of a
        // 3x2 tile (col 74 of the tile at x=72).
        stampTile3x2(p, 69, 66, 12, 12, 3, 0)
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(188, DitherClassifier.classify(p, 74, 72))
    }

    // ---- checker 3x3 ----

    @Test
    fun checker3x3Passes() {
        val p = blank(0)
        stampTile3x3(p, 66, 66, 12, 12, 3, 0)
        passingBaseline(p, 72, 72, 3, 0)
        // The centre tile has 5 dark (corners + centre) of 9 -> 5/9.
        // lin = (5/9)*0 + (4/9)*1.0 = 0.4444 -> encode -> 178.
        assertEquals(178, DitherClassifier.classify(p, 72, 72))
    }

    // ---- Bayer 4x4 ----

    @Test
    fun bayer4x4TwentyFivePercent() {
        val p = blank(0)
        stampBayer4x4(p, 64, 64, 16, 16, 3, 0, 4)
        passingBaseline(p, 72, 72, 3, 0)
        // 25% dark -> 225.
        assertEquals(225, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun bayer4x4FiftyPercent() {
        val p = blank(0)
        stampBayer4x4(p, 64, 64, 16, 16, 3, 0, 8)
        passingBaseline(p, 72, 72, 3, 0)
        // 50% dark -> 188.
        assertEquals(188, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun bayer4x4SeventyFivePercent() {
        val p = blank(0)
        stampBayer4x4(p, 64, 64, 16, 16, 3, 0, 12)
        passingBaseline(p, 72, 72, 3, 0)
        // 75% dark -> 137.
        assertEquals(137, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun bayer4x4PhaseShiftedDoesNotMatch() {
        // Strict phase 0: the 4x4 matcher itself returns null for a
        // field shifted by 1 texel (the 2x2 families cover those
        // cases — classify still inverts it, via Bayer 2x2 at 25%).
        val p = blank(0)
        stampBayer4x4(p, 65, 65, 16, 16, 3, 0, 4)
        assertEquals(null, DitherClassifier.matchBayer4x4(p, 72, 72))
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(225, DitherClassifier.classify(p, 72, 72))
    }

    // ---- vertical / horizontal lines ----

    @Test
    fun verticalLinesPass() {
        val p = blank(0)
        // Columns alternate 3/0 over >= 4 columns, each solid >= 2 rows.
        for (x in 68 until 76) {
            for (y in 68 until 76) {
                setShade(p, x, y, if (x % 2 == 0) 3 else 0)
            }
        }
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(188, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun horizontalLinesPass() {
        val p = blank(0)
        for (y in 68 until 76) {
            for (x in 68 until 76) {
                setShade(p, x, y, if (y % 2 == 0) 3 else 0)
            }
        }
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(188, DitherClassifier.classify(p, 72, 72))
    }

    @Test
    fun shortLineRunDoesNotMatch() {
        // Only 3 alternating columns — under the >= 4 run.
        val p = blank(0)
        for (x in 70 until 73) {
            for (y in 68 until 76) {
                setShade(p, x, y, if (x % 2 == 0) 3 else 0)
            }
        }
        passingBaseline(p, 72, 72, 3, 0)
        val original = p[72 * W + 72]
        assertEquals(original, DitherClassifier.classify(p, 72, 72))
    }

    // ---- diagonal staircase ----

    @Test
    fun diagonalStaircasePasses() {
        val p = blank(0)
        // A true diagonal staircase: 2x2 checker blocks along the
        // (1,1) diagonal, the top-left shade alternating each step
        // (the phase flips every row). A 1-block-wide diagonal has
        // only 2 checker neighbours, so the 2x2 field gate fails and
        // the diagonal run is the evidence.
        for (i in -4..4) {
            val bx = 72 + i * 2
            val by = 72 + i * 2
            // Top-left alternates: even i -> dark, odd i -> light.
            val tl = if (i % 2 == 0) 3 else 0
            setCell2x2(p, bx, by, tl, 3 - tl, 3 - tl, tl)
        }
        passingBaseline(p, 72, 72, 3, 0)
        assertEquals(188, DitherClassifier.classify(p, 72, 72))
    }
}
