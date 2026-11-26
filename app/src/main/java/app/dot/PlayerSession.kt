package app.dot

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.dot.gl.DeflickerGate
import app.dot.gl.FrameHash
import app.dot.gl.GlFrame
import app.dot.gl.ShadePlane
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — one game session: the emu thread, the audio thread, and the
// save discipline around them.
//
// Shape follows Atomic's proven session (two threads, generation
// guard, core ownership token, pinned teardown order), re-derived
// for DMG: 160x144 RGB565 frames at 59.7275 fps, GB sample rate
// 131072 Hz, 8 KB of video RAM, 4 manual save slots plus Auto.
//
// Input is store-only (SHELL-SPEC §4.2): setInput stores the mask
// and returns; the emu loop is the single pusher, sending the mask
// to the core only when it changes. Speed is the same shape — the
// overlay sets ffHeld / slowOn / ffSpeed, the loop applies the
// effective speed and flips the audio-discard flag once per change
// (FF wins over slow; 0.5x is the slow speed).
//
// Teardown order (pinned, each step individually guarded, joins
// bounded at 2000 ms): JOIN_EMU -> JOIN_AUDIO -> WRITE_SAVE ->
// WRITE_AUTO -> RELEASE_AUDIO -> UNLOAD_CORE. The core is never
// unloaded under a live emu thread: if the emu thread will not die,
// the unload is skipped and the core is left loaded until the
// process ends. Audio release goes through the release-once gate:
// exactly one of the audio thread's exit path and stop() may stop
// and release the track.

class PlayerSession(
    private val filesDir: File,
    private val entry: LibraryEntry,
) {
    sealed interface State {
        object Idle : State
        object Loading : State
        object Running : State
        object Paused : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    // Double-buffered frames: the emu thread writes only the back
    // buffer and never touches the front buffer Compose is drawing.
    // The UI reads the front buffer through currentFrame() after
    // onFrame fires; the two bitmaps alternate, so Compose sees a
    // new bitmap object each frame and recomposes the Image.
    private val bitmapA: Bitmap = Bitmap.createBitmap(
        Bridge.FRAME_WIDTH, Bridge.FRAME_HEIGHT, Bitmap.Config.ARGB_8888,
    )
    private val bitmapB: Bitmap = Bitmap.createBitmap(
        Bridge.FRAME_WIDTH, Bridge.FRAME_HEIGHT, Bitmap.Config.ARGB_8888,
    )
    private val backPixels = IntArray(Bridge.FRAME_WIDTH * Bridge.FRAME_HEIGHT)
    private val frameDims = IntArray(2)
    private var frontIsA = true
    private val frameLock = Any()

    /** The front buffer: the frame Compose should display now. */
    fun currentFrame(): Bitmap = synchronized(frameLock) {
        if (frontIsA) bitmapA else bitmapB
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread after each delivered frame. */
    var onFrame: (() -> Unit)? = null

    /** The GL frame sink (the GameGlView): receives each emulated
     *  frame's ARGB pixels, shade plane, content hash, and deflicker
     *  mask. Set by the GameScreen; null when no GL view is attached. */
    var glFrameSink: ((GlFrame) -> Unit)? = null

    /** The gated flicker-transparency gate (GL-CHAIN-SPEC §7). */
    private val deflickerGate = DeflickerGate()

    /** Set when a benchmark capture finishes. */
    var onBenchmarkDone: ((Benchmark.Capture) -> Unit)? = null

    /** The game-rumble gate (RumbleGate.kt): set by the GameScreen
     *  from the activity's vibrator. The emu loop polls it once per
     *  delivered frame; null until set. */
    var rumbleGate: RumbleGate? = null

    private val generation = AtomicLong(0)
    private val inputMask = AtomicInteger(0)
    private val pausedFlag = AtomicInteger(0)
    @Volatile private var stopRequested = false

    /** Speed requests from the overlay. FF wins over slow. */
    @Volatile var ffHeld = false
    @Volatile var slowOn = false
    /** Fast-forward multiplier (2, 3 or 4), from the ff_speed pref. */
    @Volatile var ffSpeed = 2

    /** Rewind capture is armed only while the rewind control is
     *  visible (SHELL-SPEC §6): with the control hidden the
     *  snapshots could never be used. Set by the GameScreen. */
    @Volatile var rewindArmed = false

    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var audioRate = 0

    /** The audio release-once gate (alpha.11): exactly one path may
     *  stop and release the track — the audio thread's exit, or
     *  stop() after the joins. A second stop() throws otherwise. */
    private val audioReleaseGate = AtomicBoolean(false)

    /** Single background executor for save/load/state work, so no
     *  serialise ever blocks the UI thread. */
    private val stateOpExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "dot-state-op").apply { isDaemon = true }
        }

    /** Set when a stop() timed out waiting for a thread: the next
     *  start() refuses while either thread is still alive, so a new
     *  emu loop can never run under a stale core. Cleared once both
     *  threads are dead. */
    @Volatile private var stopTimedOut = false

    /** The rewind ring: serialised states, newest last, at most 30
     *  (one snapshot a second while the game runs and rewind is
     *  armed). Guarded by rewindLock; written by the emu thread,
     *  popped by the overlay's rewind hold. */
    private val rewindRing = ArrayDeque<ByteArray>(REWIND_CAPACITY)
    private val rewindLock = Any()

    /** Current save-state slots that have data, for the slot picker. */
    val slotHasData: BooleanArray = BooleanArray(4)

    init {
        refreshSlotInfo()
    }

    fun refreshSlotInfo() {
        for (slot in 0 until 4) {
            slotHasData[slot] =
                Store.stateFile(filesDir, entry.sha256, slot).exists()
        }
    }

    fun hasAutoState(): Boolean =
        Store.autoStateFile(filesDir, entry.sha256).exists()

    /**
     * Stores the live input mask (libretro joypad bits,
     * Bridge.Input). Store-only: the emu loop is the single pusher
     * and sends the mask to the core only when it changes, so a tap
     * in the first frames before the core starts cannot throw and
     * the core never sees a half-written mask.
     */
    fun setInput(mask: Int) {
        inputMask.set(mask)
    }

    fun start() {
        // A previous stop that timed out leaves live threads behind:
        // refuse a new loop while either one is still alive.
        if (stopTimedOut &&
            (emuThread?.isAlive == true || audioThread?.isAlive == true)
        ) {
            _state.value = State.Error(
                "The previous game is still stopping — try again in a moment.",
            )
            return
        }
        stopTimedOut = false
        stopRequested = false
        pausedFlag.set(0)
        audioRate = 0
        clearRewindRing()
        val gen = generation.incrementAndGet()
        _state.value = State.Loading
        emuThread = Thread({
            runEmu(gen)
        }, "dot-emu").also { it.start() }
        audioThread = Thread({
            runAudio(gen)
        }, "dot-audio").also { it.start() }
    }

    /**
     * Pauses the game and nothing else: the pause toggle writes
     * neither the battery save nor the Auto state. The next resume
     * picks the game up exactly where it stopped.
     */
    fun pause() {
        pausedFlag.set(1)
        inputMask.set(0)
        _state.value = State.Paused
    }

    /**
     * The backgrounding pause (SHELL-SPEC §11.3): pause, clear the
     * held inputs, drop any rewind hold, and write the battery save
     * and the Auto state asynchronously on the state-op executor —
     * never on the UI thread.
     */
    fun backgroundPause() {
        pause()
        stateOpExecutor.execute {
            writeBatterySave()
            writeAutoState()
        }
    }

    fun resume() {
        pausedFlag.set(0)
        _state.value = State.Running
    }

    fun isPaused(): Boolean = pausedFlag.get() == 1

    /** Stops the session from a background thread. Never call on the
     *  main thread: the joins block up to 2 s each. Pinned order:
     *  JOIN_EMU -> JOIN_AUDIO -> WRITE_SAVE -> WRITE_AUTO ->
     *  RELEASE_AUDIO -> UNLOAD_CORE. */
    fun stop() {
        stopRequested = true
        val token = generation.get()
        val emu = emuThread
        val audio = audioThread
        try {
            emu?.join(2000)
        } catch (e: InterruptedException) {
            // fall through; the core is not unloaded under a live thread
        }
        try {
            audio?.join(2000)
        } catch (e: InterruptedException) {
            // fall through
        }
        val emuAlive = emu?.isAlive == true
        val audioAlive = audio?.isAlive == true
        if (emuAlive || audioAlive) stopTimedOut = true
        rumbleGate?.stop()
        clearRewindRing()
        inputMask.set(0)
        writeBatterySave()
        writeAutoState()
        // Audio release, through the release-once gate, and only
        // once the audio thread is dead — never under a live thread.
        if (!audioAlive && audioReleaseGate.compareAndSet(false, true)) {
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                // already stopped
            }
            try {
                audioTrack?.release()
            } catch (e: Exception) {
                // already released
            }
        }
        audioTrack = null
        // Never unload under a live emu thread, and never unload a
        // core a newer start() already owns (generation token).
        if (!emuAlive && generation.get() == token) {
            try {
                Bridge.nativeUnloadGame()
            } catch (e: Exception) {
                // unload is best-effort at teardown
            }
            try {
                Bridge.nativeDeinit()
            } catch (e: Exception) {
                // best-effort
            }
        }
        emuThread = null
        audioThread = null
        _state.value = State.Idle
    }

    // -- save states ---------------------------------------------------

    /** Saves to a manual slot, on the state-op executor; [onDone]
     *  runs on the main thread with the result. */
    fun saveState(slot: Int, onDone: (Boolean) -> Unit) {
        if (!Bridge.isLoaded()) {
            mainHandler.post { onDone(false) }
            return
        }
        stateOpExecutor.execute {
            val size = Bridge.nativeSerializeSize()
            val ok = if (size <= 0) {
                false
            } else {
                val data = ByteArray(size)
                Bridge.nativeSerialize(data) &&
                    Store.atomicWrite(
                        Store.stateFile(filesDir, entry.sha256, slot), data,
                    )
            }
            if (ok) slotHasData[slot] = true
            mainHandler.post { onDone(ok) }
        }
    }

    /** Loads a manual slot, on the state-op executor; [onDone] runs
     *  on the main thread. The rewind ring is cleared first — a
     *  loaded state invalidates every snapshot after it. Only
     *  callable from the pause menu, while the emu loop is paused. */
    fun loadState(slot: Int, onDone: (Boolean) -> Unit) {
        if (!Bridge.isLoaded()) {
            mainHandler.post { onDone(false) }
            return
        }
        stateOpExecutor.execute {
            clearRewindRing()
            val data = Store.readBytes(
                Store.stateFile(filesDir, entry.sha256, slot),
            )
            val ok = data != null && Bridge.nativeUnserialize(data)
            mainHandler.post { onDone(ok) }
        }
    }

    /** Loads the Auto state, on the state-op executor; [onDone] runs
     *  on the main thread. Same pause-menu-only rule as loadState. */
    fun loadAutoState(onDone: (Boolean) -> Unit) {
        if (!Bridge.isLoaded()) {
            mainHandler.post { onDone(false) }
            return
        }
        stateOpExecutor.execute {
            clearRewindRing()
            val data = Store.readBytes(
                Store.autoStateFile(filesDir, entry.sha256),
            )
            val ok = data != null && Bridge.nativeUnserialize(data)
            mainHandler.post { onDone(ok) }
        }
    }

    private fun writeAutoState() {
        if (!Bridge.isLoaded()) return
        val size = Bridge.nativeSerializeSize()
        if (size <= 0) return
        val data = ByteArray(size)
        if (!Bridge.nativeSerialize(data)) return
        Store.atomicWrite(Store.autoStateFile(filesDir, entry.sha256), data)
    }

    // -- rewind ----------------------------------------------------------

    /**
     * Steps one snapshot back (newest first). Called by the
     * overlay's rewind hold, every 150 ms, while the emu loop is
     * paused. Returns false when the ring is empty. A pause cancels
     * the hold; starting rewind with an empty ring does nothing.
     */
    fun rewindStep(): Boolean {
        if (!Bridge.isLoaded()) return false
        val data = synchronized(rewindLock) {
            if (rewindRing.isEmpty()) null else rewindRing.removeLast()
        } ?: return false
        return try {
            Bridge.nativeUnserialize(data)
        } catch (e: Exception) {
            false
        }
    }

    private fun clearRewindRing() {
        synchronized(rewindLock) { rewindRing.clear() }
    }

    private fun captureRewindSnapshot() {
        if (!rewindArmed || !Bridge.isLoaded()) return
        val size = try {
            Bridge.nativeSerializeSize()
        } catch (e: Exception) {
            0
        }
        if (size <= 0) return
        val data = ByteArray(size)
        val ok = try {
            Bridge.nativeSerialize(data)
        } catch (e: Exception) {
            false
        }
        if (!ok) return
        synchronized(rewindLock) {
            rewindRing.addLast(data)
            while (rewindRing.size > REWIND_CAPACITY) rewindRing.removeFirst()
        }
    }

    // -- battery save --------------------------------------------------

    private fun writeBatterySave() {
        if (!Bridge.isLoaded()) return
        try {
            val saveSize = Bridge.nativeSaveRamSize()
            if (saveSize > 0) {
                val data = ByteArray(saveSize)
                if (Bridge.nativeReadSaveRam(data)) {
                    Store.atomicWrite(
                        Store.batterySaveFile(filesDir, entry.sha256), data,
                    )
                }
            }
            val rtcSize = Bridge.nativeRtcSize()
            if (rtcSize > 0) {
                val data = ByteArray(rtcSize)
                if (Bridge.nativeReadRtc(data)) {
                    Store.atomicWrite(
                        Store.rtcFile(filesDir, entry.sha256), data,
                    )
                }
            }
        } catch (e: Exception) {
            // Battery-save write failure must never crash a session.
        }
    }

    private fun restoreBatterySave() {
        try {
            val save = Store.readBytes(
                Store.batterySaveFile(filesDir, entry.sha256),
            )
            if (save != null) Bridge.nativeWriteSaveRam(save)
            val rtc = Store.readBytes(Store.rtcFile(filesDir, entry.sha256))
            if (rtc != null) Bridge.nativeWriteRtc(rtc)
        } catch (e: Exception) {
            // A corrupt battery save must never block a game from loading.
        }
    }

    // -- benchmark -----------------------------------------------------

    fun startBenchmark(chainLabel: String) {
        Benchmark.startCapture(entry.sha256, chainLabel)
    }

    /** A copy of the current front frame at 1x, for problem reports. */
    fun latestFrameCopy(): Bitmap {
        return currentFrame().copy(Bitmap.Config.ARGB_8888, false)
    }

    // -- threads -------------------------------------------------------

    private fun runEmu(gen: Long) {
        if (!Bridge.ensureLoaded()) {
            _state.value = State.Error("Native libraries failed to load.")
            return
        }
        val systemDir = Store.systemDir(filesDir).apply { mkdirs() }
        val saveDir = Store.savesDir(filesDir).apply { mkdirs() }
        if (!Bridge.nativeInit(systemDir.absolutePath, saveDir.absolutePath)) {
            _state.value = State.Error(Bridge.nativeLastError())
            return
        }
        val rom = Store.romFile(filesDir, entry.sha256)
        if (!Bridge.nativeLoadGame(rom.absolutePath)) {
            _state.value = State.Error(Bridge.nativeLastError())
            return
        }
        restoreBatterySave()
        val av = Bridge.nativeGetAvInfo()
        val fpsMilli = if (av.size >= 3 && av[2] > 0) av[2] else 59727
        audioRate = if (av.size >= 4 && av[3] > 0) av[3] else 131072
        val intervalNanos = 1_000_000_000_000L / fpsMilli
        _state.value = State.Running
        var nextNanos = System.nanoTime()
        var lastPushedMask = -1
        var lastSpeed = 1.0
        var frameCount = 0L
        while (!stopRequested && generation.get() == gen) {
            if (pausedFlag.get() == 1) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                }
                nextNanos = System.nanoTime()
                continue
            }
            // The emu loop is the single pusher of the live input
            // mask: push only on change.
            val mask = inputMask.get()
            if (mask != lastPushedMask) {
                Bridge.nativeSetInput(mask)
                lastPushedMask = mask
            }
            // Speed: FF wins over slow; the audio-discard flag flips
            // once per speed change, here, never elsewhere.
            val speed = when {
                ffHeld -> ffSpeed.toDouble().coerceIn(2.0, 4.0)
                slowOn -> 0.5
                else -> 1.0
            }
            if (speed != lastSpeed) {
                Bridge.nativeSetAudioDiscard(speed != 1.0)
                lastSpeed = speed
            }
            val frameIntervalNanos = (intervalNanos / speed).toLong()
            val frameStart = System.nanoTime()
            Bridge.nativeRunFrame()
            if (Bridge.nativeGetFrame(backPixels, frameDims)) {
                synchronized(frameLock) {
                    val back = if (frontIsA) bitmapB else bitmapA
                    back.setPixels(
                        backPixels, 0, Bridge.FRAME_WIDTH,
                        0, 0, Bridge.FRAME_WIDTH, Bridge.FRAME_HEIGHT,
                    )
                    frontIsA = !frontIsA
                }
                // The GL frame: the shade plane, its content hash
                // (static-frame reuse), and the deflicker mask from
                // the gate. Posted to the view's holder (emu thread);
                // the view's gate requests exactly one render.
                val sink = glFrameSink
                if (sink != null) {
                    val shades = ShadePlane.fromArgb(backPixels)
                    val hash = FrameHash.fnv1a64(shades)
                    val mask = deflickerGate.update(shades)
                    sink(GlFrame(backPixels.copyOf(), shades, hash, mask))
                }
                // Game rumble, once per delivered frame: while the
                // game rumbles the gate drives the vibrator and the
                // haptics engine's guard refuses UI fires.
                rumbleGate?.poll()
                Benchmark.recordDelivered(frameStart)
                mainHandler.post { onFrame?.invoke() }
                val capture = Benchmark.finishIfDone()
                if (capture != null) {
                    mainHandler.post { onBenchmarkDone?.invoke(capture) }
                }
                // One rewind snapshot a second, while the game runs
                // and rewind is armed.
                frameCount++
                if (frameCount % 60L == 0L) captureRewindSnapshot()
            }
            nextNanos += frameIntervalNanos
            val sleepNanos = nextNanos - System.nanoTime()
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(
                        sleepNanos / 1_000_000,
                        (sleepNanos % 1_000_000).toInt(),
                    )
                } catch (e: InterruptedException) {
                    break
                }
            } else {
                nextNanos = System.nanoTime()
            }
        }
    }

    private fun runAudio(gen: Long) {
        // Wait for the emu thread to learn the core's sample rate.
        var waited = 0
        while (audioRate == 0 && waited < 2000 && generation.get() == gen) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                return
            }
            waited += 10
        }
        // If the core never loaded, there is nothing to play: the emu
        // thread has already reported the error. Without this guard
        // the nativeReadAudio call below would throw.
        if (!Bridge.isLoaded()) return
        val rate = if (audioRate > 0) audioRate else 131072
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = if (minBuf > 0) minBuf * 4 else rate
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            null
        } ?: return
        audioTrack = track
        try {
            track.play()
        } catch (e: Exception) {
            return
        }
        try {
            val chunkFrames = 1024
            val samples = ShortArray(chunkFrames * 2)
            while (!stopRequested && generation.get() == gen) {
                if (pausedFlag.get() == 1) {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                val frames = Bridge.nativeReadAudio(samples, chunkFrames)
                if (frames > 0) {
                    try {
                        track.write(samples, 0, frames * 2)
                    } catch (e: Exception) {
                        // A write failure ends audio, never the session.
                        break
                    }
                } else {
                    try {
                        Thread.sleep(2)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            // Audio release, through the release-once gate: exactly
            // one of this exit path and stop() may stop and release
            // the track.
            if (audioReleaseGate.compareAndSet(false, true)) {
                try {
                    track.stop()
                } catch (e: Exception) {
                    // already stopped
                }
                try {
                    track.release()
                } catch (e: Exception) {
                    // already released
                }
            }
        }
    }

    companion object {
        /** Rewind ring capacity: 30 snapshots at 1/sec = 30 seconds. */
        const val REWIND_CAPACITY = 30
    }
}
