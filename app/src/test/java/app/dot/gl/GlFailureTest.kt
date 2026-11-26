package app.dot.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * The GL failure latch and failure report (GL-CHAIN-SPEC §9.3/§9.4).
 * A GL failure fails loudly: the latch records it, the report is
 * written, and there is no fallback picture.
 */
class GlFailureTest {

    private fun sampleFailure(exceptionText: String = "java.lang.RuntimeException: boom\n\tat app.dot.Foo.bar(Foo.kt:1)"): GlFailure =
        GlFailure(
            time = "2026-11-26 19:00:00 EST",
            appVersion = "app.dot 0.1.0-phase1.1 (2)",
            device = "Pixel 10a (Android 17)",
            gpu = "Mali-G715",
            game = "Super Mario Land",
            where = "frame",
            program = "dot-chain",
            fallbackEngaged = false,
            exceptionText = exceptionText
        )

    @Test
    fun latchStartsClear() {
        val latch = GlFailureLatch()
        assertFalse(latch.isFailed)
    }

    @Test
    fun latchStaysFailedUntilRebuilt() {
        val latch = GlFailureLatch()
        latch.markFailed()
        assertTrue(latch.isFailed)
        latch.markFailed() // idempotent
        assertTrue(latch.isFailed)
        latch.clearOnSurfaceRebuilt()
        assertFalse(latch.isFailed)
    }

    @Test
    fun formatParseRoundTrip() {
        val f = sampleFailure()
        val parsed = GlFailureReport.parse(GlFailureReport.format(f))
        assertEquals(f, parsed)
    }

    @Test
    fun formatParseRoundTripWithDrawStats() {
        val stats =
            "Draws attempted: 12\n" +
                "Draws shown: 0\n" +
                "Charcoal draws: 12 (latch: 0, surface: 12, area: 0)\n" +
                "Frames skipped (unchanged): 0\n" +
                "Frames with no pending frame: 0\n" +
                "Last surface: 0x0\n" +
                "Last charcoal reason: surface 0x0\n" +
                "Latch failed: no"
        val f = sampleFailure().copy(drawStats = stats)
        val parsed = GlFailureReport.parse(GlFailureReport.format(f))
        assertEquals(f, parsed)
    }

    @Test
    fun parseOldFormatYieldsEmptyDrawStats() {
        // A report written before the stats existed has no stats
        // lines: it still parses, with drawStats empty.
        val f = sampleFailure()
        val parsed = GlFailureReport.parse(GlFailureReport.format(f))
        assertEquals("", parsed?.drawStats)
    }

    @Test
    fun formatParseRoundTripWithUnknownGpuAndGame() {
        val f = sampleFailure().copy(gpu = null, game = null, fallbackEngaged = true)
        val parsed = GlFailureReport.parse(GlFailureReport.format(f))
        assertEquals(f, parsed)
    }

    @Test
    fun formatTruncatesAtMaxChars() {
        val f = sampleFailure(exceptionText = "x".repeat(40_000))
        val text = GlFailureReport.format(f)
        assertEquals(GlFailureReport.MAX_CHARS, text.length)
    }

    @Test
    fun parseRejectsWrongHeader() {
        assertNull(GlFailureReport.parse("Not a report\nTime: x\n"))
    }

    @Test
    fun parseRejectsMissingTime() {
        val text = GlFailureReport.format(sampleFailure())
            .replace("Time: 2026-11-26 19:00:00 EST", "When: 2026-11-26 19:00:00 EST")
        assertNull(GlFailureReport.parse(text))
    }

    @Test
    fun parseRejectsMissingField() {
        val text = GlFailureReport.format(sampleFailure())
            .replace("GPU: Mali-G715\n", "")
        assertNull(GlFailureReport.parse(text))
    }

    @Test
    fun parseRejectsNoBlankLine() {
        val text = GlFailureReport.format(sampleFailure())
            .replace("Fallback engaged: no\n\n", "Fallback engaged: no\n")
        assertNull(GlFailureReport.parse(text))
    }

    @Test
    fun parseRejectsBlankException() {
        val text = GlFailureReport.format(sampleFailure(exceptionText = "   "))
        assertNull(GlFailureReport.parse(text))
    }

    @Test
    fun parseRejectsBadFallbackValue() {
        val text = GlFailureReport.format(sampleFailure())
            .replace("Fallback engaged: no", "Fallback engaged: maybe")
        assertNull(GlFailureReport.parse(text))
    }

    @Test
    fun parseRejectsTooFewLines() {
        assertNull(GlFailureReport.parse("Dot graphics failure report\nTime: x\n"))
    }

    @Test
    fun exportNameFormat() {
        val oldTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("dot-gl-failure-19700101-000000.txt", GlFailureReport.exportName(0L))
        } finally {
            TimeZone.setDefault(oldTz)
        }
        // And in any timezone it matches the pattern.
        val name = GlFailureReport.exportName(System.currentTimeMillis())
        assertTrue(name.matches(Regex("dot-gl-failure-\\d{8}-\\d{6}\\.txt")))
    }

    @Test
    fun writeReadRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory("dot-gl-failure-test").toFile()
        try {
            val f = sampleFailure()
            GlFailureReport.write(dir, f)
            assertEquals(f, GlFailureReport.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readReturnsNullWhenMissing() {
        val dir = kotlin.io.path.createTempDirectory("dot-gl-failure-test").toFile()
        try {
            assertNull(GlFailureReport.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readReturnsNullOnCorruptContent() {
        val dir = kotlin.io.path.createTempDirectory("dot-gl-failure-test").toFile()
        try {
            File(dir, GlFailureReport.FILE_NAME).writeText("garbage, not a report")
            assertNull(GlFailureReport.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesTheReport() {
        val dir = kotlin.io.path.createTempDirectory("dot-gl-failure-test").toFile()
        try {
            GlFailureReport.write(dir, sampleFailure())
            assertTrue(File(dir, GlFailureReport.FILE_NAME).exists())
            GlFailureReport.clear(dir)
            assertFalse(File(dir, GlFailureReport.FILE_NAME).exists())
            assertNull(GlFailureReport.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
