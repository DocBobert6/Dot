package app.dot

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — gamepad mapping + gate tests (SHELL-SPEC §5). Robolectric
// supplies the KeyEvent/MotionEvent implementations.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GamepadTest {

    private fun keyEvent(action: Int, keyCode: Int, source: Int): KeyEvent {
        return KeyEvent(action, keyCode).apply { setSource(source) }
    }

    @Test
    fun mappingCoversTheDmgButtons() {
        assertEquals(Bridge.Input.UP, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Bridge.Input.DOWN, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(Bridge.Input.LEFT, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Bridge.Input.RIGHT, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(Bridge.Input.A, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(Bridge.Input.B, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Bridge.Input.START, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(Bridge.Input.SELECT, Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun unmappedButtonsStayUnmapped() {
        // X/Y (no turbo in Dot), L2/R2, L3/R3: decided, not overlooked.
        assertNull(Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_X))
        assertNull(Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_Y))
        assertNull(Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_L2))
        assertNull(Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_R2))
        assertNull(Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertNull(Gamepad.keyCodeToBit(KeyEvent.KEYCODE_BUTTON_THUMBR))
    }

    @Test
    fun axisThresholdIsStrict() {
        assertEquals(0, Gamepad.axisMask(0.5f, -0.5f))
        assertEquals(Bridge.Input.RIGHT, Gamepad.axisMask(0.51f, 0f))
        assertEquals(Bridge.Input.LEFT, Gamepad.axisMask(-0.51f, 0f))
        assertEquals(Bridge.Input.DOWN, Gamepad.axisMask(0f, 0.51f))
        assertEquals(Bridge.Input.UP, Gamepad.axisMask(0f, -0.51f))
        assertEquals(
            Bridge.Input.RIGHT or Bridge.Input.DOWN,
            Gamepad.axisMask(0.8f, 0.8f),
        )
    }

    @Test
    fun disabledPadConsumesNothing() {
        val pad = Gamepad()
        assertFalse(
            pad.onKeyEvent(
                keyEvent(
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A,
                    InputDevice.SOURCE_GAMEPAD,
                ),
            ),
        )
        assertEquals(0, pad.currentMask())
    }

    @Test
    fun buttonDownAndUpTrackTheMask() {
        val pad = Gamepad().apply { enabled = true }
        var lastMask = -1
        pad.onMaskChanged = { lastMask = it }
        assertTrue(
            pad.onKeyEvent(
                keyEvent(
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A,
                    InputDevice.SOURCE_GAMEPAD,
                ),
            ),
        )
        assertEquals(Bridge.Input.A, pad.currentMask())
        assertEquals(Bridge.Input.A, lastMask)
        // A repeat down is idempotent: still consumed, mask unchanged.
        assertTrue(
            pad.onKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
                    .apply { setSource(InputDevice.SOURCE_GAMEPAD) },
            ),
        )
        assertEquals(Bridge.Input.A, pad.currentMask())
        assertTrue(
            pad.onKeyEvent(
                keyEvent(
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A,
                    InputDevice.SOURCE_GAMEPAD,
                ),
            ),
        )
        assertEquals(0, pad.currentMask())
        assertEquals(0, lastMask)
    }

    @Test
    fun backKeyIsNeverSwallowed() {
        val pad = Gamepad().apply { enabled = true }
        assertFalse(
            pad.onKeyEvent(
                keyEvent(
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
                    InputDevice.SOURCE_GAMEPAD,
                ),
            ),
        )
    }

    @Test
    fun keyboardSourceFallsThrough() {
        val pad = Gamepad().apply { enabled = true }
        assertFalse(
            pad.onKeyEvent(
                keyEvent(
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A,
                    InputDevice.SOURCE_KEYBOARD,
                ),
            ),
        )
        assertEquals(0, pad.currentMask())
    }

    @Test
    fun motionEventDrivesTheStickMask() {
        val pad = Gamepad().apply { enabled = true }
        var lastMask = -1
        pad.onMaskChanged = { lastMask = it }
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
            .apply {
                setSource(InputDevice.SOURCE_JOYSTICK)
                setAxisValue(MotionEvent.AXIS_X, 1f)
            }
        assertTrue(pad.onMotionEvent(event))
        assertEquals(Bridge.Input.RIGHT, pad.currentMask())
        assertEquals(Bridge.Input.RIGHT, lastMask)
        event.recycle()
    }

    @Test
    fun clearReleasesEverything() {
        val pad = Gamepad().apply { enabled = true }
        pad.onKeyEvent(
            keyEvent(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B,
                InputDevice.SOURCE_GAMEPAD,
            ),
        )
        assertEquals(Bridge.Input.B, pad.currentMask())
        pad.clear()
        assertEquals(0, pad.currentMask())
    }
}
