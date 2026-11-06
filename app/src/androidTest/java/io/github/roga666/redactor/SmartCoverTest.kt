package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartCoverTest {
    @Test fun tappingAllowedWordCoversOnlyThatWord() {
        val bitmap = Bitmap.createBitmap(350, 90, Bitmap.Config.ARGB_8888)
        try {
            val original = PageData(bitmap, listOf(
                WordRegion("Approved", .99f, Rect(10, 15, 110, 50)),
                WordRegion("Secret", .99f, Rect(150, 15, 280, 50))
            ))
            val updated = original.coverAt(listOf(PointF(50f, 35f)))
            assertEquals(setOf(0), updated.forcedCoveredWordIndices)
            assertTrue(updated.manualBoxes.isEmpty())
            assertEquals(setOf(0, 1), updated.autoWordIndices(Whitelist.parse("Approved"), emptyList()))
        } finally { bitmap.recycle() }
    }

    @Test fun crossingWordCoversWholeRegionEvenWhenStrokeEndpointsOutside() {
        val bitmap = Bitmap.createBitmap(360, 90, Bitmap.Config.ARGB_8888)
        try {
            val page = PageData(bitmap, listOf(WordRegion("Approved", .99f, Rect(100, 20, 230, 60))))
            val updated = page.coverAt(listOf(PointF(20f, 40f), PointF(310f, 40f)))
            assertEquals(setOf(0), updated.forcedCoveredWordIndices)
        } finally { bitmap.recycle() }
    }

    @Test fun erasingNewCoverRevealsJustSelectedWord() {
        val bitmap = Bitmap.createBitmap(350, 90, Bitmap.Config.ARGB_8888)
        try {
            val page = PageData(bitmap, listOf(
                WordRegion("A", .99f, Rect(30, 15, 80, 50)),
                WordRegion("B", .99f, Rect(180, 15, 240, 50))
            ))
            val covered = page.coverAt(listOf(PointF(50f, 30f), PointF(220f, 30f)))
            assertEquals(setOf(0, 1), covered.forcedCoveredWordIndices)
            val erased = covered.eraseAt(listOf(PointF(50f, 30f)), emptySet(), emptyList())
            assertEquals(setOf(1), erased.forcedCoveredWordIndices)
            assertEquals(setOf(0), erased.revealedWordIndices)
        } finally { bitmap.recycle() }
    }

    @Test fun manualMaskFallbackForOcrMisses() {
        val bitmap = Bitmap.createBitmap(300, 90, Bitmap.Config.ARGB_8888)
        try {
            val page = PageData(bitmap, emptyList())
            assertEquals(page, page.coverAt(listOf(PointF(25f, 35f))))
            assertEquals(1, page.coverAt(listOf(PointF(25f, 35f), PointF(150f, 35f))).manualBoxes.size)
        } finally { bitmap.recycle() }
    }
}
