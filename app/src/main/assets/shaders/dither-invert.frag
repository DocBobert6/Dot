#version 300 es
precision highp float;
precision highp int;

// Dot — dither inversion, extended dictionary (GL-CHAIN-SPEC §3).
// Replaces checker/dither cells with the intermediate grey the artist
// intended (the linear-light average of the cell's two shades).
//
// How it works: every output texel is classified by the cell it belongs
// to. A cell inverts only when all four gates hold:
//   1. pattern — the cell matches one family below (exact shade
//      equality, NEAREST reads via texelFetch);
//   2. palette — both shades are members of the 4-shade palette (a cell
//      is never inverted twice: an already-inverted intermediate grey
//      is not a palette member);
//   3. field — at least 4 of the 8 neighbouring cells (same size,
//      edge-clamped) match the same family and shade pair;
//   4. baseline density — 8 distant 2x2 samples (at +/-16 and +/-64
//      texels) vote their A-fraction; the votes must take >= 3 distinct
//      values spanning >= 0.5 (a real dither field sits on a gradient;
//      a flat intentional checkerboard shows one density everywhere).
// The replacement is the linear-light average of the two shades at the
// family's density, sRGB-encoded and quantised to 8-bit.
//
// Families (first matching pattern wins; its gates then decide):
//   checker 2x2 (both phases), Bayer 2x2 25%/75%, checker 2x3 / 3x2,
//   checker 3x3, Bayer 4x4 25%/50%/75%, vertical lines, horizontal
//   lines, diagonal staircase (a run of >= 3 checker blocks whose phase
//   flips every row — the run itself is its field evidence, since a
//   1-block-wide diagonal has only 2 checker neighbours).
// The diagonal staircase shares the 2x2 checker pattern and replacement
// (50/50); it is a separate field test, not a separate pattern.
// Vertical/horizontal lines likewise use their run (>= 4 alternating
// solid columns/rows) as the field evidence.
//
// Shade classification: the core's Grayscale palette, delivered through
// RGB565, has green-channel values 251 / 170 / 81 / 0 (measured on the
// host harness; the R/B channels run 2-4 codes higher). A texel is a
// palette member when its green value is within 6 codes of one of those
// four. Thresholds are the midpoints between consecutive palette values.
// Linear-light palette values (GL-CHAIN-SPEC §4.1): 1.0 / 0.4566 /
// 0.1022 / 0.0.
//
// Bayer matrices (public domain, GL-CHAIN-SPEC §3.3) are encoded as
// bitmasks, not arrays: for the 4x4 matrix the dark positions at
// density k/16 are the texels with threshold < k —
//   k=4:  indices {0,2,8,10}        -> 0x0505
//   k=8:  indices {0,2,5,7,8,10,13,15} -> 0xA5A5
//   k=12: indices {0,1,2,3,5,7,8,9,10,11,13,15} -> 0xAFAF
// (row-major index i = y*4+x, against the Ulichney matrix
//  0  8  2 10 / 12  4 14  6 / 3 11  1  9 / 15  7 13  5.
// NOTE: the constants first shipped here were wrong (0x0445/0xA555/
// 0xAFDF — the k=12 constant even had 13 bits set, so the 75% case
// could never match); corrected 2026-11-26 against the matrix above.
// The Kotlin oracle (DitherClassifier.kt) carries the same constants;
// the two must stay in step.)
// (row-major index i = y*4+x). The 4x4 test is strict phase-0: a field
// phase-shifted by 1-3 texels does not match, and the 2x2 families
// cover those cases. The 2x2 Bayer test accepts the dark texel(s) at
// any position (the matrix defines the density, and the field's global
// phase is unknowable); the field gate enforces that the neighbours
// share the family, pair, and density.
//
// Edge reads are clamped, never wrapped (GL-CHAIN-SPEC §8.1): a clamped
// neighbour is the centre cell itself, which counts toward the field
// gate — the same rule Atomic uses.
//
// This shader is mirrored by DitherClassifier.kt (the Kotlin oracle
// the pure tests run against). The two must implement the same rules;
// the shader is the runtime truth, the oracle is the test truth.
// Copyright (c) 2026 Dot contributors. All rights reserved.

uniform sampler2D Texture;
uniform vec2 TextureSize;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform int FrameDirection;

in vec2 vTexCoord;
out vec4 FragColor;

// Shade classification (green channel, 0..1).
float palG(int s) {
    if (s == 0) return 0.9843; // 251
    if (s == 1) return 0.6667; // 170
    if (s == 2) return 0.3176; // 81
    return 0.0;
}
float palLin(int s) {
    if (s == 0) return 1.0;
    if (s == 1) return 0.4566;
    if (s == 2) return 0.1022;
    return 0.0;
}
float srgbEncode(float lin) {
    return lin <= 0.0031308 ? 12.92 * lin
        : 1.055 * pow(lin, 1.0 / 2.4) - 0.055;
}

ivec2 frameSize() { return ivec2(TextureSize); }

// Green channel of the texel at p, edge-clamped.
float texG(ivec2 p) {
    ivec2 c = clamp(p, ivec2(0), frameSize() - 1);
    return texelFetch(Texture, c, 0).g;
}

// Shade index 0..3 of a green value, or -1 when it is not a palette
// member (an intermediate grey: already inverted, or a deband ramp).
int shadeAt(ivec2 p) {
    float g = texG(p);
    int s;
    if (g > 0.8255) s = 0;
    else if (g > 0.4922) s = 1;
    else if (g > 0.1588) s = 2;
    else s = 3;
    if (abs(g - palG(s)) > 0.0235) return -1;
    return s;
}

// Replacement grey for a cell of shades A (darker) and B (lighter) at
// density f (fraction of A), as an sRGB grey in [0,1].
vec3 replacement(int A, int B, float f) {
    float lin = f * palLin(A) + (1.0 - f) * palLin(B);
    float enc = srgbEncode(lin);
    // Quantise to 8-bit (round half up; exact .5 cases are measure-zero).
    float q = floor(enc * 255.0 + 0.5) / 255.0;
    return vec3(q);
}

// --- Family pattern tests -------------------------------------------
// Each returns true when the cell at origin o matches, setting A to the
// darker shade index, B to the lighter, and f to the A-fraction.
// "Cell" coordinates are texel coordinates; o is the cell's top-left.

// 2x2 checker, either phase. A/B are the two shades, f = 0.5.
bool matchChecker2x2(ivec2 o, out int A, out int B, out float f) {
    int tl = shadeAt(o);
    int tr = shadeAt(o + ivec2(1, 0));
    int bl = shadeAt(o + ivec2(0, 1));
    int br = shadeAt(o + ivec2(1, 1));
    if (tl < 0 || tr < 0 || bl < 0 || br < 0) return false;
    if (tl != br || tr != bl || tl == tr) return false;
    A = max(tl, tr); B = min(tl, tr); f = 0.5;
    return true;
}

// Bayer 2x2 at 25% (1 dark texel) or 75% (3 dark texels). The 50%
// case is the checker above. Any dark-texel position is accepted
// (the field's phase is unknowable); the field gate checks the
// family, pair, and density.
bool matchBayer2x2(ivec2 o, out int A, out int B, out float f) {
    int s0 = shadeAt(o);
    int s1 = shadeAt(o + ivec2(1, 0));
    int s2 = shadeAt(o + ivec2(0, 1));
    int s3 = shadeAt(o + ivec2(1, 1));
    if (s0 < 0 || s1 < 0 || s2 < 0 || s3 < 0) return false;
    int lo = min(min(s0, s1), min(s2, s3));
    int hi = max(max(s0, s1), max(s2, s3));
    if (lo == hi) return false;
    int cnt = 0;
    if (s0 == hi) cnt++; if (s1 == hi) cnt++;
    if (s2 == hi) cnt++; if (s3 == hi) cnt++;
    if (cnt != 1 && cnt != 3) return false;
    // Every texel must be one of the two shades (no third shade).
    if ((s0 != lo && s0 != hi) || (s1 != lo && s1 != hi) ||
        (s2 != lo && s2 != hi) || (s3 != lo && s3 != hi)) return false;
    A = hi; B = lo; f = float(cnt) / 4.0;
    return true;
}

// 2x3 checker (2 wide, 3 tall), alternating, 3 of each shade.
bool matchChecker2x3(ivec2 o, out int A, out int B, out float f) {
    int s[6];
    s[0] = shadeAt(o);
    s[1] = shadeAt(o + ivec2(1, 0));
    s[2] = shadeAt(o + ivec2(0, 1));
    s[3] = shadeAt(o + ivec2(1, 1));
    s[4] = shadeAt(o + ivec2(0, 2));
    s[5] = shadeAt(o + ivec2(1, 2));
    for (int i = 0; i < 6; i++) if (s[i] < 0) return false;
    // Checkerboard: (x+y) even -> one shade, odd -> the other.
    int e = s[0]; // (0,0) even
    int d = s[1]; // (1,0) odd
    if (e == d) return false;
    if (s[2] != d || s[3] != e || s[4] != e || s[5] != d) return false;
    A = max(e, d); B = min(e, d); f = 0.5;
    return true;
}

// 3x2 checker (3 wide, 2 tall), alternating, 3 of each shade.
bool matchChecker3x2(ivec2 o, out int A, out int B, out float f) {
    int s[6];
    s[0] = shadeAt(o);
    s[1] = shadeAt(o + ivec2(1, 0));
    s[2] = shadeAt(o + ivec2(2, 0));
    s[3] = shadeAt(o + ivec2(0, 1));
    s[4] = shadeAt(o + ivec2(1, 1));
    s[5] = shadeAt(o + ivec2(2, 1));
    for (int i = 0; i < 6; i++) if (s[i] < 0) return false;
    int e = s[0];
    int d = s[1];
    if (e == d) return false;
    if (s[2] != e || s[3] != d || s[4] != e || s[5] != d) return false;
    A = max(e, d); B = min(e, d); f = 0.5;
    return true;
}

// 3x3 checker, alternating; centre is the majority shade (5 of one,
// 4 of the other). f = count(A)/9.
bool matchChecker3x3(ivec2 o, out int A, out int B, out float f) {
    int first = -1; int second = -1;
    for (int y = 0; y < 3; y++) {
        for (int x = 0; x < 3; x++) {
            int s = shadeAt(o + ivec2(x, y));
            if (s < 0) return false;
            int even = ((x + y) % 2 == 0) ? 1 : 0;
            if (even == 1) {
                if (first < 0) first = s;
                else if (s != first) return false;
            } else {
                if (second < 0) second = s;
                else if (s != second) return false;
            }
        }
    }
    if (first < 0 || second < 0 || first == second) return false;
    // Even positions (5): corners + centre. Odd positions (4).
    A = max(first, second); B = min(first, second);
    int cntDark = (first == A) ? 5 : 4;
    f = float(cntDark) / 9.0;
    return true;
}

// Bayer 4x4 at 25% / 50% / 75% (k = 4 / 8 / 12 dark texels), strict
// phase-0 against the bitmasks above. f = k/16.
bool matchBayer4x4(ivec2 o, out int A, out int B, out float f) {
    int lo = 99; int hi = -1;
    int mask = 0;
    int shades[16];
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            int s = shadeAt(o + ivec2(x, y));
            if (s < 0) return false;
            shades[y * 4 + x] = s;
            lo = min(lo, s); hi = max(hi, s);
        }
    }
    if (lo == hi) return false;
    for (int i = 0; i < 16; i++) {
        int s = shades[i];
        if (s != lo && s != hi) return false;
        if (s == hi) mask |= (1 << i);
    }
    int cnt = 0;
    for (int i = 0; i < 16; i++) if ((mask & (1 << i)) != 0) cnt++;
    if (cnt != 4 && cnt != 8 && cnt != 12) return false;
    int want = (cnt == 4) ? 0x0505 : (cnt == 8) ? 0xA5A5 : 0xAFAF;
    if (mask != want) return false;
    A = hi; B = lo; f = float(cnt) / 16.0;
    return true;
}

// --- Field and baseline gates ---------------------------------------
// Family ids for the field gate's cell matcher.
const int F_CHECKER2X2 = 0;
const int F_BAYER2X2 = 1;
const int F_CHECKER2X3 = 2;
const int F_CHECKER3X2 = 3;
const int F_CHECKER3X3 = 4;
const int F_BAYER4X4 = 5;

// Does the cell at o match family fam with pair {A,B}?
// Density/phase may differ within the family.
bool cellMatches(int fam, ivec2 o, int A, int B) {
    int a2; int b2; float f2;
    bool ok = false;
    if (fam == F_CHECKER2X2) ok = matchChecker2x2(o, a2, b2, f2);
    else if (fam == F_BAYER2X2) ok = matchBayer2x2(o, a2, b2, f2);
    else if (fam == F_CHECKER2X3) ok = matchChecker2x3(o, a2, b2, f2);
    else if (fam == F_CHECKER3X2) ok = matchChecker3x2(o, a2, b2, f2);
    else if (fam == F_CHECKER3X3) ok = matchChecker3x3(o, a2, b2, f2);
    else if (fam == F_BAYER4X4) ok = matchBayer4x4(o, a2, b2, f2);
    if (!ok) return false;
    return (a2 == A && b2 == B);
}

// Field gate: >= 4 of the 8 neighbouring cells (same size,
// edge-clamped) match the same family and pair. A clamped neighbour
// is the centre cell itself, which counts.
bool fieldGate(int fam, ivec2 o, ivec2 sz, int A, int B) {
    int count = 0;
    ivec2 fs = frameSize();
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            ivec2 n = o + ivec2(dx * sz.x, dy * sz.y);
            n = clamp(n, ivec2(0), fs - sz);
            if (cellMatches(fam, n, A, B)) count++;
        }
    }
    return count >= 4;
}

// A-fraction of a 2x2 sample block at o: count of A texels / 4, or
// -1 when any texel is neither A nor B (abstain).
float sampleAFrac(ivec2 o, int A, int B) {
    int cnt = 0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            int s = shadeAt(o + ivec2(x, y));
            if (s != A && s != B) return -1.0;
            if (s == A) cnt++;
        }
    }
    return float(cnt) / 4.0;
}

// Baseline-density gate: 8 samples at +/-16 and +/-64 texels on the
// axes from the cell origin; votes are A-fractions; invert iff the
// votes take >= 3 distinct values spanning >= 0.5.
bool baselineGate(ivec2 o, int A, int B) {
    float votes[8];
    int n = 0;
    ivec2 offs[8] = ivec2[8](
        ivec2(16, 0), ivec2(-16, 0), ivec2(0, 16), ivec2(0, -16),
        ivec2(64, 0), ivec2(-64, 0), ivec2(0, 64), ivec2(0, -64));
    for (int i = 0; i < 8; i++) {
        float v = sampleAFrac(o + offs[i], A, B);
        if (v >= 0.0) { votes[n] = v; n++; }
    }
    if (n < 3) return false;
    float mn = votes[0]; float mx = votes[0];
    int distinct = 1;
    for (int i = 1; i < n; i++) {
        mn = min(mn, votes[i]); mx = max(mx, votes[i]);
        bool seen = false;
        for (int j = 0; j < i; j++) if (votes[j] == votes[i]) seen = true;
        if (!seen) distinct++;
    }
    return distinct >= 3 && (mx - mn) >= 0.5;
}

// Diagonal staircase: from the centre 2x2 checker block, count the
// run of consecutive checker blocks along each diagonal (phase must
// flip every step). True when either diagonal run is >= 3 blocks.
bool diagonalRun(ivec2 o, int A, int B) {
    // Phase of the centre block: TL == A means phase 0.
    int tl = shadeAt(o);
    int phase0 = (tl == A) ? 0 : 1;
    // Directions: (1,1) and (1,-1), in block steps of 2 texels.
    for (int d = 0; d < 2; d++) {
        ivec2 step = (d == 0) ? ivec2(2, 2) : ivec2(2, -2);
        int run = 1;
        for (int dir = -1; dir <= 1; dir += 2) {
            // Each direction starts one row from the centre, so the
            // first block in either direction has the flipped phase.
            int expectPhase = 1 - phase0;
            for (int k = 1; k <= 8; k++) {
                ivec2 c = o + step * (k * dir);
                // Stay inside the frame (a run that leaves the frame ends).
                if (c.x < 0 || c.y < 0 ||
                    c.x + 1 >= frameSize().x || c.y + 1 >= frameSize().y) break;
                int a2; int b2; float f2;
                if (!matchChecker2x2(c, a2, b2, f2)) break;
                if (a2 != A || b2 != B) break;
                int p = (shadeAt(c) == A) ? 0 : 1;
                if (p != expectPhase) break;
                run++;
                expectPhase = 1 - expectPhase;
            }
        }
        if (run >= 3) return true;
    }
    return false;
}

// Vertical lines: columns alternate A/B over a run of >= 4 columns
// containing x, each column solid over >= 2 rows including y.
bool matchVLines(ivec2 p, out int A, out int B, out float f) {
    int s0 = shadeAt(p);
    if (s0 < 0) return false;
    // A column is solid when the texel above or below matches it.
    // (Edge-clamped: at the frame edge the neighbour is itself.)
    // Find the run of alternating columns containing p.x. The run uses
    // exactly two shades; a third shade stops the run.
    int run = 1;
    int other = -1;
    int leftShade = s0; int rightShade = s0;
    // Extend left: column x-1 must be the other shade and solid.
    for (int x = p.x - 1; x >= 0; x--) {
        int s = shadeAt(ivec2(x, p.y));
        if (s < 0 || s == leftShade) break;
        if (other < 0) other = s;
        if (s != s0 && s != other) break;
        if (shadeAt(ivec2(x, p.y + 1)) != s &&
            shadeAt(ivec2(x, p.y - 1)) != s) break;
        leftShade = s; run++;
    }
    for (int x = p.x + 1; x < frameSize().x; x++) {
        int s = shadeAt(ivec2(x, p.y));
        if (s < 0 || s == rightShade) break;
        if (other < 0) other = s;
        if (s != s0 && s != other) break;
        if (shadeAt(ivec2(x, p.y + 1)) != s &&
            shadeAt(ivec2(x, p.y - 1)) != s) break;
        rightShade = s; run++;
    }
    if (run < 4) return false;
    // The two shades are the pair; A is the darker (higher index).
    // leftShade/rightShade end as the shades at the run ends; recover
    // the pair from the texel and its neighbour.
    int nb = shadeAt(p + ivec2(1, 0));
    if (nb < 0 || nb == s0) {
        nb = shadeAt(p + ivec2(-1, 0));
        if (nb < 0 || nb == s0) return false;
    }
    A = max(s0, nb); B = min(s0, nb); f = 0.5;
    return true;
}

// Horizontal lines: rows alternate A/B over a run of >= 4 rows
// containing y, each row solid over >= 2 columns including x.
bool matchHLines(ivec2 p, out int A, out int B, out float f) {
    int s0 = shadeAt(p);
    if (s0 < 0) return false;
    int run = 1;
    int other = -1;
    int upShade = s0; int downShade = s0;
    for (int y = p.y - 1; y >= 0; y--) {
        int s = shadeAt(ivec2(p.x, y));
        if (s < 0 || s == upShade) break;
        if (other < 0) other = s;
        if (s != s0 && s != other) break;
        if (shadeAt(ivec2(p.x + 1, y)) != s &&
            shadeAt(ivec2(p.x - 1, y)) != s) break;
        upShade = s; run++;
    }
    for (int y = p.y + 1; y < frameSize().y; y++) {
        int s = shadeAt(ivec2(p.x, y));
        if (s < 0 || s == downShade) break;
        if (other < 0) other = s;
        if (s != s0 && s != other) break;
        if (shadeAt(ivec2(p.x + 1, y)) != s &&
            shadeAt(ivec2(p.x - 1, y)) != s) break;
        downShade = s; run++;
    }
    if (run < 4) return false;
    int nb = shadeAt(p + ivec2(0, 1));
    if (nb < 0 || nb == s0) {
        nb = shadeAt(p + ivec2(0, -1));
        if (nb < 0 || nb == s0) return false;
    }
    A = max(s0, nb); B = min(s0, nb); f = 0.5;
    return true;
}

void main() {
    ivec2 xy = ivec2(vTexCoord * InputSize);
    vec4 orig = texelFetch(Texture, clamp(xy, ivec2(0), frameSize() - 1), 0);
    // Not a palette member: cannot be part of a dither cell.
    if (shadeAt(xy) < 0) { FragColor = orig; return; }

    int A; int B; float f;
    ivec2 o; ivec2 sz; int fam;

    // 1. 2x2 checker (either phase). If the pattern matches, the
    //    field gate decides; if it fails, the diagonal run is tried
    //    before giving up on this texel.
    o = ivec2(xy.x - (xy.x % 2), xy.y - (xy.y % 2));
    if (matchChecker2x2(o, A, B, f)) {
        if (fieldGate(F_CHECKER2X2, o, ivec2(2, 2), A, B) &&
            baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        if (diagonalRun(o, A, B) && baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 2. Bayer 2x2, 25% / 75%.
    if (matchBayer2x2(o, A, B, f)) {
        if (fieldGate(F_BAYER2X2, o, ivec2(2, 2), A, B) &&
            baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 3. Checker 2x3.
    o = ivec2(xy.x - (xy.x % 2), xy.y - (xy.y % 3));
    if (matchChecker2x3(o, A, B, f)) {
        if (fieldGate(F_CHECKER2X3, o, ivec2(2, 3), A, B) &&
            baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 4. Checker 3x2.
    o = ivec2(xy.x - (xy.x % 3), xy.y - (xy.y % 2));
    if (matchChecker3x2(o, A, B, f)) {
        if (fieldGate(F_CHECKER3X2, o, ivec2(3, 2), A, B) &&
            baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 5. Checker 3x3.
    o = ivec2(xy.x - (xy.x % 3), xy.y - (xy.y % 3));
    if (matchChecker3x3(o, A, B, f)) {
        if (fieldGate(F_CHECKER3X3, o, ivec2(3, 3), A, B) &&
            baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 6. Bayer 4x4, 25% / 50% / 75%.
    o = ivec2(xy.x - (xy.x % 4), xy.y - (xy.y % 4));
    if (matchBayer4x4(o, A, B, f)) {
        if (fieldGate(F_BAYER4X4, o, ivec2(4, 4), A, B) &&
            baselineGate(o, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 7. Vertical lines.
    if (matchVLines(xy, A, B, f)) {
        if (baselineGate(xy, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    // 8. Horizontal lines.
    if (matchHLines(xy, A, B, f)) {
        if (baselineGate(xy, A, B)) {
            FragColor = vec4(replacement(A, B, f), orig.a);
            return;
        }
        FragColor = orig; return;
    }

    FragColor = orig;
}
