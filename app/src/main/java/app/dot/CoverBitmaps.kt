package app.dot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover decode + the tile bitmap cache (SHELL-SPEC §8.5).
//
// Decode: cover bytes/files are decoded downsampled to at most
// 512 px on a side (downsample factor is a power of two). Null when
// the bytes are not a decodable image. Only PNG (8-byte magic) or
// JPEG (2-byte SOI) bytes are accepted, and acceptance requires a
// real decode, never a magic-bytes-only check.
//
// The tile bitmap cache holds at most 8 decoded covers, LRU,
// keyed by absolute path, so a fetched cover and a custom cover
// for the same game never collide; an entry is re-decoded when its
// file's last-modified time changed (a re-import, a re-fetch, a
// new custom cover), so no caller has to remember to invalidate
// anything.
//
// B1 (remembered-null decode) — do not repeat: a decode that
// returns null is never memoised on the file path alone. A null
// result is simply not cached, so a later replacement of the file
// at the same path re-decodes instead of showing nothing.

object CoverBitmaps {

    /** Decoded covers are downsampled to at most this many px. */
    const val MAX_SIDE_PX = 512

    /** At most this many decoded covers are held, LRU. */
    const val MAX_CACHED = 8

    private data class Entry(val bitmap: Bitmap, val lastModified: Long)

    private val cache = object : LinkedHashMap<String, Entry>(
        MAX_CACHED, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Entry>,
        ): Boolean {
            val evict = size > MAX_CACHED
            if (evict) eldest.value.bitmap.recycle()
            return evict
        }
    }

    /** Decodes [bytes] (PNG or JPEG) downsampled to <= 512 px. */
    fun decode(bytes: ByteArray): Bitmap? {
        if (!isPngOrJpeg(bytes)) return null
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            var sample = 1
            while (width / sample > MAX_SIDE_PX ||
                height / sample > MAX_SIDE_PX
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes [file], honouring the 8-entry LRU keyed by path +
     *  last-modified time. Null when undecodable (never cached). */
    @Synchronized
    fun decodeFile(file: File): Bitmap? {
        val path = file.absolutePath
        val lastModified = try {
            file.lastModified()
        } catch (_: Exception) {
            0L
        }
        val cached = cache[path]
        if (cached != null && cached.lastModified == lastModified) {
            return cached.bitmap
        }
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        val bitmap = decode(bytes) ?: return null
        cache[path] = Entry(bitmap, lastModified)
        return bitmap
    }

    /** Drops the cache (a test hook; production never calls it). */
    @Synchronized
    fun clearForTests() {
        for (entry in cache.values) entry.bitmap.recycle()
        cache.clear()
    }

    /**
     * A blurred wash of [bitmap] for the tray-cart background
     * (§7.8 item 6): downscaled to 32x32, then two passes of a
     * 3x3 box blur. The caller draws it cropped and dimmed
     * behind the label; the blur itself is real, not a scrim.
     * The source bitmap is never modified or recycled.
     */
    fun blurredWash(bitmap: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val once = boxBlur(small)
        small.recycle()
        val twice = boxBlur(once)
        once.recycle()
        return twice
    }

    private fun boxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val yy = (y + dy).coerceIn(0, h - 1)
                        val p = pixels[yy * w + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[y * w + x] =
                    (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun isPngOrJpeg(bytes: ByteArray): Boolean {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return true
        }
        return bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }
}
