package io.github.roga666.redactor

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowConfidenceAllowedTest {
    @Test fun explicitWhitelistEntriesAreNotBlackenedOnlyDueToWeakConfidence() {
        val words = listOf(
            WordRegion("EP", 0.44f, Rect(10, 10, 36, 26), 0),
            WordRegion("E", 0.37f, Rect(40, 10, 51, 26), 0),
            WordRegion("N", 0.90f, Rect(60, 10, 70, 26), 0)
        )
        assertEquals(setOf(0, 1), Whitelist.allowedIndices(words, Whitelist.parse("EP, E"), emptyList()))
    }

    @Test fun lowConfidenceTimeAndPeriodMustBothMatchConfiguredRange() {
        val words = listOf(
            WordRegion("09:15", 0.40f, Rect(10, 10, 48, 26), 0),
            WordRegion("AM", 0.30f, Rect(50, 10, 67, 26), 0),
            WordRegion("09:15", 0.90f, Rect(10, 30, 48, 46), 1),
            WordRegion("PM", 0.80f, Rect(50, 30, 67, 46), 1)
        )
        assertEquals(setOf(0, 1), Whitelist.allowedIndices(words, emptySet(), listOf(TimeRangeRule())))
    }
}
