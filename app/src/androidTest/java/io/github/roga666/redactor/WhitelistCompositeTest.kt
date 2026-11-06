package io.github.roga666.redactor

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhitelistCompositeTest {
    private fun region(text: String, n: Int) =
        WordRegion(text, 0.23f, Rect(10 + n * 40, 10, 40 + n * 40, 30), 0)

    @Test fun joinedAndSplitApprovedLabelsAreVisible() {
        val whitelist = Whitelist.parse("EP, E, EP E, EP CL, NP CL, NP EEX, C")
        val joined = listOf(region("EP/E", 0), region("EP|CL", 1),
            region("NP-CL", 2), region("NP EEX", 3))
        assertEquals(setOf(0, 1, 2, 3), Whitelist.allowedIndices(joined, whitelist, emptyList()))
        val split = listOf(region("EP", 0), region("E", 1))
        assertEquals(setOf(0, 1), Whitelist.allowedIndices(split, whitelist, emptyList()))
    }

    @Test fun unexpectedSuffixRemainsCovered() {
        val whitelist = Whitelist.parse("EP, EP E, NP, NP CL")
        assertTrue(Whitelist.allowedIndices(listOf(region("EPN", 0)), whitelist, emptyList()).isEmpty())
        assertTrue(Whitelist.allowedIndices(listOf(region("EP N", 0)), whitelist, emptyList()).isEmpty())
    }

    @Test fun generatedTimeDoesNotWhitelistUnrelatedText() {
        val r = listOf(TimeRangeRule())
        assertEquals(setOf(0, 1), Whitelist.allowedIndices(
            listOf(region("09:15", 0), region("AM", 1)), emptySet(), r))
        assertTrue(Whitelist.allowedIndices(
            listOf(region("EP", 0)), emptySet(), r).isEmpty())
    }
}
