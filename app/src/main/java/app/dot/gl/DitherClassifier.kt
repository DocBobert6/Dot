package app.dot.gl

// Dot — the Kotlin oracle for the dither-inversion classifier
// (GL-CHAIN-SPEC §3). This mirrors dither-invert.frag rule for rule:
// the same families, the same gates, the same replacements. The shader
// is the runtime truth; this class is what the pure tests run against,
// and what the host harness uses to check a frame without a GPU.
//
// Convention (as in the shader): A is the DARKER shade (the higher
// shade index), B is the lighter, and f is the A-fraction (the
// fraction of the darker shade). The replacement is the linear-light
// average f*lin(A) + (1-f)*lin(B), sRGB-encoded to an 8-bit code.
//
// Input: the green channel of the composited frame (160x144, row-major).
// Output: the inverted green plane — each texel's replacement grey
// where its cell inverts, the original green value elsewhere.
//
// A cell inverts only when all four gates hold:
//   1. pattern — the cell matches one family below (exact shade
//      equality, nearest reads);
//   2. palette — both shades are members of the 4-shade palette (a cell
//      is never inverted twice: an already-inverted intermediate grey
//      is not a palette member);
//   3. field — at least 4 of the 8 neighbouring cells (same size,
//      edge-clamped — a clamped neighbour is the centre cell itself,
//      which counts) match the same family and shade pair;
//   4. baseline density — 8 distant 2x2 samples (at +/-16 and +/-64
//      texels) vote their A-fraction; the votes must take >= 3 distinct
//      values spanning >= 0.5.
//
// Families, in priority order (first matching pattern wins; its gates
// then decide — an intentional checkerboard that fails the gates is
// left alone, never re-tried under another family):
//   1. 2x2 checker (both phases)          6. Bayer 4x4 25/50/75%
//   2. Bayer 2x2 25% / 75%                7. vertical lines
//   3. checker 2x3                        8. horizontal lines
//   4. checker 3x2                        9. diagonal staircase
//   5. checker 3x3
//
// Bayer 4x4 bitmasks (Ulichney matrix, row-major i = y*4+x):
//   k=4  -> 0x0505, k=8  -> 0xA5A5, k=12 -> 0xAFAF
// (corrected 2026-11-26 — the first values 0x0445/0xA555/0xAFDF were
// wrong; the k=12 constant even had 13 bits set, so the 75% case
// could never match. The shader carries the same constants.)
//
// SPDX-License-Identifier: GPL-3.0-or-later

object DitherClassifier {
    const val WIDTH = ShadePlane.WIDTH
    const val HEIGHT = ShadePlane.HEIGHT
    const val SIZE = ShadePlane.SIZE

    // Family ids for the field gate's cell matcher (as in the shader).
    const val F_CHECKER2X2 = 0
    const val F_BAYER2X2 = 1
    const val F_CHECKER2X3 = 2
    const val F_CHECKER3X2 = 3
    const val F_CHECKER3X3 = 4
    const val F_BAYER4X4 = 5

    // Bayer 4x4 dark-position bitmasks (see the header note).
    const val BAYER4_MASK_25 = 0x0505
    const val BAYER4_MASK_50 = 0xA5A5
    const val BAYER4_MASK_75 = 0xAFAF

    /** The inverted green plane for a frame. */
    fun invert(green: IntArray): IntArray {
        require(green.size == SIZE) { "green plane must be exactly $SIZE texels" }
        val out = IntArray(SIZE)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                out[y * WIDTH + x] = classify(green, x, y)
            }
        }
        return out
    }

    /** The inverted value of one texel (its cell's replacement, or itself). */
    fun classify(g: IntArray, x: Int, y: Int): Int {
        val self = g[y * WIDTH + x]
        // 2x2 checker (both phases), the most common family, first.
        // If the pattern matches, the field gate decides; if it fails,
        // the diagonal run is tried before giving up on this texel
        // (the diagonal shares the 2x2 pattern and replacement — it
        // is a separate field test, not a separate family).
        val c2 = matchChecker2x2(g, x and 1.inv(), y and 1.inv())
        if (c2 != null) {
            val (a, b, _) = c2
            val ox = x and 1.inv()
            val oy = y and 1.inv()
            if (fieldGate(g, F_CHECKER2X2, ox, oy, 2, 2, a, b) &&
                baselineGate(g, ox, oy, a, b)
            ) {
                return LinearLight.averageShade(a, b, 0.5f)
            }
            if (diagonalRun(g, ox, oy, a, b) && baselineGate(g, ox, oy, a, b)) {
                return LinearLight.averageShade(a, b, 0.5f)
            }
            return self
        }
        // Bayer 2x2 25% / 75% (exactly one texel of the darker shade, or three).
        val b2 = matchBayer2x2(g, x and 1.inv(), y and 1.inv())
        if (b2 != null) {
            val (a, b, f) = b2
            if (fieldGate(g, F_BAYER2X2, x and 1.inv(), y and 1.inv(), 2, 2, a, b) &&
                baselineGate(g, x and 1.inv(), y and 1.inv(), a, b)
            ) {
                return LinearLight.averageShade(a, b, f)
            }
            return self
        }
        // Checker 2x3.
        val c23 = matchChecker2x3(g, x and 1.inv(), y - y % 3)
        if (c23 != null) {
            val (a, b, _) = c23
            if (fieldGate(g, F_CHECKER2X3, x and 1.inv(), y - y % 3, 2, 3, a, b) &&
                baselineGate(g, x and 1.inv(), y - y % 3, a, b)
            ) {
                return LinearLight.averageShade(a, b, 0.5f)
            }
            return self
        }
        // Checker 3x2.
        val c32 = matchChecker3x2(g, x - x % 3, y and 1.inv())
        if (c32 != null) {
            val (a, b, _) = c32
            if (fieldGate(g, F_CHECKER3X2, x - x % 3, y and 1.inv(), 3, 2, a, b) &&
                baselineGate(g, x - x % 3, y and 1.inv(), a, b)
            ) {
                return LinearLight.averageShade(a, b, 0.5f)
            }
            return self
        }
        // Checker 3x3 (5/9 or 4/9).
        val c33 = matchChecker3x3(g, x - x % 3, y - y % 3)
        if (c33 != null) {
            val (a, b, f) = c33
            if (fieldGate(g, F_CHECKER3X3, x - x % 3, y - y % 3, 3, 3, a, b) &&
                baselineGate(g, x - x % 3, y - y % 3, a, b)
            ) {
                return LinearLight.averageShade(a, b, f)
            }
            return self
        }
        // Bayer 4x4 25% / 50% / 75% (strict phase 0).
        val b4 = matchBayer4x4(g, x - x % 4, y - y % 4)
        if (b4 != null) {
            val (a, b, f) = b4
            if (fieldGate(g, F_BAYER4X4, x - x % 4, y - y % 4, 4, 4, a, b) &&
                baselineGate(g, x - x % 4, y - y % 4, a, b)
            ) {
                return LinearLight.averageShade(a, b, f)
            }
            return self
        }
        // Vertical lines (the run is the field evidence).
        val vl = matchVLines(g, x, y)
        if (vl != null) {
            val (a, b, _) = vl
            if (baselineGate(g, x, y, a, b)) {
                return LinearLight.averageShade(a, b, 0.5f)
            }
            return self
        }
        // Horizontal lines (the run is the field evidence).
        val hl = matchHLines(g, x, y)
        if (hl != null) {
            val (a, b, _) = hl
            if (baselineGate(g, x, y, a, b)) {
                return LinearLight.averageShade(a, b, 0.5f)
            }
            return self
        }
        return self
    }

    // ---- pattern matchers ----
    // Each takes a cell origin (top-left) and returns (A, B, f) with
    // A the darker shade, B the lighter, f the A-fraction — or null.
    // All shade reads are membership-tested: a non-member returns -1
    // and fails the match (never inverted twice).

    /** Shade index 0..3, or -1 when not a palette member / out of frame. */
    fun shadeAt(g: IntArray, x: Int, y: Int): Int {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return -1
        val v = g[y * WIDTH + x]
        val s = ShadePlane.shadeOfGreen(v)
        return if (ShadePlane.isPaletteMember(v)) s else -1
    }

    /** 2x2 exact checker, both phases. f = 0.5. */
    fun matchChecker2x2(g: IntArray, ox: Int, oy: Int): Triple<Int, Int, Float>? {
        val tl = shadeAt(g, ox, oy)
        val tr = shadeAt(g, ox + 1, oy)
        val bl = shadeAt(g, ox, oy + 1)
        val br = shadeAt(g, ox + 1, oy + 1)
        if (tl < 0 || tr < 0 || bl < 0 || br < 0) return null
        if (tl != br || tr != bl || tl == tr) return null
        return Triple(maxOf(tl, tr), minOf(tl, tr), 0.5f)
    }

    /**
     * Bayer 2x2 at 25% (1 dark texel) or 75% (3 dark texels). The 50%
     * case is the checker above. Any dark-texel position is accepted
     * (the field's phase is unknowable); the field gate enforces the
     * family, pair, and density. f = count(darker)/4.
     */
    fun matchBayer2x2(g: IntArray, ox: Int, oy: Int): Triple<Int, Int, Float>? {
        val s0 = shadeAt(g, ox, oy)
        val s1 = shadeAt(g, ox + 1, oy)
        val s2 = shadeAt(g, ox, oy + 1)
        val s3 = shadeAt(g, ox + 1, oy + 1)
        if (s0 < 0 || s1 < 0 || s2 < 0 || s3 < 0) return null
        val lo = minOf(minOf(s0, s1), minOf(s2, s3))
        val hi = maxOf(maxOf(s0, s1), maxOf(s2, s3))
        if (lo == hi) return null
        var cnt = 0
        if (s0 == hi) cnt++
        if (s1 == hi) cnt++
        if (s2 == hi) cnt++
        if (s3 == hi) cnt++
        if (cnt != 1 && cnt != 3) return null
        return Triple(hi, lo, cnt / 4f)
    }

    /** Checker 2x3 (2 wide, 3 tall), alternating, 3 of each shade. */
    fun matchChecker2x3(g: IntArray, ox: Int, oy: Int): Triple<Int, Int, Float>? {
        val s = IntArray(6)
        s[0] = shadeAt(g, ox, oy)
        s[1] = shadeAt(g, ox + 1, oy)
        s[2] = shadeAt(g, ox, oy + 1)
        s[3] = shadeAt(g, ox + 1, oy + 1)
        s[4] = shadeAt(g, ox, oy + 2)
        s[5] = shadeAt(g, ox + 1, oy + 2)
        if (s.any { it < 0 }) return null
        val e = s[0] // (0,0) even
        val d = s[1] // (1,0) odd
        if (e == d) return null
        if (s[2] != d || s[3] != e || s[4] != e || s[5] != d) return null
        return Triple(maxOf(e, d), minOf(e, d), 0.5f)
    }

    /** Checker 3x2 (3 wide, 2 tall), alternating, 3 of each shade. */
    fun matchChecker3x2(g: IntArray, ox: Int, oy: Int): Triple<Int, Int, Float>? {
        val s = IntArray(6)
        s[0] = shadeAt(g, ox, oy)
        s[1] = shadeAt(g, ox + 1, oy)
        s[2] = shadeAt(g, ox + 2, oy)
        s[3] = shadeAt(g, ox, oy + 1)
        s[4] = shadeAt(g, ox + 1, oy + 1)
        s[5] = shadeAt(g, ox + 2, oy + 1)
        if (s.any { it < 0 }) return null
        val e = s[0]
        val d = s[1]
        if (e == d) return null
        if (s[2] != e || s[3] != d || s[4] != e || s[5] != d) return null
        return Triple(maxOf(e, d), minOf(e, d), 0.5f)
    }

    /**
     * Checker 3x3, alternating; the centre is the majority shade
     * (5 of one, 4 of the other). f = count(darker)/9.
     */
    fun matchChecker3x3(g: IntArray, ox: Int, oy: Int): Triple<Int, Int, Float>? {
        var first = -1 // even positions (5): corners + centre
        var second = -1 // odd positions (4)
        for (dy in 0..2) {
            for (dx in 0..2) {
                val s = shadeAt(g, ox + dx, oy + dy)
                if (s < 0) return null
                if ((dx + dy) % 2 == 0) {
                    if (first < 0) first = s else if (s != first) return null
                } else {
                    if (second < 0) second = s else if (s != second) return null
                }
            }
        }
        if (first < 0 || second < 0 || first == second) return null
        val a = maxOf(first, second)
        val b = minOf(first, second)
        val cntDark = if (first == a) 5 else 4
        return Triple(a, b, cntDark / 9f)
    }

    /**
     * Bayer 4x4 at 25% / 50% / 75% (k = 4 / 8 / 12 dark texels),
     * strict phase-0 against the bitmasks above. f = k/16. A
     * phase-shifted field does not match — the 2x2 families cover
     * those cases.
     */
    fun matchBayer4x4(g: IntArray, ox: Int, oy: Int): Triple<Int, Int, Float>? {
        var lo = 99
        var hi = -1
        val shades = IntArray(16)
        for (dy in 0..3) {
            for (dx in 0..3) {
                val s = shadeAt(g, ox + dx, oy + dy)
                if (s < 0) return null
                shades[dy * 4 + dx] = s
                lo = minOf(lo, s)
                hi = maxOf(hi, s)
            }
        }
        if (lo == hi) return null
        var mask = 0
        for (i in 0 until 16) {
            val s = shades[i]
            if (s != lo && s != hi) return null
            if (s == hi) mask = mask or (1 shl i)
        }
        var cnt = 0
        for (i in 0 until 16) if (mask and (1 shl i) != 0) cnt++
        if (cnt != 4 && cnt != 8 && cnt != 12) return null
        val want = when (cnt) {
            4 -> BAYER4_MASK_25
            8 -> BAYER4_MASK_50
            else -> BAYER4_MASK_75
        }
        if (mask != want) return null
        return Triple(hi, lo, cnt / 16f)
    }

    /**
     * Vertical lines: columns alternate A/B over a run of >= 4 columns
     * containing x, each column solid over >= 2 rows including y. The
     * run itself is the field evidence. Returns (A, B, 0.5).
     */
    fun matchVLines(g: IntArray, x: Int, y: Int): Triple<Int, Int, Float>? {
        val s0 = shadeAt(g, x, y)
        if (s0 < 0) return null
        var run = 1
        var other = -1
        var leftShade = s0
        var xLeft = x - 1
        while (xLeft >= 0) {
            val s = shadeAt(g, xLeft, y)
            if (s < 0 || s == leftShade) break
            if (other < 0) other = s
            if (s != s0 && s != other) break
            if (shadeAt(g, xLeft, y + 1) != s && shadeAt(g, xLeft, y - 1) != s) break
            leftShade = s
            run++
            xLeft--
        }
        var rightShade = s0
        var xRight = x + 1
        while (xRight < WIDTH) {
            val s = shadeAt(g, xRight, y)
            if (s < 0 || s == rightShade) break
            if (other < 0) other = s
            if (s != s0 && s != other) break
            if (shadeAt(g, xRight, y + 1) != s && shadeAt(g, xRight, y - 1) != s) break
            rightShade = s
            run++
            xRight++
        }
        if (run < 4) return null
        val nb = shadeAt(g, x + 1, y).takeIf { it >= 0 && it != s0 }
            ?: shadeAt(g, x - 1, y).takeIf { it >= 0 && it != s0 }
            ?: return null
        return Triple(maxOf(s0, nb), minOf(s0, nb), 0.5f)
    }

    /**
     * Horizontal lines: rows alternate A/B over a run of >= 4 rows
     * containing y, each row solid over >= 2 columns including x.
     * Transposed from matchVLines.
     */
    fun matchHLines(g: IntArray, x: Int, y: Int): Triple<Int, Int, Float>? {
        val s0 = shadeAt(g, x, y)
        if (s0 < 0) return null
        var run = 1
        var other = -1
        var topShade = s0
        var yTop = y - 1
        while (yTop >= 0) {
            val s = shadeAt(g, x, yTop)
            if (s < 0 || s == topShade) break
            if (other < 0) other = s
            if (s != s0 && s != other) break
            if (shadeAt(g, x + 1, yTop) != s && shadeAt(g, x - 1, yTop) != s) break
            topShade = s
            run++
            yTop--
        }
        var bottomShade = s0
        var yBottom = y + 1
        while (yBottom < HEIGHT) {
            val s = shadeAt(g, x, yBottom)
            if (s < 0 || s == bottomShade) break
            if (other < 0) other = s
            if (s != s0 && s != other) break
            if (shadeAt(g, x + 1, yBottom) != s && shadeAt(g, x - 1, yBottom) != s) break
            bottomShade = s
            run++
            yBottom++
        }
        if (run < 4) return null
        val nb = shadeAt(g, x, y + 1).takeIf { it >= 0 && it != s0 }
            ?: shadeAt(g, x, y - 1).takeIf { it >= 0 && it != s0 }
            ?: return null
        return Triple(maxOf(s0, nb), minOf(s0, nb), 0.5f)
    }

    /**
     * Diagonal staircase run test (the 2x2 checker's alternate field
     * evidence). From the centre 2x2 checker block at (ox, oy), count
     * the run of consecutive checker blocks along each diagonal
     * (phase must flip every step). True when either diagonal run
     * is >= 3 blocks. Mirrors the shader's diagonalRun.
     */
    fun diagonalRun(g: IntArray, ox: Int, oy: Int, a: Int, b: Int): Boolean {
        // Phase of the centre block: top-left == A means phase 0.
        val phase0 = if (shadeAt(g, ox, oy) == a) 0 else 1
        // Directions: (1,1) and (1,-1), in block steps of 2 texels.
        for ((stepX, stepY) in listOf(2 to 2, 2 to -2)) {
            var run = 1
            for (dir in listOf(-1, 1)) {
                // Each direction starts one row from the centre, so
                // the first block in either direction has the flipped phase.
                var expectPhase = 1 - phase0
                for (k in 1..8) {
                    val cx = ox + stepX * k * dir
                    val cy = oy + stepY * k * dir
                    if (cx < 0 || cy < 0 || cx + 1 >= WIDTH || cy + 1 >= HEIGHT) break
                    val m = matchChecker2x2(g, cx, cy) ?: break
                    if (m.first != a || m.second != b) break
                    val p = if (shadeAt(g, cx, cy) == a) 0 else 1
                    if (p != expectPhase) break
                    run++
                    expectPhase = 1 - expectPhase
                }
            }
            if (run >= 3) return true
        }
        return false
    }

    // ---- gates ----

    /**
     * Does the cell at (cx, cy) match family [fam] with pair (a, b)?
     * Density/phase may differ within the family.
     */
    private fun cellMatches(g: IntArray, fam: Int, cx: Int, cy: Int, a: Int, b: Int): Boolean {
        val m = when (fam) {
            F_CHECKER2X2 -> matchChecker2x2(g, cx, cy)
            F_BAYER2X2 -> matchBayer2x2(g, cx, cy)
            F_CHECKER2X3 -> matchChecker2x3(g, cx, cy)
            F_CHECKER3X2 -> matchChecker3x2(g, cx, cy)
            F_CHECKER3X3 -> matchChecker3x3(g, cx, cy)
            F_BAYER4X4 -> matchBayer4x4(g, cx, cy)
            else -> null
        } ?: return false
        return m.first == a && m.second == b
    }

    /**
     * Field gate: >= 4 of the 8 neighbouring cells (same size,
     * edge-clamped) match the same family and pair. A clamped
     * neighbour is the centre cell itself, which counts.
     */
    fun fieldGate(
        g: IntArray, fam: Int, ox: Int, oy: Int, cellW: Int, cellH: Int, a: Int, b: Int
    ): Boolean {
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = ox + dx * cellW
                var ny = oy + dy * cellH
                nx = nx.coerceIn(0, WIDTH - cellW)
                ny = ny.coerceIn(0, HEIGHT - cellH)
                if (cellMatches(g, fam, nx, ny, a, b)) count++
            }
        }
        return count >= 4
    }

    /**
     * A-fraction of a 2x2 sample block at (ox, oy): count of A texels
     * / 4, or null when any texel is neither A nor B (abstain — a
     * non-member shade is neither).
     */
    private fun sampleAFrac(g: IntArray, ox: Int, oy: Int, a: Int, b: Int): Float? {
        var cnt = 0
        for (dy in 0..1) {
            for (dx in 0..1) {
                val s = shadeAt(g, ox + dx, oy + dy)
                if (s != a && s != b) return null
                if (s == a) cnt++
            }
        }
        return cnt / 4f
    }

    /**
     * Baseline-density gate: 8 samples at +/-16 and +/-64 texels on
     * the axes from the cell origin; votes are A-fractions; invert
     * iff the votes take >= 3 distinct values spanning >= 0.5.
     */
    fun baselineGate(g: IntArray, ox: Int, oy: Int, a: Int, b: Int): Boolean {
        val votes = mutableListOf<Float>()
        for ((dx, dy) in listOf(
            16 to 0, -16 to 0, 0 to 16, 0 to -16,
            64 to 0, -64 to 0, 0 to 64, 0 to -64
        )) {
            val v = sampleAFrac(g, ox + dx, oy + dy, a, b) ?: continue
            votes.add(v)
        }
        if (votes.size < 3) return false
        val distinct = votes.toSet()
        if (distinct.size < 3) return false
        return votes.max() - votes.min() >= 0.5f
    }
}
