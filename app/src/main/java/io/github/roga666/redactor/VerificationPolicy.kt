package io.github.roga666.redactor

import kotlin.math.max
import kotlin.math.min

/** Plain integer coordinates allow the export matcher to be tested without Android graphics. */
data class TextBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(0)
    val height get() = (bottom - top).coerceAtLeast(0)
    val area get() = width.toLong() * height

    fun overlapFractionOf(other: TextBounds): Double {
        if (other.area == 0L) return 0.0
        val w = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0)
        val h = (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0)
        return w.toDouble() * h / other.area.toDouble()
    }

    /** A second OCR scan may move a word a few pixels without changing its location. */
    fun correspondsTo(observed: TextBounds): Boolean {
        if (area == 0L || observed.area == 0L) return false
        val dx = max(4, width / 3)
        val dy = max(4, height / 3)
        val centerX = (observed.left.toLong() + observed.right) / 2.0
        val centerY = (observed.top.toLong() + observed.bottom) / 2.0
        return centerX >= left - dx && centerX <= right + dx &&
            centerY >= top - dy && centerY <= bottom + dy &&
            overlapFractionOf(observed) >= 0.55
    }
}

data class ExpectedWord(val text: String, val bounds: TextBounds)

/**
 * A second OCR result is expected only if both its text AND its position match an
 * original word deliberately left visible. Re-OCR confidence is not a permission
 * decision; the first-pass whitelist/manual overrides decide what may be visible.
 * Whitelisted words found in wholly different locations must still block export.
 */
object VerificationPolicy {
    fun isExpected(
        observed: ExpectedWord,
        visibleSourceWords: List<ExpectedWord>,
        redactionMasks: List<TextBounds>
    ): Boolean {
        if (redactionMasks.any { it.overlapFractionOf(observed.bounds) > 0.20 }) return false
        val text = Whitelist.normalize(observed.text)
        if (text.isEmpty()) return false
        return visibleSourceWords.any { source ->
            source.bounds.correspondsTo(observed.bounds) && equivalent(source.text, text)
        }
    }

    private fun equivalent(originalText: String, observedNormalized: String): Boolean {
        val source = Whitelist.normalize(originalText)
        if (source == observedNormalized) return true
        // An OCR element sometimes covers a whole approved phrase in one pass and
        // individual words in the next pass. Only complete component words qualify.
        if (source.split(Regex("\\s+")).any { it == observedNormalized }) return true
        val a = TimeRangeRule.parseClockTime(originalText)
        val b = TimeRangeRule.parseClockTime(observedNormalized)
        return a != null && a == b
    }
}
