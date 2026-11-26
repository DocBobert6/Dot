package app.dot

import java.io.File

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art attempt records (SHELL-SPEC §8.4). Failed and
// NoMatch are NEVER permanent. Records hold ONLY a count and
// timestamps — never a failure reason string, never a wall-clock
// reset time. Persisted format, one record per line, in
// `cover-attempts.txt`:
//   `F <sha> <attempts> <lastAttemptMs>`
//   `N <sha> <indexSha> <checkedAtMs>`
// A corrupt line is skipped, never fatal.
//
// Failed: 1-hour cooldown before an automatic retry; at most 3
// automatic attempts per record lifetime; then manual-only until
// the record expires. A failure record older than 24 hours is
// dropped — the game gets a fresh budget.
// NoMatch: re-checked when the index changed since it was checked,
// or when its record is older than 7 days (TTL).

object CoverAttempts {

    const val FAILED_COOLDOWN_MS = 60 * 60 * 1000L
    const val FAILED_MAX_AUTO = 3
    const val FAILED_RECORD_TTL_MS = 24 * 60 * 60 * 1000L
    const val NOMATCH_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    const val FILE_NAME = "cover-attempts.txt"

    data class FailedRecord(val attempts: Int, val lastAttemptMs: Long)
    data class NoMatchRecord(val indexSha: String, val checkedAtMs: Long)

    data class Attempts(
        val failed: Map<String, FailedRecord> = emptyMap(),
        val noMatch: Map<String, NoMatchRecord> = emptyMap(),
    )

    fun attemptsFile(filesDir: File): File = File(filesDir, FILE_NAME)

    fun load(filesDir: File): Attempts {
        return try {
            val file = attemptsFile(filesDir)
            if (!file.exists()) return Attempts()
            val failed = HashMap<String, FailedRecord>()
            val noMatch = HashMap<String, NoMatchRecord>()
            for (line in file.readLines(Charsets.UTF_8)) {
                val parts = line.trim().split(Regex("\\s+"))
                when {
                    parts.size == 4 && parts[0] == "F" -> {
                        val attempts = parts[2].toIntOrNull() ?: continue
                        val last = parts[3].toLongOrNull() ?: continue
                        failed[parts[1]] = FailedRecord(attempts, last)
                    }
                    parts.size == 4 && parts[0] == "N" -> {
                        val checkedAt = parts[3].toLongOrNull() ?: continue
                        noMatch[parts[1]] = NoMatchRecord(parts[2], checkedAt)
                    }
                    else -> continue
                }
            }
            Attempts(failed, noMatch)
        } catch (_: Exception) {
            Attempts()
        }
    }

    fun save(filesDir: File, attempts: Attempts): Boolean {
        val text = buildString {
            for ((sha, record) in attempts.failed) {
                append("F $sha ${record.attempts} ${record.lastAttemptMs}\n")
            }
            for ((sha, record) in attempts.noMatch) {
                append("N $sha ${record.indexSha} ${record.checkedAtMs}\n")
            }
        }
        return Store.atomicWrite(
            attemptsFile(filesDir),
            text.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Whether a Failed game may auto-retry now. No record, or a
     * record older than 24 h, means a fresh budget. Inside the
     * budget: fewer than 3 attempts and past the 1-hour cooldown.
     */
    fun failedAutoRetryAllowed(
        record: FailedRecord?,
        nowMs: Long,
    ): Boolean {
        if (record == null) return true
        if (nowMs - record.lastAttemptMs > FAILED_RECORD_TTL_MS) return true
        if (record.attempts >= FAILED_MAX_AUTO) return false
        return nowMs - record.lastAttemptMs >= FAILED_COOLDOWN_MS
    }

    /**
     * Whether a NoMatch game re-checks now: the index changed
     * since it was checked, its record is older than 7 days, or
     * the user asked for a re-check with a manual refresh.
     */
    fun noMatchRecheckNeeded(
        record: NoMatchRecord?,
        currentIndexSha: String,
        nowMs: Long,
        manualRefresh: Boolean,
    ): Boolean {
        if (record == null) return true
        if (manualRefresh) return true
        if (record.indexSha != currentIndexSha) return true
        return nowMs - record.checkedAtMs >= NOMATCH_TTL_MS
    }
}
