package io.github.roga666.redactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetTransferTest {
    @Test fun allProfilesAndTimeRangesRoundTrip() {
        val originals = listOf(
            RedactionPreset(id = "a", name = "LensCrafters", allowedText = "EP, E, NP CL",
                timeRanges = listOf(TimeRangeRule(540, 1080, 15))),
            RedactionPreset(id = "b", name = "Other", allowedText = "Status, Confirmed")
        )
        val export = PresetTransfer.encode(originals)
        val imported = PresetTransfer.merge(export, emptyList())
        assertEquals(originals, imported.presets)
        assertEquals(2, imported.imported)
    }

    @Test fun importingIdenticalBackupAgainDoesNotDuplicate() {
        val originals = listOf(RedactionPreset(id = "x", name = "Sample", allowedText = "AM, PM"))
        val merged = PresetTransfer.merge(PresetTransfer.encode(originals), originals)
        assertEquals(1, merged.presets.size)
        assertEquals(0, merged.imported)
        assertEquals(1, merged.duplicatesSkipped)
    }

    @Test fun changedProfileDoesNotSilentlyReplaceExisting() {
        val old = RedactionPreset(id = "x", name = "Work", allowedText = "E")
        val changed = old.copy(allowedText = "EP")
        val merged = PresetTransfer.merge(PresetTransfer.encode(listOf(changed)), listOf(old))
        assertEquals(old, merged.presets[0])
        assertNotEquals(old.id, merged.presets[1].id)
        assertEquals(1, merged.imported)
    }

    @Test fun rejectsUnrecognizedBackup() {
        assertThrows(IllegalArgumentException::class.java) {
            PresetTransfer.merge("{\"schema\":\"unknown\",\"version\":1,\"presets\":[]}", emptyList())
        }
    }
}
