package app.dot.gl

// Dot — the sRGB EOTF pair and the palette's linear-light values
// (GL-CHAIN-SPEC §4.1). All blends and averages in the chain happen in
// linear light; code values are only for storage and display.
// SPDX-License-Identifier: GPL-3.0-or-later

object LinearLight {
    /** sRGB code value in [0,1] -> linear light in [0,1]. */
    fun decode(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f
        else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /** Linear light in [0,1] -> sRGB code value in [0,1]. */
    fun encode(lin: Float): Float =
        if (lin <= 0.0031308f) 12.92f * lin
        else (1.055f * Math.pow(lin.toDouble(), 1.0 / 2.4).toFloat() - 0.055f)

    /** 8-bit sRGB code (0..255) -> linear light. */
    fun decode8(code: Int): Float = decode(code / 255f)

    /**
     * Linear light -> 8-bit sRGB code, rounded half-to-even
     * (Math.rint, matching NumPy), clamped to 0..255.
     */
    fun encode8(lin: Float): Int {
        val v = Math.rint((encode(lin.coerceIn(0f, 1f)) * 255.0)).toInt()
        return v.coerceIn(0, 255)
    }

    /** The 4 palette shades in sRGB codes (Micro-hybrid C adjusted). */
    val PALETTE_SRGB = intArrayOf(255, 180, 90, 0)

    /** The 4 palette shades in linear light (§4.1). */
    val PALETTE_LINEAR = floatArrayOf(1.0f, 0.4566f, 0.1022f, 0.0f)

    /**
     * The linear-light average of palette shades [a] and [b] at density
     * [fA] (fraction of A), sRGB-encoded to an 8-bit code. A is the
     * darker shade. Worked examples (§4.1): 50/50 of 0+3 -> 188,
     * 50/50 of 1+2 -> 144, 25% of 0 + 75% of 3 -> 137.
     */
    fun averageShade(a: Int, b: Int, fA: Float): Int {
        val lin = fA * PALETTE_LINEAR[a] + (1f - fA) * PALETTE_LINEAR[b]
        return encode8(lin)
    }
}
