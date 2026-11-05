package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF

data class WordRegion(val content: String, val confidence: Float, val bounds: Rect)

data class PageData(
    val bitmap: Bitmap,
    val words: List<WordRegion>,
    val manualBoxes: List<RectF> = emptyList()
)

fun PageData.autoBoxes(allowed: Set<String>): List<RectF> = words
    .filterNot { Whitelist.mayShow(it.content, it.confidence, allowed) }
    .map { word ->
        val r = word.bounds
        val padX = maxOf(3f, r.height() * 0.12f)
        val padY = maxOf(3f, r.height() * 0.14f)
        RectF(
            (r.left - padX).coerceAtLeast(0f),
            (r.top - padY).coerceAtLeast(0f),
            (r.right + padX).coerceAtMost(bitmap.width.toFloat()),
            (r.bottom + padY).coerceAtMost(bitmap.height.toFloat())
        )
    }

fun PageData.allBoxes(allowed: Set<String>): List<RectF> = autoBoxes(allowed) + manualBoxes
