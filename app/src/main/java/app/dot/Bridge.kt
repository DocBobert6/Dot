package app.dot

// Dot — JNI bridge to the mGBA libretro core.
//
// The native side is a thin binding over the libretro API (a public
// standard defined in mgba's own tree, src/platform/libretro/libretro.h).
// JNI symbol names are Java_app_dot_Bridge_nativeXxx.
//
// Loading is lazy on purpose: the libraries are loaded only when a game
// starts, never at app launch and never from a static initializer, so
// the app can always open its library with no native code present.
// The core is DMG-shaped throughout: 160x144 RGB565 frames at
// 59.7275 fps, model forced to "Game Boy" so dual-mode games run in
// DMG mode, grayscale palette, no colour correction, no interframe
// blending.
// SPDX-License-Identifier: GPL-3.0-or-later

object Bridge {

    private var loaded = false

    /** True once both libraries have loaded. */
    fun isLoaded(): Boolean = loaded

    /**
     * Loads the core library ("mgba_libretro") and then the Dot bridge
     * ("dot-bridge"). Returns false if either library cannot be loaded.
     * Never throws. Call only when a game starts.
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

    external fun nativeApiVersion(): Int
    external fun nativeInit(systemDir: String, saveDir: String): Boolean
    external fun nativeLoadGame(path: String): Boolean

    /** [width, height, fpsMilli, sampleRate]. fpsMilli is frames per
     *  second times 1000 (for example 59727 for 59.727 fps). */
    external fun nativeGetAvInfo(): IntArray

    external fun nativeRunFrame()
    external fun nativeGetFrame(pixels: IntArray, dims: IntArray): Boolean
    external fun nativeReadAudio(samples: ShortArray, maxFrames: Int): Int
    external fun nativeSetAudioDiscard(discard: Boolean)
    external fun nativeSetInput(mask: Int)
    external fun nativeSetOption(key: String, value: String): Boolean
    external fun nativeSerializeSize(): Int
    external fun nativeSerialize(data: ByteArray): Boolean
    external fun nativeUnserialize(data: ByteArray): Boolean
    external fun nativeSaveRamSize(): Int
    external fun nativeReadSaveRam(data: ByteArray): Boolean
    external fun nativeWriteSaveRam(data: ByteArray): Boolean
    external fun nativeRtcSize(): Int
    external fun nativeReadRtc(data: ByteArray): Boolean
    external fun nativeWriteRtc(data: ByteArray): Boolean

    /**
     * Copies the core's 8 KB video RAM into [data] (must be at least
     * 8192 bytes). Used by the dither-inversion classifier, which
     * proves patterns from VRAM tiles. Returns false when no game is
     * loaded or the core exposes less than 8 KB of video RAM.
     */
    external fun nativeReadVram(data: ByteArray): Boolean

    external fun nativeUnloadGame()
    external fun nativeDeinit()
    external fun nativeLastError(): String

    /** DMG frame size, in pixels. The core must report exactly this. */
    const val FRAME_WIDTH = 160
    const val FRAME_HEIGHT = 144

    /** Libretro joypad bit for each DMG button (libretro id order). */
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
