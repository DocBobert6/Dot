package app.dot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — crash report (SHELL-SPEC §9). One file, latest crash only,
// local only, no send, no network, no new permission.
//
// Installed in MainActivity.onCreate, first: a JVM
// UncaughtExceptionHandler that synchronously writes the report,
// then chains to the previously installed default handler, so the
// system still handles the crash normally — the handler never
// swallows the crash or keeps the process alive. The write itself
// never throws.
//
// A single direct write, NOT temp-file + rename: the handler runs
// while the process is dying, and a kill between two filesystem
// operations leaves NO report at all. A direct write puts the
// header, the time and the exception type first, so even a partial
// write is still a useful report. A torn file never shows garbage
// as a report: the reader treats a file without the intact header
// as no report.
//
// Honest limits (stated on the Settings page): uncaught JVM
// exceptions only — a native crash in the game core does not go
// through this handler and leaves no report. A task-removal kill
// (swiping the app away, or a force-stop) leaves no report.

object CrashReport {

    const val HEADER = "Dot crash report"

    /** The report body is truncated to this many characters. */
    const val MAX_CHARS = 32_768

    fun reportFile(filesDir: File): File = File(filesDir, "crash-report.txt")

    /**
     * Installs the handler. Installing twice (activity recreated
     * in-process) just wraps the previous handler, so a crash
     * writes the same report twice — harmless, same content.
     */
    fun install(filesDir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(filesDir, throwable)
            } catch (_: Exception) {
                // The write never throws by design; this is the
                // last-resort guard so the handler can never mask
                // the crash itself.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Formats a report for [throwable] at the current time. */
    fun format(throwable: Throwable, now: Date = Date()): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(now)
        val body = StringBuilder()
        var current: Throwable? = throwable
        while (current != null) {
            body.append(current.javaClass.name)
            current.message?.let { body.append(": ").append(it) }
            body.append('\n')
            for (element in current.stackTrace) {
                body.append("    at ").append(element.toString()).append('\n')
            }
            current = current.cause
            if (current != null) body.append("Caused by: ")
        }
        val text = body.toString()
        val truncated = if (text.length > MAX_CHARS) {
            text.substring(0, MAX_CHARS)
        } else {
            text
        }
        return "$HEADER\nTime: $time\n\n$truncated"
    }

    /** Writes the report for [throwable] (direct write, §9). */
    fun write(filesDir: File, throwable: Throwable): Boolean {
        return try {
            reportFile(filesDir).writeText(
                format(throwable), Charsets.UTF_8,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Loads the latest report, or null when there is no file, the
     * file is unreadable, oversized, malformed UTF-8, or fails
     * validation (first line the header, second a non-blank
     * `Time: …` line). Never throws.
     */
    fun load(filesDir: File): String? {
        return try {
            val file = reportFile(filesDir)
            if (!file.exists()) return null
            if (file.length() > MAX_CHARS.toLong() * 4 + 1024) return null
            val bytes = file.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            // A malformed-UTF-8 read produces replacement
            // characters; a report written by this code never
            // contains one, so its presence means a torn file.
            if (text.contains('\uFFFD')) return null
            val lines = text.split('\n')
            if (lines.size < 2) return null
            if (lines[0] != HEADER) return null
            val timeLine = lines[1]
            if (!timeLine.startsWith("Time: ")) return null
            if (timeLine.removePrefix("Time: ").isBlank()) return null
            text
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes the report. Never throws. */
    fun clear(filesDir: File): Boolean {
        return try {
            val file = reportFile(filesDir)
            if (!file.exists()) true else file.delete()
        } catch (_: Exception) {
            false
        }
    }
}
