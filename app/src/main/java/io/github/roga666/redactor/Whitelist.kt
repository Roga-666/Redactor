package io.github.roga666.redactor

import java.text.Normalizer
import java.util.Locale

/** All times are local wall-clock minutes after midnight, inclusive at both ends. */
data class TimeRangeRule(val startMinutes: Int = 9 * 60, val endMinutes: Int = 18 * 60, val everyMinutes: Int = 15) {
    init {
        require(startMinutes in 0..1439 && endMinutes in startMinutes..1439)
        require(everyMinutes in 1..120)
    }

    val count: Int get() = (endMinutes - startMinutes) / everyMinutes + 1

    fun matches(raw: String): Boolean {
        val mins = parseClockTime(raw) ?: return false
        return mins in startMinutes..endMinutes && (mins - startMinutes) % everyMinutes == 0
    }

    companion object {
        private val clock = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*(AM|PM)?\\s*$", RegexOption.IGNORE_CASE)

        fun parseClockTime(text: String): Int? {
            val m = clock.matchEntire(text.trim().replace('.', ':')) ?: return null
            val hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: return null
            if (minute !in 0..59) return null
            val period = m.groupValues[3].uppercase(Locale.ROOT)
            return if (period.isNotEmpty()) {
                if (hour !in 1..12) null else (hour % 12 + if (period == "PM") 12 else 0) * 60 + minute
            } else {
                if (hour !in 0..23) null else hour * 60 + minute
            }
        }

        fun format(mins: Int): String {
            val hour = mins / 60
            val display = when (hour % 12) { 0 -> 12; else -> hour % 12 }
            return "%02d:%02d %s".format(Locale.ROOT, display, mins % 60, if (hour < 12) "AM" else "PM")
        }
    }
}

object Whitelist {
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim { !it.isLetterOrDigit() }
        .lowercase(Locale.ROOT)

    /** Comma, semicolon, or newline separates entries. An entry may contain spaces (a phrase). */
    fun parse(input: String): Set<String> = input.split(Regex("[,;\\n\\r]+"))
        .map(::normalize).filter(String::isNotEmpty).toSet()

    fun mayShow(ocrText: String, confidence: Float, allowed: Set<String>): Boolean =
        normalize(ocrText).let { it.isNotEmpty() && confidence.isFinite() && confidence >= 0.80f && it in allowed }

    /**
     * OCR may segment a small label ("EP CL") as one element, two elements,
     * or "EP/CL". Only entire, explicitly allowed tokens/phrases can qualify;
     * substring matches (EP inside EPN) are deliberately forbidden.
     */
    private fun tokens(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)

    /** Return OCR word indices approved by a complete whitelist phrase or time. */
    fun allowedIndices(words: List<WordRegion>, terms: Set<String>, ranges: List<TimeRangeRule>): Set<Int> {
        val result = mutableSetOf<Int>()
        val normalized = words.map { normalize(it.content) }
        val allowedPhrases = terms.map { tokens(it).joinToString(" ") }.filter(String::isNotBlank).toSet()
        fun recognized(i: Int): Boolean = words[i].confidence.isFinite() && words[i].confidence >= 0f

        for (i in words.indices) {
            if (!recognized(i)) continue
            val wordTokens = tokens(words[i].content)
            val canonical = wordTokens.joinToString(" ")
            // OCR can include whitespace or punctuation within one bounding box.
            // Require every resulting component to be allowed if no phrase matches.
            if (canonical.isNotEmpty() &&
                (canonical in allowedPhrases || (wordTokens.size > 1 && wordTokens.all { it in allowedPhrases }))
            ) result += i

            val followingPeriod = i + 1 < words.size &&
                words[i].lineIndex == words[i + 1].lineIndex &&
                normalized[i + 1] in setOf("am", "pm")
            if (!followingPeriod && ranges.any { it.matches(words[i].content) }) result += i
            if (followingPeriod && recognized(i + 1) &&
                ranges.any { it.matches(words[i].content + " " + words[i + 1].content) }) {
                result += i
                result += i + 1
            }
        }

        // OCR can also split a phrase into multiple elements. Match consecutive
        // elements on the same original line, never a random substring.
        val maxTokens = allowedPhrases.maxOfOrNull { it.split(' ').size }?.coerceAtMost(12) ?: 0
        if (maxTokens > 1) {
            for (start in words.indices) {
                if (!recognized(start)) continue
                val parts = mutableListOf<String>()
                val indices = mutableListOf<Int>()
                for (end in start until minOf(words.size, start + 12)) {
                    if (!recognized(end) || words[end].lineIndex != words[start].lineIndex) break
                    val currentTokens = tokens(words[end].content)
                    if (currentTokens.isEmpty()) break
                    parts.addAll(currentTokens)
                    if (parts.size > maxTokens) break
                    indices += end
                    if (parts.size > 1 && parts.joinToString(" ") in allowedPhrases) result.addAll(indices)
                }
            }
        }
        return result
    }

}
