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
    points.forEach { point ->
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
