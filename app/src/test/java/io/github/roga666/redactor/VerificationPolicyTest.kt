package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class VerificationPolicyTest {
    private val src = ExpectedWord("EP", TextBounds(100, 20, 132, 42))

    @Test fun `permitted abbreviations are accepted at their original position`() {
        assertTrue(VerificationPolicy.isExpected(
            ExpectedWord("EP", TextBounds(101, 19, 133, 43)), listOf(src), emptyList()))
        assertTrue(VerificationPolicy.isExpected(
            ExpectedWord("E", TextBounds(101, 19, 110, 43)),
            listOf(ExpectedWord("E", TextBounds(100, 20, 112, 42))), emptyList()))
    }

    @Test fun `a matching word elsewhere is not approved`() {
        assertFalse(VerificationPolicy.isExpected(
            ExpectedWord("EP", TextBounds(400, 20, 432, 42)), listOf(src), emptyList()))
    }

    @Test fun `an unexpected fragment stays blocked`() {
        assertFalse(VerificationPolicy.isExpected(
            ExpectedWord("N", TextBounds(100, 20, 112, 42)),
            listOf(ExpectedWord("NP", TextBounds(100, 20, 132, 42))), emptyList()))
    }

    @Test fun `redaction crossing the detected word must not be bypassed`() {
        assertFalse(VerificationPolicy.isExpected(
            ExpectedWord("EP", TextBounds(100, 20, 132, 42)), listOf(src),
            listOf(TextBounds(105, 19, 130, 44))))
    }

    @Test fun `normal OCR segmentation differences are allowed for expected phrases and times`() {
        assertTrue(VerificationPolicy.isExpected(
            ExpectedWord("EP", TextBounds(100, 20, 123, 42)),
            listOf(ExpectedWord("EP E", TextBounds(100, 20, 142, 42))), emptyList()))
        assertTrue(VerificationPolicy.isExpected(
            ExpectedWord("9:15 AM", TextBounds(100, 20, 180, 42)),
            listOf(ExpectedWord("09:15 AM", TextBounds(100, 20, 180, 42))), emptyList()))
    }

    @Test fun `no original matching source token still blocks export`() {
        assertFalse(VerificationPolicy.isExpected(
            ExpectedWord("EP", TextBounds(100, 20, 132, 42)), emptyList(), emptyList()))
    }
}
