package io.github.roga666.redactor

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RedactionPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val allowedText: String = "",
    val timeRanges: List<TimeRangeRule> = emptyList()
) {
    val terms: Set<String> get() = Whitelist.parse(allowedText)
}

/** Presets remain in the app's private storage; Android backup is disabled in the manifest. */
class PresetStore(private val prefs: SharedPreferences) {
    fun load(): List<RedactionPreset> = try {
        val json = prefs.getString("presets_v2", null)
        if (json == null) {
            // Carry the original whitelist forward for existing installations.
            listOf(RedactionPreset(id = "quick", name = "Quick", allowedText = prefs.getString("allowed_words", "").orEmpty()))
        } else {
            val data = JSONArray(json)
            buildList {
                for (i in 0 until data.length()) {
                    val p = data.getJSONObject(i)
                    val timeData = p.optJSONArray("times") ?: JSONArray()
                    val ranges = buildList {
                        for (j in 0 until timeData.length()) {
                            val rule = timeData.getJSONObject(j)
                            val start = rule.optInt("start", 540)
                            val end = rule.optInt("end", 1080)
                            val step = rule.optInt("step", 15)
                            runCatching { TimeRangeRule(start, end, step) }.getOrNull()?.let(::add)
                        }
                    }
                    add(RedactionPreset(p.getString("id"), p.getString("name"), p.optString("words"), ranges))
                }
            }.ifEmpty { listOf(RedactionPreset(id = "quick", name = "Quick")) }
        }
    } catch (_: Exception) {
        listOf(RedactionPreset(id = "quick", name = "Quick"))
    }

    fun selectedId(): String = prefs.getString("selected_preset_v2", "quick") ?: "quick"

    fun save(presets: List<RedactionPreset>, activeId: String) {
        val data = JSONArray()
        presets.forEach { preset ->
            val times = JSONArray()
            preset.timeRanges.forEach { rule ->
                times.put(JSONObject().put("start", rule.startMinutes).put("end", rule.endMinutes).put("step", rule.everyMinutes))
            }
            data.put(JSONObject().put("id", preset.id).put("name", preset.name).put("words", preset.allowedText).put("times", times))
        }
        prefs.edit().putString("presets_v2", data.toString()).putString("selected_preset_v2", activeId).apply()
    }
}
