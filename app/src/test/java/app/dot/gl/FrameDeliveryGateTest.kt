package app.dot.gl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-delivery gate (FrameDeliveryGate): an unchanged frame
 * must not be delivered to the view, so no render request and no
 * buffer swap happens for a static frame. This is the Phase 1.4
 * fix for the on-device flashing: the 1.3 counts showed 361
 * attempts, 45 draws, 316 hash-skips — and GLSurfaceView swaps
 * after every onDrawFrame, including the 316 that drew nothing,
 * so the screen cycled stale buffers.
 *
 * Discrimination: the last test runs the 1.3 delivery rule
 * (deliver every frame) alongside the gate and asserts they
 * differ on the repeated hash — if the gate is ever reverted to
 * always-deliver, that test fails.
 */
class FrameDeliveryGateTest {

    @Test
    fun firstFrameAlwaysDelivers() {
        val gate = FrameDeliveryGate()
        assertTrue(gate.deliverIfChanged(42L))
    }

    @Test
    fun unchangedFrameIsNotDelivered() {
        val gate = FrameDeliveryGate()
        val hash = 12345L
        assertTrue(gate.deliverIfChanged(hash))
        // The identical frame again: no delivery, no render, no swap.
        assertFalse(gate.deliverIfChanged(hash))
        assertFalse(gate.deliverIfChanged(hash))
    }

    @Test
    fun changedFrameDeliversAgain() {
        val gate = FrameDeliveryGate()
        assertTrue(gate.deliverIfChanged(1L))
        assertFalse(gate.deliverIfChanged(1L))
        assertTrue(gate.deliverIfChanged(2L))
        assertFalse(gate.deliverIfChanged(2L))
        assertTrue(gate.deliverIfChanged(1L))
    }

    @Test
    fun invalidateReEnablesDeliveryOfTheSameHash() {
        val gate = FrameDeliveryGate()
        val hash = 777L
        assertTrue(gate.deliverIfChanged(hash))
        assertFalse(gate.deliverIfChanged(hash))
        // A delivered frame that never drew (charcoal), a surface
        // rebuild, or a test-pattern toggle change: the next
        // identical frame must be delivered again.
        gate.invalidate()
        assertTrue(gate.deliverIfChanged(hash))
        assertFalse(gate.deliverIfChanged(hash))
    }

    @Test
    fun skipIsRealWhereLegacyLogicWouldDeliver() {
        // The 1.3 delivery rule, simulated: every frame was posted
        // to the view with no hash check before the render request,
        // so an unchanged frame still caused an onDrawFrame and a
        // swap. The gate must differ from it on the repeated hash.
        fun legacyDeliver(@Suppress("UNUSED_PARAMETER") hash: Long): Boolean = true
        val gate = FrameDeliveryGate()
        val hash = 99L
        assertTrue(gate.deliverIfChanged(hash))
        assertTrue(legacyDeliver(hash))
        assertFalse(gate.deliverIfChanged(hash))
    }
}
