package io.github.roga666.redactor

import java.text.Normalizer
import java.util.Locale

/** Exact, case-insensitive WORD matching. No partial or fuzzy matches allowed. */
object Whitelist {
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim { !it.isLetterOrDigit() }
        .lowercase(Locale.ROOT)

    fun parse(input: String): Set<String> = input
        .split(Regex("[,;\\s]+"))
        .map(::normalize)
        .filter(String::isNotEmpty)
        .toSet()

    fun mayShow(ocrText: String, confidence: Float, allowed: Set<String>): Boolean {
        val word = normalize(ocrText)
        return word.isNotEmpty() && confidence.isFinite() && confidence >= 0.80f && word in allowed
    }
}
