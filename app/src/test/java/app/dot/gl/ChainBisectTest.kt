package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chain bisection types (ChainBisect): stage parsing, order, and the first-black detector. */
class ChainBisectTest {

    @Test
    fun fromPrefParsesEveryStage() {
        assertEquals(ChainStage.OFF, ChainStage.fromPref("off"))
        assertEquals(ChainStage.UPLOAD, ChainStage.fromPref("upload"))
        assertEquals(ChainStage.INVERT, ChainStage.fromPref("invert"))
        assertEquals(ChainStage.DEFLICKER, ChainStage.fromPref("deflicker"))
        assertEquals(ChainStage.SCALEFX, ChainStage.fromPref("scalefx"))
        assertEquals(ChainStage.RAA, ChainStage.fromPref("raa"))
        assertEquals(ChainStage.SHARP, ChainStage.fromPref("sharp"))
        assertEquals(ChainStage.MITCHELL, ChainStage.fromPref("mitchell"))
        assertEquals(ChainStage.RCAS, ChainStage.fromPref("rcas"))
    }

    @Test
    fun fromPrefFallsBackToOff() {
        // Unknown, null, blank, and wrong-case values all mean the
        // normal chain: a bad pref must never select a stage.
        assertEquals(ChainStage.OFF, ChainStage.fromPref(null))
        assertEquals(ChainStage.OFF, ChainStage.fromPref(""))
        assertEquals(ChainStage.OFF, ChainStage.fromPref("bogus"))
        assertEquals(ChainStage.OFF, ChainStage.fromPref("Upload"))
        assertEquals(ChainStage.OFF, ChainStage.fromPref("SCALEFX"))
    }

    @Test
    fun stagesAreInChainOrder() {
        assertEquals(
            listOf(
                ChainStage.OFF, ChainStage.UPLOAD, ChainStage.INVERT,
                ChainStage.DEFLICKER, ChainStage.SCALEFX, ChainStage.RAA,
                ChainStage.SHARP, ChainStage.MITCHELL, ChainStage.RCAS,
            ),
            ChainStage.ORDER,
        )
    }

    @Test
    fun isBlackTreatsOnlyNearZeroAsBlack() {
        // An unwritten target reads 0; a drawn black quad reads 0.
        assertTrue(StageProbe(ChainStage.INVERT, 0, 0, 0, 255).isBlack)
        assertTrue(StageProbe(ChainStage.INVERT, 8, 8, 8, 0).isBlack)
        // The pattern's colours are 0 or 255 per channel — never
        // black — and a single bright channel is not black.
        assertFalse(StageProbe(ChainStage.INVERT, 255, 0, 255, 255).isBlack)
        assertFalse(StageProbe(ChainStage.INVERT, 0, 255, 255, 255).isBlack)
        assertFalse(StageProbe(ChainStage.INVERT, 9, 0, 0, 255).isBlack)
        assertFalse(StageProbe(ChainStage.INVERT, 0, 0, 9, 255).isBlack)
    }

    @Test
    fun firstBlackStageIsNullWhenNothingIsBlack() {
        val probes = listOf(
            StageProbe(ChainStage.UPLOAD, 255, 0, 255, 255),
            StageProbe(ChainStage.INVERT, 0, 255, 255, 255),
            StageProbe(ChainStage.DEFLICKER, 255, 0, 255, 255),
            StageProbe(ChainStage.SCALEFX, 0, 255, 255, 255),
            StageProbe(ChainStage.RAA, 255, 0, 255, 255),
            StageProbe(ChainStage.SHARP, 0, 255, 255, 255),
            StageProbe(ChainStage.RCAS, 255, 0, 255, 255),
        )
        assertNull(ChainBisect.firstBlackStage(probes))
    }

    @Test
    fun firstBlackStageNamesTheFirstBlackStage() {
        val probes = listOf(
            StageProbe(ChainStage.UPLOAD, 255, 0, 255, 255),
            StageProbe(ChainStage.INVERT, 0, 255, 255, 255),
            StageProbe(ChainStage.DEFLICKER, 0, 0, 0, 255),
            StageProbe(ChainStage.SCALEFX, 0, 0, 0, 255),
            StageProbe(ChainStage.RAA, 0, 0, 0, 255),
            StageProbe(ChainStage.SHARP, 0, 0, 0, 255),
            StageProbe(ChainStage.RCAS, 0, 0, 0, 255),
        )
        // Everything after deflicker is black too, but deflicker is
        // the fault: it turned the picture black, the rest only
        // propagated it.
        assertEquals(ChainStage.DEFLICKER, ChainBisect.firstBlackStage(probes))
    }

    @Test
    fun firstBlackStageSkipsStagesWithNoProbe() {
        // Mitchell only runs when the internal area exceeds the
        // screen, so a frame without it has no Mitchell probe — and
        // a black RCAS after bright earlier stages still names RCAS.
        val probes = listOf(
            StageProbe(ChainStage.UPLOAD, 255, 0, 255, 255),
            StageProbe(ChainStage.SHARP, 0, 255, 255, 255),
            StageProbe(ChainStage.RCAS, 0, 0, 0, 255),
        )
        assertEquals(ChainStage.RCAS, ChainBisect.firstBlackStage(probes))
    }

    @Test
    fun discriminatesBrokenStageFromGoodChain() {
        // The discrimination test (Phase 1.6): the detector must
        // name the deliberately broken stage and clear the good
        // chain. The "good" sequence is every stage holding the
        // test pattern (magenta/cyan — never black). The "broken"
        // sequence is the same chain with ScaleFX outputting black
        // from its stage onward: the detector must name ScaleFX,
        // not the later stages that merely propagated the black.
        val good = listOf(
            StageProbe(ChainStage.UPLOAD, 255, 0, 255, 255),
            StageProbe(ChainStage.INVERT, 0, 255, 255, 255),
            StageProbe(ChainStage.DEFLICKER, 255, 0, 255, 255),
            StageProbe(ChainStage.SCALEFX, 0, 255, 255, 255),
            StageProbe(ChainStage.RAA, 255, 0, 255, 255),
            StageProbe(ChainStage.SHARP, 0, 255, 255, 255),
            StageProbe(ChainStage.RCAS, 255, 0, 255, 255),
        )
        assertNull(ChainBisect.firstBlackStage(good))

        val brokenAtScalefx = listOf(
            StageProbe(ChainStage.UPLOAD, 255, 0, 255, 255),
            StageProbe(ChainStage.INVERT, 0, 255, 255, 255),
            StageProbe(ChainStage.DEFLICKER, 255, 0, 255, 255),
            StageProbe(ChainStage.SCALEFX, 0, 0, 0, 255),
            StageProbe(ChainStage.RAA, 0, 0, 0, 255),
            StageProbe(ChainStage.SHARP, 0, 0, 0, 255),
            StageProbe(ChainStage.RCAS, 0, 0, 0, 255),
        )
        assertEquals(ChainStage.SCALEFX, ChainBisect.firstBlackStage(brokenAtScalefx))

        // And a break at the very first stage names the upload,
        // not a later stage.
        val brokenAtUpload = listOf(
            StageProbe(ChainStage.UPLOAD, 0, 0, 0, 255),
            StageProbe(ChainStage.INVERT, 0, 0, 0, 255),
            StageProbe(ChainStage.RCAS, 0, 0, 0, 255),
        )
        assertEquals(ChainStage.UPLOAD, ChainBisect.firstBlackStage(brokenAtUpload))
    }
}
