package app.dot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Dot — store tests: library index round-trip (including escaped
// fields), SHA-256 against a known vector, and atomic writes.
// SPDX-License-Identifier: GPL-3.0-or-later

class StoreTest {

    @Test
    fun sha256OfEmptyMatchesKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Store.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun libraryRoundTripPreservesEscapedFields() {
        val entries = listOf(
            LibraryEntry(
                sha256 = "aa".repeat(32),
                title = "Game\tWith Tab \\ and\nnewline",
                sourceName = "weird\\name",
                dualMode = true,
                addedMs = 123456789L,
            ),
            LibraryEntry(
                sha256 = "bb".repeat(32),
                title = "Plain Game",
                sourceName = "plain",
                dualMode = false,
                addedMs = 987654321L,
            ),
        )
        val decoded = Store.decodeLibrary(Store.encodeLibrary(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun decodeSkipsBadLines() {
        val text = "only-one-field\n" +
            "aa\tb\tc\td\tnot-a-number\n" +
            "cc\ttitle\tstem\t0\t42\n"
        val decoded = Store.decodeLibrary(text)
        assertEquals(1, decoded.size)
        assertEquals("cc", decoded[0].sha256)
        assertEquals(42L, decoded[0].addedMs)
    }

    @Test
    fun atomicWriteAndReadBack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dot-store-test")
        dir.deleteRecursively()
        val file = File(dir, "sub/library.txt")
        assertTrue(
            Store.atomicWrite(file, "hello".toByteArray(Charsets.UTF_8)),
        )
        assertEquals("hello", String(Store.readBytes(file)!!, Charsets.UTF_8))
        assertFalse(File(dir, "sub/library.txt.tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun readBytesOfMissingFileIsNull() {
        assertEquals(
            null,
            Store.readBytes(File("/nonexistent-dir-xyz/nope.txt")),
        )
    }
}
