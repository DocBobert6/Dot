package app.dot.gl

// Dot — the CPU debanding detector + ramp (GL-CHAIN-SPEC §4.3).
// Runs on the composited frame BEFORE dither inversion (the two are
// disjoint by construction: a deband region is a true gradient, a
// dither field is a pattern; the detector excludes checker overlap).
//
// Detector (on the shade plane, GL-CHAIN-SPEC §4.3.1):
//   1. Solid runs: maximal runs of one shade, >= 2 texels wide.
//      A 1-texel run breaks a sequence.
//   2. Step sequence: >= 5 consecutive bands, monotone in shade
//      index, each step |d shade| <= 1, total span >= 2 shade steps.
//      A direction reversal restarts the sequence at the previous band.
//   3. Region coherence: adjacent lines carry the same shade sequence,
//      band boundaries within +/-2 texels, >= 16 lines, area >= 1,000
//      texels. The full-extent rule: if the immediately neighbouring
//      line carries a sequence with the same shade list (necessarily
//      with drifted boundaries), the group is only a locally straight
//      span of a longer drifting feature and is rejected.
//   4. No checker overlap: a region is dropped if any of its texels
//      belongs to a 2x2 exact checker block.
//
// Ramp (§4.3.2): every texel becomes the linear-light lerp between
// the first and last band colours at its fractional position along
// the gradient axis (per channel, from the actual band colours in
// the frame); vertical first, then horizontal; later regions are
// masked by already-claimed texels.
//
// Documented misses (§4.3.4): diagonal gradients; stacking after
// dither inversion. Deband runs first, then inversion.
// SPDX-License-Identifier: GPL-3.0-or-later

object Deband {
    private const val MIN_BANDS = 5
    private const val MIN_SPAN = 2
    private const val MIN_LINES = 16
    private const val MIN_AREA = 1_000

    /** A detected gradient region. */
    data class Region(
        val vertical: Boolean, // true: gradient runs vertically (bands stacked in y)
        val x0: Int, val y0: Int, val x1: Int, val y1: Int, // inclusive bounds
        val startShade: Int, val endShade: Int, // first and last band shades
        val startPos: Int, val endPos: Int // first and last band centres along the axis
    )

    /** The debanded ARGB frame (a new array; the input is untouched). */
    fun deband(argb: IntArray): IntArray {
        require(argb.size == ShadePlane.SIZE) { "ARGB frame must be exactly ${ShadePlane.SIZE} pixels" }
        val shades = ShadePlane.fromArgb(argb)
        val regions = detect(shades)
        if (regions.isEmpty()) return argb.copyOf()
        val out = argb.copyOf()
        val claimed = BooleanArray(ShadePlane.SIZE)
        for (region in regions) applyRamp(out, argb, shades, region, claimed)
        return out
    }

    /** All detected regions, vertical first, then horizontal. */
    fun detect(shades: ByteArray): List<Region> {
        require(shades.size == ShadePlane.SIZE) { "shade plane must be exactly ${ShadePlane.SIZE} texels" }
        val regions = mutableListOf<Region>()
        regions += detectVertical(shades)
        regions += detectHorizontal(shades)
        return regions
    }

    // ---- vertical gradients (bands stacked along y, coherent across x) ----

    private fun detectVertical(shades: ByteArray): List<Region> {
        // Per-column band sequences.
        val sequences = Array(ShadePlane.WIDTH) { x -> bandSequence(shades, x, vertical = true) }
        val regions = mutableListOf<Region>()
        var x = 0
        while (x < ShadePlane.WIDTH) {
            val seq = sequences[x]
            if (seq == null) {
                x++
                continue
            }
            // The sequence at column x: walk right while columns share it.
            var x1 = x
            while (x1 + 1 < ShadePlane.WIDTH) {
                val next = sequences[x1 + 1] ?: break
                if (!sameSequence(seq, next)) break
                x1++
            }
            val width = x1 - x + 1
            val height = seq.endPos - seq.startPos + 1
            if (width >= MIN_LINES && width * height >= MIN_AREA) {
                val region = Region(
                    vertical = true, x0 = x, y0 = seq.startPos, x1 = x1, y1 = seq.endPos,
                    startShade = seq.shades.first(), endShade = seq.shades.last(),
                    startPos = seq.startPos, endPos = seq.endPos
                )
                if (!fullExtentRejectVertical(shades, sequences, x, x1, seq) &&
                    !regionHasChecker(shades, region)
                ) {
                    regions.add(region)
                }
            }
            x = x1 + 1
        }
        return regions
    }

    // ---- horizontal gradients (bands stacked along x, coherent across y) ----

    private fun detectHorizontal(shades: ByteArray): List<Region> {
        val sequences = Array(ShadePlane.HEIGHT) { y -> bandSequence(shades, y, vertical = false) }
        val regions = mutableListOf<Region>()
        var y = 0
        while (y < ShadePlane.HEIGHT) {
            val seq = sequences[y]
            if (seq == null) {
                y++
                continue
            }
            var y1 = y
            while (y1 + 1 < ShadePlane.HEIGHT) {
                val next = sequences[y1 + 1] ?: break
                if (!sameSequence(seq, next)) break
                y1++
            }
            val height = y1 - y + 1
            val width = seq.endPos - seq.startPos + 1
            if (height >= MIN_LINES && width * height >= MIN_AREA) {
                val region = Region(
                    vertical = false, x0 = seq.startPos, y0 = y, x1 = seq.endPos, y1 = y1,
                    startShade = seq.shades.first(), endShade = seq.shades.last(),
                    startPos = seq.startPos, endPos = seq.endPos
                )
                if (!fullExtentRejectHorizontal(shades, sequences, y, y1, seq) &&
                    !regionHasChecker(shades, region)
                ) {
                    regions.add(region)
                }
            }
            y = y1 + 1
        }
        return regions
    }

    // ---- band sequences along one line ----

    private data class BandSeq(
        val shades: List<Int>, // the band shades, in axis order
        val startPos: Int, // centre of the first band along the axis
        val endPos: Int // centre of the last band along the axis
    )

    /**
     * The band sequence along one column (vertical = true) or row
     * (vertical = false). Bands are maximal runs of one shade, >= 2
     * texels wide; a 1-texel run breaks the sequence. The sequence is
     * monotone in shade index with |step| <= 1 and >= 5 bands spanning
     * >= 2 shade steps. A direction reversal restarts at the previous
     * band. Returns the longest qualifying sequence on the line, or null.
     */
    private fun bandSequence(shades: ByteArray, line: Int, vertical: Boolean): BandSeq? {
        val len = if (vertical) ShadePlane.HEIGHT else ShadePlane.WIDTH
        // Bands: (shade, start, end) along the axis.
        val bands = mutableListOf<Triple<Int, Int, Int>>()
        var i = 0
        while (i < len) {
            val s = shadeAtLine(shades, line, i, vertical)
            var j = i
            while (j + 1 < len && shadeAtLine(shades, line, j + 1, vertical) == s) j++
            bands.add(Triple(s, i, j))
            i = j + 1
        }
        // Walk the bands; a run of >= 2 texels is required, and a
        // direction reversal restarts at the previous band.
        var best: BandSeq? = null
        var start = 0
        while (start < bands.size) {
            // Skip 1-texel bands: they break a sequence.
            if (bands[start].third - bands[start].second < 1) { start++; continue }
            val seq = mutableListOf(bands[start])
            var dir = 0
            var k = start + 1
            while (k < bands.size) {
                val band = bands[k]
                if (band.third - band.second < 1) break // 1-texel band breaks it
                val step = band.first - seq.last().first
                if (step == 0) break // same shade twice in a row cannot happen (maximal runs)
                if (kotlin.math.abs(step) > 1) break
                if (dir == 0) dir = step
                if (step != dir) {
                    // Reversal: restart the sequence at the previous band.
                    break
                }
                seq.add(band)
                k++
            }
            if (seq.size >= MIN_BANDS) {
                val span = kotlin.math.abs(seq.last().first - seq.first().first)
                if (span >= MIN_SPAN) {
                    val cand = BandSeq(
                        shades = seq.map { it.first },
                        startPos = (seq.first().second + seq.first().third) / 2,
                        endPos = (seq.last().second + seq.last().third) / 2
                    )
                    if (best == null || cand.shades.size > best.shades.size) best = cand
                }
            }
            // Move past the sequence we just scanned (or past the
            // reversal point, so the new sequence starts at the peak).
            start = if (k > start + 1) k - 1 else start + 1
        }
        return best
    }

    private fun shadeAtLine(shades: ByteArray, line: Int, pos: Int, vertical: Boolean): Int =
        if (vertical) shades[pos * ShadePlane.WIDTH + line].toInt()
        else shades[line * ShadePlane.WIDTH + pos].toInt()

    /**
     * Two band sequences are the same when they carry the same shade
     * list and their band centres agree within +/-2 texels.
     */
    private fun sameSequence(a: BandSeq, b: BandSeq): Boolean {
        if (a.shades != b.shades) return false
        return kotlin.math.abs(a.startPos - b.startPos) <= 2 &&
            kotlin.math.abs(a.endPos - b.endPos) <= 2
    }

    /**
     * Full-extent rule (vertical): the line immediately outside the
     * region (left or right) carrying a sequence with the same shade
     * list rejects the region — it is a locally straight span of a
     * longer drifting feature.
     */
    private fun fullExtentRejectVertical(
        shades: ByteArray, sequences: Array<BandSeq?>, x0: Int, x1: Int, seq: BandSeq
    ): Boolean {
        val left = if (x0 > 0) sequences[x0 - 1] else null
        val right = if (x1 + 1 < ShadePlane.WIDTH) sequences[x1 + 1] else null
        return (left != null && left.shades == seq.shades) ||
            (right != null && right.shades == seq.shades)
    }

    private fun fullExtentRejectHorizontal(
        shades: ByteArray, sequences: Array<BandSeq?>, y0: Int, y1: Int, seq: BandSeq
    ): Boolean {
        val top = if (y0 > 0) sequences[y0 - 1] else null
        val bottom = if (y1 + 1 < ShadePlane.HEIGHT) sequences[y1 + 1] else null
        return (top != null && top.shades == seq.shades) ||
            (bottom != null && bottom.shades == seq.shades)
    }

    /** True when any 2x2 block overlapping the region is an exact checker. */
    private fun regionHasChecker(shades: ByteArray, region: Region): Boolean {
        for (y in region.y0..region.y1) {
            for (x in region.x0..region.x1) {
                if (isChecker2x2(shades, x, y)) return true
            }
        }
        return false
    }

    private fun isChecker2x2(shades: ByteArray, x: Int, y: Int): Boolean {
        if (x + 1 >= ShadePlane.WIDTH || y + 1 >= ShadePlane.HEIGHT) return false
        val s00 = shades[y * ShadePlane.WIDTH + x].toInt()
        val s10 = shades[y * ShadePlane.WIDTH + x + 1].toInt()
        val s01 = shades[(y + 1) * ShadePlane.WIDTH + x].toInt()
        val s11 = shades[(y + 1) * ShadePlane.WIDTH + x + 1].toInt()
        return s00 == s11 && s10 == s01 && s00 != s10
    }

    // ---- ramp ----

    /**
     * Applies the region's ramp to [out] (per channel, linear-light
     * lerp between the first and last band colours at the texel's
     * fractional position along the axis). Texels already claimed by
     * an earlier region are left alone.
     */
    private fun applyRamp(
        out: IntArray, src: IntArray, shades: ByteArray, region: Region, claimed: BooleanArray
    ) {
        val startArgb = bandColour(src, shades, region, region.startShade, region.startPos)
        val endArgb = bandColour(src, shades, region, region.endShade, region.endPos)
        for (y in region.y0..region.y1) {
            for (x in region.x0..region.x1) {
                val idx = y * ShadePlane.WIDTH + x
                if (claimed[idx]) continue
                claimed[idx] = true
                val t = if (region.vertical) {
                    (y - region.startPos).toFloat() / (region.endPos - region.startPos).coerceAtLeast(1)
                } else {
                    (x - region.startPos).toFloat() / (region.endPos - region.startPos).coerceAtLeast(1)
                }.coerceIn(0f, 1f)
                out[idx] = lerpArgb(startArgb, endArgb, t)
            }
        }
    }

    /** The ARGB colour of a band: the first texel of that shade at the band centre. */
    private fun bandColour(
        src: IntArray, shades: ByteArray, region: Region, shade: Int, centre: Int
    ): Int {
        if (region.vertical) {
            val y = centre.coerceIn(region.y0, region.y1)
            for (x in region.x0..region.x1) {
                val idx = y * ShadePlane.WIDTH + x
                if (shades[idx].toInt() == shade) return src[idx]
            }
        } else {
            val x = centre.coerceIn(region.x0, region.x1)
            for (y in region.y0..region.y1) {
                val idx = y * ShadePlane.WIDTH + x
                if (shades[idx].toInt() == shade) return src[idx]
            }
        }
        // Fallback: the palette shade as a grey (should not happen —
        // the band centre is inside its own band).
        val g = LinearLight.PALETTE_SRGB[shade]
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    /** Per-channel linear-light lerp between two ARGB colours. */
    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val r = LinearLight.encode8(
            LinearLight.decode8((a shr 16) and 0xFF) * (1f - t) +
                LinearLight.decode8((b shr 16) and 0xFF) * t
        )
        val g = LinearLight.encode8(
            LinearLight.decode8((a shr 8) and 0xFF) * (1f - t) +
                LinearLight.decode8((b shr 8) and 0xFF) * t
        )
        val bl = LinearLight.encode8(
            LinearLight.decode8(a and 0xFF) * (1f - t) +
                LinearLight.decode8(b and 0xFF) * t
        )
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
