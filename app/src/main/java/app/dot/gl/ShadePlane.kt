package app.dot.gl

// Dot — the 160x144 shade-index plane and its content hash
// (GL-CHAIN-SPEC §8.4). The plane is derived from the core's ARGB
// frame: each texel maps to its nearest palette shade (0 = white,
// 3 = black). The hash gates static-frame reuse: a frame whose shade
// plane hashes the same as the previous frame needs no re-render.
// SPDX-License-Identifier: GPL-3.0-or-later

object ShadePlane {
    const val WIDTH = 160
    const val HEIGHT = 144
    const val SIZE = WIDTH * HEIGHT

    /**
     * Palette green values, as delivered by the core's Grayscale preset
     * through RGB565 (measured on the host harness): 251 / 170 / 81 / 0.
     * Thresholds are the midpoints between consecutive values.
     */
    private val PALETTE_G = intArrayOf(251, 170, 81, 0)

    /** Nearest palette shade (0..3) for a green-channel value 0..255. */
    fun shadeOfGreen(g: Int): Int = when {
        g > 210 -> 0 // midpoint (251+170)/2 = 210.5
        g > 125 -> 1 // midpoint (170+81)/2 = 125.5
        g > 40 -> 2 // midpoint (81+0)/2 = 40.5
        else -> 3
    }

    /**
     * True when a green-channel value is an exact palette member
     * (within 6 codes of a palette value). An intermediate grey —
     * an already-inverted dither average, or a deband ramp value —
     * is not a member, so a cell containing it is never inverted
     * twice (GL-CHAIN-SPEC §3.2 palette gate (a)).
     */
    fun isPaletteMember(g: Int): Boolean {
        val s = shadeOfGreen(g)
        return kotlin.math.abs(g - PALETTE_G[s]) <= 6
    }

    /** Shade index (0..3) of an ARGB pixel, from its green channel. */
    fun shadeOfArgb(argb: Int): Int = shadeOfGreen((argb shr 8) and 0xFF)

    /**
     * The shade-index plane for an ARGB frame. [argb] must hold exactly
     * [SIZE] pixels, row-major, top row first.
     */
    fun fromArgb(argb: IntArray): ByteArray {
        require(argb.size == SIZE) { "ARGB frame must be exactly $SIZE pixels, got ${argb.size}" }
        val plane = ByteArray(SIZE)
        for (i in argb.indices) plane[i] = shadeOfArgb(argb[i]).toByte()
        return plane
    }
}

/** FNV-1a-64 content hash (GL-CHAIN-SPEC §8.4). */
object FrameHash {
    private const val OFFSET_BASIS = -0x340D631B7BDDDCDBL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    /**
     * FNV-1a-64 over [bytes]. The buffer must be exactly [expectedSize]
     * bytes — a hash/length precondition failure fails loudly, never
     * hashes a prefix.
     */
    fun fnv1a64(bytes: ByteArray, expectedSize: Int = ShadePlane.SIZE): Long {
        require(bytes.size == expectedSize) {
            "hash buffer must be exactly $expectedSize bytes, got ${bytes.size}"
        }
        var hash = OFFSET_BASIS
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xFFL)
            hash *= PRIME
        }
        return hash
    }
}
