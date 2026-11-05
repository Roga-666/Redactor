package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureExporterTest {
    @Test fun redactedPixelsAreActuallyBlack() {
        val source = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val page = PageData(source, listOf(WordRegion("SECRET", 0.98f, Rect(20, 10, 100, 30))))
        val output = SecureExporter.sanitize(page, Whitelist.parse("PUBLIC"))
        assertEquals(Color.BLACK, output.getPixel(50, 20))
        assertEquals(Color.RED, output.getPixel(1, 1))
        assertEquals(Color.RED, source.getPixel(50, 20))
        output.recycle()
        source.recycle()
    }
}
