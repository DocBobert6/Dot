package app.dot

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

// Dot — haptics engine.
//
// Behaviour follows SHELL-SPEC §2 (the guard order, the per-button
// character, the probe shape), which itself records what Atomic
// proved on device (alpha.41 probe, alpha.48 fix, alpha.54
// gate-removal). The implementation here is Dot's own, written
// against the Android VibrationEffect API; no Atomic source was
// read or copied for it.
//
// One guard, in this order, for every fire: game rumble wins (the
// RumbleGate suppresses UI haptics while the game itself rumbles),
// then haptics off / strength 0 / no vibrator. The effect is built
// lazily, after the guard — building first would throw at strength
// 0, since a one-shot rejects amplitude 0. The outcome of every
// fire is PLAYED, REJECTED, or THREW; the game ignores it, only
// the probe screen reports it.
//
// Per-button character (DMG mapping drops L/R and the stick):
// A/B full press click; Start/Select the same click
// lighter (0.6x, envelope on API 36+); D-pad direction change a
// lighter tick (0.6x, envelope on a press from neutral or a roll
// to a cardinal, plain tick on a roll to a diagonal); release a
// soft envelope settle (0.5x), never another press-strength tick.
// A/B deliberately never take the envelope (alpha.15 device
// verdict: the envelope is worse on A/B).
//
// The envelope is a refinement only, and unproven on the Pixel
// 10a: nothing here claims it feels better — that is a device
// verdict, not something tests settle. Every envelope attempt is
// fallback-first: a claimed envelope that will not build falls
// back to the plain effect, and a failed fallback stays silent
// and reports THREW.
// Copyright (c) 2026 Dot contributors. All rights reserved.

/** Outcome of one haptic fire. */
enum class HapticOutcome { PLAYED, REJECTED, THREW }

/** The two haptics settings, read live on every fire. */
data class HapticsSettings(val enabled: Boolean, val strength: Int)

class HapticsEngine(
    private val vibrator: Vibrator?,
    private val settings: () -> HapticsSettings,
    /** True while the game itself is rumbling — game rumble wins over UI clicks. */
    private val isRumbling: () -> Boolean = { false },
) {

    companion object {
        const val TAG = "DotHaptics"

        /** Amplitude floor: any strength above 0 lands at least here, so the weakest setting is still felt. */
        const val AMPLITUDE_FLOOR = 0.05f

        /** Plain-click one-shot length, ms. */
        const val CLICK_ONE_SHOT_MS = 12L

        /** Plain-tick one-shot length, ms. */
        const val TICK_ONE_SHOT_MS = 8L

        /** Envelope press click: ~15 ms total. */
        const val ENVELOPE_CLICK_MS = 15L

        /** Release settle: ~25 ms total, longer than the click so it reads as a settle. */
        const val ENVELOPE_RELEASE_MS = 25L

        /** Start/Select and D-pad character scale. */
        const val LIGHT_SCALE = 0.6f

        /** Release character scale. */
        const val RELEASE_SCALE = 0.5f

        /** D-pad direction envelope sharpness is pulled down a quarter from resonant. */
        const val DIRECTION_SHARPNESS_SCALE = 0.75f

        /** Strength (0-100) to primitive scale (0.0-1.0), with the amplitude floor. Pure, tested. */
        fun strengthScale(strength: Int): Float {
            if (strength <= 0) return 0f
            return (strength / 100f).coerceAtLeast(AMPLITUDE_FLOOR)
        }

        /** Strength (0-100) to one-shot amplitude (1-255). 0 means off. Pure, tested. */
        fun oneShotAmplitude(strength: Int): Int {
            if (strength <= 0) return 0
            return (strength * 255 / 100).coerceIn(1, 255)
        }
    }

    /** Lift-off gate: the release fires only when a direction was actually held, exactly once. */
    private var directionHeld = false

    // -- guard -------------------------------------------------------

    /** The primitive scale for this fire, or null when the guard stops it. */
    private fun guardScale(): Float? {
        if (isRumbling()) return null
        if (vibrator == null) return null
        val s = settings()
        if (!s.enabled) return null
        return strengthScale(s.strength).takeIf { it > 0f }
    }

    private fun fire(effect: VibrationEffect): HapticOutcome {
        val vib = vibrator ?: return HapticOutcome.REJECTED
        return try {
            vib.vibrate(effect)
            HapticOutcome.PLAYED
        } catch (e: Exception) {
            Log.w(TAG, "vibrate threw", e)
            HapticOutcome.THREW
        }
    }

    private fun fireLegacy(durationMs: Long): HapticOutcome {
        val vib = vibrator ?: return HapticOutcome.REJECTED
        return try {
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
            HapticOutcome.PLAYED
        } catch (e: Exception) {
            Log.w(TAG, "vibrate threw", e)
            HapticOutcome.THREW
        }
    }

    // -- capability probes (guarded: a vibrator that cannot answer reports "not supported") --

    fun hasVibrator(): Boolean = vibrator != null

    fun primitivesSupported(vararg primitives: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val vib = vibrator ?: return false
        return try {
            vib.areAllPrimitivesSupported(*primitives)
        } catch (_: Exception) {
            false
        }
    }

    fun envelopeSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val vib = vibrator ?: return false
        return try {
            vib.areEnvelopeEffectsSupported()
        } catch (_: Exception) {
            false
        }
    }

    /** Resonant frequency's place in the profile's supported range, clamped 0-1; 1.0 when unknown. */
    fun resonantSharpness(): Float {
        if (Build.VERSION.SDK_INT < 36) return 1.0f
        val vib = vibrator ?: return 1.0f
        return try {
            val resonant = vib.resonantFrequency
            val profile = vib.frequencyProfile ?: return 1.0f
            val min = profile.minFrequencyHz
            val max = profile.maxFrequencyHz
            if (resonant <= 0f || max <= min) return 1.0f
            ((resonant - min) / (max - min)).coerceIn(0f, 1f)
        } catch (_: Exception) {
            1.0f
        }
    }

    // -- effect construction ------------------------------------------

    /** Plain press click at [scale]: primitive, else prebaked at full strength, else one-shot. */
    private fun plainClickEffect(scale: Float, strength: Int): HapticOutcome {
        if (primitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            return try {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .compose()
                fire(effect)
            } catch (_: Exception) {
                plainClickFallback(strength)
            }
        }
        return plainClickFallback(strength)
    }

    private fun plainClickFallback(strength: Int): HapticOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && strength >= 100) {
            return try {
                fire(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } catch (_: Exception) {
                oneShotClick(strength)
            }
        }
        return oneShotClick(strength)
    }

    private fun oneShotClick(strength: Int): HapticOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                fire(
                    VibrationEffect.createOneShot(
                        CLICK_ONE_SHOT_MS, oneShotAmplitude(strength),
                    ),
                )
            } catch (_: Exception) {
                fireLegacy(CLICK_ONE_SHOT_MS)
            }
        }
        return fireLegacy(CLICK_ONE_SHOT_MS)
    }

    /** Plain tick at [scale]: tick primitive, else an 8 ms one-shot. Never the predefined click. */
    private fun plainTickEffect(scale: Float, strength: Int): HapticOutcome {
        if (primitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            return try {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                    .compose()
                fire(effect)
            } catch (_: Exception) {
                oneShotTick(scale, strength)
            }
        }
        return oneShotTick(scale, strength)
    }

    private fun oneShotTick(scale: Float, strength: Int): HapticOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude =
                (oneShotAmplitude(strength) * scale).toInt().coerceIn(1, 255)
            return try {
                fire(VibrationEffect.createOneShot(TICK_ONE_SHOT_MS, amplitude))
            } catch (_: Exception) {
                fireLegacy(TICK_ONE_SHOT_MS)
            }
        }
        return fireLegacy(TICK_ONE_SHOT_MS)
    }

    /**
     * Envelope click: instant attack (~1 ms), decay taking the rest of
     * [totalMs]. Null when envelopes are unsupported or will not build.
     */
    private fun envelopeEffect(
        scale: Float,
        sharpness: Float,
        totalMs: Long,
    ): VibrationEffect? {
        if (!envelopeSupported()) return null
        return try {
            val attackMs = 1L
            val decayMs = (totalMs - attackMs).coerceAtLeast(attackMs)
            VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(sharpness)
                .addControlPoint(scale, sharpness, attackMs)
                .addControlPoint(0f, sharpness, decayMs)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    // -- per-button character ------------------------------------------

    /** A/B: the full press click. Never the envelope. */
    fun pressClick(): HapticOutcome {
        val scale = guardScale() ?: return HapticOutcome.REJECTED
        return plainClickEffect(scale, settings().strength)
    }

    /** Start/Select: the same click, lighter — envelope first, plain click as fallback. */
    fun lightClick(): HapticOutcome {
        val scale = guardScale() ?: return HapticOutcome.REJECTED
        val strength = settings().strength
        val lightScale = scale * LIGHT_SCALE
        val envelope = envelopeEffect(lightScale, resonantSharpness(), ENVELOPE_CLICK_MS)
        if (envelope != null) return fire(envelope)
        return plainClickEffect(lightScale, strength)
    }

    /**
     * D-pad direction change while held. Routing: a press from neutral
     * (previous mask empty) or a roll to a cardinal (new mask holds
     * exactly one direction bit) takes the direction envelope; a roll
     * to a diagonal takes the plain tick. Where there are no envelopes
     * the envelope branch falls back to the same 0.6x tick, so the
     * choice is only about character, never about how hard the roll
     * lands. Fires only when the direction set changes to something
     * non-empty — the caller checks that.
     */
    fun directionTick(prevMask: Int, newMask: Int): HapticOutcome {
        val scale = guardScale() ?: return HapticOutcome.REJECTED
        val strength = settings().strength
        val tickScale = scale * LIGHT_SCALE
        directionHeld = true
        val fromNeutral = prevMask == 0
        val toCardinal = Integer.bitCount(newMask) == 1
        if (fromNeutral || toCardinal) {
            val sharpness = resonantSharpness() * DIRECTION_SHARPNESS_SCALE
            val envelope = envelopeEffect(tickScale, sharpness, ENVELOPE_CLICK_MS)
            if (envelope != null) return fire(envelope)
        }
        return plainTickEffect(tickScale, strength)
    }

    /**
     * Release: a soft envelope settle, not another tick. Fires only on
     * lift-off (the gate remembers whether a direction was held) and
     * exactly once; a slide back to neutral while still touching fires
     * nothing. Where there are no envelopes the fallback is the tick
     * at 0.5x — the same amplitude the envelope lands at.
     */
    fun releaseTick(): HapticOutcome {
        if (!directionHeld) return HapticOutcome.REJECTED
        directionHeld = false
        val scale = guardScale() ?: return HapticOutcome.REJECTED
        val strength = settings().strength
        val releaseScale = scale * RELEASE_SCALE
        val envelope = envelopeEffect(
            releaseScale, resonantSharpness() * RELEASE_SCALE, ENVELOPE_RELEASE_MS,
        )
        if (envelope != null) return fire(envelope)
        return plainTickEffect(releaseScale, strength)
    }

    /** A pause restart drops the lift-off gate, so it never fires on pause. */
    fun dropReleaseGate() {
        directionHeld = false
    }

    /** FF / RW hold buttons: a light click on press. */
    fun utilityClick(): HapticOutcome = lightClick()

    /** FF / RW hold buttons: a release tick on lift. No lift-off gate — the button's own hold loop calls this. */
    fun utilityReleaseTick(): HapticOutcome {
        val scale = guardScale() ?: return HapticOutcome.REJECTED
        return plainTickEffect(scale * RELEASE_SCALE, settings().strength)
    }

    /** The 0.5x toggle: a light click on toggle. */
    fun toggleClick(): HapticOutcome = lightClick()

    // -- probe (Settings -> Controls) ------------------------------------

    fun probePressClick(): HapticOutcome = pressClick()
    fun probeLightClick(): HapticOutcome = lightClick()
    fun probeRollTick(): HapticOutcome = directionTick(0, Bridge.Input.UP)
    fun probeReleaseTick(): HapticOutcome {
        directionHeld = true
        return releaseTick()
    }

    fun probeEnvelopeClick(): HapticOutcome {
        val scale = guardScale() ?: return HapticOutcome.REJECTED
        val envelope = envelopeEffect(scale, resonantSharpness(), ENVELOPE_CLICK_MS)
        if (envelope != null) return fire(envelope)
        return plainClickEffect(scale, settings().strength)
    }

    // -- phone facts (probe screen) ---------------------------------------

    data class PhoneFacts(
        val apiLevel: Int,
        val hasVibrator: Boolean,
        val envelopeSupported: Boolean,
        val clickSupported: Boolean,
        val tickSupported: Boolean,
        val thudSupported: Boolean,
        val hapticsEnabled: Boolean,
        val hapticStrength: Int,
    )

    fun phoneFacts(): PhoneFacts {
        val s = settings()
        return PhoneFacts(
            apiLevel = Build.VERSION.SDK_INT,
            hasVibrator = hasVibrator(),
            envelopeSupported = envelopeSupported(),
            clickSupported = primitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            ),
            tickSupported = primitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK,
            ),
            thudSupported = primitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD,
            ),
            hapticsEnabled = s.enabled,
            hapticStrength = s.strength,
        )
    }
}
