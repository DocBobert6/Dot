package app.dot

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art matcher (SHELL-SPEC §8.2, stem-first).
//
// Match inputs per game, in order: the outer filename stem, then
// the name inside the zip, then the ROM header title. The first
// input that yields a match wins; the tile's full name uses the
// SAME match the art pipeline makes, so the name a tile shows and
// the art it shows can never disagree about which game this is.
//
// Match steps, in order: (1) exact title; (2) title prefix (a real
// title only, >= 2 words); (3) bare prefix (a truncated header
// title — the art must continue the SAME word); (4) reverse prefix
// (the title starts with the art title); (5) contiguous phrase
// (both >= 2 words). Ranking among candidates: non-variant first,
// then shortest normalized title, then USA/World, then Europe,
// then any other single region, then fewest parenthetical groups,
// then shortest filename.
//
// The denylist starts EMPTY for Dot (the GBA entries do not carry
// over) and adds entries only from measured header-only mismatches
// against the live GB index. It fires only on the header path.

object CoverMatcher {

    val REGION_WORDS = setOf(
        "japan", "usa", "us", "europe", "eu", "world", "asia", "korea",
        "brazil", "france", "germany", "spain", "italy", "uk",
        "australia", "canada", "netherlands", "china", "taiwan",
        "mexico", "argentina", "portugal",
    )

    val EDITION_WORDS = setOf(
        "rev", "beta", "proto", "prototype", "demo", "kiosk", "sample",
    )

    val LANGUAGE_CODES = setOf(
        "en", "ja", "fr", "de", "es", "it", "pt", "nl", "ko", "zh",
        "ru", "sv", "da", "fi", "no", "pl", "tr", "ar", "he", "cs",
        "hu", "el", "id", "ms", "th", "vi", "jp", "kr", "cn", "tw",
        "au", "ca", "mx", "br", "us", "uk", "eu",
    )

    /** Words that mark a file a variant (re-release / repack / demo). */
    val VARIANT_WORDS = setOf(
        "beta", "proto", "prototype", "bootleg", "demo", "kiosk",
        "sample", "virtual", "console", "collection",
    )

    /** One match input: the text, and whether it is the header title. */
    data class MatchInput(val text: String, val isHeader: Boolean)

    /**
     * The matched art filename (with .png), or null. Inputs are
     * tried in order; within an input, the earliest match step
     * with any candidate wins, ranked among its candidates.
     */
    fun match(
        inputs: List<MatchInput>,
        artFilenames: List<String>,
        denylist: Set<String> = emptySet(),
    ): String? {
        if (artFilenames.isEmpty()) return null
        val indexed = artFilenames.map { ArtEntry(it) }
        for (input in inputs) {
            val query = normalise(input.text)
            if (query.isEmpty()) continue
            // The denylist fires only on the header path: a pinned
            // header title never matches at all.
            if (input.isHeader && query.joinToString(" ") in denylist) continue
            for (step in 1..5) {
                val candidates = indexed.filter { stepMatches(step, query, it.words) }
                if (candidates.isNotEmpty()) {
                    return rank(candidates).filename
                }
            }
        }
        return null
    }

    /** Normalises a title to its match words (§8.2). */
    fun normalise(title: String): List<String> {
        var s = title.replace('$', 's')
        // ", The" dropped.
        s = s.replace(Regex(",\\s+the\\b", RegexOption.IGNORE_CASE), "")
        // Parenthetical and bracket groups stripped (edition/region
        // tags, never the title). Repeated: an inner group can hide
        // behind an outer one.
        var prev: String
        do {
            prev = s
            s = s.replace(Regex("\\([^)]*\\)"), " ")
            s = s.replace(Regex("\\[[^]]*\\]"), " ")
        } while (s != prev)
        val words = s.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .toMutableList()
        // A leading "the" dropped when more than one word remains.
        while (words.size > 1 && words.first() == "the") {
            words.removeAt(0)
        }
        // Trailing region / language / edition tags stripped while
        // more than one word remains.
        while (words.size > 1 && isTagWord(words.last())) {
            words.removeAt(words.size - 1)
        }
        return words
    }

    /** A trailing tag word: region, edition, or a 2/4/6-letter
     *  string made of 2-letter language/region codes. */
    fun isTagWord(word: String): Boolean {
        if (word in REGION_WORDS || word in EDITION_WORDS) return true
        if (word.length != 2 && word.length != 4 && word.length != 6) {
            return false
        }
        return word.chunked(2).all { it in LANGUAGE_CODES }
    }

    private data class ArtEntry(val filename: String) {
        val words: List<String> = normalise(filename.removeSuffix(".png"))
        val parenGroups: Int = filename.count { it == '(' }
        val regionRank: Int = regionRank(filename)
        // Read from the RAW filename, not the normalised words:
        // the variant tags (Beta, Demo, ...) live in the
        // parenthetical groups that normalisation strips.
        val isVariant: Boolean = filename.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .any { it in VARIANT_WORDS }
    }

    /** 0 = USA/World, 1 = Europe, 2 = any other single region, 3 = none/multiple. */
    private fun regionRank(filename: String): Int {
        val lower = filename.lowercase()
        val hasUsa = lower.contains("usa") || lower.contains("world")
        val hasEurope = lower.contains("europe")
        if (hasUsa) return 0
        if (hasEurope) return 1
        val regions = REGION_WORDS.count { lower.contains(it) }
        return if (regions == 1) 2 else 3
    }

    private fun stepMatches(step: Int, query: List<String>, art: List<String>): Boolean {
        return when (step) {
            1 -> query == art
            2 -> query.size >= 2 && art.size > query.size &&
                art.subList(0, query.size) == query
            3 -> {
                // Bare prefix: every word but the last matches
                // exactly, and the last query word is a proper
                // prefix (at least 2 letters) of the art's word at
                // that position — the art continues the SAME word.
                query.size >= 1 && art.size >= query.size &&
                    query.last().length >= 2 &&
                    art.subList(0, query.size - 1) ==
                    query.subList(0, query.size - 1) &&
                    art[query.size - 1].startsWith(query.last()) &&
                    art[query.size - 1] != query.last()
            }
            4 -> art.size < query.size &&
                query.subList(0, art.size) == art
            5 -> {
                // Contiguous phrase, both >= 2 words: the shorter
                // appears as one contiguous run inside the longer.
                if (query.size < 2 || art.size < 2) {
                    false
                } else if (query.size <= art.size) {
                    art.windowed(query.size).any { it == query }
                } else {
                    query.windowed(art.size).any { it == art }
                }
            }
            else -> false
        }
    }

    private fun rank(candidates: List<ArtEntry>): ArtEntry {
        return candidates.sortedWith(
            compareBy<ArtEntry> { if (it.isVariant) 1 else 0 }
                .thenBy { it.words.joinToString(" ").length }
                .thenBy { it.regionRank }
                .thenBy { it.parenGroups }
                .thenBy { it.filename.length },
        ).first()
    }
}
