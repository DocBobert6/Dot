package app.dot.gl

import org.junit.Assert.assertEquals
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
        GlDrawStats.noteCharcoal(CharcoalReason.Surface)
        GlDrawStats.noteSurface(1080, 972)
        GlDrawStats.noteLatchFailed()
        GlDrawStats.reset()
        assertEquals(
            "Draws attempted: 0\n" +
                "Draws shown: 0\n" +
                "Charcoal draws: 0 (latch: 0, surface: 0, area: 0)\n" +
                "Frames skipped (unchanged): 0\n" +
                "Frames with no pending frame: 0\n" +
                "Last surface: 0x0\n" +
                "Last charcoal reason: none\n" +
                "Latch failed: no",
            GlDrawStats.snapshot(),
        )
    }
}
