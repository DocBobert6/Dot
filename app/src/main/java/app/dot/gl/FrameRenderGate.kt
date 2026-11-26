package app.dot.gl

// Dot — the frame render gate (GL-CHAIN-SPEC §9.2). Exactly one
// render per frame, whichever side completes second: the session
// thread posts the frame (onFrameComplete), the main thread lands
// the bitmap (onFrameLanded), and the GL thread re-arms after
// rendering (beginRender). Each method returns true iff this call
// caused a render request. All methods are synchronised; the three
// booleans are the whole state.
//
// CALLERS MUST ACT ON THE RETURN VALUE. Both sides request the
// render when they complete a pair. Discarding the value on
// either side wedges the gate permanently: the pair completes
// with no render requested, so no draw ever re-arms it (the
// Phase 1 black screen on device, v0.1.0-phase1.1).
// Copyright (c) 2026 Dot contributors. All rights reserved.

class FrameRenderGate {
    private var frameComplete = false
    private var bitmapLanded = false
    private var requestQueued = false

    @Synchronized
    fun onFrameComplete(): Boolean {
        frameComplete = true
        return maybeRequest()
    }

    @Synchronized
    fun onFrameLanded(): Boolean {
        bitmapLanded = true
        return maybeRequest()
    }

    /** Called by the GL thread after a render: re-arms the gate. */
    @Synchronized
    fun beginRender() {
        frameComplete = false
        bitmapLanded = false
        requestQueued = false
    }

    private fun maybeRequest(): Boolean {
        if (frameComplete && bitmapLanded && !requestQueued) {
            requestQueued = true
            return true
        }
        return false
    }
}
