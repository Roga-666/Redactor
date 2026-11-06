package io.github.roga666.redactor

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PresetImportResult(
    val presets: List<RedactionPreset>,
    val imported: Int,
    val duplicatesSkipped: Int
)

/** Portable local JSON backup. Contains whitelist entries, NOT documents or document pixels. */
object PresetTransfer {
    private const val SCHEMA = "redactor-presets"
    private const val FORMAT_VERSION = 1
    const val MAX_JSON_BYTES = 2_000_000
    private const val MAX_PRESETS = 1_000
    private const val MAX_WORDS_CHARS = 65_536
    private const val MAX_RANGES = 100

    fun encode(presets: List<RedactionPreset>): String {
        val items = JSONArray()
        presets.forEach { profile ->
            val ranges = JSONArray()
            profile.timeRanges.forEach {
                ranges.put(JSONObject().put("start", it.startMinutes)
                    .put("end", it.endMinutes).put("step", it.everyMinutes))
            }
            items.put(JSONObject()
                .put("id", profile.id).put("name", profile.name)
                .put("words", profile.allowedText).put("times", ranges))
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", FORMAT_VERSION)
            .put("presets", items)
            .toString(2)
    }

    /**
     * Merge without replacing existing presets. Identical profile IDs/content
     * are skipped; an edited backup with a colliding ID is imported as a new,
     * separately named preset so existing user data is never silently lost.
     * A malformed backup is rejected entirely before any persistence.
     */
    fun merge(json: String, existing: List<RedactionPreset>): PresetImportResult {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Backup file is too large" }
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA &&
            root.optInt("version", -1) == FORMAT_VERSION) { "Unsupported Redactor preset backup format" }
        val data = root.optJSONArray("presets") ?: error("Missing preset list")
        require(data.length() <= MAX_PRESETS) { "Too many presets in backup" }

        val incoming = buildList {
            for (i in 0 until data.length()) {
                val profile = data.getJSONObject(i)
                val id = profile.getString("id").trim()
                val name = profile.getString("name").trim()
                val words = profile.getString("words")
                require(id.isNotEmpty() && id.length <= 150 && name.isNotEmpty() && name.length <= 120) {
                    "Invalid preset name or identifier"
                }
                require(words.length <= MAX_WORDS_CHARS) { "Preset is too large" }
                val rules = profile.getJSONArray("times")
                require(rules.length() <= MAX_RANGES) { "Too many time ranges" }
                val times = buildList {
                    for (j in 0 until rules.length()) {
                        val rule = rules.getJSONObject(j)
                        add(TimeRangeRule(rule.getInt("start"), rule.getInt("end"), rule.getInt("step")))
                    }
                }
                add(RedactionPreset(id = id, name = name, allowedText = words, timeRanges = times))
            }
        }

        val result = existing.toMutableList()
        var imported = 0
        var skipped = 0
        incoming.forEach { p ->
            val prior = result.firstOrNull { it.id == p.id }
            if (prior == p) {
                skipped++
            } else {
                require(result.size < MAX_PRESETS) { "Preset collection exceeds the 1,000-preset safety limit" }
                result += if (prior == null) p else p.copy(
                    id = UUID.randomUUID().toString(),
                    name = (p.name + " (imported)").take(120)
                )
                imported++
            }
        }
        return PresetImportResult(result, imported, skipped)
    }
}
