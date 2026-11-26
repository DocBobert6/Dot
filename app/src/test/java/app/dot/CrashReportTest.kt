package app.dot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — crash-report format + validation tests (SHELL-SPEC §9).
// Pure file logic; no Robolectric needed.
class CrashReportTest {

    private fun tempDir(): File {
        return File.createTempFile("dot-crash-", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    @Test
    fun formatStartsWithHeaderAndTime() {
        val text = CrashReport.format(RuntimeException("boom"))
        val lines = text.split('\n')
        assertEquals(CrashReport.HEADER, lines[0])
        assertTrue(lines[1].startsWith("Time: "))
        assertTrue(lines[1].removePrefix("Time: ").isNotBlank())
        assertTrue(text.contains("java.lang.RuntimeException: boom"))
    }

    @Test
    fun loadReturnsAValidReport() {
        val dir = tempDir()
        assertTrue(CrashReport.write(dir, IllegalStateException("bad state")))
        val loaded = CrashReport.load(dir)
        assertTrue(loaded != null)
        assertTrue(loaded!!.contains("java.lang.IllegalStateException: bad state"))
    }

    @Test
    fun loadReturnsNullWithoutAFile() {
        assertNull(CrashReport.load(tempDir()))
    }

    @Test
    fun loadRejectsABadHeader() {
        val dir = tempDir()
        CrashReport.reportFile(dir).writeText(
            "Something else\nTime: 2026-11-26 00:00:00 UTC\n\nx",
            Charsets.UTF_8,
        )
        assertNull(CrashReport.load(dir))
    }

    @Test
    fun loadRejectsABlankTimeLine() {
        val dir = tempDir()
        CrashReport.reportFile(dir).writeText(
            "Dot crash report\nTime: \n\nx",
            Charsets.UTF_8,
        )
        assertNull(CrashReport.load(dir))
    }

    @Test
    fun loadRejectsAnOversizedFile() {
        val dir = tempDir()
        val file = CrashReport.reportFile(dir)
        file.writeText(
            CrashReport.HEADER + "\nTime: 2026-11-26 00:00:00 UTC\n\n" +
                "x".repeat(CrashReport.MAX_CHARS * 4 + 2048),
            Charsets.UTF_8,
        )
        assertNull(CrashReport.load(dir))
    }

    @Test
    fun clearDeletesTheReport() {
        val dir = tempDir()
        CrashReport.write(dir, RuntimeException("x"))
        assertTrue(CrashReport.clear(dir))
        assertFalse(CrashReport.reportFile(dir).exists())
        assertNull(CrashReport.load(dir))
    }
}
