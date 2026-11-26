package app.dot

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — gamepad input (SHELL-SPEC §5). Player 1 only.
//
// Buttons and the D-pad arrive as key events; the left stick and
// the D-pad hat arrive as motion events. Both fold into one mask,
// which the player screen ORs with the touch mask before it
// reaches the session — the session's setInput stores the merged
// mask, and the emu loop stays the single pusher to the core. The
// mask-change callback re-stores the same merged mask, never a
// direct core push.
//
// DMG mapping drops L/R: the DMG has no shoulders, no X/Y, no
// second stick. Button X / Y, L2/R2, the right stick and L3/R3 are
// unmapped by decision, not by oversight (Dot has no turbo and
// the DMG has no X/Y). A gamepad press fires no haptic — haptics
// come only from the touch controls.
//
// The enabled gate is a volatile flag, set by the player screen
// on entry and cleared on exit, so the pad never hijacks keys on
// the library screen. Only events from a gamepad/joystick source
// are ever consumed; everything else falls through to default
// dispatch. The Back key is never swallowed.

class Gamepad {

    /** The enabled gate (SHELL-SPEC §5). Set on player entry, cleared on exit. */
    @Volatile
    var enabled: Boolean = false

    /** Called with the gamepad's mask portion whenever it changes. */
    var onMaskChanged: ((Int) -> Unit)? = null

    private val pressedKeys = HashSet<Int>()
    private var stickMask = 0
    private var hatMask = 0

    /** The gamepad's current mask portion (keys + stick + hat). */
    fun currentMask(): Int {
        var mask = stickMask or hatMask
        for (keyCode in pressedKeys) {
            keyCodeToBit(keyCode)?.let { mask = mask or it }
        }
        return mask
    }

    /**
     * Handles a key event. Returns true when the event was consumed
     * (a mapped gamepad button, down or up). Repeat downs are
     * idempotent — the button is already in the pressed set.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        if (!isGamepadSource(event.source)) return false
        val bit = keyCodeToBit(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (pressedKeys.add(event.keyCode)) emit()
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (pressedKeys.remove(event.keyCode)) emit()
                return true
            }
            else -> return false
        }
    }

    /**
     * Handles a motion event. Consumed only when the source is a
     * joystick and the action is ACTION_MOVE. The stick and hat
     * masks are OR-ed; the callback fires only when the merged
     * mask actually changes.
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK !=
            InputDevice.SOURCE_JOYSTICK
        ) {
            return false
        }
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val before = currentMask()
        val masks = motionMask(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        stickMask = masks.first
        hatMask = masks.second
        if (currentMask() != before) emit()
        return true
    }

    /**
     * Clears every held pad button, the stick state and the hat
     * state (pause clears pad state; dispose releases held buttons).
     * The callback fires when the mask actually changes.
     */
    fun clear() {
        if (pressedKeys.isEmpty() && stickMask == 0 && hatMask == 0) return
        pressedKeys.clear()
        stickMask = 0
        hatMask = 0
        emit()
    }

    private fun emit() {
        onMaskChanged?.invoke(currentMask())
    }

    companion object {
        /** Fixed axis threshold (§5): no setting. */
        const val AXIS_THRESHOLD = 0.5f

        fun isGamepadSource(source: Int): Boolean {
            return source and InputDevice.SOURCE_GAMEPAD ==
                InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK ==
                InputDevice.SOURCE_JOYSTICK ||
                source and InputDevice.SOURCE_DPAD ==
                InputDevice.SOURCE_DPAD
        }

        /** The DMG mask bit for a keycode, or null when unmapped. */
        fun keyCodeToBit(keyCode: Int): Int? {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> Bridge.Input.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Bridge.Input.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> Bridge.Input.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> Bridge.Input.RIGHT
                KeyEvent.KEYCODE_BUTTON_A -> Bridge.Input.A
                KeyEvent.KEYCODE_BUTTON_B -> Bridge.Input.B
                KeyEvent.KEYCODE_BUTTON_START -> Bridge.Input.START
                KeyEvent.KEYCODE_BUTTON_SELECT -> Bridge.Input.SELECT
                else -> null
            }
        }

        /**
         * The stick and hat masks for one motion event, as a pair:
         * the stick (AXIS_X/Y) and the hat (AXIS_HAT_X/Y) OR into
         * the same direction bits, so a D-pad that reports as a hat
         * and a stick that reports as axes agree.
         */
        fun motionMask(
            stickX: Float,
            stickY: Float,
            hatX: Float,
            hatY: Float,
        ): Pair<Int, Int> {
            return axisMask(stickX, stickY) to axisMask(hatX, hatY)
        }

        /**
         * The direction mask for a stick/hat deflection (x right,
         * y down). Each axis is independent, so a diagonal counts
         * both directions. Exactly at the threshold counts as
         * centred — the deflection must pass 0.5.
         */
        fun axisMask(x: Float, y: Float): Int {
            var mask = 0
            if (x > AXIS_THRESHOLD) mask = mask or Bridge.Input.RIGHT
            else if (x < -AXIS_THRESHOLD) mask = mask or Bridge.Input.LEFT
            if (y > AXIS_THRESHOLD) mask = mask or Bridge.Input.DOWN
            else if (y < -AXIS_THRESHOLD) mask = mask or Bridge.Input.UP
            return mask
        }
    }
}
