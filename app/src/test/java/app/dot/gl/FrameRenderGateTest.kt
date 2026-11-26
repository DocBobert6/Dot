package app.dot.gl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame render gate (GL-CHAIN-SPEC §9.2): exactly one render
 * per frame, whichever side completes second.
 */
class FrameRenderGateTest {

    @Test
    fun frameCompleteAloneDoesNotRequest() {
        val gate = FrameRenderGate()
        assertFalse(gate.onFrameComplete())
    }

    @Test
    fun frameLandedAloneDoesNotRequest() {
        val gate = FrameRenderGate()
        assertFalse(gate.onFrameLanded())
    }

    @Test
    fun completeThenLandedRequests() {
        val gate = FrameRenderGate()
        assertFalse(gate.onFrameComplete())
        assertTrue(gate.onFrameLanded())
    }

    @Test
    fun landedThenCompleteRequests() {
        val gate = FrameRenderGate()
        assertFalse(gate.onFrameLanded())
        assertTrue(gate.onFrameComplete())
    }

    @Test
    fun onlyOneRequestPerFrame() {
        val gate = FrameRenderGate()
        gate.onFrameComplete()
        assertTrue(gate.onFrameLanded())
        // Both sides fire again before the render: no second request.
        assertFalse(gate.onFrameComplete())
        assertFalse(gate.onFrameLanded())
    }

    @Test
    fun beginRenderRearms() {
        val gate = FrameRenderGate()
        gate.onFrameComplete()
        assertTrue(gate.onFrameLanded())
        gate.beginRender()
        // A fresh frame: complete alone does not request...
        assertFalse(gate.onFrameComplete())
        // ...but landed completes it.
        assertTrue(gate.onFrameLanded())
    }

    @Test
    fun twoFramesTwoRequests() {
        val gate = FrameRenderGate()
        gate.onFrameComplete()
        assertTrue(gate.onFrameLanded())
        gate.beginRender()
        gate.onFrameLanded()
        assertTrue(gate.onFrameComplete())
    }
}
