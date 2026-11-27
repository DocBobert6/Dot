package app.dot.gl

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The direct-pattern diagnostic (Phase 1.5): a magenta/cyan
 * checkerboard drawn straight to the screen, bypassing the whole
 * chain — plus the media-overlay z-order diagnostic.
 *
 * The direct shader must be a pure computation (no texture, no
 * sampler), or it is not a bypass at all. The mode switch must
 * move the view between continuous and on-demand rendering, and
 * turning the pattern off must re-arm the delivery gate so the
 * game frame draws again instead of the last presented buffer
 * (the pattern) staying on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Project standard: every Robolectric test pins 35 (36 has no android-all jar).
class DirectPatternModeTest {

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
    fun directShaderIsAPureComputation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = context.assets.open("shaders/direct.frag")
            .readBytes().toString(Charsets.UTF_8)
        // No texture of any kind: a sampler would make the "direct"
        // draw depend on an upload again.
        assertFalse(src.contains("sampler"))
        assertFalse(src.contains("texture("))
        assertFalse(src.contains("texelFetch"))
        // The checkerboard itself: the texcoord drives the cells,
        // magenta and cyan are the two colours.
        assertTrue(src.contains("vTexCoord"))
        assertTrue(src.contains("vec3(1.0, 0.0, 1.0)"))
        assertTrue(src.contains("vec3(0.0, 1.0, 1.0)"))
    }

    @Test
    fun directPatternModeSwitchesRenderMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, view.renderMode)
        view.setDirectPatternMode(true)
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, view.renderMode)
        // Idempotent: a second call with the same value changes nothing.
        view.setDirectPatternMode(true)
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, view.renderMode)
        view.setDirectPatternMode(false)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, view.renderMode)
    }

    @Test
    fun turningDirectPatternOffReArmsTheDeliveryGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        assertTrue(view.postFrame(frame(5L)))
        // The identical game frame is dropped as unchanged.
        assertFalse(view.postFrame(frame(5L)))
        // Turning the pattern on, then off again, must let the
        // same game frame through: otherwise the last presented
        // buffer (the pattern) would stay on screen forever on a
        // static title screen.
        view.setDirectPatternMode(true)
        view.setDirectPatternMode(false)
        assertTrue(view.postFrame(frame(5L)))
        assertFalse(view.postFrame(frame(5L)))
    }

    @Test
    fun mediaOverlayToggleDoesNotThrow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        view.setMediaOverlay(true)
        view.setMediaOverlay(false)
        view.setMediaOverlay(true)
    }
}
