package app.dot

import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Dot — haptics engine tests.
//
// The pure strength mapping is tested exactly; the guard/outcome
// behaviour is tested through Robolectric's vibrator (a fire that
// reaches the vibrator reports PLAYED, a guard stop reports
// REJECTED). What the effect feels like is a device verdict, never
// a test verdict — these tests prove the wiring, not the feel.
// Copyright (c) 2026 Dot contributors. All rights reserved.

class HapticsPureTest {

    @Test
    fun strengthZeroIsOff() {
        assertEquals(0f, HapticsEngine.strengthScale(0), 0.0001f)
        assertEquals(0f, HapticsEngine.strengthScale(-5), 0.0001f)
        assertEquals(0, HapticsEngine.oneShotAmplitude(0))
    }

    @Test
    fun strengthMapsLinearlyWithFloor() {
        assertEquals(1.0f, HapticsEngine.strengthScale(100), 0.0001f)
        assertEquals(0.5f, HapticsEngine.strengthScale(50), 0.0001f)
        // The floor: any strength above 0 lands at least at 0.05.
        assertEquals(0.05f, HapticsEngine.strengthScale(1), 0.0001f)
        assertEquals(0.05f, HapticsEngine.strengthScale(4), 0.0001f)
        assertEquals(0.05f, HapticsEngine.strengthScale(5), 0.0001f)
        assertEquals(0.06f, HapticsEngine.strengthScale(6), 0.0001f)
    }

    @Test
    fun oneShotAmplitudeFloorsAtOne() {
        assertEquals(255, HapticsEngine.oneShotAmplitude(100))
        assertEquals(127, HapticsEngine.oneShotAmplitude(50))
        assertEquals(1, HapticsEngine.oneShotAmplitude(1))
    }
}

@RunWith(RobolectricTestRunner::class)
class HapticsEngineTest {

    private fun engine(
        enabled: Boolean = true,
        strength: Int = 100,
        vibrator: Vibrator? = realVibrator(),
        rumbling: Boolean = false,
    ): HapticsEngine {
        return HapticsEngine(
            vibrator = vibrator,
            settings = { HapticsSettings(enabled, strength) },
            isRumbling = { rumbling },
        )
    }

    private fun realVibrator(): Vibrator? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Test
    fun pressClickPlaysWhenEnabled() {
        assertEquals(HapticOutcome.PLAYED, engine().pressClick())
    }

    @Test
    fun disabledHapticsRejects() {
        assertEquals(HapticOutcome.REJECTED, engine(enabled = false).pressClick())
    }

    @Test
    fun strengthZeroRejects() {
        assertEquals(HapticOutcome.REJECTED, engine(strength = 0).pressClick())
    }

    @Test
    fun noVibratorRejects() {
        assertEquals(
            HapticOutcome.REJECTED,
            engine(vibrator = null).pressClick(),
        )
    }

    @Test
    fun gameRumbleWinsOverUiClicks() {
        assertEquals(HapticOutcome.REJECTED, engine(rumbling = true).pressClick())
        assertEquals(HapticOutcome.REJECTED, engine(rumbling = true).lightClick())
    }

    @Test
    fun releaseTickNeedsAHeldDirection() {
        // No direction held: the lift-off gate stops the release.
        assertEquals(HapticOutcome.REJECTED, engine().releaseTick())
        // After a direction tick, the release fires once...
        val e = engine()
        assertEquals(
            HapticOutcome.PLAYED,
            e.directionTick(0, Bridge.Input.UP),
        )
        assertEquals(HapticOutcome.PLAYED, e.releaseTick())
        // ...and only once.
        assertEquals(HapticOutcome.REJECTED, e.releaseTick())
    }

    @Test
    fun pauseRestartDropsTheReleaseGate() {
        val e = engine()
        e.directionTick(0, Bridge.Input.UP)
        e.dropReleaseGate()
        assertEquals(HapticOutcome.REJECTED, e.releaseTick())
    }

    @Test
    fun phoneFactsNeverThrow() {
        val facts = engine().phoneFacts()
        assertTrue(facts.apiLevel >= 24)
        assertEquals(true, facts.hapticsEnabled)
        assertEquals(100, facts.hapticStrength)
    }
}
