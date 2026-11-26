package app.dot

import android.content.res.AssetManager
import java.io.File

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art index (SHELL-SPEC §8.1). Dot reads the
// libretro-thumbnails repo `Nintendo_-_Game_Boy` (the GB repo, NOT
// the GBA index). Bundled snapshot asset `art-index-gb.txt`,
// refreshed index file `art-index-gb.txt`, meta file
// `art-index-meta.txt`.
//
// Index load is two steps, never a download to show the first
// tile: (a) the top-level tree, non-recursive, whose `Named_Boxarts`
// entry carries the subtree SHA, fetched fresh on every index load,
// never hardcoded; (b) the `Named_Boxarts` subtree itself,
// downloaded only when the SHA changed. The subtree SHA is parsed
// from the top-tree response: the entry whose path is exactly
// `Named_Boxarts` and whose type is `tree`; the entry is sliced to
// its closing brace so a later entry's SHA can never be mistaken
// for it; null when absent.
//
// Index precedence: memory → disk cache (a refresh result) →
// bundled snapshot. A failed refresh leaves the bundled snapshot
// (or the disk cache) in place and reports the reason; it never
// blanks tiles.

object ArtIndex {

    const val REPO = "Nintendo_-_Game_Boy"
    const val ASSET_NAME = "art-index-gb.txt"
    const val CACHED_NAME = "art-index-gb.txt"
    const val META_NAME = "art-index-meta.txt"

    const val TOP_TREE_URL =
        "https://api.github.com/repos/libretro-thumbnails/$REPO/git/trees/master"
    const val SUBTREE_URL_PREFIX =
        "https://api.github.com/repos/libretro-thumbnails/$REPO/git/trees/"
    const val RAW_URL_PREFIX =
        "https://raw.githubusercontent.com/libretro-thumbnails/$REPO/master/Named_Boxarts/"

    /** One art file's URL (spaces encoded as %20, not +). */
    fun artUrl(filename: String): String {
        return RAW_URL_PREFIX + filename
            .replace(" ", "%20")
            .replace("#", "%23")
            .replace("?", "%3F")
    }

    data class Index(
        val entries: List<String>,
        val snapshotDate: String,
        /** "bundled" or "refreshed copy". */
        val source: String,
        val subtreeSha: String,
    )

    /**
     * Parses the bundled snapshot asset (or a refreshed copy): a
     * `# ...` header line, then one filename per line. Null when
     * there are no entries.
     */
    fun parseSnapshot(text: String): Index? {
        var date = ""
        var sha = ""
        val entries = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                // "# Dot art index snapshot 2026-11-26
                //  Nintendo_-_Game_Boy <sha>"
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 8) {
                    date = parts[5]
                    sha = parts[7]
                }
                continue
            }
            if (line.endsWith(".png")) entries.add(line)
        }
        if (entries.isEmpty()) return null
        return Index(entries, date, "bundled", sha)
    }

    fun loadBundled(assets: AssetManager): Index? {
        return try {
            assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .let { parseSnapshot(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun loadCached(filesDir: File): Index? {
        return try {
            val file = File(filesDir, CACHED_NAME)
            if (!file.exists()) return null
            val index = parseSnapshot(file.readText(Charsets.UTF_8))
                ?: return null
            val meta = readMeta(filesDir)
            index.copy(
                source = "refreshed copy",
                subtreeSha = meta?.first ?: index.subtreeSha,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Meta: "sha=<sha>\ncheckedAtMs=<ms>\n". */
    fun readMeta(filesDir: File): Pair<String, Long>? {
        return try {
            val file = File(filesDir, META_NAME)
            if (!file.exists()) return null
            var sha: String? = null
            var checkedAt: Long? = null
            for (line in file.readLines(Charsets.UTF_8)) {
                when {
                    line.startsWith("sha=") -> sha = line.removePrefix("sha=")
                    line.startsWith("checkedAtMs=") ->
                        checkedAt = line.removePrefix("checkedAtMs=").toLongOrNull()
                }
            }
            if (sha.isNullOrBlank() || checkedAt == null) null
            else sha to checkedAt
        } catch (_: Exception) {
            null
        }
    }

    fun writeMeta(filesDir: File, sha: String, checkedAtMs: Long): Boolean {
        return Store.atomicWrite(
            File(filesDir, META_NAME),
            "sha=$sha\ncheckedAtMs=$checkedAtMs\n".toByteArray(Charsets.UTF_8),
        )
    }

    fun writeCached(
        filesDir: File,
        entries: List<String>,
        sha: String,
        date: String,
    ): Boolean {
        val text = buildString {
            append("# Dot art index snapshot $date $REPO $sha\n")
            for (entry in entries) append(entry).append('\n')
        }
        return Store.atomicWrite(
            File(filesDir, CACHED_NAME),
            text.toByteArray(Charsets.UTF_8),
        )
    }

    // -- git-trees parsing (no JSON dependency) -------------------------

    /**
     * Parses a git-trees response into art filenames (the
     * `Named_Boxarts/<name>.png` form or bare `<name>.png`). Null
     * when the response is truncated, empty, or unparseable. A
     * response whose `truncated` flag is true is a failure, not a
     * short index.
     */
    fun parseTreeResponse(json: String): List<String>? {
        if (Regex("\"truncated\"\\s*:\\s*true").containsMatchIn(json)) {
            return null
        }
        val names = ArrayList<String>()
        for (obj in treeObjects(json)) {
            if (obj["type"] != "blob") continue
            val path = obj["path"] ?: continue
            if (!path.endsWith(".png")) continue
            names.add(path.removePrefix("Named_Boxarts/"))
        }
        if (names.isEmpty()) return null
        return names
    }

    /**
     * The Named_Boxarts subtree SHA from a top-tree response: the
     * entry whose path is exactly `Named_Boxarts` and whose type
     * is `tree`. Null when absent.
     */
    fun parseTopTreeSha(json: String): String? {
        for (obj in treeObjects(json)) {
            if (obj["path"] == "Named_Boxarts" && obj["type"] == "tree") {
                return obj["sha"]
            }
        }
        return null
    }

    /**
     * Each flat object in the response, sliced to its closing
     * brace, as a map of its string fields. The tree arrays hold
     * flat objects (path/mode/type/sha/url), so `{[^{}]*}` slices
     * exactly one entry — a later entry's SHA can never be
     * mistaken for this one's.
     */
    private fun treeObjects(json: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        val objectRegex = Regex("\\{[^{}]*\\}")
        val fieldRegex =
            Regex("\"(path|type|sha|truncated)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false)")
        for (match in objectRegex.findAll(json)) {
            val fields = HashMap<String, String>()
            for (field in fieldRegex.findAll(match.value)) {
                val key = field.groupValues[1]
                val raw = field.groupValues[2]
                fields[key] = if (raw.startsWith("\"")) {
                    unescapeJsonString(field.groupValues[3])
                } else {
                    raw
                }
            }
            if (fields.isNotEmpty()) out.add(fields)
        }
        return out
    }

    private fun unescapeJsonString(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6)
                                .toIntOrNull(16)
                            if (code != null) out.append(code.toChar())
                            i += 4
                        }
                    }
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
