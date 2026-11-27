package app.dot.gl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The frame-delivery wiring at the view (Phase 1.4): an unchanged
 * frame posted to the view must be dropped before the render gate,
 * so no onDrawFrame and no buffer swap happens for a static frame.
 * The 1.3 behaviour posted every frame: 361 attempts, 45 draws,
 * 316 hash-skips — and GLSurfaceView swaps after every
 * onDrawFrame, including the 316 that drew nothing, which is the
 * flashing on device. A surface rebuild (or a test-pattern toggle
 * change) must re-arm the gate so the next identical frame is
 * delivered again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Project standard: every Robolectric test pins 35 (36 has no android-all jar).
class GameGlViewDeliveryTest {

    private fun frame(hash: Long) = GlFrame(
        argb = IntArray(160 * 144),
        shades = ByteArray(160 * 144),
        hash = hash,
        deflickerMask = ByteArray(160 * 144),
    )

    @Before
    fun setUp() {
        GlDrawStats.reset()
    }

    @After
    fun tearDown() {
        GlDrawStats.reset()
    }

    @Test
    fun unchangedFrameIsDroppedBeforeTheGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        assertTrue(view.postFrame(frame(42L)))
        // The identical frame again: dropped, no render request.
        assertFalse(view.postFrame(frame(42L)))
        assertFalse(view.postFrame(frame(42L)))
        // A changed frame delivers again.
        assertTrue(view.postFrame(frame(43L)))
        assertFalse(view.postFrame(frame(43L)))
    }

    @Test
    fun invalidationReEnablesDeliveryOfTheSameHash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        assertTrue(view.postFrame(frame(7L)))
        assertFalse(view.postFrame(frame(7L)))
        // A delivered frame that never drew, or a surface rebuild.
        view.onDeliveryInvalidated("test")
        assertTrue(view.postFrame(frame(7L)))
        assertFalse(view.postFrame(frame(7L)))
    }

    @Test
    fun testPatternToggleForcesTheNextFrameThrough() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var patternOn = false
        val view = GameGlView(
            context,
            pictureTarget = { "auto" },
            testPattern = { patternOn },
        )
        assertTrue(view.postFrame(frame(9L)))
        assertFalse(view.postFrame(frame(9L)))
        // Turning the pattern on: the next identical frame must be
        // delivered (it draws the pattern, not the game).
        patternOn = true
        assertTrue(view.postFrame(frame(9L)))
        assertFalse(view.postFrame(frame(9L)))
        // Turning it off: the next identical frame must be delivered
        // again (it re-draws the game frame over the pattern).
        patternOn = false
        assertTrue(view.postFrame(frame(9L)))
        assertFalse(view.postFrame(frame(9L)))
    }

    @Test
    fun droppedFramesCountAsSkipped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        assertTrue(view.postFrame(frame(1L)))
        assertFalse(view.postFrame(frame(1L)))
        assertFalse(view.postFrame(frame(1L)))
        assertTrue(GlDrawStats.snapshot().contains("Frames skipped (unchanged): 2"))
    }
}
