package app.dot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art matcher tests (SHELL-SPEC §8.2). Pure logic.
class CoverMatcherTest {

    private fun stem(text: String) = CoverMatcher.MatchInput(text, false)
    private fun header(text: String) = CoverMatcher.MatchInput(text, true)

    @Test
    fun normaliseStripsTagsAndRegions() {
        assertEquals(
            listOf("super", "mario", "land", "2", "6", "golden", "coins"),
            CoverMatcher.normalise(
                "Super Mario Land 2 - 6 Golden Coins (USA, Europe)",
            ),
        )
        assertEquals(
            listOf("legend", "of", "zelda", "link", "s", "awakening"),
            CoverMatcher.normalise("The Legend of Zelda - Link's Awakening (USA)"),
        )
        assertEquals(listOf("tetris"), CoverMatcher.normalise("Tetris (World)"))
        assertEquals(
            listOf("legend", "of", "zelda"),
            CoverMatcher.normalise("Legend of Zelda, The"),
        )
    }

    @Test
    fun dollarReadsAsS() {
        assertEquals(listOf("asb"), CoverMatcher.normalise("A\$B"))
    }

    @Test
    fun tagWordsCoverRegionsEditionsAndCodes() {
        assertTrue(CoverMatcher.isTagWord("japan"))
        assertTrue(CoverMatcher.isTagWord("rev"))
        assertTrue(CoverMatcher.isTagWord("enus"))
        assertTrue(CoverMatcher.isTagWord("enja"))
        assertFalse(CoverMatcher.isTagWord("xyz"))
        assertFalse(CoverMatcher.isTagWord("mario"))
        assertFalse(CoverMatcher.isTagWord("enusx"))
    }

    @Test
    fun exactTitleMatches() {
        val art = listOf("Tetris (World).png", "Tetris (Japan).png")
        assertEquals(
            "Tetris (World).png",
            CoverMatcher.match(listOf(stem("Tetris")), art),
        )
    }

    @Test
    fun titlePrefixNeedsARealTitle() {
        val art = listOf(
            "Pokemon Red Version (USA).png",
            "Pokemon Blue Version (USA).png",
        )
        assertEquals(
            "Pokemon Red Version (USA).png",
            CoverMatcher.match(listOf(stem("Pokemon Red")), art),
        )
        // A bare series name never prefix-matches a different game.
        assertNull(CoverMatcher.match(listOf(stem("Pokemon")), art))
    }

    @Test
    fun barePrefixContinuesTheSameWord() {
        val art = listOf("Pokemon Emerald (USA).png")
        assertEquals(
            "Pokemon Emerald (USA).png",
            CoverMatcher.match(listOf(header("POKEMON EMER")), art),
        )
    }

    @Test
    fun reversePrefixMatches() {
        val art = listOf("Super Mario Land (USA).png")
        assertEquals(
            "Super Mario Land (USA).png",
            CoverMatcher.match(
                listOf(stem("Super Mario Land 2 - 6 Golden Coins")),
                art,
            ),
        )
    }

    @Test
    fun contiguousPhraseNeedsTwoWords() {
        val art = listOf("The Legend of Zelda - Link's Awakening (USA).png")
        assertEquals(
            "The Legend of Zelda - Link's Awakening (USA).png",
            CoverMatcher.match(listOf(stem("Link's Awakening")), art),
        )
        // A bare "zelda" header title never matches the wrong game.
        assertNull(CoverMatcher.match(listOf(header("ZELDA")), art))
    }

    @Test
    fun nonVariantRanksFirst() {
        val art = listOf(
            "Tetris (World) (Beta).png",
            "Tetris (World).png",
        )
        assertEquals(
            "Tetris (World).png",
            CoverMatcher.match(listOf(stem("Tetris")), art),
        )
    }

    @Test
    fun usaRanksBeforeJapan() {
        val art = listOf("Tetris (Japan).png", "Tetris (USA).png")
        assertEquals(
            "Tetris (USA).png",
            CoverMatcher.match(listOf(stem("Tetris")), art),
        )
    }

    @Test
    fun denylistFiresOnlyOnTheHeaderPath() {
        val art = listOf("Castlevania (USA).png")
        val denylist = setOf("castlevania")
        assertNull(
            CoverMatcher.match(listOf(header("CASTLEVANIA")), art, denylist),
        )
        assertEquals(
            "Castlevania (USA).png",
            CoverMatcher.match(listOf(stem("Castlevania")), art, denylist),
        )
    }

    @Test
    fun stemWinsOverHeader() {
        val art = listOf(
            "Super Mario Land (USA).png",
            "Super Mario Bros. (USA).png",
        )
        assertEquals(
            "Super Mario Land (USA).png",
            CoverMatcher.match(
                listOf(stem("Super Mario Land"), header("SUPER MARIO")),
                art,
            ),
        )
    }

    @Test
    fun realIndexNamesMatch() {
        val art = listOf(
            "Dr. Mario (World).png",
            "Dr. Mario (World) (Rev 1).png",
            "Mario no Picross (Japan) (SGB Enhanced).png",
        )
        assertEquals(
            "Dr. Mario (World).png",
            CoverMatcher.match(listOf(stem("Dr. Mario")), art),
        )
    }
}
