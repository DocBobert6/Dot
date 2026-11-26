package app.dot.gl

// Dot — the gated flicker-transparency CPU gate (GL-CHAIN-SPEC §7).
// The GBI deflicker rule, and nothing else: blend frame n with
// frame n-1 only where frame n == frame n-2 and frame n != frame
// n-1 (an exact period-2 alternation), sustained for >= 2
// alternations, at 0.55/0.45 in linear light. When the alternation
// stops, the blend decays back to unblended over 3 frames (a linear
// ramp inside the 2-4 frame window §7 recommends).
//
// The gate tests on the RAW shade planes (what the session has).
// The blend operates on the dither-inverted frames (GL-CHAIN-SPEC
// §1.1) — a false candidate is harmless when the inverted values
// are equal, since blending equal values is a no-op.
//
// Phase 1 limitation (decided): Dot Phase 1 has no scroll data from
// the core, so there is no scroll compensation. A scrolling periodic
// background CAN false-positive (the sustain + decay gates bound the
// damage); the device A/B hunts it. Scroll compensation is Phase 2.
//
// Output: a per-texel blend mask, 0..255 (0 = unblended, 255 = full
// 0.55/0.45 blend), uploaded as an R8 texture. The shader applies
// w = 0.45 * m, out = (1 - w) * frame[n] + w * frame[n-1].
// Copyright (c) 2026 Dot contributors. All rights reserved.

class DeflickerGate {
    private var prev: ByteArray? = null
    private var prevPrev: ByteArray? = null
    private val sustain = ByteArray(ShadePlane.SIZE) // alternation count per texel
    private val blend = FloatArray(ShadePlane.SIZE) // current mask value per texel, 0..1

    /** The minimum alternations before blending starts. */
    private val sustainThreshold = 2

    /** The decay step: a full blend reaches 0 in 3 non-alternating frames. */
    private val decayStep = 1f / 3f

    /**
     * Updates the gate with the current frame's shade plane and
     * returns the blend mask for this frame (0..255 per texel).
     * The first two frames have no history and return an all-zero mask.
     */
    fun update(current: ByteArray): ByteArray {
        require(current.size == ShadePlane.SIZE) {
            "shade plane must be exactly ${ShadePlane.SIZE} texels"
        }
        val mask = ByteArray(ShadePlane.SIZE)
        val p = prev
        val pp = prevPrev
        if (p != null && pp != null) {
            for (i in current.indices) {
                val candidate = current[i] == pp[i] && current[i] != p[i]
                if (candidate) {
                    val s = (sustain[i].toInt() + 1).coerceAtMost(127).toByte()
                    sustain[i] = s
                    if (s.toInt() >= sustainThreshold) {
                        blend[i] = 1f
                    }
                } else {
                    sustain[i] = 0
                    if (blend[i] > 0f) {
                        blend[i] = (blend[i] - decayStep).coerceAtLeast(0f)
                    }
                }
                mask[i] = Math.round(blend[i] * 255f).coerceIn(0, 255).toByte()
            }
        }
        prevPrev = p
        prev = current.copyOf()
        return mask
    }

    /** Clears all state (new game, renderer reset). */
    fun reset() {
        prev = null
        prevPrev = null
        sustain.fill(0)
        blend.fill(0f)
    }
}
