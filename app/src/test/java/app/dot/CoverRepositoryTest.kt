package app.dot

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover repository tests (SHELL-SPEC §8). Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverRepositoryTest {

    private fun tempDir(): File {
        return File.createTempFile("dot-cover-repo-", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    private fun tinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun repo(dir: File): CoverRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("dot-cover-test", Context.MODE_PRIVATE)
        return CoverRepository(context, dir, prefs, CoverBitmaps::decode)
    }

    private fun entry(sha: String, title: String, source: String) =
        LibraryEntry(sha, title, source, false, 0L)

    @Test
    fun classificationPrefersCustomCoverOverFetched() {
        val dir = tempDir()
        val repository = repo(dir)
        val sha = "a".repeat(64)
        val fetched = repository.coverFile(sha)
        fetched.parentFile?.mkdirs()
        fetched.writeBytes(tinyPng())
        val custom = repository.customCoverFile(sha)
        custom.parentFile?.mkdirs()
        custom.writeBytes(tinyPng())
        repository.setGames(listOf(entry(sha, "Tetris", "Tetris (World)")))
        Thread.sleep(500)
        val state = repository.states[sha]
        assertTrue(state is CoverRepository.CoverState.Ready)
        assertTrue((state as CoverRepository.CoverState.Ready).isCustom)
    }

    @Test
    fun setCustomCoverWritesAndRemoves() {
        val dir = tempDir()
        val repository = repo(dir)
        val sha = "b".repeat(64)
        assertTrue(repository.setCustomCover(sha, tinyPng()))
        assertTrue(repository.customCoverFile(sha).exists())
        val state = repository.states[sha]
        assertTrue(state is CoverRepository.CoverState.Ready)
        assertTrue((state as CoverRepository.CoverState.Ready).isCustom)
        repository.removeCustomCover(sha)
        Thread.sleep(500)
        assertTrue(!repository.customCoverFile(sha).exists())
        assertTrue(repository.states[sha] is CoverRepository.CoverState.Missing)
    }

    @Test
    fun setCustomCoverRejectsUndecodableBytes() {
        val dir = tempDir()
        val repository = repo(dir)
        val sha = "c".repeat(64)
        assertTrue(!repository.setCustomCover(sha, "not an image".toByteArray()))
        assertTrue(!repository.customCoverFile(sha).exists())
    }

    @Test
    fun startupScanDeletesAnUndecodableCover() {
        val dir = tempDir()
        val repository = repo(dir)
        val sha = "d".repeat(64)
        val cover = repository.coverFile(sha)
        cover.parentFile?.mkdirs()
        cover.writeBytes("not an image".toByteArray())
        repository.start()
        Thread.sleep(500)
        repository.stop()
        assertTrue(!cover.exists())
    }

    @Test
    fun statusLineNamesMissingGames() {
        val dir = tempDir()
        val repository = repo(dir)
        repository.setGames(listOf(entry("e".repeat(64), "Tetris", "Tetris (World)")))
        Thread.sleep(500)
        assertEquals(CoverRepository.CoverState.Missing, repository.states["e".repeat(64)])
        assertTrue(repository.statusLine.contains("still missing: Tetris"))
    }
}
