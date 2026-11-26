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
        /** False when the capture was stopped early (a partial). */
        val completed: Boolean = true,
    ) {
        /** Capture duration in ms, from the delivered intervals. */
        val durationMs: Double
            get() = if (delivered.avgFps > 0) {
                delivered.frames * 1000.0 / delivered.avgFps
            } else {
                0.0
            }
    }

    /** A manual stop's outcome (§7.2). */
    sealed interface StopOutcome {
        data class Kept(val capture: Capture) : StopOutcome
        data object Discarded : StopOutcome
        data object NotRunning : StopOutcome
    }

    /** A partial capture is kept only with at least this many frames. */
    const val MIN_PARTIAL_FRAMES = 60

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

        /** Timestamps recorded (first is the priming timestamp). */
        fun progress(): Int = times.size

        fun stats(): Stats? {
            if (times.size < 2) return null
            val intervals = ArrayList<Long>(times.size - 1)
            for (i in 1 until times.size) {
                intervals.add(times[i] - times[i - 1])
            }
            return statsFromIntervals(intervals)
        }
    }

    /** Stats from raw frame intervals (sorted here; the snapshot
     *  is the only place sorting happens, never per frame). */
    private fun statsFromIntervals(intervals: List<Long>): Stats? {
        if (intervals.isEmpty()) return null
        val sorted = intervals.sorted()
        val n = sorted.size
        val totalNanos = sorted.sum()
        val avgFps = 1_000_000_000.0 / (totalNanos.toDouble() / n)
        val p95 = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)] / 1_000_000.0
        val p99 = sorted[(n * 0.99).toInt().coerceAtMost(n - 1)] / 1_000_000.0
        val worst = sorted[n - 1] / 1_000_000.0
        // 1% low: the average fps of the slowest 1% of intervals.
        val slowestCount = (n * 0.01).toInt().coerceAtLeast(1)
        val slowest = sorted.takeLast(slowestCount)
        val low1PctFps =
            1_000_000_000.0 / (slowest.sum().toDouble() / slowest.size)
        val drops = sorted.count { it > NOMINAL_FRAME_NANOS * 3 / 2 }
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

    private val deliveredClock = Clock()
    private val presentedClock = Clock()
    private var captureGameSha = ""
    private var captureChainLabel = ""
    private var captureStartedMs = 0L
    @Volatile var liveFps: Double = 0.0
        private set

    /**
     * The last finished capture, for the Developer screen. Cleared
     * when a game stops (§7.2: a stale partial capture and stale
     * game title must not survive into the next launch) and when a
     * manual stop discards a too-short capture.
     */
    @Volatile
    var lastCapture: Capture? = null
        private set

    private val recentPresented = ArrayDeque<Long>(128)
    private var lastLiveUpdateMs = 0L

    /**
     * Starts a capture. Returns false when one is already running
     * (§7.2: starting while running returns false — it never
     * restarts a capture in flight). [startNanos] is exposed only
     * so tests can drive the clocks with exact timestamps;
     * production callers omit it.
     */
    @Synchronized
    fun startCapture(
        gameSha: String,
        chainLabel: String,
        startNanos: Long = System.nanoTime(),
    ): Boolean {
        if (isCapturing()) return false
        val now = startNanos
        deliveredClock.start(now)
        presentedClock.start(now)
        captureGameSha = gameSha
        captureChainLabel = chainLabel
        captureStartedMs = System.currentTimeMillis()
        // A new capture supersedes the old result.
        lastCapture = null
        return true
    }

    /**
     * Retargets the running capture at another game (the capture
     * was armed early, before the game started). Only the labels
     * change — the recorded frames are untouched.
     */
    @Synchronized
    fun retargetCapture(gameSha: String, chainLabel: String) {
        if (!isCapturing()) return
        captureGameSha = gameSha
        captureChainLabel = chainLabel
    }

    /** Delivered frames recorded so far in the running capture. */
    @Synchronized
    fun captureProgress(): Int {
        if (!isCapturing()) return 0
        return (deliveredClock.progress() - 1).coerceIn(0, CAPTURE_FRAMES)
    }

    /**
     * Stops the running capture by hand. A partial is kept only
     * with at least 60 frames (marked incomplete); anything shorter
     * is discarded as noise — and the previously displayed result
     * is cleared with it, so the Developer screen does not keep
     * showing an older capture as if this stop had produced it.
     */
    @Synchronized
    fun stopCapture(): StopOutcome {
        if (!isCapturing()) return StopOutcome.NotRunning
        val delivered = deliveredClock.stats()
        val presented = presentedClock.stats()
        deliveredClock.capturing = false
        presentedClock.capturing = false
        val frames = delivered?.frames ?: 0
        if (frames < MIN_PARTIAL_FRAMES || delivered == null || presented == null) {
            lastCapture = null
            return StopOutcome.Discarded
        }
        val capture = Capture(
            delivered = delivered,
            presented = presented,
            startedMs = captureStartedMs,
            gameSha = captureGameSha,
            chainLabel = captureChainLabel,
            completed = false,
        )
        lastCapture = capture
        return StopOutcome.Kept(capture)
    }

    /**
     * Cancels a running capture without keeping a result. Used
     * when the game stops (§7.2).
     */
    @Synchronized
    fun cancelCapture() {
        deliveredClock.capturing = false
        presentedClock.capturing = false
    }

    /** Clears the last capture (a game stop must not leave a stale
     *  result for the next launch). */
    fun clearLastCapture() {
        lastCapture = null
    }

    /**
     * Test support: clears the clocks, the last capture and the
     * live window, so tests start from a clean slate. Production
     * never calls this — a capture start clears the clocks, and a
     * game stop cancels the running capture.
     */
    fun resetForTests() {
        deliveredClock.capturing = false
        presentedClock.capturing = false
        lastCapture = null
        recentPresented.clear()
        liveFps = 0.0
    }

    /**
     * The live rolling stats from the presented 120-frame window
     * (the same window liveFps comes from), or null when fewer
     * than two frames have landed. The Developer screen polls this
     * twice a second.
     */
    @Synchronized
    fun liveStats(): Stats? {
        if (recentPresented.size < 2) return null
        val intervals = ArrayList<Long>(recentPresented.size - 1)
        val iterator = recentPresented.iterator()
        var prev = iterator.next()
        while (iterator.hasNext()) {
            val now = iterator.next()
            intervals.add(now - prev)
            prev = now
        }
        return statsFromIntervals(intervals)
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
        val capture = Capture(
            delivered = delivered,
            presented = presented,
            startedMs = captureStartedMs,
            gameSha = captureGameSha,
            chainLabel = captureChainLabel,
            completed = true,
        )
        lastCapture = capture
        return capture
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
