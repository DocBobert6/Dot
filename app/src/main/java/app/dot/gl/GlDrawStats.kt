package app.dot.gl

// Dot — process-wide draw counters for the GL renderer (no Android
// imports, so plain JUnit can drive it).
//
// The counters are what the next on-device report discriminates
// with: a black screen with draws attempted and charcoal by surface
// is a sizing problem, charcoal by latch is a failed chain, and
// zero attempts is a gate that never fired — three different fixes.
// The snapshot text goes into the graphics failure report and is
// shown live in Settings, so it never needs a benchmark run to read.
// Copyright (c) 2026 Dot contributors. All rights reserved.

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object GlDrawStats {
    private val attempted = AtomicLong(0)
    private val drawn = AtomicLong(0)
    private val charcoalLatch = AtomicLong(0)
    private val charcoalSurface = AtomicLong(0)
    private val charcoalArea = AtomicLong(0)
    private val hashSkipped = AtomicLong(0)
    private val idle = AtomicLong(0)
    @Volatile private var lastSurfaceW = 0
    @Volatile private var lastSurfaceH = 0
    @Volatile private var lastCharcoalReason: CharcoalReason? = null
    private val latchFailed = AtomicBoolean(false)

    /** A fresh GL context starts the counts over. */
    fun reset() {
        attempted.set(0)
        drawn.set(0)
        charcoalLatch.set(0)
        charcoalSurface.set(0)
        charcoalArea.set(0)
        hashSkipped.set(0)
        idle.set(0)
        lastSurfaceW = 0
        lastSurfaceH = 0
        lastCharcoalReason = null
        latchFailed.set(false)
    }

    fun noteAttempt() {
        attempted.incrementAndGet()
    }

    /** Draws attempted so far (for the throttled logcat summary). */
    fun attemptedCount(): Long = attempted.get()

    fun noteDrawn() {
        drawn.incrementAndGet()
    }

    /** Frames that actually drew so far (for the first-draw log). */
    fun drawnCount(): Long = drawn.get()

    fun noteCharcoal(reason: CharcoalReason) {
        when (reason) {
            CharcoalReason.Latch -> charcoalLatch.incrementAndGet()
            CharcoalReason.Surface -> charcoalSurface.incrementAndGet()
            CharcoalReason.Area -> charcoalArea.incrementAndGet()
        }
        lastCharcoalReason = reason
    }

    fun noteHashSkipped() {
        hashSkipped.incrementAndGet()
    }

    fun noteIdle() {
        idle.incrementAndGet()
    }

    fun noteSurface(width: Int, height: Int) {
        lastSurfaceW = width
        lastSurfaceH = height
    }

    fun noteLatchFailed() {
        latchFailed.set(true)
    }

    private fun charcoalTotal(): Long =
        charcoalLatch.get() + charcoalSurface.get() + charcoalArea.get()

    private fun reasonName(reason: CharcoalReason?): String = when (reason) {
        CharcoalReason.Latch -> "latch"
        CharcoalReason.Surface -> "surface ${lastSurfaceW}x$lastSurfaceH"
        CharcoalReason.Area -> "area"
        null -> "none"
    }

    /**
     * The snapshot text: one labelled line each, no trailing
     * newline, so it drops verbatim into the failure report (and
     * parses back out of it).
     */
    fun snapshot(): String = listOf(
        "Draws attempted: ${attempted.get()}",
        "Draws shown: ${drawn.get()}",
        "Charcoal draws: ${charcoalTotal()} " +
            "(latch: ${charcoalLatch.get()}, " +
            "surface: ${charcoalSurface.get()}, " +
            "area: ${charcoalArea.get()})",
        "Frames skipped (unchanged): ${hashSkipped.get()}",
        "Frames with no pending frame: ${idle.get()}",
        "Last surface: ${lastSurfaceW}x$lastSurfaceH",
        "Last charcoal reason: ${reasonName(lastCharcoalReason)}",
        "Latch failed: ${if (latchFailed.get()) "yes" else "no"}",
    ).joinToString("\n")
}
