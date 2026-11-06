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

    /** Re-scan sanitized pixels and compare against originally approved visible regions. */
    suspend fun findUnexpected(bitmap: Bitmap, page: PageData, terms: Set<String>, times: List<TimeRangeRule>): List<WordRegion> {
        val secondScan = identify(bitmap)
        val approved = Whitelist.allowedIndices(page.words, terms, times)
        val approvedSource = page.words.indices.filter { index ->
            (index in approved || index in page.revealedWordIndices) &&
                index !in page.forcedCoveredWordIndices
        }.map { index ->
            val word = page.words[index]
            ExpectedWord(word.content, word.bounds.toTextBounds())
        }
        val masks = page.allBoxes(terms, times).map { rect ->
            TextBounds(rect.left.toInt(), rect.top.toInt(),
                kotlin.math.ceil(rect.right.toDouble()).toInt(),
                kotlin.math.ceil(rect.bottom.toDouble()).toInt())
        }
        return secondScan.filterNot { detected ->
            VerificationPolicy.isExpected(
                ExpectedWord(detected.content, detected.bounds.toTextBounds()),
                approvedSource, masks
            )
        }
    }

    private fun Rect.toTextBounds() = TextBounds(left, top, right, bottom)

    override fun close() = engine.close()
}
