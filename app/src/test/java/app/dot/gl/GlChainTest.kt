package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain parity table (GL-CHAIN-SPEC §1.3, §10.1): the FBO pass
 * count and the final flip for every inversion x Mitchell
 * combination. This is the alpha.65 guard — a wrong count mirrors
 * the picture, so the table is pinned exactly.
 */
class GlChainTest {

    @Test
    fun fboPassCountTable() {
        // Without Mitchell: 10 with inversion, 9 without.
        assertEquals(10, ChainParity.fboPassCount(true, false))
        assertEquals(9, ChainParity.fboPassCount(false, false))
        // With Mitchell (H + V = 2 passes): 12 with inversion, 11 without.
        assertEquals(12, ChainParity.fboPassCount(true, true))
        assertEquals(11, ChainParity.fboPassCount(false, true))
    }

    @Test
    fun finalFlipTable() {
        // Flip iff the count is odd.
        assertFalse(ChainParity.needsFinalFlip(true, false)) // 10, even
        assertTrue(ChainParity.needsFinalFlip(false, false)) // 9, odd
        assertFalse(ChainParity.needsFinalFlip(true, true)) // 12, even
        assertTrue(ChainParity.needsFinalFlip(false, true)) // 11, odd
    }

    @Test
    fun phase1IsAlwaysNoFlip() {
        // Inversion is locked ON in Phase 1, so the parity is even
        // (no flip) in practice — with and without Mitchell.
        assertFalse(ChainParity.needsFinalFlip(true, false))
        assertFalse(ChainParity.needsFinalFlip(true, true))
    }

    @Test
    fun chainLabel() {
        assertEquals(
            "gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>rcas",
            ChainParity.chainLabel(false),
        )
        assertEquals(
            "gl:deband>dither-invert>deflicker>scalefx3x+raa>sharp-bilinear>mitchell>rcas",
            ChainParity.chainLabel(true),
        )
    }

    @Test
    fun poolSizes() {
        // One target per pass per size (§1.2, §10.1): with inversion
        // on, Dot holds 5 targets at 160x144 (invert + ScaleFX 0-3),
        // 3 at 480x432 (ScaleFX 4 + rAA 0-1), and 1 at the internal
        // game area (sharp-bilinear output). Simulate the chain's
        // acquisitions and assert the exact counts.
        val pool = FrameTargetPool()
        val exclude = emptySet<Int>()
        pool.beginFrame()
        pool.acquire(160, 144, TargetFilter.NEAREST, exclude) // invert
        pool.acquire(160, 144, TargetFilter.NEAREST, exclude) // deflicker
        pool.acquire(160, 144, TargetFilter.NEAREST, exclude) // ScaleFX 0
        pool.acquire(160, 144, TargetFilter.NEAREST, exclude) // ScaleFX 1
        pool.acquire(160, 144, TargetFilter.NEAREST, exclude) // ScaleFX 2
        pool.acquire(160, 144, TargetFilter.NEAREST, exclude) // ScaleFX 3
        // ScaleFX 4 is 480x432; the 160x144 bucket stays at 6
        // (invert + deflicker + ScaleFX 0-3). The spec's "5" counts
        // invert + ScaleFX 0-3 only (deflicker is the temporal stage,
        // listed separately in §1.1).
        assertEquals(6, pool.targetCount(160, 144, TargetFilter.NEAREST))
        pool.acquire(480, 432, TargetFilter.NEAREST, exclude) // ScaleFX 4
        pool.acquire(480, 432, TargetFilter.NEAREST, exclude) // rAA 0
        pool.acquire(480, 432, TargetFilter.NEAREST, exclude) // rAA 1
        assertEquals(3, pool.targetCount(480, 432, TargetFilter.NEAREST))
        pool.acquire(1200, 1080, TargetFilter.LINEAR, exclude) // sharp-bilinear
        assertEquals(1, pool.targetCount(1200, 1080, TargetFilter.LINEAR))
    }

    @Test
    fun poolNeverHandsOutATargetTwiceInOneFrame() {
        // The speckle regression (§1.4): with inversion on, ScaleFX
        // pass 4's main (tag) and extra (chain source) inputs must
        // be different textures, and no target acquired as an input
        // earlier in the frame may be re-handed-out as an output.
        // Simulate the chain's acquisitions in order, three frames.
        val pool = FrameTargetPool()
        repeat(3) {
            pool.beginFrame()
            val exclude = emptySet<Int>()
            val invert = pool.acquire(160, 144, TargetFilter.NEAREST, exclude)
            val deflicker = pool.acquire(160, 144, TargetFilter.NEAREST, exclude)
            // The chain source for ScaleFX pass 4 is the deflicker
            // output: it must survive as an input until pass 4.
            val metric = pool.acquire(160, 144, TargetFilter.NEAREST, exclude)
            val strength = pool.acquire(160, 144, TargetFilter.NEAREST, exclude)
            val junction = pool.acquire(160, 144, TargetFilter.NEAREST, exclude)
            val tag = pool.acquire(160, 144, TargetFilter.NEAREST, exclude)
            // Every 160x144 target this frame is distinct.
            val ids160 = listOf(invert.id, deflicker.id, metric.id, strength.id, junction.id, tag.id)
            assertEquals(ids160.size, ids160.toSet().size)
            // Pass 4's main (tag) and extra (chain source) are
            // different textures — the speckle bug was reading one
            // texture as both.
            assertTrue(tag.id != deflicker.id)
            // The 480x432 targets are distinct from each other.
            val sfx = pool.acquire(480, 432, TargetFilter.NEAREST, exclude)
            val raa0 = pool.acquire(480, 432, TargetFilter.NEAREST, exclude)
            val raa1 = pool.acquire(480, 432, TargetFilter.NEAREST, exclude)
            assertEquals(3, listOf(sfx.id, raa0.id, raa1.id).toSet().size)
            // The game target (LINEAR) is a different bucket.
            val game = pool.acquire(1200, 1080, TargetFilter.LINEAR, exclude)
            assertTrue(game.id != sfx.id)
        }
    }
}
