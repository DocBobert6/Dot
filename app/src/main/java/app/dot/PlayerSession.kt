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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Dot — one game session: the emu thread, the audio thread, and the
// save discipline around them.
//
// Shape follows Atomic's proven session (two threads, generation
// guard, core ownership token, pinned teardown order), re-derived
// for DMG: 160x144 RGB565 frames at 59.7275 fps, GB sample rate
// 131072 Hz, 8 KB of video RAM, 4 manual save slots plus Auto.
//
// Teardown order (pinned, each step individually guarded, joins
// bounded at 2000 ms): JOIN_EMU -> JOIN_AUDIO -> WRITE_SAVE ->
// RELEASE_AUDIO -> UNLOAD_CORE. The core is never unloaded under a
// live emu thread: if the emu thread will not die, the unload is
// skipped and the core is left loaded until the process ends.
// SPDX-License-Identifier: GPL-3.0-or-later

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

    private val generation = AtomicLong(0)
    private val inputMask = AtomicInteger(0)
    private val pausedFlag = AtomicInteger(0)
    @Volatile private var stopRequested = false
    @Volatile private var audioDiscard = false

    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var audioRate = 0

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

    /** Sets the live input mask (libretro joypad bits, Bridge.Input).
     *  A no-op until the native libraries are loaded, so a tap in the
     *  first frames before the core starts cannot throw. */
    fun setInput(mask: Int) {
        inputMask.set(mask)
        if (Bridge.isLoaded()) {
            Bridge.nativeSetInput(mask)
        }
    }

    fun start() {
        val gen = generation.incrementAndGet()
        _state.value = State.Loading
        emuThread = Thread({
            runEmu(gen)
        }, "dot-emu").also { it.start() }
        audioThread = Thread({
            runAudio(gen)
        }, "dot-audio").also { it.start() }
    }

    fun pause() {
        pausedFlag.set(1)
        writeBatterySave()
        writeAutoState()
        _state.value = State.Paused
    }

    fun resume() {
        pausedFlag.set(0)
        _state.value = State.Running
    }

    fun isPaused(): Boolean = pausedFlag.get() == 1

    /** Stops the session from a background thread. Never call on the
     *  main thread: the joins block up to 2 s each. */
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
        writeBatterySave()
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            // release-once gate: a second stop() throws
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            // already released
        }
        audioTrack = null
        // Never unload under a live emu thread.
        val emuAlive = emu?.isAlive == true
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

    fun saveState(slot: Int): Boolean {
        val size = Bridge.nativeSerializeSize()
        if (size <= 0) return false
        val data = ByteArray(size)
        if (!Bridge.nativeSerialize(data)) return false
        val ok = Store.atomicWrite(
            Store.stateFile(filesDir, entry.sha256, slot), data,
        )
        if (ok) slotHasData[slot] = true
        return ok
    }

    fun loadState(slot: Int): Boolean {
        val data = Store.readBytes(
            Store.stateFile(filesDir, entry.sha256, slot),
        ) ?: return false
        return Bridge.nativeUnserialize(data)
    }

    fun loadAutoState(): Boolean {
        val data = Store.readBytes(
            Store.autoStateFile(filesDir, entry.sha256),
        ) ?: return false
        return Bridge.nativeUnserialize(data)
    }

    private fun writeAutoState() {
        val size = Bridge.nativeSerializeSize()
        if (size <= 0) return
        val data = ByteArray(size)
        if (!Bridge.nativeSerialize(data)) return
        Store.atomicWrite(Store.autoStateFile(filesDir, entry.sha256), data)
    }

    // -- battery save --------------------------------------------------

    private fun writeBatterySave() {
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
                Benchmark.recordDelivered(frameStart)
                mainHandler.post { onFrame?.invoke() }
                val capture = Benchmark.finishIfDone()
                if (capture != null) {
                    mainHandler.post { onBenchmarkDone?.invoke(capture) }
                }
            }
            nextNanos += intervalNanos
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
        val chunkFrames = 1024
        val samples = ShortArray(chunkFrames * 2)
        while (!stopRequested && generation.get() == gen) {
            if (pausedFlag.get() == 1) {
                if (audioDiscard) {
                    Bridge.nativeSetAudioDiscard(true)
                    audioDiscard = false
                }
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
    }
}
