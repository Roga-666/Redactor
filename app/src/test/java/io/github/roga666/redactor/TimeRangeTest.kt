package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class TimeRangeTest {
    @Test fun `nine to six by quarter hour yields 37 times inclusive`() {
        val range = TimeRangeRule()
        assertEquals(37, range.count)
        assertTrue(range.matches("09:00 AM"))
        assertTrue(range.matches("9:15 am"))
        assertTrue(range.matches("6:00 PM"))
        assertTrue(range.matches("18:00"))
        assertFalse(range.matches("9:07 AM"))
        assertFalse(range.matches("6:15 PM"))
        assertFalse(range.matches("08:45 AM"))
        assertFalse(range.matches("9:00 PM"))
    }

}
