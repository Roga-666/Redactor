package io.github.roga666.redactor

/**
 * Export verification must always be performed. An explicit, persistent opt-in
 * permits saving a FLATTENED, manually reviewed export even with unresolved OCR
 * findings. The override never authorizes exposing underlying source layers.
 */
object OcrExportPolicy {
    fun canExport(unexpectedRegions: Int, allowWarnings: Boolean): Boolean {
        require(unexpectedRegions >= 0) { "Unexpected OCR region count cannot be negative" }
        return unexpectedRegions == 0 || allowWarnings
    }
}
