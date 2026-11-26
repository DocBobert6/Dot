package app.dot

import org.junit.Assert.assertFalse
import org.junit.Test

// Dot — rumble-gate tests.
//
// The core is never loaded in a unit test, so poll() is a no-op
// here; these tests prove the gate's safe defaults. The live path
// (a rumble-pak game driving the phone vibrator, UI haptics
// suppressed while it rumbles) is a device verdict, not a test
// verdict — only Pokemon Pinball exercises it.
// SPDX-License-Identifier: GPL-3.0-or-later

class RumbleGateTest {

    @Test
    fun gateIsOpenWithNoCoreLoaded() {
        val gate = RumbleGate(null)
        assertFalse(gate.isRumbling())
        gate.poll()
        assertFalse(gate.isRumbling())
    }

    @Test
    fun stopIsSafeWhenIdle() {
        val gate = RumbleGate(null)
        gate.stop()
        assertFalse(gate.isRumbling())
    }
}
