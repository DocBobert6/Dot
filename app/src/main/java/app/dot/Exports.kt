package app.dot

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

// Dot — exports to the public Downloads folder.
//
// Two exports, both local only, no network:
//   * Benchmark captures: Downloads/benchmarks/dot-<sha>-<ms>.json
//   * Picture-problem reports: Downloads/dot-reports/<sha>-<ms>/
//     frame-1x.png (the 160x144 pre-upscale frame), frame-seen.png
//     (nearest-neighbour at the portrait display size 1080x972, the
//     picture as the player shows it in slice 1), and report.json
//     (chain label, game SHA and title, timestamp).
//
// MediaStore (API 29+) needs no permission for files the app creates.
// Below API 29 the fallback is the app's own external files dir.
// SPDX-License-Identifier: GPL-3.0-or-later

object Exports {

    fun exportBenchmark(context: Context, capture: Benchmark.Capture): Boolean {
        val name = "dot-${capture.gameSha.take(12)}-${capture.startedMs}.json"
        return writeDownload(
            context,
            relativePath = "Download/benchmarks",
            fileName = name,
            mimeType = "application/json",
            bytes = Benchmark.toJson(capture).toByteArray(Charsets.UTF_8),
        )
    }

    fun exportPictureProblem(
        context: Context,
        entry: LibraryEntry,
        session: PlayerSession,
    ): Boolean {
        val ms = System.currentTimeMillis()
        val prefix = "${entry.sha256.take(12)}-$ms"
        val frame1x = session.latestFrameCopy()
        val seen = Bitmap.createScaledBitmap(frame1x, 1080, 972, false)
        val meta = "{\"game_sha\":\"${entry.sha256}\"," +
            "\"title\":\"${entry.title.replace("\"", "")}\"," +
            "\"chain\":\"bitmap-1x\"," +
            "\"timestamp_ms\":$ms}"
        val ok1 = writeDownload(
            context,
            relativePath = "Download/dot-reports",
            fileName = "$prefix-frame-1x.png",
            mimeType = "image/png",
            bytes = pngBytes(frame1x),
        )
        val ok2 = writeDownload(
            context,
            relativePath = "Download/dot-reports",
            fileName = "$prefix-frame-seen.png",
            mimeType = "image/png",
            bytes = pngBytes(seen),
        )
        val ok3 = writeDownload(
            context,
            relativePath = "Download/dot-reports",
            fileName = "$prefix-report.json",
            mimeType = "application/json",
            bytes = meta.toByteArray(Charsets.UTF_8),
        )
        return ok1 && ok2 && ok3
    }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun writeDownload(
        context: Context,
        relativePath: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                } ?: return false
                true
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    relativePath.removePrefix("Download/"),
                )
                dir.mkdirs()
                Store.atomicWrite(File(dir, fileName), bytes)
            }
        } catch (e: Exception) {
            false
        }
    }
}
