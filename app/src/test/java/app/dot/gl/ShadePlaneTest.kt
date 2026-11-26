package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GL-CHAIN-SPEC §10.1: the shade plane (nearest-shade classification)
 * and the FNV-1a-64 content hash (static-frame reuse gate).
 */
class ShadePlaneTest {

    private fun argb(green: Int): Int =
        (0xFF shl 24) or (green shl 16) or (green shl 8) or green

    @Test
    fun shadeThresholdsAreMidpoints() {
        // Palette greens 251/170/81/0; thresholds are the midpoints.
        assertEquals(0, ShadePlane.shadeOfGreen(251))
        assertEquals(0, ShadePlane.shadeOfGreen(211)) // > 210.5
        assertEquals(1, ShadePlane.shadeOfGreen(210))
        assertEquals(1, ShadePlane.shadeOfGreen(170))
        assertEquals(1, ShadePlane.shadeOfGreen(126)) // > 125.5
        assertEquals(2, ShadePlane.shadeOfGreen(125))
        assertEquals(2, ShadePlane.shadeOfGreen(81))
        assertEquals(2, ShadePlane.shadeOfGreen(41)) // > 40.5
        assertEquals(3, ShadePlane.shadeOfGreen(40))
        assertEquals(3, ShadePlane.shadeOfGreen(0))
    }

    @Test
    fun paletteMembershipEpsilon() {
        assertTrue(ShadePlane.isPaletteMember(251))
        assertTrue(ShadePlane.isPaletteMember(245)) // within 6 of 251
        assertTrue(ShadePlane.isPaletteMember(170))
        assertTrue(ShadePlane.isPaletteMember(176)) // within 6 of 170
        assertTrue(!ShadePlane.isPaletteMember(188)) // an inverted grey, not a member
        assertTrue(!ShadePlane.isPaletteMember(144)) // an inverted grey, not a member
        assertTrue(!ShadePlane.isPaletteMember(128))
    }

    @Test
    fun fromArgbMapsGreenChannel() {
        val argb = IntArray(ShadePlane.SIZE) { argb(251) }
        argb[0] = argb(170)
        argb[1] = argb(81)
        argb[2] = argb(0)
        val plane = ShadePlane.fromArgb(argb)
        assertEquals(1, plane[0].toInt())
        assertEquals(2, plane[1].toInt())
        assertEquals(3, plane[2].toInt())
        assertEquals(0, plane[3].toInt())
    }

    @Test
    fun hashIsStableForIdenticalPlanes() {
        val a = ByteArray(ShadePlane.SIZE) { (it % 4).toByte() }
        val b = a.copyOf()
        assertEquals(FrameHash.fnv1a64(a), FrameHash.fnv1a64(b))
    }

    @Test
    fun oneTexelChangeChangesTheHash() {
        // Discrimination: the hash must notice a single-texel change.
        val a = ByteArray(ShadePlane.SIZE) { (it % 4).toByte() }
        val b = a.copyOf()
        b[12345] = ((b[12345].toInt() + 1) % 4).toByte()
        assertNotEquals(FrameHash.fnv1a64(a), FrameHash.fnv1a64(b))
    }

    @Test
    fun hashRejectsWrongLength() {
        // A hash/length precondition failure fails loudly — never
        // hashes a prefix.
        var threw = false
        try {
            FrameHash.fnv1a64(ByteArray(100))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected IllegalArgumentException for a short buffer", threw)
    }
}
