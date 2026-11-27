package app.dot.gl

// Dot — chain bisection diagnostics (Phase 1.6). The surface path
// is exonerated (the 1.5 direct pattern shows) and the through-chain
// pattern stays invisible, so the fault is inside the chain: some
// stage turns the picture black, and every later stage only
// propagates it. These pure types name the stages and read the
// per-stage centre-pixel probes, so the device log (and the
// on-screen draw stats) can say exactly which stage first outputs
// black — no guessing, no shader fix by inspection.
//
// The stages are the chain in order (GL-CHAIN-SPEC §1): upload,
// dither-invert, deflicker, ScaleFX 0-4, rAA 0-1, sharp-bilinear,
// Mitchell H+V (only when the internal area exceeds the screen),
// RCAS. The bisection selector stops the chain after the chosen
// stage and draws that stage's target straight to the screen
// through the passthrough shader — the same known-good path the
// direct pattern proved — so a visible stage output exonerates
// everything up to it, and a black one convicts that stage.
// RCAS as a selector value means the full chain including the
// normal RCAS final pass (the control: the selector plumbing must
// not change the normal path).
// Copyright (c) 2026 Dot contributors. All rights reserved.

/** One chain stage, in chain order, as stored in the `dev_chain_stage` pref. */
enum class ChainStage(val pref: String, val label: String) {
    OFF("off", "Off"),
    UPLOAD("upload", "Upload"),
    INVERT("invert", "Invert"),
    DEFLICKER("deflicker", "DeFlick"),
    SCALEFX("scalefx", "ScaleFX"),
    RAA("raa", "rAA"),
    SHARP("sharp", "Sharp"),
    MITCHELL("mitchell", "Mitchell"),
    RCAS("rcas", "RCAS");

    companion object {
        /** The stages in chain order. */
        val ORDER: List<ChainStage> = entries.toList()

        /** Parses the pref value; unknown, null, or blank means OFF (the normal chain). */
        fun fromPref(value: String?): ChainStage =
            entries.firstOrNull { it.pref == value } ?: OFF
    }
}

/**
 * The centre-pixel probe result for one stage: the RGBA the stage's
 * target held at its centre, read back with glReadPixels right
 * after the stage ran. A stage that was never written reads 0; a
 * stage that drew the test pattern reads magenta or cyan.
 */
data class StageProbe(val stage: ChainStage, val r: Int, val g: Int, val b: Int, val a: Int) {
    /**
     * Black: every colour channel at or below 8. The pattern's
     * colours are 0 or 255 per channel, so a pattern stage reads
     * (255,0,255), (0,255,255), or a blend of the two — never
     * black — while an unwritten or black stage reads (0,0,0).
     * Alpha is not part of the test: the chain writes opaque
     * colour, and a half-float target's alpha can read back as 0
     * on some drivers even when the colour is real.
     */
    val isBlack: Boolean get() = r <= 8 && g <= 8 && b <= 8
}

object ChainBisect {
    /**
     * The first stage (in chain order) whose probe pixel is black,
     * or null when no stage is black. The fault is the first stage
     * that turns the picture black: later stages only propagate
     * what they are given, so a black stage after a bright one is
     * the one to fix. Stages with no probe are skipped (the probe
     * toggle was off for that frame, or the stage did not run —
     * Mitchell only runs when the internal area exceeds the screen).
     */
    fun firstBlackStage(probes: List<StageProbe>): ChainStage? {
        val byStage = probes.associateBy { it.stage }
        for (stage in ChainStage.ORDER) {
            val probe = byStage[stage] ?: continue
            if (probe.isBlack) return stage
        }
        return null
    }
}
