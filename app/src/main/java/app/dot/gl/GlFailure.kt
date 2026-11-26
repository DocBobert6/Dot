package app.dot.gl

// Dot — the GL failure latch and failure report (GL-CHAIN-SPEC §9.3/§9.4).
// A GL failure fails loudly: the latch records it, the player shows
// an honest error, the report is written, and the benchmark flags
// the capture (gl_failed) so it can never pass. There is no fallback
// picture (Atomic alpha.38; Robert's no-fallbacks rule).
// Copyright (c) 2026 Dot contributors. All rights reserved.

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The latch: once a GL failure is recorded, it stays until the surface is rebuilt. */
class GlFailureLatch {
    @Volatile
    var isFailed: Boolean = false
        private set

    @Synchronized
    fun markFailed() {
        isFailed = true
    }

    /** Clears the latch — called LAST in onSurfaceCreated, after the rebuild. */
    @Synchronized
    fun clearOnSurfaceRebuilt() {
        isFailed = false
    }
}

/** One recorded GL failure (the report's fields, GL-CHAIN-SPEC §9.4). */
data class GlFailure(
    val time: String, // yyyy-MM-dd HH:mm:ss z
    val appVersion: String,
    val device: String,
    val gpu: String?, // null before the first surface creation ("unknown")
    val game: String?, // null when no game was running
    val where: String, // "frame" / "layer chain" / "chain", or the fallback's own where
    val program: String,
    val fallbackEngaged: Boolean, // Dot has no fallback — always false until one is added
    val exceptionText: String // exception type + message + stack trace, verbatim
)

/** The failure report: format, parse, store, export (GL-CHAIN-SPEC §9.4). */
object GlFailureReport {
    const val FILE_NAME = "gl-failure-report.txt"
    const val MAX_CHARS = 32_768
    private const val HEADER = "Dot graphics failure report"

    fun format(f: GlFailure): String {
        val sb = StringBuilder()
        sb.appendLine(HEADER)
        sb.appendLine("Time: ${f.time}")
        sb.appendLine("App: ${f.appVersion}")
        sb.appendLine("Device: ${f.device}")
        sb.appendLine("GPU: ${f.gpu ?: "unknown"}")
        sb.appendLine("Game: ${f.game ?: "unknown"}")
        sb.appendLine("Where: ${f.where}")
        sb.appendLine("Program: ${f.program}")
        sb.appendLine("Fallback engaged: ${if (f.fallbackEngaged) "yes" else "no"}")
        sb.appendLine()
        sb.append(f.exceptionText)
        val text = sb.toString()
        return if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
    }

    /**
     * Parses a report back. Returns null on any corruption (wrong
     * header, missing Time line, missing labelled field, no blank
     * line, blank exception text, or a Fallback-engaged value other
     * than exactly "yes" or "no").
     */
    fun parse(text: String): GlFailure? {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0] != HEADER) return null
        if (lines.size < 10) return null
        if (!lines[1].startsWith("Time: ") || lines[1].length <= 6) return null
        val fields = mutableMapOf<String, String>()
        for (i in 2..8) {
            val line = lines[i]
            val colon = line.indexOf(": ")
            if (colon < 0) return null
            fields[line.substring(0, colon)] = line.substring(colon + 2)
        }
        val required = listOf("App", "Device", "GPU", "Game", "Where", "Program", "Fallback engaged")
        if (!fields.keys.containsAll(required)) return null
        if (lines[9].isNotEmpty()) return null // the blank line after the fields
        val exceptionText = lines.drop(10).joinToString("\n")
        if (exceptionText.isBlank()) return null
        val fallback = when (fields["Fallback engaged"]) {
            "yes" -> true
            "no" -> false
            else -> return null
        }
        return GlFailure(
            time = lines[1].substring(6),
            appVersion = fields.getValue("App"),
            device = fields.getValue("Device"),
            gpu = fields.getValue("GPU").takeIf { it != "unknown" },
            game = fields.getValue("Game").takeIf { it != "unknown" },
            where = fields.getValue("Where"),
            program = fields.getValue("Program"),
            fallbackEngaged = fallback,
            exceptionText = exceptionText
        )
    }

    /** A single direct write (never temp+rename — the report must land even mid-crash). */
    fun write(filesDir: File, failure: GlFailure) {
        try {
            File(filesDir, FILE_NAME).writeText(format(failure), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // A failure report that cannot be written is logged by the caller, never thrown.
        }
    }

    /** Loads the latest report, or null when there is none / it is unreadable / corrupt. */
    fun read(filesDir: File): GlFailure? {
        return try {
            val file = File(filesDir, FILE_NAME)
            if (!file.exists()) return null
            if (file.length() > MAX_CHARS.toLong() * 4 + 1024) return null
            val bytes = file.readBytes()
            val text = String(bytes, StandardCharsets.UTF_8)
            // Strict UTF-8: a malformed sequence decodes to U+FFFD; reject it.
            if (text.contains('\uFFFD')) return null
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(filesDir: File) {
        try {
            File(filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    /** The export filename: dot-gl-failure-<yyyyMMdd-HHmmss>.txt. */
    fun exportName(nowMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return "dot-gl-failure-${sdf.format(Date(nowMs))}.txt"
    }

    /**
     * Exports the report. Android 10+ -> Downloads/gl-failure via
     * MediaStore; older / failure -> filesDir/gl-failure/. The name
     * is generated once and used for both paths. Returns a message
     * naming the true location verbatim (or the failure).
     */
    fun export(context: Context, filesDir: File, failure: GlFailure, nowMs: Long = System.currentTimeMillis()): String {
        val name = exportName(nowMs)
        val text = format(failure)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/gl-failure")
                }
                val uri: Uri? = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(StandardCharsets.UTF_8))
                    }
                    return "Saved to Downloads/gl-failure/$name"
                }
            } catch (_: Exception) {
                // Falls through to the filesDir path below.
            }
        }
        return try {
            val dir = File(filesDir, "gl-failure")
            dir.mkdirs()
            File(dir, name).writeText(text, StandardCharsets.UTF_8)
            "Saved to app storage/gl-failure/$name"
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }
}
