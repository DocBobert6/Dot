package app.dot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — per-game preset tests (SHELL-SPEC §12.3).
class PresetsTest {

    private fun tempDir(): File {
        return File.createTempFile("dot-preset-", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    private val entry = LibraryEntry(
        sha256 = "ab".repeat(32),
        title = "Header Title",
        sourceName = "game",
        dualMode = false,
        addedMs = 0L,
    )

    @Test
    fun missingPresetReadsAsDefault() {
        val preset = Presets.load(tempDir(), entry.sha256)
        assertNull(preset.rename)
        assertEquals("Header Title", Presets.displayName(entry, preset))
    }

    @Test
    fun renameRoundTrips() {
        val dir = tempDir()
        assertTrue(Presets.save(dir, entry.sha256, Presets.GamePreset("My Game")))
        val loaded = Presets.load(dir, entry.sha256)
        assertEquals("My Game", loaded.rename)
        assertEquals("My Game", Presets.displayName(entry, loaded))
    }

    @Test
    fun blankRenameFallsBackToTheTitle() {
        assertEquals(
            "Header Title",
            Presets.displayName(entry, Presets.GamePreset("   ")),
        )
    }

    @Test
    fun corruptPresetReadsAsDefault() {
        val dir = tempDir()
        Presets.presetFile(dir, entry.sha256).apply {
            parentFile?.mkdirs()
            writeText("{not json", Charsets.UTF_8)
        }
        assertNull(Presets.load(dir, entry.sha256).rename)
    }

    @Test
    fun renameWithQuotesRoundTrips() {
        val dir = tempDir()
        val name = "He said \"hi\" \\ back"
        assertTrue(Presets.save(dir, entry.sha256, Presets.GamePreset(name)))
        assertEquals(name, Presets.load(dir, entry.sha256).rename)
    }

    @Test
    fun deleteRemovesThePreset() {
        val dir = tempDir()
        Presets.save(dir, entry.sha256, Presets.GamePreset("X"))
        assertTrue(Presets.delete(dir, entry.sha256))
        assertFalse(Presets.presetFile(dir, entry.sha256).exists())
    }
}
