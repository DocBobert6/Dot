package app.dot

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — session rework tests (SHELL-SPEC §4.2, §6, §11). These run
// with no native libraries loaded, so they prove the safe defaults:
// input is store-only, rewind is empty, the pause toggle writes
// nothing, and save/load fail cleanly instead of throwing.
// Robolectric supplies the main looper the session posts to.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSessionTest {

    private fun session(files: File, sha: String): PlayerSession {
        val entry = LibraryEntry(
            sha256 = sha,
            title = "Test",
            sourceName = "test",
            dualMode = false,
            addedMs = 0L,
        )
        return PlayerSession(files, entry)
    }

    @Test
    fun setInputStoresWithoutNativeCode() {
        val s = session(createTempDir("dot-session-test"), "ab".repeat(32))
        // Store-only: no native call, so no throw before the core loads.
        s.setInput(Bridge.Input.A or Bridge.Input.START)
        s.setInput(0)
    }

    @Test
    fun rewindStepWithEmptyRingDoesNothing() {
        val s = session(createTempDir("dot-session-test"), "ab".repeat(32))
        s.rewindArmed = true
        assertFalse(s.rewindStep())
    }

    @Test
    fun pauseToggleWritesNeitherSaveNorAutoState() {
        val files = createTempDir("dot-session-pause-test")
        val sha = "cd".repeat(32)
        val s = session(files, sha)
        s.pause()
        assertTrue(s.isPaused())
        assertFalse(Store.autoStateFile(files, sha).exists())
        assertFalse(Store.batterySaveFile(files, sha).exists())
        s.resume()
        assertFalse(s.isPaused())
    }

    @Test
    fun saveFailsCleanlyWithNoCoreLoaded() {
        val s = session(createTempDir("dot-session-test"), "ab".repeat(32))
        var saveResult: Boolean? = null
        s.saveState(0) { ok -> saveResult = ok }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, saveResult)
    }

    @Test
    fun loadFailsCleanlyWithNoCoreLoaded() {
        val s = session(createTempDir("dot-session-test"), "ab".repeat(32))
        var loadResult: Boolean? = null
        s.loadState(0) { ok -> loadResult = ok }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, loadResult)
    }
}
