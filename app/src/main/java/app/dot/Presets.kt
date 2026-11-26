package app.dot

import java.io.File

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — per-game presets (SHELL-SPEC §12.3). One small JSON file
// per game in filesDir/presets/, named by the game's SHA:
// `<sha>.json`. A missing or corrupt preset reads as the default
// preset, never a crash; a preset write failure never fails the
// import.
//
// Phase 1 holds one field: the user's rename. The display name is
// the rename when set and non-blank, else the header title. (The
// source/inner names the spec lists live in the library index for
// now; the preset grows if Dot needs per-game choices later.)

object Presets {

    data class GamePreset(val rename: String? = null)

    fun presetFile(filesDir: File, sha256: String): File =
        File(File(filesDir, "presets"), "$sha256.json")

    /** Loads the preset; missing or corrupt reads as the default. */
    fun load(filesDir: File, sha256: String): GamePreset {
        return try {
            val file = presetFile(filesDir, sha256)
            if (!file.exists()) return GamePreset()
            parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            GamePreset()
        }
    }

    /** Saves the preset atomically. Returns false on any failure. */
    fun save(filesDir: File, sha256: String, preset: GamePreset): Boolean {
        return Store.atomicWrite(
            presetFile(filesDir, sha256),
            encode(preset).toByteArray(Charsets.UTF_8),
        )
    }

    /** Deletes the preset (best effort; true when it is gone). */
    fun delete(filesDir: File, sha256: String): Boolean {
        return try {
            val file = presetFile(filesDir, sha256)
            if (!file.exists()) true else file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** The name the tile and the pause-menu header show. */
    fun displayName(entry: LibraryEntry, preset: GamePreset): String {
        val rename = preset.rename
        return if (!rename.isNullOrBlank()) rename else entry.title
    }

    /** Minimal JSON for one string field — no dependency. */
    fun encode(preset: GamePreset): String {
        val rename = preset.rename
        return if (rename == null) {
            "{\"rename\":null}"
        } else {
            "{\"rename\":\"${escape(rename)}\"}"
        }
    }

    fun parse(text: String): GamePreset {
        val key = "\"rename\""
        val keyAt = text.indexOf(key)
        if (keyAt < 0) return GamePreset()
        val colon = text.indexOf(':', keyAt + key.length)
        if (colon < 0) return GamePreset()
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return GamePreset()
        if (text.startsWith("null", i)) return GamePreset()
        if (text[i] != '"') return GamePreset()
        val out = StringBuilder()
        i++
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> return GamePreset(out.toString())
                ch == '\\' && i + 1 < text.length -> {
                    when (val esc = text[i + 1]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        else -> {
                            out.append('\\')
                            out.append(esc)
                        }
                    }
                    i += 2
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return GamePreset()
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\t' -> out.append("\\t")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
