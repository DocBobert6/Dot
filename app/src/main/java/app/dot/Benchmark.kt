package app.dot

// Dot — benchmark harness (ported from Atomic's shape).
//
// A capture runs for 600 frames. Two clocks are kept: the emu loop's
// delivery times and the main thread's presentation times, so a slow
// compositor cannot hide behind fast emulation (or the reverse).
// Stats: average fps, p95 / p99 / worst frame time, 1% low fps, and
// the dropped-frame count (a frame is dropped when its interval is
// more than 1.5x the nominal frame time). Export is JSON to the
// Downloads/benchmarks directory.
//
// The FPS gate (kept as Dot's own gate, §1D): avg >= 59.5 fps,
// p95 <= 18.0 ms, drops <= 1% over 600 frames, measured on-device.
// This class only measures; the gate verdict lives in the caller, so
// a measurement can never be mistaken for a pass.
// Copyright (c) 2026 Dot contributors. All rights reserved.

object Benchmark {

    const val CAPTURE_FRAMES = 600

    /** Nominal DMG frame interval, nanoseconds (59.7275 fps). */
    const val NOMINAL_FRAME_NANOS = 16_742_000L

    data class Stats(
        val frames: Int,
        val avgFps: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val worstMs: Double,
        val low1PctFps: Double,
        val drops: Int,
        val dropPct: Double,
    )

    /** A finished capture: the two clocks plus the computed stats. */
    data class Capture(
        val delivered: Stats,
        val presented: Stats,
        val startedMs: Long,
        val gameSha: String,
        val chainLabel: String,
    )

    private class Clock {
        val times = ArrayList<Long>(CAPTURE_FRAMES + 1)
        var capturing = false

        fun start(nowNanos: Long) {
            times.clear()
            times.add(nowNanos)
            capturing = true
        }

        fun tick(nowNanos: Long) {
            if (!capturing) return
            times.add(nowNanos)
            if (times.size > CAPTURE_FRAMES + 1) capturing = false
        }

        fun isDone(): Boolean = times.size >= CAPTURE_FRAMES + 1

        fun stats(): Stats? {
            if (times.size < 2) return null
            val intervals = ArrayList<Long>(times.size - 1)
            for (i in 1 until times.size) {
                intervals.add(times[i] - times[i - 1])
            }
            intervals.sort()
            val n = intervals.size
            val totalNanos = intervals.sum()
            val avgMs = totalNanos.toDouble() / n / 1_000_000.0
            val avgFps = 1_000_000_000.0 / (totalNanos.toDouble() / n)
            val p95 = intervals[(n * 0.95).toInt().coerceAtMost(n - 1)] / 1_000_000.0
            val p99 = intervals[(n * 0.99).toInt().coerceAtMost(n - 1)] / 1_000_000.0
            val worst = intervals[n - 1] / 1_000_000.0
            // 1% low: the average fps of the slowest 1% of intervals.
            val slowestCount = (n * 0.01).toInt().coerceAtLeast(1)
            val slowest = intervals.takeLast(slowestCount)
            val low1PctFps =
                1_000_000_000.0 / (slowest.sum().toDouble() / slowest.size)
            val drops = intervals.count { it > NOMINAL_FRAME_NANOS * 3 / 2 }
            return Stats(
                frames = n,
                avgFps = avgFps,
                p95Ms = p95,
                p99Ms = p99,
                worstMs = worst,
                low1PctFps = low1PctFps,
                drops = drops,
                dropPct = drops * 100.0 / n,
            )
        }
    }

    private val deliveredClock = Clock()
    private val presentedClock = Clock()
    private var captureGameSha = ""
    private var captureChainLabel = ""
    private var captureStartedMs = 0L
    @Volatile var liveFps: Double = 0.0
        private set

    private val recentPresented = ArrayDeque<Long>(128)
    private var lastLiveUpdateMs = 0L

    /** Starts a capture. Safe to call when one is already running.
     *  [startNanos] is exposed only so tests can drive the clocks
     *  with exact timestamps; production callers omit it. */
    @Synchronized
    fun startCapture(
        gameSha: String,
        chainLabel: String,
        startNanos: Long = System.nanoTime(),
    ) {
        val now = startNanos
        deliveredClock.start(now)
        presentedClock.start(now)
        captureGameSha = gameSha
        captureChainLabel = chainLabel
        captureStartedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun recordDelivered(nowNanos: Long = System.nanoTime()) {
        deliveredClock.tick(nowNanos)
    }

    @Synchronized
    fun recordPresented(nowNanos: Long = System.nanoTime()) {
        presentedClock.tick(nowNanos)
        recentPresented.addLast(nowNanos)
        while (recentPresented.size > 120) recentPresented.removeFirst()
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLiveUpdateMs >= 500 && recentPresented.size >= 2) {
            lastLiveUpdateMs = nowMs
            val span = recentPresented.last() - recentPresented.first()
            if (span > 0) {
                liveFps =
                    (recentPresented.size - 1) * 1_000_000_000.0 / span
            }
        }
    }

    @Synchronized
    fun isCapturing(): Boolean =
        deliveredClock.capturing || presentedClock.capturing

    @Synchronized
    fun finishIfDone(): Capture? {
        if (!deliveredClock.isDone() || !presentedClock.isDone()) return null
        val delivered = deliveredClock.stats() ?: return null
        val presented = presentedClock.stats() ?: return null
        deliveredClock.capturing = false
        presentedClock.capturing = false
        return Capture(
            delivered = delivered,
            presented = presented,
            startedMs = captureStartedMs,
            gameSha = captureGameSha,
            chainLabel = captureChainLabel,
        )
    }

    /** The FPS gate verdict for a capture. Measurements only — the
     *  caller decides what a failure means. */
    fun gatePass(capture: Capture): Boolean {
        val s = capture.presented
        return s.avgFps >= 59.5 && s.p95Ms <= 18.0 && s.dropPct <= 1.0
    }

    fun toJson(capture: Capture): String {
        fun statsJson(s: Stats): String =
            "{\"frames\":${s.frames}," +
                "\"avg_fps\":${"%.2f".format(s.avgFps)}," +
                "\"p95_ms\":${"%.2f".format(s.p95Ms)}," +
                "\"p99_ms\":${"%.2f".format(s.p99Ms)}," +
                "\"worst_ms\":${"%.2f".format(s.worstMs)}," +
                "\"low1pct_fps\":${"%.2f".format(s.low1PctFps)}," +
                "\"drops\":${s.drops}," +
                "\"drop_pct\":${"%.2f".format(s.dropPct)}}"
        return "{\"game_sha\":\"${capture.gameSha}\"," +
            "\"chain\":\"${capture.chainLabel}\"," +
            "\"started_ms\":${capture.startedMs}," +
            "\"gate_pass\":${gatePass(capture)}," +
            "\"delivered\":${statsJson(capture.delivered)}," +
            "\"presented\":${statsJson(capture.presented)}}"
    }
}
