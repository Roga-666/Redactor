package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhitelistGestureTest {
    private fun page(): PageData = PageData(
        bitmap = Bitmap.createBitmap(600, 180, Bitmap.Config.ARGB_8888),
        words = listOf(
            WordRegion("EP", 0.22f, Rect(30, 30, 80, 64), 0),
            WordRegion("NP CL", 0.54f, Rect(125, 30, 230, 64), 0),
            WordRegion("secret", 0.89f, Rect(280, 30, 380, 64), 0)
        )
    )

    @Test fun tappingOneMaskedWordAddsExactlyThatOriginalOcrText() {
        val p = page()
        val hit = p.whitelistAt(listOf(PointF(55f, 45f)), emptySet(), emptyList())
        assertEquals(listOf("EP"), hit.words)
        assertEquals(setOf(0), hit.touchedIndices)
        assertTrue(hit.updatedPage.manualBoxes.isEmpty())
        val updated = WhitelistEditor.add("", hit.words)
        assertEquals(setOf(1, 2), hit.updatedPage.autoWordIndices(Whitelist.parse(updated.text), emptyList()))
        p.bitmap.recycle()
    }

    @Test fun scribblingAcrossTwoMaskedWordsAddsBothAndNotThird() {
        val p = page()
        val hit = p.whitelistAt(listOf(PointF(42f, 45f), PointF(170f, 45f)),
            emptySet(), emptyList())
        assertEquals(listOf("EP", "NP CL"), hit.words)
        assertEquals(setOf(0, 1), hit.touchedIndices)
        p.bitmap.recycle()
    }

    @Test fun noOcrWordMeansNothingIsWhitelisted() {
        val p = page()
        val hit = p.whitelistAt(listOf(PointF(12f, 100f)), emptySet(), emptyList())
        assertTrue(hit.words.isEmpty())
        assertEquals(p, hit.updatedPage)
        p.bitmap.recycle()
    }

    @Test fun manualMasksOverOtherWordsAreNotSilentlyRemoved() {
        val p = page().copy(
            manualBoxes = listOf(RectF(20f, 18f, 410f, 75f))
        )
        val hit = p.whitelistAt(listOf(PointF(55f, 45f)), emptySet(), emptyList())
        assertEquals(listOf("EP"), hit.words)
        assertEquals(0, hit.removedManualMasks)
        assertEquals(1, hit.updatedPage.manualBoxes.size)
        p.bitmap.recycle()
    }

    @Test fun explicitlyForcedCoverCanBeReversedByWhitelist() {
        val p = page().copy(forcedCoveredWordIndices = setOf(0))
        val hit = p.whitelistAt(listOf(PointF(55f, 45f)), Whitelist.parse("EP"), emptyList())
        assertEquals(listOf("EP"), hit.words)
        assertTrue(hit.updatedPage.forcedCoveredWordIndices.isEmpty())
        p.bitmap.recycle()
    }

    @Test fun tappingAWordWithUnavailableOcrConfidenceRevealsThatLocation() {
        val original = page()
        val changed = original.copy(words = original.words.mapIndexed { index, word ->
            if (index == 0) word.copy(confidence = -1f) else word
        })
        val tapped = changed.whitelistAt(listOf(PointF(55f, 45f)), emptySet(), emptyList())
        assertEquals(listOf("EP"), tapped.words)
        assertTrue(0 in tapped.updatedPage.revealedWordIndices)
        // Even if the saved profile can't automatically accept negative-confidence
        // OCR elsewhere, this one deliberately approved source word becomes visible.
        assertFalse(0 in tapped.updatedPage.autoWordIndices(Whitelist.parse("EP"), emptyList()))
        original.bitmap.recycle()
    }

}
