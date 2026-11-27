package app.dot.gl

import java.util.concurrent.atomic.AtomicLong

// Dot — the frame-delivery gate (Phase 1.4). The pure decision that
// stops an unchanged frame before it can cause a render request.
//
// GLSurfaceView swaps buffers after EVERY onDrawFrame, including
// the ones that draw nothing: a hash-skipped frame still swaps, so
// the screen cycles stale buffers (the drawn frame vs an empty
// buffer) and the picture flashes. The 1.3 counts proved it: 361
// attempts, 45 draws, 316 hash-skips — 316 swaps of nothing. The
// fix is to never request the render in the first place: the view
// drops an unchanged frame at postFrame, so no onDrawFrame and no
// swap happens for a static frame.
//
// The gate keys on the frame's content hash, which the emu loop
// already computes. It is a check-and-mark in one atomic step:
// deliverIfChanged returns true and records the hash when the
// frame differs from the last delivered one, false when it is the
// same. invalidate() re-arms it — called when a delivered frame
// did not draw (charcoal), when the surface is rebuilt, and when
// the test-pattern toggle changes, so the next identical frame is
// delivered again. Without the invalidation the 1.2 charcoal
// poisoning would move here: a frame that never drew would
// suppress every later identical frame forever.
//
// This gate is the delivery decision only. The GL thread keeps its
// own DrawDecision (last DRAWN hash) as a backstop in onDrawFrame;
// the two agree in the normal case and disagree only when a
// delivered frame failed to draw, which is exactly when the
// invalidation fires.
// Copyright (c) 2026 Dot contributors. All rights reserved.

class FrameDeliveryGate {
    private val lastDelivered = AtomicLong(NONE)

    /**
     * True when this frame must be delivered to the view (its hash
     * differs from the last delivered frame's), and records the
     * hash as delivered. False when the frame is unchanged — the
     * caller must not post it, request a render, or swap for it.
     */
    fun deliverIfChanged(hash: Long): Boolean {
        while (true) {
            val current = lastDelivered.get()
            if (current == hash) return false
            if (lastDelivered.compareAndSet(current, hash)) return true
        }
    }

    /** Re-arms the gate: the next frame delivers regardless of hash. */
    fun invalidate() {
        lastDelivered.set(NONE)
    }

    companion object {
        /** No frame delivered yet (also the sentinel DrawDecision uses). */
        const val NONE = -1L
    }
}
