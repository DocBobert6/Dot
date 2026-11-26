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

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — the launch gate: the app must come up on the library screen
// with no native code loaded at all.
//
// Under Robolectric there is no .so to load, so a launch that
// touches the bridge would fail here instead of on Robert's phone.
// The gate also pins the two things a launch regression usually
// breaks first: the activity surviving a configuration change
// without restarting the session, and the launcher icon still
// pointing at the supplied artwork.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityStartupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun appContext(): Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun libraryIsTheFirstScreen() {
        composeRule.onNodeWithText("Dot").assertIsDisplayed()
        composeRule.onNodeWithText("Add game").assertIsDisplayed()
        composeRule.onNodeWithText("No games yet — add a game to start.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Licences").assertIsDisplayed()
    }

    @Test
    fun licencesScreenReachesTheNoticeText() {
        composeRule.onAllNodesWithText("Licences")[0].performClick()
        composeRule.onNodeWithText("Mozilla Public License", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun activityHandlesItsOwnConfigChanges() {
        // The session must survive rotation and window changes in
        // place: if the activity restarts instead, the game reloads.
        val info = appContext().packageManager.getActivityInfo(
            ComponentName(appContext(), MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val changes = info.configChanges
        val required = mapOf(
            "orientation" to ActivityInfo.CONFIG_ORIENTATION,
            "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE,
            "screenLayout" to ActivityInfo.CONFIG_SCREEN_LAYOUT,
            "keyboardHidden" to ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
            "smallestScreenSize" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
        )
        for ((name, flag) in required) {
            assertTrue(name, changes and flag != 0)
        }
    }

    @Test
    fun launcherIconIsTheSuppliedArtwork() {
        val context = appContext()
        val appInfo = context.packageManager
            .getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, appInfo.icon)
    }

    @Test
    fun librarySurvivesActivityRecreation() {
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("No games yet — add a game to start.")
            .assertIsDisplayed()
    }
}
