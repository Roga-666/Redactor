package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF

data class WordRegion(val content: String, val confidence: Float, val bounds: Rect, val lineIndex: Int = 0)

data class PageData(
    val bitmap: Bitmap,
    val words: List<WordRegion>,
    val manualBoxes: List<RectF> = emptyList(),
    val revealedWordIndices: Set<Int> = emptySet()
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
    return words.indices.filterTo(mutableSetOf()) { it !in approved && it !in revealedWordIndices }
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
        manualBoxes = manualBoxes.filterIndexed { index, _ -> index !in manualHits }
    )
}
