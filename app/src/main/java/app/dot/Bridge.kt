package app.dot

// Dot — JNI bridge to the mGBA libretro core.
//
// Phase 0 proves the link only: one function, retro_api_version,
// symbol-verified in CI. Nothing in the app loads native code at
// startup — load() is called only when a game starts, which is what
// the startup gate proves. The player, and the rest of the bridge,
// land in Phase 1.
// SPDX-License-Identifier: GPL-3.0-or-later

object Bridge {

    private var loaded = false

    /** Loads libdot-bridge.so (and, through it, the core). Idempotent. */
    @Synchronized
    fun load() {
        if (!loaded) {
            System.loadLibrary("dot-bridge")
            loaded = true
        }
    }

    /** libretro API version of the linked core. Loads the bridge first. */
    fun apiVersion(): Int {
        load()
        return nativeApiVersion()
    }

    private external fun nativeApiVersion(): Int
}
