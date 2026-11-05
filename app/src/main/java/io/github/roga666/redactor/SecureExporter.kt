package io.github.roga666.redactor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** Output contains newly drawn pixels only; no original PDF objects, text layers or EXIF. */
object SecureExporter {
    fun sanitize(page: PageData, allowed: Set<String>): Bitmap {
        val src = page.bitmap
        val target = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }
        for (box in page.allBoxes(allowed)) {
            val r = RectF(
                floor(box.left).coerceAtLeast(0f),
                floor(box.top).coerceAtLeast(0f),
                ceil(box.right).coerceAtMost(src.width.toFloat()),
                ceil(box.bottom).coerceAtMost(src.height.toFloat())
            )
            if (!r.isEmpty) {
                canvas.drawRect(r, paint)
                val xs = intArrayOf(r.left.toInt(), (r.right.toInt() - 1).coerceAtLeast(0))
                val ys = intArrayOf(r.top.toInt(), (r.bottom.toInt() - 1).coerceAtLeast(0))
                for (x in xs) for (y in ys) {
                    check(target.getPixel(x, y) == Color.BLACK) { "Redaction pixel validation failed" }
                }
            }
        }
        return target
    }

    fun writePng(resolver: ContentResolver, destination: Uri, image: Bitmap) {
        resolver.openOutputStream(destination, "wt")?.use { stream ->
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("PNG encoding failed")
            }
        } ?: error("Cannot write the selected destination")
    }

    fun writeImageOnlyPdf(resolver: ContentResolver, destination: Uri, images: List<Bitmap>) {
        require(images.isNotEmpty())
        val document = PdfDocument()
        try {
            images.forEachIndexed { index, image ->
                val pageInfo = PdfDocument.PageInfo.Builder(image.width, image.height, index + 1).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(image, 0f, 0f, null)
                document.finishPage(page)
            }
            resolver.openOutputStream(destination, "wt")?.use { document.writeTo(it) }
                ?: error("Cannot write the selected destination")
        } finally {
            document.close()
        }
    }
}
