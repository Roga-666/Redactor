package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class WhitelistEditorTest {
    @Test fun appendsToExistingProfileWithoutOverwriting() {
        val result = WhitelistEditor.add("EP, PM,\nNP CL", listOf("C", "EP E"))
        assertEquals(listOf("C", "EP E"), result.added)
        assertTrue(result.text.contains("NP CL"))
        assertTrue(Whitelist.parse(result.text).containsAll(setOf("ep", "ep e", "c")))
    }

    @Test fun doesNotDuplicateExistingOrRepeatedSelection() {
        val before = "EP, NP CL"
        val result = WhitelistEditor.add(before, listOf("EP", "ep", "NP CL"))
        assertEquals(before, result.text)
        assertTrue(result.added.isEmpty())
    }

    @Test fun sanitizesCharactersThatSeparateWhitelistEntries() {
        val result = WhitelistEditor.add("", listOf("EP; CL", "NP\nCL", ""))
        assertEquals(listOf("EP CL", "NP CL"), result.added)
        assertEquals(setOf("ep cl", "np cl"), Whitelist.parse(result.text))
    }

    @Test fun multipleSelectedTokensAppendOnceEach() {
        val result = WhitelistEditor.add("", listOf("E", "EP", "E", "C"))
        assertEquals(listOf("E", "EP", "C"), result.added)
    }
}
