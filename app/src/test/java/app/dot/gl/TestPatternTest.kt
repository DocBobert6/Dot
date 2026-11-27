package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test-pattern diagnostic (TestPattern): a fixed magenta/cyan
 * checkerboard the renderer draws instead of the game frame when
 * the Developer toggle is on. The test pins that the pattern is
 * exactly the size of a game frame, contains only the two
 * unmistakable colours, and is a real checkerboard — so a
 * pattern that shows on device cannot be mistaken for a game
 * frame, and a pattern that does not show cannot be blamed on
 * the pattern itself.
 */
class TestPatternTest {

    @Test
    fun patternIsOneGameFrame() {
        assertEquals(160 * 144, TestPattern.ARGB.size)
        assertEquals(160, TestPattern.WIDTH)
        assertEquals(144, TestPattern.HEIGHT)
    }

    @Test
    fun patternContainsOnlyMagentaAndCyan() {
        val colours = TestPattern.ARGB.toSet()
        assertEquals(setOf(TestPattern.MAGENTA, TestPattern.CYAN), colours)
    }

    @Test
    fun patternIsAnEvenCheckerboard() {
        // Each colour covers exactly half the frame.
        val magenta = TestPattern.ARGB.count { it == TestPattern.MAGENTA }
        val cyan = TestPattern.ARGB.count { it == TestPattern.CYAN }
        assertEquals(160 * 144 / 2, magenta)
        assertEquals(160 * 144 / 2, cyan)
        // Cell corners: (0,0) magenta, one cell over cyan in both
        // directions, one cell diagonally magenta again.
        assertEquals(TestPattern.MAGENTA, TestPattern.ARGB[0])
        assertEquals(TestPattern.CYAN, TestPattern.ARGB[TestPattern.CELL])
        assertEquals(
            TestPattern.CYAN,
            TestPattern.ARGB[TestPattern.CELL * TestPattern.WIDTH],
        )
        assertEquals(
            TestPattern.MAGENTA,
            TestPattern.ARGB[TestPattern.CELL * TestPattern.WIDTH + TestPattern.CELL],
        )
        // Bottom-right corner: (159/8 + 143/8) = 19 + 17 = 36, even.
        assertEquals(TestPattern.MAGENTA, TestPattern.ARGB.last())
    }

    @Test
    fun patternColoursAreOpaqueAndUnmistakable() {
        // Both colours are fully opaque, and neither is a game
        // colour: the core's DMG palette is greys/greens (equal
        // channels), while magenta and cyan have one channel at
        // zero and two at full.
        for (pixel in TestPattern.ARGB) {
            assertEquals(0xFF, (pixel ushr 24) and 0xFF)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue((r == 0xFF && g == 0 && b == 0xFF) ||
                (r == 0 && g == 0xFF && b == 0xFF))
        }
    }
}
