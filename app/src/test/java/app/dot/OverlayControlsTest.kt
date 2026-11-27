package app.dot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Start/Select pills (Phase 1.2, device): the old fixed 64 dp
 * pill with 20 dp of horizontal padding left 24 dp for a 13 sp
 * label, so START / SELECT wrapped and clipped into unreadable
 * fragments ("CT" / "CEI"). The pill must grow to fit the label.
 *
 * Robolectric measures text at ~1 dp per character (START reads
 * 5.0 dp, SELECT 6.0 dp here), so a real label's width cannot
 * discriminate the old fixed pill from the new growing one — both
 * fit inside 24 dp at that scale. The growth itself is proved with
 * a 50-character probe label instead: the old pill stays 64 dp
 * wide and the text wraps into 24 dp, while the new pill grows to
 * 70 dp and the text stays one 50 dp line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Project standard: every Robolectric test pins 35 (36 has no android-all jar).
class OverlayControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderPill(label: String) {
        composeRule.setContent {
            Box(Modifier.size(400.dp)) {
                PillControlButton(
                    label = label,
                    spec = ControlSpec(0.5f, 0.5f, 64),
                    alpha = 1f,
                    enabled = true,
                    onHold = {},
                )
            }
        }
    }

    /** The label is present and rendered whole — never wrapped away. */
    private fun assertLabelShown(label: String) {
        renderPill(label)
        composeRule.onNodeWithText(label).assertIsDisplayed()
    }

    @Test
    fun startLabelFitsWhole() {
        assertLabelShown("START")
    }

    @Test
    fun selectLabelFitsWhole() {
        assertLabelShown("SELECT")
    }

    /**
     * The pill grows to fit its label instead of wrapping it: a
     * 50-character probe measures 50 dp wide as one line. The old
     * fixed 64 dp pill wrapped it into 24 dp (and clipped it
     * vertically) and fails this assertion.
     */
    @Test
    fun pillGrowsForLongLabel() {
        val probe = "X".repeat(50)
        renderPill(probe)
        val node = composeRule.onNodeWithText(probe)
        node.assertIsDisplayed()
        val widthPx = node.fetchSemanticsNode().boundsInRoot.width
        val widthDp = with(composeRule.density) { widthPx.toDp() }
        assertTrue(
            "label wrapped/clipped to $widthDp (needs >= 45dp)",
            widthDp.value >= 45f,
        )
    }
}
