package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The draw counters (GlDrawStats): counts, snapshot text, reset. */
class GlDrawStatsTest {

    @Before
    fun setUp() {
        GlDrawStats.reset()
    }

    @Test
    fun snapshotStartsAtZero() {
        val text = GlDrawStats.snapshot()
        assertTrue(text.contains("Draws attempted: 0"))
        assertTrue(text.contains("Draws shown: 0"))
        assertTrue(text.contains("Charcoal draws: 0 (latch: 0, surface: 0, area: 0)"))
        assertTrue(text.contains("Frames skipped (unchanged): 0"))
        assertTrue(text.contains("Frames with no pending frame: 0"))
        assertTrue(text.contains("Last surface: 0x0"))
        assertTrue(text.contains("Last charcoal reason: none"))
        assertTrue(text.contains("Latch failed: no"))
    }

    @Test
    fun charcoalCountsByReason() {
        GlDrawStats.noteAttempt()
        GlDrawStats.noteCharcoal(CharcoalReason.Surface)
        GlDrawStats.noteCharcoal(CharcoalReason.Surface)
        GlDrawStats.noteCharcoal(CharcoalReason.Area)
        GlDrawStats.noteCharcoal(CharcoalReason.Latch)
        val text = GlDrawStats.snapshot()
        assertTrue(text.contains("Draws attempted: 1"))
        assertTrue(text.contains("Charcoal draws: 4 (latch: 1, surface: 2, area: 1)"))
        assertTrue(text.contains("Last charcoal reason: latch"))
    }

    @Test
    fun drawnAndSkippedAndIdleCount() {
        GlDrawStats.noteDrawn()
        GlDrawStats.noteDrawn()
        GlDrawStats.noteHashSkipped()
        GlDrawStats.noteIdle()
        val text = GlDrawStats.snapshot()
        assertTrue(text.contains("Draws shown: 2"))
        assertTrue(text.contains("Frames skipped (unchanged): 1"))
        assertTrue(text.contains("Frames with no pending frame: 1"))
    }

    @Test
    fun surfaceAndLatchState() {
        GlDrawStats.noteSurface(1080, 972)
        GlDrawStats.noteLatchFailed()
        val text = GlDrawStats.snapshot()
        assertTrue(text.contains("Last surface: 1080x972"))
        assertTrue(text.contains("Latch failed: yes"))
    }

    @Test
    fun resetClearsEverything() {
        GlDrawStats.noteAttempt()
        GlDrawStats.noteDrawn()
        GlDrawStats.noteDirectDrawn()
        GlDrawStats.noteCharcoal(CharcoalReason.Surface)
        GlDrawStats.noteSurface(1080, 972)
        GlDrawStats.noteLatchFailed()
        GlDrawStats.reset()
        assertEquals(
            "Draws attempted: 0\n" +
                "Draws shown: 0\n" +
                "Direct draws: 0\n" +
                "Charcoal draws: 0 (latch: 0, surface: 0, area: 0)\n" +
                "Frames skipped (unchanged): 0\n" +
                "Frames with no pending frame: 0\n" +
                "Last surface: 0x0\n" +
                "Last charcoal reason: none\n" +
                "Latch failed: no",
            GlDrawStats.snapshot(),
        )
    }

    @Test
    fun directDrawsCountSeparately() {
        // A direct-pattern draw is counted apart from chain draws,
        // so the snapshot can never confuse a diagnostic draw with
        // a game picture — and the reset clears it too.
        GlDrawStats.noteDirectDrawn()
        GlDrawStats.noteDirectDrawn()
        val text = GlDrawStats.snapshot()
        assertTrue(text.contains("Direct draws: 2"))
        assertTrue(text.contains("Draws shown: 0"))
        GlDrawStats.reset()
        assertTrue(GlDrawStats.snapshot().contains("Direct draws: 0"))
    }

    @Test
    fun stageProbesAppearInSnapshotInChainOrder() {
        // The per-stage probes (Phase 1.6) show in the snapshot in
        // chain order, so the on-screen draw stats name the stage
        // that first outputs black without a bug report.
        GlDrawStats.noteStageProbes(
            listOf(
                StageProbe(ChainStage.UPLOAD, 255, 0, 255, 255),
                StageProbe(ChainStage.INVERT, 0, 0, 0, 255),
                StageProbe(ChainStage.RCAS, 0, 0, 0, 255),
            ),
        )
        val text = GlDrawStats.snapshot()
        assertTrue(
            text.contains(
                "Stage probes: upload=(255,0,255,255) " +
                    "invert=(0,0,0,255) rcas=(0,0,0,255)",
            ),
        )
        // A later frame replaces the line wholesale, and the reset
        // clears it — the empty snapshot is unchanged.
        GlDrawStats.noteStageProbes(
            listOf(StageProbe(ChainStage.UPLOAD, 0, 255, 255, 255)),
        )
        assertTrue(
            GlDrawStats.snapshot().contains(
                "Stage probes: upload=(0,255,255,255)",
            ),
        )
        GlDrawStats.reset()
        assertFalse(GlDrawStats.snapshot().contains("Stage probes:"))
    }
}
