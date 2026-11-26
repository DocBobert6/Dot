package app.dot.gl

// Dot — the pure math of the final resample stages, shared by the
// shaders and the tests (GL-CHAIN-SPEC §5, §6, §8.6). The shaders
// are the runtime truth; these functions are what the pure tests
// run against (and what the host harness uses to check a stage
// without a GPU).
// SPDX-License-Identifier: GPL-3.0-or-later

object ResampleMath {

    // ---- sharp-bilinear (§5) ----

    /**
     * The sharp-bilinear warp: given an output-pixel centre [p] in
     * input-texel coordinates and the integer scale [s] = max(floor
     * (output/input), 1), returns the input-texel coordinate to
     * sample with one hardware-bilinear tap. Flat texel interiors
     * sample at their centres (no blend); blending is confined to 1
     * screen px at texel edges. Never samples outside [0, 1] in
     * normalised coordinates (the caller divides by the input size).
     */
    fun sharpBilinearSample(p: Float, s: Int): Float {
        val r = 0.5f - 0.5f / s
        val d = p - kotlin.math.floor(p) - 0.5f
        val clamped = d.coerceIn(-r, r)
        return kotlin.math.floor(p) + (d - clamped) * s + 0.5f
    }

    /**
     * The integer scale for a sharp-bilinear pass: s = max(floor
     * (output/input), 1). Dot's scales: 2 (1080p), 5 (4K), 3 (1440p).
     */
    fun sharpBilinearScale(input: Int, output: Int): Int =
        maxOf(output / input, 1)

    /**
     * True when the sharp-bilinear pass at this scale is exactly
     * nearest: at integer scale the warp maps every output centre to
     * a texel centre (s = output/input exactly, r = 0.5 - 0.5/s, and
     * the blend region vanishes at integer multiples). Checked by the
     * test at 5x.
     */
    fun sharpBilinearIsNearest(input: Int, output: Int): Boolean =
        output % input == 0

    // ---- Mitchell downscale (§6) ----

    /**
     * The Mitchell-Netravali kernel, B = C = 1/3 (§6.1). NOTE: the
     * kernel as written DOES dip below zero — w(1.25) ≈ -0.023,
     * w(1.5) ≈ -0.035 (largest magnitude ~0.035) — consistent with
     * the spec's own 1.8% step-edge overshoot figure. The spec's
     * "no negative lobes" claim (§6.1/§10.2) is WRONG; do not clamp
     * the weights to >= 0 (that would no longer be Mitchell).
     */
    fun mitchell(t: Float): Float {
        val a = kotlin.math.abs(t)
        return when {
            a < 1f -> (7f * a * a * a - 12f * a * a + 16f / 3f) / 6f
            a < 2f -> (-(7f / 3f) * a * a * a + 12f * a * a - 20f * a + 32f / 3f) / 6f
            else -> 0f
        }
    }

    /**
     * The Mitchell weights for one output pixel at downscale factor
     * [s] (input/output), centred at [centre] in input-texel
     * coordinates. 8 taps (support radius 2*s = 4 input texels each
     * side at s = 2). Weights are normalised per output pixel after
     * edge clamping (out-of-range taps are clamped to the edge texel,
     * and their weights still count — the spec's rule).
     *
     * Returns the list of (input texel index, normalised weight).
     */
    fun mitchellWeights(centre: Float, s: Float, inputSize: Int): List<Pair<Int, Float>> {
        val taps = mutableListOf<Pair<Int, Float>>()
        val base = kotlin.math.floor(centre).toInt() - 3
        var wsum = 0f
        for (j in 0 until 8) {
            val i = base + j
            val w = mitchell(((i + 0.5f) - centre) / s)
            val ci = i.coerceIn(0, inputSize - 1)
            taps.add(ci to w)
            wsum += w
        }
        return taps.map { (i, w) -> i to (w / wsum) }
    }

    // ---- 1-LSB blue-noise output dither (§8.6) ----

    /**
     * The output-dither step: given an sRGB code value [v] in [0,1]
     * (after RCAS, already 8-bit quantised by the shader's
     * floor(v*255+0.5)/255) and a blue-noise sample [n] in [0,1],
     * returns the dithered 8-bit code. The dither is exactly ±1/2 LSB:
     * dithered = floor((v + (n - 0.5)/255) * 255 + 0.5) / 255, so a
     * flat shade moves by at most 1 LSB.
     */
    fun outputDither(v: Float, n: Float): Int {
        val dithered = v + (n - 0.5f) / 255f
        return (kotlin.math.floor(dithered * 255f + 0.5f) / 255f * 255f).toInt().coerceIn(0, 255)
    }

    /** The blue-noise texel at (x, y), from the 64x64 table (0..255). */
    fun blueNoiseAt(x: Int, y: Int): Int =
        BLUE_NOISE_64[(y and 63) * 64 + (x and 63)].toInt() and 0xFF

    /** The per-frame blue-noise offset (in texels) for frame [frame]. */
    fun blueNoiseOffset(frame: Int): Pair<Int, Int> =
        (frame * 37 % 64) to (frame * 23 % 64)
}
