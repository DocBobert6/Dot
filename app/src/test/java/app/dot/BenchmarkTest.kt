package app.dot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Dot — benchmark tests, with discrimination: the FPS gate must
// PASS on a clean 600-frame capture at the nominal interval and
// FAIL on a slow capture, or the gate is not a gate.
// Copyright (c) 2026 Dot contributors. All rights reserved.

class BenchmarkTest {

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
