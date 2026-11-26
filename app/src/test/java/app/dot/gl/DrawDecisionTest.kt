package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure draw-decision state (DrawDecision): a charcoal draw must
 * never consume its hash, and a real surface size arriving must
 * reset the hash so the frame draws for real. This is the Phase
 * 1.2 black screen on device, pinned in code: a frame drew charcoal
 * on a 0-sized surface, had its hash consumed anyway, and every
 * later frame — a static title screen, all with the identical hash
 * — was skipped forever.
 */
class DrawDecisionTest {

    @Test
    fun charcoalOnZeroSurfaceDoesNotConsumeHash() {
        val decision = DrawDecision()
        val hash = 12345L
        assertEquals(
            CharcoalReason.Surface,
            decision.charcoalReason(0, 0, 0 to 0, 0 to 0),
        )
        // The frame never drew, so it must still need a draw.
        assertTrue(decision.needsDraw(hash))
    }

    @Test
    fun surfaceSizingResetsHashSoSameFrameDraws() {
        val decision = DrawDecision()
        val hash = 12345L
        // The 1.2 poisoning, reproduced: a frame draws charcoal on
        // a 0-sized surface, then the surface sizes, then the SAME
        // frame must draw for real.
        assertEquals(
            CharcoalReason.Surface,
            decision.charcoalReason(0, 0, 0 to 0, 0 to 0),
        )
        assertTrue(decision.needsDraw(hash))
        decision.onSurfaceSized(1080, 972)
        assertNull(
            decision.charcoalReason(1080, 972, 960 to 864, 960 to 864),
        )
        assertTrue(decision.needsDraw(hash))
        decision.markDrawn(hash)
        assertFalse(decision.needsDraw(hash))
    }

    @Test
    fun zeroSizeDoesNotResetHash() {
        val decision = DrawDecision()
        val hash = 12345L
        decision.markDrawn(hash)
        assertFalse(decision.needsDraw(hash))
        // A 0-sized surface change is not a real size: the drawn
        // frame must stay drawn.
        decision.onSurfaceSized(0, 0)
        assertFalse(decision.needsDraw(hash))
        decision.onSurfaceSized(1080, 972)
        assertTrue(decision.needsDraw(hash))
    }

    @Test
    fun staticFrameSkipsAfterDrawn() {
        val decision = DrawDecision()
        val hash = 999L
        assertTrue(decision.needsDraw(hash))
        decision.markDrawn(hash)
        // The title screen is static: the same hash must not
        // re-draw (the reuse the gate exists for).
        assertFalse(decision.needsDraw(hash))
        assertTrue(decision.needsDraw(hash + 1))
    }

    @Test
    fun charcoalReasonArea() {
        val decision = DrawDecision()
        assertEquals(
            CharcoalReason.Area,
            decision.charcoalReason(1080, 972, 0 to 864, 960 to 864),
        )
        assertEquals(
            CharcoalReason.Area,
            decision.charcoalReason(1080, 972, 960 to 864, 960 to 0),
        )
    }

    @Test
    fun charcoalReasonNullWhenDrawable() {
        val decision = DrawDecision()
        assertNull(decision.charcoalReason(1080, 972, 960 to 864, 960 to 864))
        // 4K target on a 1080p panel: internal bigger than screen
        // still draws (Mitchell downscale).
        assertNull(decision.charcoalReason(1080, 972, 2400 to 2160, 960 to 864))
    }

    @Test
    fun resetClearsHash() {
        val decision = DrawDecision()
        decision.markDrawn(7L)
        assertFalse(decision.needsDraw(7L))
        decision.reset()
        assertTrue(decision.needsDraw(7L))
    }
}
