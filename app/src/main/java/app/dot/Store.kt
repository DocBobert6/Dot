package app.dot

import java.io.File
import java.security.MessageDigest

// Dot — on-disk layout and atomic file writes.
//
// Layout (all under the app's filesDir):
//   games/    imported ROMs, one file per game: <sha256>.gb
//   saves/    battery saves: <sha256>.sav and <sha256>.rtc
//   states/   save states: <sha256>-slot<0..3>.state, plus
//             <sha256>-auto.state (Auto, written on clean exit /
//             backgrounding, never auto-loaded)
//   system/   core system directory (BIOS lives here if present)
//   library.txt  the library index, one line per game:
//             sha256 \t title \t sourceName \t dualMode \t addedMs
//             (fields are escaped: backslash, tab, newline)
//   presets/  per-game JSON (reserved for Phase 2 layout overrides)
//
// Every write goes through atomicWrite: write to a sibling .tmp file
// and rename, so a kill mid-write never leaves a torn file. A game
// is identified everywhere by the SHA-256 of its ROM bytes.
// Copyright (c) 2026 Dot contributors. All rights reserved.

/** One library entry: a game Dot knows about. */
data class LibraryEntry(
    val sha256: String,
    val title: String,
    /** Filename stem the game was imported under, for cover matching. */
    val sourceName: String,
    val dualMode: Boolean,
    val addedMs: Long,
)

object Store {

    fun gamesDir(filesDir: File): File = File(filesDir, "games")
    fun savesDir(filesDir: File): File = File(filesDir, "saves")
    fun statesDir(filesDir: File): File = File(filesDir, "states")
    fun systemDir(filesDir: File): File = File(filesDir, "system")
    fun presetsDir(filesDir: File): File = File(filesDir, "presets")
    fun libraryFile(filesDir: File): File = File(filesDir, "library.txt")

    fun romFile(filesDir: File, sha256: String): File =
        File(gamesDir(filesDir), "$sha256.gb")

    fun batterySaveFile(filesDir: File, sha256: String): File =
        File(savesDir(filesDir), "$sha256.sav")

    fun rtcFile(filesDir: File, sha256: String): File =
        File(savesDir(filesDir), "$sha256.rtc")

    fun stateFile(filesDir: File, sha256: String, slot: Int): File =
        File(statesDir(filesDir), "$sha256-slot$slot.state")

    fun autoStateFile(filesDir: File, sha256: String): File =
        File(statesDir(filesDir), "$sha256-auto.state")

    /** SHA-256 hex of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Writes [bytes] to [file] atomically: a sibling .tmp file, then
     * rename. Returns false on any failure; the old file is untouched
     * on failure.
     */
    fun atomicWrite(file: File, bytes: ByteArray): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            // One stream only: opening a second outputStream on the
            // same file truncates what the first one wrote.
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Reads [file] fully, or null when it does not exist / fails. */
    fun readBytes(file: File): ByteArray? {
        return try {
            if (!file.exists()) null else file.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    // -- library index -------------------------------------------------

    private fun escape(field: String): String {
        val out = StringBuilder(field.length)
        for (ch in field) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun unescape(field: String): String {
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val ch = field[i]
            if (ch == '\\' && i + 1 < field.length) {
                when (field[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> {
                        out.append('\\')
                        out.append(field[i + 1])
                    }
                }
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }

    private fun splitEscaped(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && i + 1 < line.length) {
                current.append(ch)
                current.append(line[i + 1])
                i += 2
            } else if (ch == '\t') {
                fields.add(current.toString())
                current.clear()
                i += 1
            } else {
                current.append(ch)
                i += 1
            }
        }
        fields.add(current.toString())
        return fields
    }

    fun encodeLibrary(entries: List<LibraryEntry>): String {
        return entries.joinToString("\n") { e ->
            listOf(
                e.sha256,
                escape(e.title),
                escape(e.sourceName),
                if (e.dualMode) "1" else "0",
                e.addedMs.toString(),
            ).joinToString("\t")
        }
    }

    /** Parses the library index. Bad lines are skipped, never fatal. */
    fun decodeLibrary(text: String): List<LibraryEntry> {
        if (text.isBlank()) return emptyList()
        val entries = ArrayList<LibraryEntry>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val fields = splitEscaped(line)
            if (fields.size != 5) continue
            val addedMs = fields[4].toLongOrNull() ?: continue
            entries.add(
                LibraryEntry(
                    sha256 = fields[0],
                    title = unescape(fields[1]),
                    sourceName = unescape(fields[2]),
                    dualMode = fields[3] == "1",
                    addedMs = addedMs,
                ),
            )
        }
        return entries
    }

    fun loadLibrary(filesDir: File): List<LibraryEntry> {
        val bytes = readBytes(libraryFile(filesDir)) ?: return emptyList()
        return decodeLibrary(String(bytes, Charsets.UTF_8))
    }

    fun saveLibrary(filesDir: File, entries: List<LibraryEntry>): Boolean {
        return atomicWrite(
            libraryFile(filesDir),
            encodeLibrary(entries).toByteArray(Charsets.UTF_8),
        )
    }
}
