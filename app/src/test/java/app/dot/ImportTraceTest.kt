package app.dot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — import-trace append/read/trim tests (SHELL-SPEC §1.8).
class ImportTraceTest {

    private fun tempDir(): File {
        return File.createTempFile("dot-trace-", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    @Test
    fun appendThenReadRoundTrips() {
        val dir = tempDir()
        assertEquals("", ImportTrace.read(dir))
        ImportTrace.append(dir, "Picked a game.")
        ImportTrace.append(dir, "Header valid.")
        val text = ImportTrace.read(dir)
        assertTrue(text.contains("Picked a game."))
        assertTrue(text.contains("Header valid."))
        assertEquals(2, text.trim().split('\n').size)
    }

    @Test
    fun clearEmptiesTheTrace() {
        val dir = tempDir()
        ImportTrace.append(dir, "Something happened.")
        assertTrue(ImportTrace.clear(dir))
        assertEquals("", ImportTrace.read(dir))
    }

    @Test
    fun trimKeepsTheNewestLinesUnderTheCap() {
        val dir = tempDir()
        val longLine = "x".repeat(1024)
        for (i in 0 until 150) {
            ImportTrace.append(dir, "$longLine $i")
        }
        val file = ImportTrace.traceFile(dir)
        assertTrue(file.length() <= ImportTrace.MAX_BYTES)
        val text = ImportTrace.read(dir)
        assertTrue(text.contains("149"))
        assertTrue(!text.contains(" 0\n"))
    }
}
