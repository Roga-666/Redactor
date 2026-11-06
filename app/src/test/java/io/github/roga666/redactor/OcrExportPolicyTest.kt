package io.github.roga666.redactor

import org.junit.Assert.*
import org.junit.Test

class OcrExportPolicyTest {
    @Test fun cleanFileExportsNormally() {
        assertTrue(OcrExportPolicy.canExport(0, allowWarnings = false))
        assertTrue(OcrExportPolicy.canExport(0, allowWarnings = true))
    }

    @Test fun warningRegionsBlockExportByDefault() {
        assertFalse(OcrExportPolicy.canExport(1, allowWarnings = false))
        assertFalse(OcrExportPolicy.canExport(6, allowWarnings = false))
    }

    @Test fun explicitOptInPermitsExportWithWarnings() {
        assertTrue(OcrExportPolicy.canExport(1, allowWarnings = true))
        assertTrue(OcrExportPolicy.canExport(6, allowWarnings = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRegionCountsAreInvalid() {
        OcrExportPolicy.canExport(-1, allowWarnings = true)
    }
}
