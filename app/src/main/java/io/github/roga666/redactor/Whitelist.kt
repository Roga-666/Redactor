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

    /** Return only word positions covered by an exact allowed term/phrase or allowed time. */
    fun allowedIndices(words: List<WordRegion>, terms: Set<String>, ranges: List<TimeRangeRule>): Set<Int> {
        val result = mutableSetOf<Int>()
        val normalized = words.map { normalize(it.content) }
        fun trustworthy(i: Int): Boolean = words[i].confidence.isFinite() && words[i].confidence >= 0.80f

        // Standalone terms never implicitly allow AM or PM just because a time-range is enabled.
        for (i in words.indices) {
            if (trustworthy(i) && normalized[i].isNotEmpty() && normalized[i] in terms) result += i
            val followingPeriod = i + 1 < words.size && words[i].lineIndex == words[i + 1].lineIndex &&
                normalized[i + 1] in setOf("am", "pm")
            if (!followingPeriod && trustworthy(i) && ranges.any { it.matches(words[i].content) }) result += i
            if (followingPeriod && trustworthy(i) && trustworthy(i + 1) &&
                ranges.any { it.matches(words[i].content + " " + words[i + 1].content) }
            ) {
                result += i
                result += i + 1
            }
        }

        // Permit exact multi-word entries only when all OCR components appear consecutively on one line.
        for (term in terms) {
            val components = term.split(Regex("\\s+")).filter(String::isNotBlank)
            if (components.size < 2) continue
            for (start in 0..(words.size - components.size).coerceAtLeast(-1)) {
                if (components.indices.all { delta ->
                        val i = start + delta
                        trustworthy(i) && normalized[i] == components[delta] &&
                            words[i].lineIndex == words[start].lineIndex
                    }) {
                    components.indices.forEach { result += start + it }
                }
            }
        }
        return result
    }
}
