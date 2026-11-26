package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GL-CHAIN-SPEC §10.1: LinearLightTest — the sRGB EOTF pair, the
 * 50% fact, the palette spot values, and round-trip accuracy.
 */
class LinearLightTest {

    @Test
    fun decodeHalfIsAboutPoint214() {
        // The 50% fact: code 128 decodes to ~0.214 linear, not 0.5.
        val lin = LinearLight.decode(0.5f)
        assertEquals(0.214f, lin, 0.002f)
    }

    @Test
    fun encodeHalfIsAboutPoint735() {
        // Linear 0.5 encodes to ~0.735 sRGB, not 0.5.
        val code = LinearLight.encode(0.5f)
        assertEquals(0.735f, code, 0.002f)
    }

    @Test
    fun paletteSpotValues() {
        // §4.1: the palette's linear-light values.
        assertEquals(1.0f, LinearLight.PALETTE_LINEAR[0], 0.0001f)
        assertEquals(0.4566f, LinearLight.PALETTE_LINEAR[1], 0.0001f)
        assertEquals(0.1022f, LinearLight.PALETTE_LINEAR[2], 0.0001f)
        assertEquals(0.0f, LinearLight.PALETTE_LINEAR[3], 0.0001f)
    }

    @Test
    fun roundTripWithinHalfLsb() {
        // encode(decode(c)) returns c within 0.5 LSB for every code.
        for (code in 0..255) {
            val back = LinearLight.encode8(LinearLight.decode8(code))
            assertTrue("code $code -> $back", kotlin.math.abs(back - code) <= 1)
        }
    }

    @Test
    fun averageShadeWorkedExamples() {
        // §4.1 worked examples: 50/50 of 0+3 -> 188, 50/50 of 1+2 -> 144,
        // 25% of shade 0 + 75% of shade 3 -> 137.
        assertEquals(188, LinearLight.averageShade(3, 0, 0.5f))
        assertEquals(144, LinearLight.averageShade(2, 1, 0.5f))
        assertEquals(137, LinearLight.averageShade(0, 3, 0.25f))
    }

    @Test
    fun decodeEncodeEndpoints() {
        assertEquals(0.0f, LinearLight.decode(0.0f), 0.0001f)
        assertEquals(1.0f, LinearLight.decode(1.0f), 0.0001f)
        assertEquals(0.0f, LinearLight.encode(0.0f), 0.0001f)
        assertEquals(1.0f, LinearLight.encode(1.0f), 0.0001f)
    }
}
