package app.dot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — art-index parsing + meta tests (SHELL-SPEC §8.1). Pure.
class ArtIndexTest {

    private val topTreeJson = """
        {"sha":"abc","truncated":false,"tree":[
        {"path":"Named_Boxarts","mode":"040000","type":"tree","sha":"deadbeef","url":"https://example/x"},
        {"path":"Named_Snaps","mode":"040000","type":"tree","sha":"000000","url":"https://example/y"},
        {"path":"README.md","mode":"100644","type":"blob","sha":"111111","url":"https://example/z"}]}
    """.trimIndent()

    private val subtreeJson = """
        {"sha":"deadbeef","truncated":false,"tree":[
        {"path":"Named_Boxarts/Tetris (World).png","mode":"100644","type":"blob","sha":"aa","url":"https://example/a"},
        {"path":"Tetris (Japan).png","mode":"100644","type":"blob","sha":"bb","url":"https://example/b"},
        {"path":"Named_Boxarts","mode":"040000","type":"tree","sha":"cc","url":"https://example/c"},
        {"path":"notes.txt","mode":"100644","type":"blob","sha":"dd","url":"https://example/d"}]}
    """.trimIndent()

    @Test
    fun parseTopTreeShaFindsNamedBoxarts() {
        assertEquals("deadbeef", ArtIndex.parseTopTreeSha(topTreeJson))
        assertNull(ArtIndex.parseTopTreeSha("{\"tree\":[]}"))
    }

    @Test
    fun parseTreeResponseStripsThePrefix() {
        assertEquals(
            listOf("Tetris (World).png", "Tetris (Japan).png"),
            ArtIndex.parseTreeResponse(subtreeJson),
        )
    }

    @Test
    fun parseTreeResponseRejectsTruncatedAndEmpty() {
        assertNull(
            ArtIndex.parseTreeResponse(
                "{\"truncated\":true,\"tree\":[{\"path\":\"A.png\",\"type\":\"blob\"}]}",
            ),
        )
        assertNull(ArtIndex.parseTreeResponse("{\"truncated\":false,\"tree\":[]}"))
    }

    @Test
    fun parseSnapshotReadsHeaderAndEntries() {
        val text = "# Dot art index snapshot 2026-11-26 Nintendo_-_Game_Boy deadbeef\n" +
            "Tetris (World).png\nTetris (Japan).png\n"
        val index = ArtIndex.parseSnapshot(text)
        assertTrue(index != null)
        assertEquals("2026-11-26", index!!.snapshotDate)
        assertEquals("deadbeef", index.subtreeSha)
        assertEquals(2, index.entries.size)
    }

    @Test
    fun parseSnapshotRejectsNoEntries() {
        assertNull(ArtIndex.parseSnapshot("# only a header\n"))
    }

    @Test
    fun artUrlEncodesSpaces() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy/master/Named_Boxarts/Tetris%20(World).png",
            ArtIndex.artUrl("Tetris (World).png"),
        )
    }

    @Test
    fun metaRoundTrips() {
        val dir = File.createTempFile("dot-art-", "").also {
            it.delete()
            it.mkdirs()
        }
        assertTrue(ArtIndex.writeMeta(dir, "deadbeef", 123456789L))
        assertEquals(
            "deadbeef" to 123456789L,
            ArtIndex.readMeta(dir),
        )
    }

    @Test
    fun cachedIndexLoadsAsRefreshedCopy() {
        val dir = File.createTempFile("dot-art-", "").also {
            it.delete()
            it.mkdirs()
        }
        assertTrue(
            ArtIndex.writeCached(
                dir,
                listOf("Tetris (World).png"),
                "deadbeef",
                "2026-11-26",
            ),
        )
        assertTrue(ArtIndex.writeMeta(dir, "deadbeef", 123456789L))
        val index = ArtIndex.loadCached(dir)
        assertTrue(index != null)
        assertEquals("refreshed copy", index!!.source)
        assertEquals("deadbeef", index.subtreeSha)
        assertEquals(listOf("Tetris (World).png"), index.entries)
    }
}
