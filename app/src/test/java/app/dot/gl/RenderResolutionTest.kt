package app.dot.gl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The render-resolution pref fix (1.5): Settings writes the
 * Auto/1080p/4K choice under "render_resolution", and the player
 * must read that same key. Before the fix the player read
 * "picture_target" — a key nothing ever wrote — so the choice
 * never took effect and the player always ran Auto. These tests
 * prove the player's resolved resolution follows the Settings
 * choice, and that the dead key is ignored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Project standard: every Robolectric test pins 35 (36 has no android-all jar).
class RenderResolutionTest {

    private class FakeHost : GlChainHost {
        override fun onGlFailed(where: String, program: String, e: Throwable) {}
        override fun onDrawDurationNanos(nanos: Long) {}
        override fun onChainLabel(label: String) {}
        override fun glFailureGameTitle(): String? = null
        override fun glFailureAppVersion(): String = "test"
        override fun takePendingFrame(): GlFrame? = null
    }

    private fun rendererWithSettingsTarget(prefsName: String): GlRenderer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return GlRenderer(
            context = context,
            host = FakeHost(),
            // The exact expression GameScreen uses after the fix.
            pictureTarget = { prefs.getString("render_resolution", "auto") ?: "auto" },
        )
    }

    @Test
    fun settingsChoiceReachesThePlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "dot-settings-test-resolution", Context.MODE_PRIVATE,
        )
        val renderer = rendererWithSettingsTarget("dot-settings-test-resolution")

        prefs.edit().putString("render_resolution", "4k").apply()
        assertEquals(2160, renderer.resolveTargetHeight())

        prefs.edit().putString("render_resolution", "1080p").apply()
        assertEquals(1080, renderer.resolveTargetHeight())

        prefs.edit().putString("render_resolution", "auto").apply()
        // Auto with no surface yet follows the display default.
        assertEquals(1080, renderer.resolveTargetHeight())
    }

    @Test
    fun deadPictureTargetKeyIsIgnored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "dot-settings-test-resolution-dead", Context.MODE_PRIVATE,
        )
        val renderer = rendererWithSettingsTarget("dot-settings-test-resolution-dead")

        // The Settings choice says 1080p; the old dead key says 4k.
        prefs.edit().putString("render_resolution", "1080p").apply()
        prefs.edit().putString("picture_target", "4k").apply()
        assertEquals(1080, renderer.resolveTargetHeight())

        // And with no Settings choice at all, the dead key alone
        // must not move the player off Auto.
        prefs.edit().remove("render_resolution").apply()
        assertEquals(1080, renderer.resolveTargetHeight())
    }
}
