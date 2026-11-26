package app.dot

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Dot — Robolectric startup gate.
//
// The app must launch into the Library with NO native code loaded:
// Bridge.load() is never called by the UI under test, and the
// activity only shows the library and licences. If the launch ever
// regresses — a composable that grew past the verifier, a missing
// resource, a crash before first frame — this fails in CI instead
// of on Robert's phone.
// SPDX-License-Identifier: GPL-3.0-or-later
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityStartupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsLibrary() {
        composeRule.onNodeWithText("Dot").assertIsDisplayed()
        composeRule.onNodeWithText("Add game").assertIsDisplayed()
        composeRule.onNodeWithText("No games yet — add a game to start.").assertIsDisplayed()
        composeRule.onNodeWithText("Licences").assertIsDisplayed()
    }

    @Test
    fun licencesScreenShowsNotice() {
        composeRule.onAllNodesWithText("Licences")[0].performClick()
        composeRule.onNodeWithText("Mozilla Public License", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun declaresRequiredConfigChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val flags = info.configChanges
        assertTrue("orientation", flags and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue("screenSize", flags and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        assertTrue("screenLayout", flags and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0)
        assertTrue("keyboardHidden", flags and ActivityInfo.CONFIG_KEYBOARD_HIDDEN != 0)
        assertTrue(
            "smallestScreenSize",
            flags and ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE != 0,
        )
    }

    @Test
    fun launcherIconIsSuppliedArtwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, appInfo.icon)
    }

    @Test
    fun survivesRecreation() {
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("No games yet — add a game to start.").assertIsDisplayed()
    }
}
