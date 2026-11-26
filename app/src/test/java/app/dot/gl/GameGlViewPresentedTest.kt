package app.dot.gl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dot.Benchmark
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The presented-frame wiring (Phase 1.3): before this fix nothing
 * in production ever called Benchmark.recordPresented, so the
 * presented stats never ticked, a 600-frame capture could never
 * complete, and the FPS counter read 0.0 forever — proved on
 * device, where the benchmark sat waiting and never finished. The
 * view's onFramePresented must tick it.
 */
@RunWith(RobolectricTestRunner::class)
class GameGlViewPresentedTest {

    @Before
    fun setUp() {
        Benchmark.resetForTests()
    }

    @After
    fun tearDown() {
        Benchmark.resetForTests()
    }

    @Test
    fun presentedTicksThroughTheView() {
        assertNull(Benchmark.liveStats())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = GameGlView(context, pictureTarget = { "auto" })
        view.onFramePresented()
        view.onFramePresented()
        assertNotNull(Benchmark.liveStats())
    }
}
