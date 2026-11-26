package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Sharp-bilinear final resample (GL-CHAIN-SPEC §5). The warp maps
 * flat texel interiors to their centres (no blend) and confines
 * blending to 1 screen px at texel edges.
 */
class SharpBilinearTest {

    @Test
    fun dotScales() {
        // Sharp-bilinear input is the 480-wide ScaleFX output.
        assertEquals(2, ResampleMath.sharpBilinearScale(480, 1200)) // 1080p, 2.5x
        assertEquals(5, ResampleMath.sharpBilinearScale(480, 2400)) // 4K, 5x
        assertEquals(3, ResampleMath.sharpBilinearScale(480, 1600)) // 1440p, 3.333x
        assertEquals(1, ResampleMath.sharpBilinearScale(480, 480))
        assertEquals(1, ResampleMath.sharpBilinearScale(480, 240)) // never below 1
    }

    @Test
    fun integerScaleIsNearest() {
        assertTrue(ResampleMath.sharpBilinearIsNearest(480, 2400)) // 5x exact
        assertTrue(!ResampleMath.sharpBilinearIsNearest(480, 1200)) // 2.5x
        assertTrue(!ResampleMath.sharpBilinearIsNearest(480, 1600)) // 3.333x
    }

    @Test
    fun integerScaleMapsEveryCentreToTexelCentre() {
        // At s = 5, every output-pixel centre lands on a texel centre.
        for (i in 0 until 5) {
            val p = (i + 0.5f) / 5f
            assertEquals(0.5f, ResampleMath.sharpBilinearSample(p, 5), 1e-6f)
        }
        // And at s = 2 (an integer scale), the same holds.
        for (i in 0 until 2) {
            val p = (i + 0.5f) / 2f
            assertEquals(0.5f, ResampleMath.sharpBilinearSample(p, 2), 1e-6f)
        }
    }

    @Test
    fun flatInteriorSamplesAtCentre() {
        // p = 0.5 is a texel centre: no warp at any scale.
        assertEquals(0.5f, ResampleMath.sharpBilinearSample(0.5f, 2), 1e-6f)
        assertEquals(0.5f, ResampleMath.sharpBilinearSample(0.5f, 5), 1e-6f)
        assertEquals(1.5f, ResampleMath.sharpBilinearSample(1.5f, 2), 1e-6f)
    }

    @Test
    fun edgeBlendIsConfined() {
        // At s = 2, an output centre at p = 0.2 (near the texel edge)
        // warps to 0.4 — pulled toward the edge but still inside the
        // texel, so the hardware bilinear tap blends at most 1 px.
        assertEquals(0.4f, ResampleMath.sharpBilinearSample(0.2f, 2), 1e-6f)
        // At the texel boundary p = 0.9 the sample moves to 0.8.
        assertEquals(0.8f, ResampleMath.sharpBilinearSample(0.9f, 2), 1e-6f)
    }

    @Test
    fun warpNeverLeavesTheTexel() {
        // For every output centre in a 2.5x scale, the warped sample
        // stays inside the texel the centre belongs to.
        for (i in 0 until 25) {
            val p = (i + 0.5f) / 2.5f
            val w = ResampleMath.sharpBilinearSample(p, 2)
            val texel = kotlin.math.floor(p)
            assertTrue("warped $w for p=$p should stay in texel $texel",
                w >= texel && w < texel + 1f)
        }
    }
}
