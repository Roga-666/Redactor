package io.github.roga666.redactor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.roundToInt

/** Input URI stays in the selected document provider. No original-document temp files. */
object DocumentImporter {
    const val MAX_PAGES = 12
    const val MAX_TOTAL_PIXELS = 36_000_000L

    fun load(context: Context, uri: Uri): List<Bitmap> {
        val type = context.contentResolver.getType(uri) ?: ""
        val isPdf = type.equals("application/pdf", ignoreCase = true) ||
            uri.lastPathSegment.orEmpty().endsWith(".pdf", ignoreCase = true)
        return if (isPdf) renderPdf(context, uri) else listOf(decodeImage(context, uri))
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            require(!info.isAnimated) { "Animated images are not supported; convert to a still PNG or JPEG first." }
            val w = info.size.width
            val h = info.size.height
            require(w > 0 && h > 0 && w.toLong() * h <= MAX_TOTAL_PIXELS) {
                "Image is too large for safe processing (36 megapixel limit)."
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    private fun renderPdf(context: Context, uri: Uri): List<Bitmap> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open PDF. Choose a locally available PDF file.")
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount in 1..MAX_PAGES) {
                    "PDF has ${renderer.pageCount} pages. This first version supports 1–$MAX_PAGES pages; no partial exports."
                }
                var pixelBudget = 0L
                val results = mutableListOf<Bitmap>()
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val scale = minOf(2.5f, 3600f / maxOf(page.width, page.height))
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        pixelBudget += width.toLong() * height
                        require(pixelBudget <= MAX_TOTAL_PIXELS) {
                            "PDF exceeds the 36 megapixel memory budget. No pages were exported."
                        }
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        results += bitmap
                    }
                }
                return results
            }
        }
    }
}
