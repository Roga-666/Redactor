package io.github.roga666.redactor

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class LocalOcr : AutoCloseable {
    private val engine = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun identify(bitmap: Bitmap): List<WordRegion> {
        val result = engine.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { it.lines }.flatMapIndexed { index, line ->
            line.elements.mapNotNull { element ->
                val bounds = element.boundingBox
                if (bounds == null || bounds.isEmpty) null
                else WordRegion(element.text, element.confidence, Rect(bounds), index)
            }
        }
    }

    /** Verification permits only exact, explicitly unmasked source regions, not arbitrary new text. */
    suspend fun findUnexpected(bitmap: Bitmap, page: PageData, terms: Set<String>, times: List<TimeRangeRule>): List<WordRegion> {
        val words = identify(bitmap)
        val approved = Whitelist.allowedIndices(words, terms, times)
        return words.filterIndexed { index, detected ->
            if (index in approved) return@filterIndexed false
            val manuallyApprovedAtThisLocation = page.revealedWordIndices.any { originalIndex ->
                val original = page.words.getOrNull(originalIndex) ?: return@any false
                val box = original.bounds
                val center = detected.bounds.centerX() to detected.bounds.centerY()
                val match = Whitelist.normalize(original.content) == Whitelist.normalize(detected.content)
                match && box.contains(center.first, center.second)
            }
            !manuallyApprovedAtThisLocation
        }
    }

    override fun close() = engine.close()
}
