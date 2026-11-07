package io.github.roga666.redactor

data class AllowedTextChange(val text: String, val added: List<String>)

/** Deterministic, duplicate-free additions to the current profile's allowed text. */
object WhitelistEditor {
    fun add(current: String, picked: List<String>): AllowedTextChange {
        val known = Whitelist.parse(current).toMutableSet()
        val additions = mutableListOf<String>()
        picked.forEach { raw ->
            val value = raw.replace(Regex("[,;\r\n]+"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val norm = Whitelist.normalize(value)
            if (norm.isNotEmpty() && value.length <= 120 && norm !in known) {
                additions += value
                known += norm
            }
        }
        return AllowedTextChange(
            text = if (additions.isEmpty()) current else
                current.trimEnd() + (if (current.isBlank()) "" else "\n") +
                    additions.joinToString("\n"),
            added = additions
        )
    }
}
