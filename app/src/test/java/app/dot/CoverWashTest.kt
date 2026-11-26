package app.dot

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — blurred-wash tests (§7.8 item 6): the tray-cart wash is a
// real blur, not a dimmed copy. Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverWashTest {

    @Test
    fun solidColourWashIsUnchanged() {
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        val wash = CoverBitmaps.blurredWash(src)
        assertEquals(32, wash.width)
        assertEquals(32, wash.height)
        assertEquals(Color.RED, wash.getPixel(16, 16))
    }

    @Test
    fun washBlursAHardEdge() {
        // Left half black, right half white: a mere copy would keep
        // a hard edge at the middle; a blur puts grey there.
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                src.setPixel(x, y, if (x < 32) Color.BLACK else Color.WHITE)
            }
        }
        val wash = CoverBitmaps.blurredWash(src)
        val middle = wash.getPixel(16, 16)
        val grey = Color.red(middle)
        assertTrue("expected a blended grey at the edge, got $grey", grey in 1..254)
        assertEquals(Color.BLACK, wash.getPixel(0, 16))
        assertEquals(Color.WHITE, wash.getPixel(31, 16))
    }

    @Test
    fun washNeverModifiesTheSource() {
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.BLUE)
        CoverBitmaps.blurredWash(src)
        assertEquals(Color.BLUE, src.getPixel(0, 0))
        assertEquals(Color.BLUE, src.getPixel(63, 63))
    }
}
