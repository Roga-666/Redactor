package io.github.roga666.redactor

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class LocalOcr : AutoCloseable {
    private val engine = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun identify(bitmap: Bitmap): List<WordRegion> {
        val result = engine.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { block -> block.lines }.flatMap { line ->
            line.elements.mapNotNull { element ->
                val bounds = element.boundingBox
                if (bounds == null || bounds.isEmpty) null
                else WordRegion(element.text, element.confidence, bounds)
            }
        }
    }

    suspend fun findUnapproved(bitmap: Bitmap, allowed: Set<String>): List<WordRegion> =
        identify(bitmap).filter { region ->
            val normalized = Whitelist.normalize(region.content)
            normalized.isNotEmpty() && normalized !in allowed
        }

    override fun close() = engine.close()
}
