package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1-LSB blue-noise output dither (GL-CHAIN-SPEC §8.6). The dither
 * is exactly +-1/2 LSB: a flat shade moves by at most 1 LSB, so it
 * breaks up banding without adding visible noise.
 */
class OutputDitherTest {

    @Test
    fun ditherMovesByAtMostOneLsb() {
        // A flat 128 with no noise stays 128; with full noise it
        // moves to 129 — exactly 1 LSB.
        assertEquals(128, ResampleMath.outputDither(128f / 255f, 0.0f))
        assertEquals(129, ResampleMath.outputDither(128f / 255f, 1.0f))
        assertEquals(128, ResampleMath.outputDither(128f / 255f, 0.5f))
    }

    @Test
    fun ditherClampsAtTheEnds() {
        assertEquals(0, ResampleMath.outputDither(0.0f, 0.0f))
        assertEquals(0, ResampleMath.outputDither(0.0f, 0.5f))
        assertEquals(255, ResampleMath.outputDither(1.0f, 1.0f))
        assertEquals(255, ResampleMath.outputDither(1.0f, 0.5f))
    }

    @Test
    fun ditherNeverMovesMoreThanOneLsb() {
        for (v in 0..255) {
            val plain = v
            for (n in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val d = ResampleMath.outputDither(v / 255f, n)
                assertTrue("dither of $v at n=$n moved to $d",
                    kotlin.math.abs(d - plain) <= 1)
            }
        }
    }

    @Test
    fun blueNoiseTableWraps() {
        val v = ResampleMath.blueNoiseAt(0, 0)
        assertTrue(v in 0..255)
        assertEquals(v, ResampleMath.blueNoiseAt(64, 0))
        assertEquals(v, ResampleMath.blueNoiseAt(0, 64))
        assertEquals(ResampleMath.blueNoiseAt(1, 1), ResampleMath.blueNoiseAt(65, 65))
        // The table is not constant: neighbouring texels differ.
        var different = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                if (ResampleMath.blueNoiseAt(x, y) != ResampleMath.blueNoiseAt(x + 1, y)) different++
            }
        }
        assertTrue(different > 1000)
    }

    @Test
    fun blueNoiseOffset() {
        assertEquals(0 to 0, ResampleMath.blueNoiseOffset(0))
        assertEquals(37 to 23, ResampleMath.blueNoiseOffset(1))
        assertEquals(10 to 46, ResampleMath.blueNoiseOffset(2)) // 74 % 64 = 10
        // The offset always stays inside the 64x64 table.
        for (f in 0 until 200) {
            val (ox, oy) = ResampleMath.blueNoiseOffset(f)
            assertTrue(ox in 0 until 64)
            assertTrue(oy in 0 until 64)
        }
    }
}
