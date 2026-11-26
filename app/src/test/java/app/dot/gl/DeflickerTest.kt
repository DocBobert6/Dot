package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gated flicker-transparency CPU gate (GL-CHAIN-SPEC §7): the
 * GBI rule only — blend n with n-1 where n == n-2 and n != n-1,
 * sustained >= 2 alternations, decaying back over 3 frames when the
 * alternation stops.
 */
class DeflickerTest {
    private val SIZE = ShadePlane.SIZE

    /** A plane that is shade [s] everywhere except [region] (shade [rs]). */
    private fun plane(s: Int, region: IntRange = IntRange.EMPTY, rs: Int = 3): ByteArray {
        val p = ByteArray(SIZE) { s.toByte() }
        for (i in region) p[i] = rs.toByte()
        return p
    }

    @Test
    fun firstTwoFramesAreZero() {
        val gate = DeflickerGate()
        val a = plane(0)
        val b = plane(0, 0 until 100, 3)
        assertTrue(gate.update(a).all { it == 0.toByte() })
        assertTrue(gate.update(b).all { it == 0.toByte() })
    }

    @Test
    fun staticFrameNeverBlends() {
        val gate = DeflickerGate()
        val a = plane(0)
        repeat(6) { gate.update(a) }
        assertTrue(gate.update(a).all { it == 0.toByte() })
    }

    @Test
    fun alternationBlendsAfterTwo() {
        val gate = DeflickerGate()
        val a = plane(0)
        val b = plane(0, 0 until 100, 3) // left strip alternates 0/3
        gate.update(a) // frame 1: no history
        gate.update(b) // frame 2: no history
        // Frame 3: first alternation (sustain 1) — below threshold.
        assertTrue(gate.update(a).all { it == 0.toByte() })
        // Frame 4: second alternation (sustain 2) — full blend.
        val mask = gate.update(b)
        for (i in 0 until 100) assertEquals(255.toByte(), mask[i])
        for (i in 100 until SIZE) assertEquals(0.toByte(), mask[i])
    }

    @Test
    fun blendDecaysOverThreeFrames() {
        val gate = DeflickerGate()
        val a = plane(0)
        val b = plane(0, 0 until 100, 3)
        gate.update(a)
        gate.update(b)
        gate.update(a)
        gate.update(b) // full blend now
        // The alternation stops (b repeats): decay 255 -> 170 -> 85 -> 0.
        assertEquals(170.toByte(), gate.update(b)[0])
        assertEquals(85.toByte(), gate.update(b)[0])
        assertEquals(0.toByte(), gate.update(b)[0])
        assertTrue(gate.update(b).all { it == 0.toByte() })
    }

    @Test
    fun singleAlternationDoesNotBlend() {
        // One alternation and back to static: sustain never reaches 2.
        val gate = DeflickerGate()
        val a = plane(0)
        val b = plane(0, 0 until 100, 3)
        gate.update(a)
        gate.update(b)
        gate.update(a) // one alternation
        assertTrue(gate.update(a).all { it == 0.toByte() })
        assertTrue(gate.update(a).all { it == 0.toByte() })
    }

    @Test
    fun resetClearsState() {
        val gate = DeflickerGate()
        val a = plane(0)
        val b = plane(0, 0 until 100, 3)
        gate.update(a)
        gate.update(b)
        gate.update(a)
        gate.update(b) // full blend
        gate.reset()
        // After a reset the next two frames have no history again.
        assertTrue(gate.update(a).all { it == 0.toByte() })
        assertTrue(gate.update(b).all { it == 0.toByte() })
        assertTrue(gate.update(a).all { it == 0.toByte() })
    }

    @Test
    fun wrongSizeRejected() {
        val gate = DeflickerGate()
        try {
            gate.update(ByteArray(10))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }
}
