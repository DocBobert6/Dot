package app.dot.gl

// Dot — the frame render gate (GL-CHAIN-SPEC §9.2). Exactly one
// render per frame, whichever side completes second: the session
// thread posts the frame (onFrameComplete), the main thread lands
// the bitmap (onFrameLanded), and the GL thread re-arms after
// rendering (beginRender). Each method returns true iff this call
// caused a render request. All methods are synchronised; the three
// booleans are the whole state.
// SPDX-License-Identifier: GPL-3.0-or-later

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
