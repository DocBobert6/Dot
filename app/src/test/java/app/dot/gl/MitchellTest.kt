package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Mitchell-Netravali downscale kernel, B = C = 1/3 (GL-CHAIN-SPEC
 * §6). The kernel DOES dip below zero — the spec's "no negative
 * lobes" claim is wrong; the test pins the real values so nobody
 * "fixes" the kernel by clamping.
 */
class MitchellTest {

    @Test
    fun kernelCentre() {
        // w(0) = (16/3)/6 = 8/9.
        assertEquals(8f / 9f, ResampleMath.mitchell(0f), 1e-6f)
    }

    @Test
    fun kernelAtOne() {
        // w(1) = (7 - 12 + 16/3)/6 = 1/18.
        assertEquals(1f / 18f, ResampleMath.mitchell(1f), 1e-6f)
    }

    @Test
    fun kernelNegativeLobe() {
        // w(1.25) ~ -0.023, w(1.5) ~ -0.035 — the negative lobe is
        // real and must NOT be clamped away.
        assertEquals(-0.02344f, ResampleMath.mitchell(1.25f), 1e-4f)
        assertEquals(-0.03472f, ResampleMath.mitchell(1.5f), 1e-4f)
        assertTrue(ResampleMath.mitchell(1.25f) < 0f)
        assertTrue(ResampleMath.mitchell(1.5f) < 0f)
        // The lobe is bounded below: w(t) >= -0.04 for t in [1, 2]
        // (the most negative value is w(1.5) ~ -0.035). The centre
        // (t < 1) is large and positive, so only the lower bound is
        // pinned here.
        for (i in 100..200) {
            val t = i * 0.01f
            assertTrue("w($t) should be >= -0.04", ResampleMath.mitchell(t) >= -0.04f - 1e-6f)
        }
    }

    @Test
    fun kernelSymmetricAndCompact() {
        assertEquals(ResampleMath.mitchell(0.5f), ResampleMath.mitchell(-0.5f), 1e-6f)
        assertEquals(ResampleMath.mitchell(1.5f), ResampleMath.mitchell(-1.5f), 1e-6f)
        assertEquals(0f, ResampleMath.mitchell(2f), 1e-6f)
        assertEquals(0f, ResampleMath.mitchell(3f), 1e-6f)
        assertEquals(0f, ResampleMath.mitchell(-2.5f), 1e-6f)
    }

    @Test
    fun weightsSumToOne() {
        // 8 taps at s = 2, normalised per output pixel.
        val w = ResampleMath.mitchellWeights(100.5f, 2f, 480)
        assertEquals(8, w.size)
        val sum = w.sumOf { it.second.toDouble() }
        assertEquals(1.0, sum, 1e-5)
    }

    @Test
    fun weightsContainNegativeLobe() {
        val w = ResampleMath.mitchellWeights(100.5f, 2f, 480)
        assertTrue("Mitchell weights must include the negative lobe",
            w.any { it.second < 0f })
        // The centre tap is the largest.
        val centre = w.maxByOrNull { it.second }!!
        assertEquals(100, centre.first)
    }

    @Test
    fun weightsClampAtEdges() {
        // At the left edge the out-of-range taps clamp to texel 0,
        // and the weights still sum to 1.
        val w = ResampleMath.mitchellWeights(0.5f, 2f, 480)
        assertEquals(8, w.size)
        assertTrue(w.all { it.first in 0 until 480 })
        val sum = w.sumOf { it.second.toDouble() }
        assertEquals(1.0, sum, 1e-5)
        // Texel 0 is hit by several clamped taps.
        assertTrue(w.count { it.first == 0 } >= 2)
    }

    @Test
    fun weightsAreShiftInvariant() {
        // Shifting the centre by one input texel shifts the tap
        // indices by one, with the same weights.
        val a = ResampleMath.mitchellWeights(100.5f, 2f, 480)
        val b = ResampleMath.mitchellWeights(101.5f, 2f, 480)
        for (i in a.indices) {
            assertEquals(a[i].first + 1, b[i].first)
            assertEquals(a[i].second, b[i].second, 1e-6f)
        }
    }
}
