package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deband detector + ramp (GL-CHAIN-SPEC §4.3). The detector runs on
 * the shade plane; the ramp lerps in linear light between the first
 * and last band colours at the texel's fractional position.
 */
class DebandingTest {
    private val W = ShadePlane.WIDTH // 160
    private val H = ShadePlane.HEIGHT // 144

    /** ARGB grey for palette shade s (PALETTE_SRGB = 255/180/90/0). */
    private fun grey(s: Int): Int {
        val g = LinearLight.PALETTE_SRGB[s]
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    /** A frame of horizontal bands: rows [r0, r1) shade s, etc. */
    private fun horizontalBands(vararg bands: Pair<IntRange, Int>): IntArray {
        val argb = IntArray(W * H)
        for ((rows, s) in bands) {
            for (y in rows) {
                for (x in 0 until W) argb[y * W + x] = grey(s)
            }
        }
        return argb
    }

    /** A frame of vertical bands: columns [c0, c1) shade s, etc. */
    private fun verticalBands(vararg bands: Pair<IntRange, Int>): IntArray {
        val argb = IntArray(W * H)
        for ((cols, s) in bands) {
            for (y in 0 until H) {
                for (x in cols) argb[y * W + x] = grey(s)
            }
        }
        return argb
    }

    @Test
    fun verticalGradientDetected() {
        val argb = horizontalBands(
            0 until 36 to 0, 36 until 72 to 1, 72 until 108 to 2, 108 until 144 to 3
        )
        val regions = Deband.detect(ShadePlane.fromArgb(argb))
        assertEquals(1, regions.size)
        val r = regions[0]
        assertTrue(r.vertical)
        assertEquals(0, r.startShade)
        assertEquals(3, r.endShade)
        assertEquals(17, r.startPos) // centre of rows 0-35
        assertEquals(125, r.endPos) // centre of rows 108-143
        assertEquals(0, r.x0)
        assertEquals(W - 1, r.x1)
    }

    @Test
    fun verticalRampIsSmooth() {
        val argb = horizontalBands(
            0 until 36 to 0, 36 until 72 to 1, 72 until 108 to 2, 108 until 144 to 3
        )
        val out = Deband.deband(argb)
        // Start of the ramp: shade 0 (255). Middle (t = 0.5): the
        // linear-light lerp between 255 and 0 -> 188. End: shade 3 (0).
        assertEquals(255, (out[17 * W + 80] shr 16) and 0xFF)
        assertEquals(188, (out[71 * W + 80] shr 16) and 0xFF)
        assertEquals(0, (out[125 * W + 80] shr 16) and 0xFF)
        // Monotone down the ramp.
        var prev = 256
        for (y in 17..125) {
            val g = (out[y * W + 80] shr 16) and 0xFF
            assertTrue("grey at row $y ($g) should not rise above $prev", g <= prev)
            prev = g
        }
    }

    @Test
    fun horizontalGradientDetected() {
        val argb = verticalBands(
            0 until 40 to 0, 40 until 80 to 1, 80 until 120 to 2, 120 until 160 to 3
        )
        val regions = Deband.detect(ShadePlane.fromArgb(argb))
        assertEquals(1, regions.size)
        val r = regions[0]
        assertTrue(!r.vertical)
        assertEquals(0, r.startShade)
        assertEquals(3, r.endShade)
        assertEquals(19, r.startPos) // centre of cols 0-39
        assertEquals(139, r.endPos) // centre of cols 120-159
    }

    @Test
    fun horizontalRampIsSmooth() {
        val argb = verticalBands(
            0 until 40 to 0, 40 until 80 to 1, 80 until 120 to 2, 120 until 160 to 3
        )
        val out = Deband.deband(argb)
        assertEquals(255, (out[72 * W + 19] shr 16) and 0xFF)
        assertEquals(188, (out[72 * W + 79] shr 16) and 0xFF)
        assertEquals(0, (out[72 * W + 139] shr 16) and 0xFF)
    }

    @Test
    fun reversedGradientDetected() {
        val argb = horizontalBands(
            0 until 36 to 3, 36 until 72 to 2, 72 until 108 to 1, 108 until 144 to 0
        )
        val regions = Deband.detect(ShadePlane.fromArgb(argb))
        assertEquals(1, regions.size)
        assertEquals(3, regions[0].startShade)
        assertEquals(0, regions[0].endShade)
    }

    @Test
    fun threeBandPartialGradientDetected() {
        // MIN_BANDS is 3 (the spec's >= 5 is unreachable with 4
        // shades — see Deband.MIN_BANDS); a 0->1->2 gradient counts.
        val argb = horizontalBands(
            0 until 48 to 0, 48 until 96 to 1, 96 until 144 to 2
        )
        val regions = Deband.detect(ShadePlane.fromArgb(argb))
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].startShade)
        assertEquals(2, regions[0].endShade)
    }

    @Test
    fun twoBandGradientNotDetected() {
        val argb = horizontalBands(0 until 72 to 0, 72 until 144 to 1)
        assertEquals(0, Deband.detect(ShadePlane.fromArgb(argb)).size)
    }

    @Test
    fun solidFrameNotDetected() {
        val argb = IntArray(W * H) { grey(0) }
        assertEquals(0, Deband.detect(ShadePlane.fromArgb(argb)).size)
        // And deband returns an identical copy.
        assertTrue(Deband.deband(argb).contentEquals(argb))
    }

    @Test
    fun oneTexelBandBreaksSequence() {
        // A 1-texel band of shade 1 splits the gradient: the longest
        // surviving sequence is [2, 3] (2 bands), below MIN_BANDS.
        val argb = horizontalBands(
            0 until 36 to 0, 36 until 37 to 1, 37 until 72 to 2, 72 until 144 to 3
        )
        assertEquals(0, Deband.detect(ShadePlane.fromArgb(argb)).size)
    }

    @Test
    fun checkerFieldNotDetected() {
        // A full-frame 2x2 checker: every band is 1 texel, so no
        // sequence forms (and regionHasChecker would reject it anyway).
        val argb = IntArray(W * H) { i ->
            val x = i % W
            val y = i / W
            grey(if ((x + y) % 2 == 0) 3 else 0)
        }
        assertEquals(0, Deband.detect(ShadePlane.fromArgb(argb)).size)
    }

    @Test
    fun debandLeavesNonGradientAlone() {
        // A frame with a solid block and a checker patch: no region,
        // so deband returns the input unchanged.
        val argb = IntArray(W * H) { grey(0) }
        for (y in 60 until 80) {
            for (x in 60 until 80) argb[y * W + x] = grey(3)
        }
        assertTrue(Deband.deband(argb).contentEquals(argb))
    }
}
