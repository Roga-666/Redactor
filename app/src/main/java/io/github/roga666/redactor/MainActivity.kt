package io.github.roga666.redactor

import android.net.Uri
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val Accent = Color(0xFF7BDCB5)
private val Back = Color(0xFF101417)
private val Fore = Color(0xFFF2F4F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color(0xFF001B12),
                    background = Back,
                    surface = Color(0xFF192024),
                    onSurface = Fore
                )
            ) {
                RedactorScreen()
            }
        }
    }
}

@Composable
private fun RedactorScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("redactor_settings", 0) }
    val scope = rememberCoroutineScope()
    var whitelistText by remember { mutableStateOf(prefs.getString("allowed_words", "").orEmpty()) }
    val allowedWords = remember(whitelistText) { Whitelist.parse(whitelistText) }
    var pages by remember { mutableStateOf<List<PageData>>(emptyList()) }
    var pageIndex by remember { mutableStateOf(0) }
    var reviewedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var cleanPreview by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Import an image or PDF to begin. Nothing is uploaded.") }
    var fileName by remember { mutableStateOf("document") }

    val readyToExport = pages.isNotEmpty() && reviewedPages.size == pages.size && !busy

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            scope.launch {
                busy = true
                message = "Reading document and scanning text locally…"
                pages = emptyList()
                reviewedPages = emptySet()
                pageIndex = 0
                try {
                    val bitmaps = withContext(Dispatchers.IO) { DocumentImporter.load(context, uri) }
                    val ocr = LocalOcr()
                    try {
                        val loaded = mutableListOf<PageData>()
                        bitmaps.forEachIndexed { i, bitmap ->
                            message = "Scanning page ${i + 1} of ${bitmaps.size}…"
                            loaded += PageData(bitmap, ocr.identify(bitmap))
                        }
                        pages = loaded
                        fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                            ?.take(30) ?: "document"
                        message = "Scan complete. Inspect EVERY page and add manual covers for any missed text."
                    } finally {
                        ocr.close()
                    }
                } catch (e: Exception) {
                    pages = emptyList()
                    message = "Import blocked: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun exportTo(uri: Uri, pdf: Boolean) {
        if (!readyToExport) {
            message = "Review every page before exporting."
            return
        }
        val snapshot = if (pdf) pages else listOf(pages[pageIndex])
        val whitelistSnapshot = allowedWords.toSet()
        scope.launch {
            busy = true
            message = "Creating fresh flattened pixels and running a final local OCR check…"
            val images = mutableListOf<Bitmap>()
            try {
                images += withContext(Dispatchers.Default) {
                    snapshot.map { SecureExporter.sanitize(it, whitelistSnapshot) }
                }
                val verifier = LocalOcr()
                try {
                    images.forEachIndexed { index, bitmap ->
                        val unexpected = verifier.findUnapproved(bitmap, whitelistSnapshot)
                        if (unexpected.isNotEmpty()) {
                            val sample = unexpected.take(3).joinToString { "‘${it.content.take(25)}’" }
                            error("Verification found text outside the whitelist on output page ${index + 1}: $sample. Add manual covers and retry.")
                        }
                    }
                } finally {
                    verifier.close()
                }
                withContext(Dispatchers.IO) {
                    if (pdf) SecureExporter.writeImageOnlyPdf(context.contentResolver, uri, images)
                    else SecureExporter.writePng(context.contentResolver, uri, images.single())
                }
                message = "Export complete: fresh ${if (pdf) "image-only PDF" else "PNG"}. Remember: OCR cannot prove every word was found."
            } catch (e: Exception) {
                message = "Export blocked/failed: ${e.message ?: e.javaClass.simpleName}. The document may have an empty destination file."
            } finally {
                images.forEach { if (!it.isRecycled) it.recycle() }
                busy = false
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) exportTo(uri, true)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) exportTo(uri, false)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Back) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Redactor", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Fore)
            Text("Offline • Whitelist-first • Image & PDF", color = Accent, fontSize = 13.sp)
            Text(
                "Only words on your whitelist may remain. Unlisted and low-confidence OCR words are covered. Draw covers over anything OCR misses.",
                color = Color(0xFFC8D0D2), fontSize = 14.sp
            )

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    }
                    importLauncher.launch(intent)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import image or PDF") }

            OutlinedTextField(
                value = whitelistText,
                enabled = !busy,
                onValueChange = { value ->
                    whitelistText = value
                    prefs.edit().putString("allowed_words", value).apply()
                    reviewedPages = emptySet()
                },
                label = { Text("Allowed words") },
                supportingText = { Text("Separate words using commas, spaces or new lines. Exact whole-word, case-insensitive matching only.") },
                placeholder = { Text("Patient, Date, Approved") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Text("${allowedWords.size} allowed words • Saved privately on this phone", color = Color.LightGray, fontSize = 12.sp)

            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp), color = Accent, strokeWidth = 2.dp)
                    Text("Processing locally…", color = Color.LightGray)
                }
            }
            Text(message, color = if (message.startsWith("Export blocked") || message.startsWith("Import blocked")) Color(0xFFFFA4A4) else Color.LightGray, fontSize = 13.sp)

            if (pages.isNotEmpty()) {
                val page = pages[pageIndex]
                val redactedCount = page.words.count { !Whitelist.mayShow(it.content, it.confidence, allowedWords) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { pageIndex-- }, enabled = pageIndex > 0 && !busy, modifier = Modifier.weight(1f)) {
                        Text("Previous")
                    }
                    OutlinedButton(onClick = { pageIndex++ }, enabled = pageIndex < pages.lastIndex && !busy, modifier = Modifier.weight(1f)) {
                        Text("Next")
                    }
                }
                Text("Page ${pageIndex + 1}/${pages.size} • ${page.words.size} detected words • $redactedCount masked", color = Fore)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sanitized preview", modifier = Modifier.weight(1f), color = Fore)
                    Switch(checked = cleanPreview, onCheckedChange = { cleanPreview = it }, enabled = !busy)
                }

                RedactionPreview(
                    page = page,
                    allowed = allowedWords,
                    showMasks = cleanPreview,
                    onManualCover = { box ->
                        if (!busy) {
                            pages = pages.toMutableList().apply {
                                this[pageIndex] = page.copy(manualBoxes = page.manualBoxes + box)
                            }
                            reviewedPages = reviewedPages - pageIndex
                            message = "Manual cover added. Re-review this page."
                        }
                    }
                )
                Text("Drag on the image to permanently cover any missed word or area. Switch preview off to inspect the original.", color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            pages = pages.toMutableList().apply {
                                this[pageIndex] = page.copy(manualBoxes = page.manualBoxes.dropLast(1))
                            }
                            reviewedPages = reviewedPages - pageIndex
                        },
                        enabled = page.manualBoxes.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Undo cover") }
                    OutlinedButton(
                        onClick = {
                            pages = pages.toMutableList().apply {
                                this[pageIndex] = page.copy(manualBoxes = emptyList())
                            }
                            reviewedPages = reviewedPages - pageIndex
                        },
                        enabled = page.manualBoxes.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear covers") }
                }

                val reviewed = pageIndex in reviewedPages
                Button(
                    onClick = {
                        reviewedPages = if (reviewed) reviewedPages - pageIndex else reviewedPages + pageIndex
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = if (reviewed) Color(0xFF355B50) else Accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (reviewed) "✓ Page reviewed (tap to undo)" else "I checked this entire page", color = if (reviewed) Color.White else Color.Black)
                }
                Text("${reviewedPages.size} of ${pages.size} pages reviewed", fontSize = 12.sp, color = Color.LightGray)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { pdfLauncher.launch("${fileName.ifBlank { "document" }}-redacted.pdf") },
                        enabled = readyToExport,
                        modifier = Modifier.weight(1f)
                    ) { Text("Export PDF") }
                    Button(
                        onClick = { pngLauncher.launch("${fileName.ifBlank { "document" }}-page-${pageIndex + 1}.png") },
                        enabled = readyToExport,
                        modifier = Modifier.weight(1f)
                    ) { Text("Export PNG") }
                }
                Text("PDF: all pages. PNG: current page only. Exports are flattened and stripped of source metadata.", color = Color.LightGray, fontSize = 12.sp)
            }

            Spacer(Modifier.height(4.dp))
            Text("Security note", fontWeight = FontWeight.SemiBold, color = Accent)
            Text(
                "No internet permission, no remote processing, no raw-document temp files. OCR misses are possible: do not release any export until you have visually inspected every pixel, including faint or rotated text. Original files and existing backups are not deleted.",
                color = Color.LightGray, fontSize = 12.sp
            )
        }
    }
}

private data class Viewport(val scale: Float, val left: Float, val top: Float) {
    fun toSource(p: Offset, width: Int, height: Int): Offset = Offset(
        ((p.x - left) / scale).coerceIn(0f, width.toFloat()),
        ((p.y - top) / scale).coerceIn(0f, height.toFloat())
    )
}

private fun makeViewport(viewW: Float, viewH: Float, bitmapW: Int, bitmapH: Int): Viewport {
    val scale = min(viewW / bitmapW, viewH / bitmapH)
    return Viewport(scale, (viewW - bitmapW * scale) / 2f, (viewH - bitmapH * scale) / 2f)
}

@Composable
private fun RedactionPreview(
    page: PageData,
    allowed: Set<String>,
    showMasks: Boolean,
    onManualCover: (RectF) -> Unit
) {
    val bitmap = page.bitmap
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val boxes = remember(page, allowed) { page.allBoxes(allowed) }
    var dragBegin by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier = Modifier.fillMaxWidth().height(390.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF283034))
            .pointerInput(bitmap.width, bitmap.height, onManualCover) {
                detectDragGestures(
                    onDragStart = { dragBegin = it; dragCurrent = it },
                    onDrag = { change, _ -> dragCurrent = change.position; change.consume() },
                    onDragEnd = {
                        val a = dragBegin
                        val b = dragCurrent
                        if (a != null && b != null) {
                            val viewport = makeViewport(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                            val s = viewport.toSource(a, bitmap.width, bitmap.height)
                            val e = viewport.toSource(b, bitmap.width, bitmap.height)
                            if (abs(s.x - e.x) >= 5f && abs(s.y - e.y) >= 5f) {
                                onManualCover(RectF(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y)))
                            }
                        }
                        dragBegin = null
                        dragCurrent = null
                    },
                    onDragCancel = { dragBegin = null; dragCurrent = null }
                )
            }
    ) {
        val v = makeViewport(size.width, size.height, bitmap.width, bitmap.height)
        drawImage(
            image = image,
            dstOffset = IntOffset(v.left.roundToInt(), v.top.roundToInt()),
            dstSize = IntSize((bitmap.width * v.scale).roundToInt(), (bitmap.height * v.scale).roundToInt()),
            filterQuality = FilterQuality.Medium
        )
        for (box in boxes) {
            val tl = Offset(v.left + box.left * v.scale, v.top + box.top * v.scale)
            val rectSize = Size(box.width() * v.scale, box.height() * v.scale)
            if (showMasks) drawRect(Color.Black, topLeft = tl, size = rectSize)
            else drawRect(Color(0xFFFF7474), topLeft = tl, size = rectSize, style = Stroke(width = 2.5f))
        }
        val a = dragBegin
        val b = dragCurrent
        if (a != null && b != null) {
            drawRect(
                Color(0xFFFFC773),
                topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                size = Size(abs(a.x - b.x), abs(a.y - b.y)),
                style = Stroke(width = 3f)
            )
        }
    }
}
