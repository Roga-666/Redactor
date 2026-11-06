package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class UndoHistoryTest {
    @Test fun fiveEditsCanBeReversedInOrder() {
        var h = UndoHistory<Int>()
        for (i in 0..4) h = h.record(0, i)
        assertEquals(5, h.count)
        for (i in 4 downTo 0) {
            val taken = h.takeLastFor(0)!!
            assertEquals(i, taken.first)
            h = taken.second
        }
        assertFalse(h.canUndo(0))
    }

    @Test fun sixthEditDropsOldestSnapshot() {
        var h = UndoHistory<Int>()
        for (i in 0..5) h = h.record(0, i)
        assertEquals(5, h.count)
        val collected = mutableListOf<Int>()
        while (h.canUndo(0)) {
            val snapshot = h.takeLastFor(0)!!
            collected += snapshot.first
            h = snapshot.second
        }
        assertEquals(listOf(5, 4, 3, 2, 1), collected)
    }

    @Test fun pageUndoLeavesOtherPagesAvailable() {
        val h = UndoHistory<String>().record(0, "first")
            .record(1, "second").record(0, "third")
        val (restored, remaining) = h.takeLastFor(1)!!
        assertEquals("second", restored)
        assertTrue(remaining.canUndo(0))
        assertFalse(remaining.canUndo(1))
    }
}
