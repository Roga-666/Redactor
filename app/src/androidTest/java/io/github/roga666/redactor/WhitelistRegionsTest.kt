package io.github.roga666.redactor

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class WhitelistRegionsTest {
    @Test fun onlyMatchingTimeSuffixCanBeVisible() {
        val words = listOf(
            WordRegion("9:15", 0.98f, Rect(0, 0, 35, 15), 0),
            WordRegion("AM", 0.98f, Rect(37, 0, 60, 15), 0),
            WordRegion("9:15", 0.99f, Rect(0, 20, 35, 35), 1),
            WordRegion("PM", 0.99f, Rect(37, 20, 60, 35), 1)
        )
        assertEquals(setOf(0, 1), Whitelist.allowedIndices(words, emptySet(), listOf(TimeRangeRule(540, 600, 15))))
    }

    @Test fun explicitPhraseMustAppearOnOneLine() {
        val words = listOf(
            WordRegion("Eye", 0.98f, Rect(0, 0, 20, 15), 0),
            WordRegion("Exam", 0.98f, Rect(25, 0, 60, 15), 0),
            WordRegion("Eye", 0.98f, Rect(0, 20, 20, 35), 1),
            WordRegion("Private", 0.98f, Rect(25, 20, 60, 35), 1)
        )
        assertEquals(setOf(0, 1), Whitelist.allowedIndices(words, Whitelist.parse("Eye Exam"), emptyList()))
    }
}
