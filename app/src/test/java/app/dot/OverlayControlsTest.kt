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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Project standard: every Robolectric test pins 35 (36 has no android-all jar).
class OverlayControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Renders one pill and asserts the whole label is visible: the
     * text node's width must be at least the unclipped label width.
     * The old layout measured ~24 dp here and fails.
     */
    private fun assertLabelUnclipped(label: String, minWidthDp: Float) {
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
        val node = composeRule.onNodeWithText(label)
        node.assertIsDisplayed()
        val widthPx = node.fetchSemanticsNode().boundsInRoot.width
        val widthDp = with(composeRule.density) { widthPx.toDp() }
        assertTrue(
            "label '$label' clipped to $widthDp (needs >= ${minWidthDp}dp)",
            widthDp.value >= minWidthDp,
        )
    }

    @Test
    fun startLabelFitsWhole() {
        assertLabelUnclipped("START", 30f)
    }

    @Test
    fun selectLabelFitsWhole() {
        assertLabelUnclipped("SELECT", 38f)
    }
}
