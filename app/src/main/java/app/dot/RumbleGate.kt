package app.dot

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

// Dot — the game-rumble gate, designed for Dot (there is no rumble
// gate in Atomic to port: Atomic has no cartridge rumble and no
// native rumble callback).
//
// The rule: game rumble wins over UI clicks. The libretro core
// drives rumble for rumble-pak games (Pokemon Pinball) through the
// bridge's rumble interface (bridge.cpp: GET_RUMBLE_INTERFACE).
// This gate reads the core's rumble level once per delivered frame
// (called from the session's emu loop) and drives the phone's
// vibrator with a repeating waveform while the game rumbles. While
// rumbling, the haptics engine's guard refuses every UI fire, so a
// UI click can never cancel the game rumble or land on top of it —
// the fire reports REJECTED, not THREW, because nothing went
// wrong. When the game stops rumbling the gate reopens and the
// vibrator is cancelled.
//
// If the core never calls the rumble interface (no rumble-pak game
// loaded, or a core that does not drive rumble), the level stays 0
// and the gate is always open — the gate costs one JNI call per
// frame and nothing else. Game rumble is best-effort throughout:
// a vibrator failure never touches the session.
// SPDX-License-Identifier: GPL-3.0-or-later

class RumbleGate(private val vibrator: Vibrator?) {

    @Volatile
    private var rumbling = false
    private var lastLevel = 0

    /** True while the game itself is rumbling. */
    fun isRumbling(): Boolean = rumbling

    /**
     * Called once per delivered frame (emu thread). Reads the core's
     * rumble level and starts / updates / stops the phone vibration
     * to match. No-op until the core is loaded.
     */
    fun poll() {
        if (!Bridge.isLoaded()) return
        val level = try {
            Bridge.nativeRumbleLevel()
        } catch (_: Throwable) {
            0
        }
        val active = level > 0
        if (active == rumbling && level == lastLevel) return
        lastLevel = level
        rumbling = active
        val vib = vibrator ?: return
        try {
            if (active) {
                val amplitude = (level * 255 / 65535).coerceIn(1, 255)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 100),
                            intArrayOf(0, amplitude),
                            1,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 100), 1)
                }
            } else {
                vib.cancel()
            }
        } catch (_: Throwable) {
            // Game rumble is best-effort; a vibrator failure never
            // touches the session.
        }
    }

    /** Stops any game rumble in flight (session end / pause). */
    fun stop() {
        if (!rumbling) return
        rumbling = false
        lastLevel = 0
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
            // Best-effort.
        }
    }
}
