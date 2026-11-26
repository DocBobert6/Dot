package app.dot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Dot — benchmark tests, with discrimination: the FPS gate must
// PASS on a clean 600-frame capture at the nominal interval and
// FAIL on a slow capture, or the gate is not a gate.
// Copyright (c) 2026 Dot contributors. All rights reserved.

class BenchmarkTest {

    private fun reset() {
        Benchmark.resetForTests()
    }

    private fun driveFrames(count: Int, intervalNanos: Long, startNanos: Long = 0L) {
        var t = startNanos
        for (k in 1..count) {
            t += intervalNanos
            Benchmark.recordDelivered(t)
            Benchmark.recordPresented(t)
        }
    }

    @Test
    fun startWhileRunningReturnsFalse() {
        reset()
        assertTrue(Benchmark.startCapture("sha", "chain", startNanos = 0L))
        assertFalse(Benchmark.startCapture("sha", "chain", startNanos = 0L))
        reset()
    }

    @Test
    fun captureProgressCountsDeliveredFrames() {
        reset()
        Benchmark.startCapture("sha", "chain", startNanos = 0L)
        assertEquals(0, Benchmark.captureProgress())
        driveFrames(100, Benchmark.NOMINAL_FRAME_NANOS)
        assertEquals(100, Benchmark.captureProgress())
        reset()
    }

    @Test
    fun stopKeepsAPartialOverSixtyFrames() {
        reset()
        Benchmark.startCapture("sha", "chain", startNanos = 0L)
        driveFrames(100, Benchmark.NOMINAL_FRAME_NANOS)
        val outcome = Benchmark.stopCapture()
        assertTrue(outcome is Benchmark.StopOutcome.Kept)
        val capture = (outcome as Benchmark.StopOutcome.Kept).capture
        assertFalse(capture.completed)
        assertEquals(100, capture.delivered.frames)
        assertEquals(capture, Benchmark.lastCapture)
        reset()
    }

    @Test
    fun stopDiscardsUnderSixtyFramesAndClearsTheLastCapture() {
        reset()
        // First a full capture, so there IS a last result to clear.
        val kept = runCapture(Benchmark.NOMINAL_FRAME_NANOS)
        assertEquals(kept, Benchmark.lastCapture)
        Benchmark.startCapture("sha", "chain", startNanos = 0L)
        driveFrames(10, Benchmark.NOMINAL_FRAME_NANOS)
        val outcome = Benchmark.stopCapture()
        assertTrue(outcome is Benchmark.StopOutcome.Discarded)
        assertNull(Benchmark.lastCapture)
        reset()
    }

    @Test
    fun stopWhenNotRunningReportsNotRunning() {
        reset()
        assertTrue(Benchmark.stopCapture() is Benchmark.StopOutcome.NotRunning)
    }

    @Test
    fun liveStatsComeFromThePresentedWindow() {
        reset()
        assertNull(Benchmark.liveStats())
        driveFrames(120, Benchmark.NOMINAL_FRAME_NANOS)
        val stats = Benchmark.liveStats()
        assertNotNull(stats)
        assertTrue(stats!!.avgFps > 59.6 && stats.avgFps < 59.9)
        assertEquals(0, stats.drops)
        reset()
    }

    @Test
    fun cancelCaptureLeavesNoCaptureRunning() {
        reset()
        Benchmark.startCapture("sha", "chain", startNanos = 0L)
        assertTrue(Benchmark.isCapturing())
        Benchmark.cancelCapture()
        assertFalse(Benchmark.isCapturing())
        reset()
    }

    private fun runCapture(intervalNanos: Long, droppedGapAt: Int = -1): Benchmark.Capture {
        Benchmark.startCapture("test-sha", "test-chain", startNanos = 0L)
        var t = 0L
        for (k in 1..Benchmark.CAPTURE_FRAMES) {
            t += if (k == droppedGapAt) intervalNanos * 4 else intervalNanos
            Benchmark.recordDelivered(t)
            Benchmark.recordPresented(t)
        }
        val capture = Benchmark.finishIfDone()
        assertNotNull(capture)
        return capture!!
    }

    @Test
    fun cleanCaptureAtNominalIntervalPassesGate() {
        val capture = runCapture(Benchmark.NOMINAL_FRAME_NANOS)
        val s = capture.presented
        assertEquals(Benchmark.CAPTURE_FRAMES, s.frames)
        assertEquals(0, s.drops)
        assertTrue("avg fps ${s.avgFps} should be near 59.73",
            s.avgFps > 59.6 && s.avgFps < 59.9)
        assertTrue(Benchmark.gatePass(capture))
    }

    @Test
    fun slowCaptureFailsGate() {
        // 33 ms intervals: ~30 fps, far below the 59.5 gate.
        val capture = runCapture(33_000_000L)
        assertFalse(Benchmark.gatePass(capture))
    }

    @Test
    fun droppedFramesAreCounted() {
        // One 4x gap counts as a drop; the rest are nominal.
        val capture = runCapture(Benchmark.NOMINAL_FRAME_NANOS, droppedGapAt = 100)
        assertEquals(1, capture.presented.drops)
    }

    @Test
    fun statsHaveSaneShape() {
        val capture = runCapture(Benchmark.NOMINAL_FRAME_NANOS)
        val s = capture.presented
        assertTrue(s.p95Ms > 0.0)
        assertTrue(s.p99Ms >= s.p95Ms)
        assertTrue(s.worstMs >= s.p99Ms)
        assertTrue(s.low1PctFps > 0.0)
        assertTrue(Benchmark.toJson(capture).contains("\"gate_pass\":true"))
    }
}
