package app.dot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art attempt-record tests (SHELL-SPEC §8.4). Pure.
class CoverAttemptsTest {

    private fun tempDir(): File {
        return File.createTempFile("dot-attempts-", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    @Test
    fun loadSaveRoundTripsAndSkipsCorruptLines() {
        val dir = tempDir()
        val attempts = CoverAttempts.Attempts(
            failed = mapOf(
                "aa" to CoverAttempts.FailedRecord(2, 1000L),
            ),
            noMatch = mapOf(
                "bb" to CoverAttempts.NoMatchRecord("deadbeef", 2000L),
            ),
        )
        assertTrue(CoverAttempts.save(dir, attempts))
        // A corrupt line must be skipped, never fatal.
        CoverAttempts.attemptsFile(dir).appendText("garbage line\n")
        val loaded = CoverAttempts.load(dir)
        assertEquals(2, loaded.failed["aa"]?.attempts)
        assertEquals(1000L, loaded.failed["aa"]?.lastAttemptMs)
        assertEquals("deadbeef", loaded.noMatch["bb"]?.indexSha)
    }

    @Test
    fun failedRetryNeedsCooldownAndBudget() {
        val hour = CoverAttempts.FAILED_COOLDOWN_MS
        val now = 10 * hour
        assertTrue(CoverAttempts.failedAutoRetryAllowed(null, now))
        assertTrue(
            CoverAttempts.failedAutoRetryAllowed(
                CoverAttempts.FailedRecord(1, now - 2 * hour),
                now,
            ),
        )
        assertFalse(
            CoverAttempts.failedAutoRetryAllowed(
                CoverAttempts.FailedRecord(1, now - hour / 2),
                now,
            ),
        )
        assertFalse(
            CoverAttempts.failedAutoRetryAllowed(
                CoverAttempts.FailedRecord(3, now - 2 * hour),
                now,
            ),
        )
        // A record older than 24 h is dropped: fresh budget.
        assertTrue(
            CoverAttempts.failedAutoRetryAllowed(
                CoverAttempts.FailedRecord(
                    3,
                    now - CoverAttempts.FAILED_RECORD_TTL_MS - 1,
                ),
                now,
            ),
        )
    }

    @Test
    fun noMatchRechecksOnIndexChangeTtlOrManual() {
        val day = 24 * 60 * 60 * 1000L
        val now = 30 * day
        val record = CoverAttempts.NoMatchRecord("deadbeef", now - day)
        assertFalse(
            CoverAttempts.noMatchRecheckNeeded(record, "deadbeef", now, false),
        )
        assertTrue(
            CoverAttempts.noMatchRecheckNeeded(record, "deadbeef", now, true),
        )
        assertTrue(
            CoverAttempts.noMatchRecheckNeeded(record, "cafe1234", now, false),
        )
        assertTrue(
            CoverAttempts.noMatchRecheckNeeded(
                CoverAttempts.NoMatchRecord("deadbeef", now - 8 * day),
                "deadbeef",
                now,
                false,
            ),
        )
        assertTrue(
            CoverAttempts.noMatchRecheckNeeded(null, "deadbeef", now, false),
        )
    }
}
