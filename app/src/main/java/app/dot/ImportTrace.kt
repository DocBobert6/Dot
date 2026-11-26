package app.dot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — import trace (SHELL-SPEC §1.8, §12.1). A timestamped log
// of every Add game step, kept for screenshotting after a failed
// import. Every import step is a trace line, including the
// per-check diagnosis of a refused header. The Trace screen shows
// it; nothing here ever throws.

object ImportTrace {

    /** The trace is trimmed to this many bytes, oldest lines first. */
    const val MAX_BYTES = 100 * 1024

    fun traceFile(filesDir: File): File = File(filesDir, "import-trace.txt")

    /** Appends one timestamped line. Never throws. */
    fun append(filesDir: File, line: String, now: Date = Date()) {
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(now)
            val file = traceFile(filesDir)
            file.parentFile?.mkdirs()
            file.appendText("$time  $line\n", Charsets.UTF_8)
            trim(file)
        } catch (_: Exception) {
            // Tracing must never break an import.
        }
    }

    /** The whole trace, or an empty string when there is none. */
    fun read(filesDir: File): String {
        return try {
            val file = traceFile(filesDir)
            if (!file.exists()) "" else file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /** Clears the trace. Never throws. */
    fun clear(filesDir: File): Boolean {
        return try {
            val file = traceFile(filesDir)
            if (!file.exists()) true else file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun trim(file: File) {
        try {
            if (file.length() <= MAX_BYTES) return
            val lines = file.readLines(Charsets.UTF_8)
            var bytes = 0
            var keepFrom = lines.size
            for (i in lines.indices.reversed()) {
                bytes += lines[i].toByteArray(Charsets.UTF_8).size + 1
                if (bytes > MAX_BYTES) {
                    keepFrom = i + 1
                    break
                }
            }
            if (keepFrom in 1 until lines.size) {
                file.writeText(
                    lines.subList(keepFrom, lines.size)
                        .joinToString("\n", postfix = "\n"),
                    Charsets.UTF_8,
                )
            }
        } catch (_: Exception) {
            // A failed trim leaves the oversized file; the next
            // append retries it.
        }
    }
}
