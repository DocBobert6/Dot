package app.dot.gl

// Dot — the chain parity table (GL-CHAIN-SPEC §1.1, §1.3, §10.1).
// The FBO pass count and the final flip, as pure functions of the
// passes actually run this frame — never a constant. The flip
// parity is what the alpha.65 lesson is about: a wrong count
// mirrors the picture, and only the device (or a host run of the
// real shader) sees it. This table pins the count so a future
// toggle cannot silently flip the picture.
//
// Pass counts (inversion on/off x Mitchell on/off):
//   invert (1, if on) + deflicker (1) + ScaleFX 0-4 (5) + rAA 0-1
//   (2) + sharp-bilinear (1) + Mitchell H+V (2, if on)
//   = 10 / 9 without Mitchell, 12 / 11 with it.
// The final screen pass flips iff the count is odd.
// SPDX-License-Identifier: GPL-3.0-or-later

object ChainParity {
    /** The FBO pass count for a frame with these passes. */
    fun fboPassCount(inversionOn: Boolean, mitchellOn: Boolean): Int {
        var n = 0
        if (inversionOn) n += 1 // dither-invert
        n += 1 // deflicker
        n += 5 // ScaleFX passes 0-4
        n += 2 // rAA passes 0-1
        n += 1 // sharp-bilinear
        if (mitchellOn) n += 2 // Mitchell H + V
        return n
    }

    /** True when the final (RCAS) screen pass must use the flipped quad. */
    fun needsFinalFlip(inversionOn: Boolean, mitchellOn: Boolean): Boolean =
        fboPassCount(inversionOn, mitchellOn) % 2 == 1

    /** The chain label naming the passes actually run (§1.1). */
    fun chainLabel(mitchellOn: Boolean): String =
        if (mitchellOn) {
            "gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>mitchell>rcas"
        } else {
            "gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>rcas"
        }
}
