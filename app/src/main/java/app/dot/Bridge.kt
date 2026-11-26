package app.dot

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — the Kotlin side of the native bridge.
//
// Everything the app needs from native code lives here: the mGBA
// libretro core ("mgba_libretro") plus Dot's own JNI layer
// ("dot-bridge"), which adapts the libretro calls to the shapes the
// session wants (frames as int arrays, audio as short arrays, save
// data as byte arrays). The native functions are declared below; the
// C++ that implements them is bridge/jni/bridge.cpp.
//
// The two libraries load lazily, the first time a game starts —
// never at app launch, never from a static initializer — so the
// library screen always opens even if native code is missing. Once
// loaded they stay loaded for the process; there is no unload path
// for the libraries themselves, only for the game inside the core.
//
// Frame geometry is fixed by the hardware Dot emulates: 160x144.
// The core is configured for the original Game Boy, so a dual-mode
// cartridge runs in DMG mode and the pixels are four greys, never
// colour.

object Bridge {

    private var loaded = false

    /** True after both native libraries have loaded successfully. */
    fun isLoaded(): Boolean = loaded

    /**
     * Loads "mgba_libretro" first and then "dot-bridge". Returns true
     * when both are in, false when either one cannot be loaded. Never
     * throws; safe to call again after a failure.
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("mgba_libretro")
            System.loadLibrary("dot-bridge")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    // -- core lifecycle ------------------------------------------------

    /** The libretro API version the bridge was built against. */
    external fun nativeApiVersion(): Int

    /** Starts the core. [systemDir] and [saveDir] are absolute paths. */
    external fun nativeInit(systemDir: String, saveDir: String): Boolean

    /** Loads the cartridge at [path] into the core. */
    external fun nativeLoadGame(path: String): Boolean

    /** Unloads the cartridge, if one is loaded. */
    external fun nativeUnloadGame()

    /** Shuts the core down. */
    external fun nativeDeinit()

    /** The last core-side error message, or "" when there is none. */
    external fun nativeLastError(): String

    // -- video ---------------------------------------------------------

    /**
     * [width, height, fpsMilli, sampleRate]: the frame size in
     * pixels, the frame rate times 1000 (59727 means 59.727 fps), and
     * the audio sample rate in Hz.
     */
    external fun nativeGetAvInfo(): IntArray

    /** Advances the core by one emulated frame. */
    external fun nativeRunFrame()

    /**
     * Copies the latest frame into [pixels] (160x144 ARGB) and its
     * size into [dims]. Returns false when no frame is ready.
     */
    external fun nativeGetFrame(pixels: IntArray, dims: IntArray): Boolean

    /**
     * Copies the core's 8 KB of video RAM into [data], which must be
     * at least 8192 bytes. The dither-inversion classifier reads tile
     * patterns from this. False when no game is loaded or the core
     * exposes less than 8 KB.
     */
    external fun nativeReadVram(data: ByteArray): Boolean

    // -- audio ---------------------------------------------------------

    /**
     * Drains up to [maxFrames] stereo frames into [samples]. Returns
     * the number of frames actually read (0 when the core has none
     * ready yet).
     */
    external fun nativeReadAudio(samples: ShortArray, maxFrames: Int): Int

    /**
     * When true, the core's audio output is thrown away instead of
     * queued — used while fast-forwarding or playing at half speed,
     * where the audio cannot keep up with the picture anyway.
     */
    external fun nativeSetAudioDiscard(discard: Boolean)

    // -- input and rumble ----------------------------------------------

    /** Pushes the live joypad mask (see [Input]) into the core. */
    external fun nativeSetInput(mask: Int)

    /**
     * The core's rumble level right now, 0..65535, 0 when the game
     * is not rumbling. The stronger of the two motors; driven by the
     * core for rumble-pak games. 0 when no game is loaded.
     */
    external fun nativeRumbleLevel(): Int

    // -- core options --------------------------------------------------

    /** Sets one libretro core option (for example "mgba_gb_model"). */
    external fun nativeSetOption(key: String, value: String): Boolean

    // -- save states and battery saves ---------------------------------

    /** The byte size of a serialised state, or 0 when unavailable. */
    external fun nativeSerializeSize(): Int

    /** Serialises the core into [data]. False when it cannot. */
    external fun nativeSerialize(data: ByteArray): Boolean

    /** Restores the core from a state serialised earlier. */
    external fun nativeUnserialize(data: ByteArray): Boolean

    /** The battery-save (SRAM) size in bytes, or 0 when the game has none. */
    external fun nativeSaveRamSize(): Int

    /** Reads the battery save into [data]. */
    external fun nativeReadSaveRam(data: ByteArray): Boolean

    /** Writes [data] back as the battery save. */
    external fun nativeWriteSaveRam(data: ByteArray): Boolean

    /** The real-time-clock state size in bytes, or 0 when the game has no RTC. */
    external fun nativeRtcSize(): Int

    /** Reads the RTC state into [data]. */
    external fun nativeReadRtc(data: ByteArray): Boolean

    /** Writes [data] back as the RTC state. */
    external fun nativeWriteRtc(data: ByteArray): Boolean

    /** The frame size Dot emulates, in pixels. */
    const val FRAME_WIDTH = 160
    const val FRAME_HEIGHT = 144

    /**
     * One bit per DMG button, in libretro joypad id order. A set bit
     * means the button is held.
     */
    object Input {
        const val B = 1 shl 0
        const val SELECT = 1 shl 2
        const val START = 1 shl 3
        const val UP = 1 shl 4
        const val DOWN = 1 shl 5
        const val LEFT = 1 shl 6
        const val RIGHT = 1 shl 7
        const val A = 1 shl 8
    }
}
