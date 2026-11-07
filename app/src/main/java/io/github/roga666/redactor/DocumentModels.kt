package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PointF
import kotlin.math.hypot

data class WordRegion(val content: String, val confidence: Float, val bounds: Rect, val lineIndex: Int = 0)

data class PageData(
    val bitmap: Bitmap,
    val words: List<WordRegion>,
    val manualBoxes: List<RectF> = emptyList(),
    val revealedWordIndices: Set<Int> = emptySet(),
    val forcedCoveredWordIndices: Set<Int> = emptySet()
)

fun PageData.wordBox(index: Int): RectF {
    val r = words[index].bounds
    val padX = maxOf(3f, r.height() * 0.12f)
    val padY = maxOf(3f, r.height() * 0.14f)
    return RectF(
        (r.left - padX).coerceAtLeast(0f),
        (r.top - padY).coerceAtLeast(0f),
        (r.right + padX).coerceAtMost(bitmap.width.toFloat()),
        (r.bottom + padY).coerceAtMost(bitmap.height.toFloat())
    )
}

fun PageData.autoWordIndices(terms: Set<String>, times: List<TimeRangeRule>): Set<Int> {
    val approved = Whitelist.allowedIndices(words, terms, times)
    return words.indices.filterTo(mutableSetOf()) {
        it in forcedCoveredWordIndices || (it !in approved && it !in revealedWordIndices)
    }
}

fun PageData.autoBoxes(terms: Set<String>, times: List<TimeRangeRule> = emptyList()): List<RectF> =
    autoWordIndices(terms, times).sorted().map(::wordBox)

fun PageData.allBoxes(terms: Set<String>, times: List<TimeRangeRule> = emptyList()): List<RectF> =
    autoBoxes(terms, times) + manualBoxes

/** Remove entire redaction objects touched by the stroke; never erase partial black pixels. */
fun PageData.eraseAt(points: List<android.graphics.PointF>, terms: Set<String>, times: List<TimeRangeRule>): PageData {
    val manualHits = mutableSetOf<Int>()
    val revealed = mutableSetOf<Int>()
    val visibleAuto = autoWordIndices(terms, times)
    // Sample between OS touch points; quick scribbles can cross a short black
    // box without Android sending a touch event *inside* that rectangle.
    val samples = buildList {
        if (points.isNotEmpty()) add(points.first())
        points.zipWithNext().forEach { (a, b) ->
            val steps = (maxOf(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)) / 7f)
                .toInt().coerceIn(1, 600)
            for (i in 1..steps) {
                val ratio = i.toFloat() / steps
                add(PointF(a.x + (b.x - a.x) * ratio, a.y + (b.y - a.y) * ratio))
            }
        }
    }
    samples.forEach { point ->
        // Manually drawn masks are above auto masks. Remove only the topmost object at each point.
        val manualHit = manualBoxes.indices.lastOrNull { i -> manualBoxes[i].contains(point.x, point.y) }
        if (manualHit != null) manualHits += manualHit
        else visibleAuto.firstOrNull { wordBox(it).contains(point.x, point.y) }?.let(revealed::add)
    }
    return copy(
        revealedWordIndices = revealedWordIndices + revealed,
        forcedCoveredWordIndices = forcedCoveredWordIndices - revealed,
        manualBoxes = manualBoxes.filterIndexed { index, _ -> index !in manualHits }
    )
}


/** Tap a detected OCR word or scribble across several to cover their entire OCR regions.
 * A deliberate drag outside recognized text falls back to an area mask (needed for OCR misses).
 * Single taps in blank space do nothing instead of accidentally covering unrelated content.
 */
fun PageData.coverAt(points: List<PointF>): PageData {
    if (points.isEmpty()) return this
    val touched = words.indices.filterTo(mutableSetOf()) { index ->
        val rect = wordBox(index)
        points.any { rect.contains(it.x, it.y) } ||
            points.zipWithNext().any { (a, b) -> segmentHitsRect(a, b, rect) }
    }
    if (touched.isNotEmpty()) return copy(
        forcedCoveredWordIndices = forcedCoveredWordIndices + touched,
        revealedWordIndices = revealedWordIndices - touched
    )
    if (points.size < 2) return this
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    if (hypot(maxX - minX, maxY - minY) < 8f) return this
    // The fallback covers the touched area, not an OCR-verified whole word.
    val pad = 12f
    val box = RectF((minX - pad).coerceAtLeast(0f), (minY - pad).coerceAtLeast(0f),
        (maxX + pad).coerceAtMost(bitmap.width.toFloat()),
        (maxY + pad).coerceAtMost(bitmap.height.toFloat()))
    return copy(manualBoxes = manualBoxes + box)
}


/**
 * Picks only OCR-backed words whose source regions are currently under a redaction.
 * This changes the on-screen preview and active preset, never the original bitmap.
 * No OCR hit means no whitelist entry is invented.
 */
data class WhitelistPick(
    val words: List<String>,
    val updatedPage: PageData,
    val touchedIndices: Set<Int>,
    val removedManualMasks: Int
)

fun PageData.whitelistAt(
    points: List<PointF>, terms: Set<String>, times: List<TimeRangeRule>
): WhitelistPick {
    if (points.isEmpty()) return WhitelistPick(emptyList(), this, emptySet(), 0)
    val automatic = autoWordIndices(terms, times)
    val touched = words.indices.filterTo(mutableSetOf()) { index ->
        val box = wordBox(index)
        val actuallyCovered = index in automatic || manualBoxes.any { RectF.intersects(it, box) }
        actuallyCovered && (points.any { box.contains(it.x, it.y) } ||
            points.zipWithNext().any { (a, b) -> segmentHitsRect(a, b, box) })
    }
    // Only source OCR text; blank/invalid OCR fragments must never be guessed.
    val eligible = touched.filterTo(mutableSetOf()) { index ->
        val value = words[index].content.trim()
        value.isNotBlank() && value.length <= 120 && value.any(Char::isLetterOrDigit) &&
            words[index].confidence.isFinite()
    }
    val selections = eligible.sorted().map { index ->
        words[index].content.trim()
            .replace(Regex("[,;\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }.filter(String::isNotBlank).distinctBy(Whitelist::normalize)

    // Remove user-drawn masks only when their footprint fits the selected OCR
    // words and doesn't cover any other source word. Broad manual privacy masks
    // intentionally remain in place; the Erase tool can remove those explicitly.
    val removeMasks = manualBoxes.indices.filterTo(mutableSetOf()) { maskIndex ->
        val mask = manualBoxes[maskIndex]
        val overlapping = words.indices.filter { RectF.intersects(mask, wordBox(it)) }
        if (overlapping.isEmpty() || overlapping.any { it !in eligible }) return@filterTo false
        val left = overlapping.minOf { wordBox(it).left }
        val top = overlapping.minOf { wordBox(it).top }
        val right = overlapping.maxOf { wordBox(it).right }
        val bottom = overlapping.maxOf { wordBox(it).bottom }
        val selectedWidth = (right - left).coerceAtLeast(1f)
        val selectedHeight = (bottom - top).coerceAtLeast(1f)
        // A modest margin for thick hand-drawn bars is fine, broad masks aren't.
        mask.width() <= selectedWidth * 1.4f &&
            mask.height() <= selectedHeight * 1.6f &&
            eligible.any { boxIndex -> RectF.intersects(mask, wordBox(boxIndex)) }
    }

    return WhitelistPick(
        words = selections,
        updatedPage = copy(
            forcedCoveredWordIndices = forcedCoveredWordIndices - eligible,
            // The explicitly selected source locations are approved even when
            // ML Kit supplies a weak/unavailable (-1) confidence score.
            // Other positions still require matching the saved whitelist.
            revealedWordIndices = revealedWordIndices + eligible,
            manualBoxes = manualBoxes.filterIndexed { index, _ -> index !in removeMasks }
        ),
        touchedIndices = eligible,
        removedManualMasks = removeMasks.size
    )
}

private fun segmentHitsRect(a: PointF, b: PointF, rect: RectF): Boolean {
    if (rect.contains(a.x, a.y) || rect.contains(b.x, b.y)) return true
    // Liang–Barsky line-segment clipping test, including horizontal and vertical strokes.
    val dx = b.x - a.x; val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - rect.left, rect.right - a.x, a.y - rect.top, rect.bottom - a.y)
    var enter = 0f; var exit = 1f
    for (i in 0..3) {
        if (p[i] == 0f) { if (q[i] < 0f) return false }
        else {
            val t = q[i] / p[i]
            if (p[i] < 0f) enter = maxOf(enter, t) else exit = minOf(exit, t)
            if (enter > exit) return false
        }
    }
    return true
}
