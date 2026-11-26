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

    /**
     * The Phase 1 black-screen wedge (v0.1.0-phase1.1): a
     * one-frame slip between the emu, main, and GL threads.
     * Frame A completes its pair and requests a render; frame B
     * posts before that draw runs, so the draw consumes B, not A;
     * B's landing then arrives after B was already consumed. The
     * gate sits at bitmapLanded=true, frameComplete=false — and
     * the next post MUST return true, so its caller requests a
     * render. The old view discarded this value, which left
     * requestQueued true forever with no render ever requested
     * and no draw ever re-arming the gate.
     */
    @Test
    fun wedgeInterleavingStillRequestsOnPost() {
        val gate = FrameRenderGate()
        // Frame A: the pair completes, a render is requested.
        assertFalse(gate.onFrameComplete())
        assertTrue(gate.onFrameLanded())
        // Frame B posts before the requested draw runs...
        assertFalse(gate.onFrameComplete())
        // ...and the draw consumes B (the latest pending frame).
        gate.beginRender()
        // B's landing arrives after B was consumed: no request,
        // and the gate now holds bitmapLanded=true alone.
        assertFalse(gate.onFrameLanded())
        // Frame C posts and completes the pair: this true is the
        // render request, and it must not be lost.
        assertTrue(gate.onFrameComplete())
        // Acting on it (a render + a draw) re-arms the gate.
        gate.beginRender()
        // The next frame is normal again.
        assertFalse(gate.onFrameComplete())
        assertTrue(gate.onFrameLanded())
    }

    @Test
    fun oneRenderPerFrameOverManyFrames() {
        val gate = FrameRenderGate()
        repeat(5) {
            var requests = 0
            if (gate.onFrameComplete()) requests++
            if (gate.onFrameLanded()) requests++
            assertTrue(requests == 1)
            gate.beginRender()
        }
    }

    @Test
    fun oneRenderPerFrameWhenLandedFirstOverManyFrames() {
        val gate = FrameRenderGate()
        repeat(5) {
            var requests = 0
            if (gate.onFrameLanded()) requests++
            if (gate.onFrameComplete()) requests++
            assertTrue(requests == 1)
            gate.beginRender()
        }
    }
}
