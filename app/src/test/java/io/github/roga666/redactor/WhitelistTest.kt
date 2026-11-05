package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class WhitelistTest {
    @Test fun `case insensitive exact words`() {
        val allowed = Whitelist.parse("Patient, DATE; Approved")
        assertTrue(Whitelist.mayShow("patient:", 0.99f, allowed))
        assertTrue(Whitelist.mayShow("APPROVED", 1f, allowed))
        assertFalse(Whitelist.mayShow("Approval", 0.99f, allowed))
    }

    @Test fun `suspicious confidence remains hidden`() {
        val allowed = Whitelist.parse("safe")
        assertFalse(Whitelist.mayShow("safe", 0.79f, allowed))
        assertFalse(Whitelist.mayShow("safe", Float.NaN, allowed))
        assertTrue(Whitelist.mayShow("safe", 0.80f, allowed))
    }

    @Test fun `empty whitelist never releases words`() {
        assertFalse(Whitelist.mayShow("John", 0.99f, Whitelist.parse("")))
    }

    @Test fun `unicode normalization`() {
        assertTrue(Whitelist.mayShow("ＡＢＣ", 1f, Whitelist.parse("abc")))
    }
}
