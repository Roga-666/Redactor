package io.github.roga666.redactor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val Accent = Color(0xFF7BDCB5)
private val Back = Color(0xFF101417)
private val Fore = Color(0xFFF2F4F5)
private val Muted = Color(0xFF9DAEB4)
private enum class PaintTool { COVER, ERASE }
private data class VerificationFinding(val pageIndex: Int, val word: WordRegion)
private data class PreparedExport(val pages: List<Bitmap>, val pdf: Boolean, val name: String) {
    fun dispose() = pages.forEach { if (!it.isRecycled) it.recycle() }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep sensitive pages protected unless the user explicitly opts into screenshots.
        val screenshotPreference = getSharedPreferences("redactor_settings", Context.MODE_PRIVATE)
        if (!screenshotPreference.getBoolean("allow_screenshots", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF001B12),
                    background = Back, surface = Color(0xFF192024), onSurface = Fore
                )
            ) { RedactorScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedactorScreen() {
    val context = LocalContext.current
    val store = remember { PresetStore(context.getSharedPreferences("redactor_settings", 0)) }
    val scope = rememberCoroutineScope()
    val appSettings = remember { context.getSharedPreferences("redactor_settings", Context.MODE_PRIVATE) }
    var presets by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(store.selectedId()) }
    val active = presets.firstOrNull { it.id == selectedId } ?: presets.first()
    val allowed = remember(active.allowedText) { active.terms }
    val ranges = active.timeRanges

    var pages by remember { mutableStateOf<List<PageData>>(emptyList()) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var reviewed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var undoHistory by remember { mutableStateOf(UndoHistory<PageData>()) }
    var tool by remember { mutableStateOf(PaintTool.COVER) }
    var showMasks by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import a file to begin") }
    var fileName by remember { mutableStateOf("document") }
    var showWhitelist by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var exportFolder by remember { mutableStateOf(appSettings.getString("export_folder_uri", null)) }
    var allowScreenshots by remember { mutableStateOf(appSettings.getBoolean("allow_screenshots", false)) }
    // Changing the toggle takes effect immediately, without restarting the activity.
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            if (allowScreenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    var exportError by remember { mutableStateOf<String?>(null) }
    var validationFindings by remember { mutableStateOf<List<VerificationFinding>>(emptyList()) }
    var preparedExport by remember { mutableStateOf<PreparedExport?>(null) }
    var rangeEditIndex by remember { mutableStateOf<Int?>(null) }
    var newPresetName by remember { mutableStateOf("") }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }

    fun persist(next: List<RedactionPreset> = presets, id: String = selectedId, invalidate: Boolean = false) {
        presets = next
        selectedId = id
        store.save(next, id)
        if (invalidate) {
            reviewed = emptySet()
            undoHistory = UndoHistory()
            pages = pages.map { it.copy(revealedWordIndices = emptySet()) }
        }
    }

    fun editActive(change: (RedactionPreset) -> RedactionPreset) {
        persist(presets.map { if (it.id == active.id) change(it) else it }, invalidate = true)
    }

    fun editCurrentPage(change: (PageData) -> PageData) {
        if (busy || pages.isEmpty()) return
        val before = pages[pageIndex]
        val after = change(before)
        if (before == after) return
        undoHistory = undoHistory.record(pageIndex, before)
        pages = pages.toMutableList().apply { this[pageIndex] = after }
        reviewed = reviewed - pageIndex
        showMasks = true
        status = "Change saved · review this page again"
    }

    val ready = pages.isNotEmpty() && reviewed.size == pages.size && !busy
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            scope.launch {
                busy = true
                status = "Scanning locally…"
                pages = emptyList()
                reviewed = emptySet()
                undoHistory = UndoHistory()
                pageIndex = 0
                showMasks = true
                try {
                    val bitmaps = withContext(Dispatchers.IO) { DocumentImporter.load(context, uri) }
                    val scanner = LocalOcr()
                    try {
                        val loaded = mutableListOf<PageData>()
                        bitmaps.forEachIndexed { index, bitmap ->
                            status = "Scanning ${index + 1}/${bitmaps.size}…"
                            loaded += PageData(bitmap, scanner.identify(bitmap))
                        }
                        pages = loaded
                        fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.take(35) ?: "document"
                        val lowerConfidenceMatches = loaded.sumOf { page ->
                            Whitelist.allowedIndices(page.words, allowed, ranges)
                                .count { index -> page.words[index].confidence < 0.80f }
                        }
                        status = if (lowerConfidenceMatches > 0)
                            "Inspect pages · $lowerConfidenceMatches allowed matches had uncertain OCR"
                        else "Scan complete · inspect every page"
                    } finally { scanner.close() }
                } catch (e: Exception) {
                    pages = emptyList()
                    status = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                } finally { busy = false }
            }
        }
    }

    fun showExportError(message: String) {
        exportError = message
        status = "Export failed · see full error"
    }

    suspend fun savePreparedTo(uri: Uri, prepared: PreparedExport) {
        withContext(Dispatchers.IO) {
            if (prepared.pdf) SecureExporter.writeImageOnlyPdf(context.contentResolver, uri, prepared.pages)
            else SecureExporter.writePng(context.contentResolver, uri, prepared.pages.single())
        }
    }

    fun finishExport(uri: Uri?) {
        val pending = preparedExport ?: return
        preparedExport = null
        if (uri == null) {
            pending.dispose()
            status = "Save cancelled"
            return
        }
        scope.launch {
            busy = true
            try {
                savePreparedTo(uri, pending)
                status = "Exported ${if (pending.pdf) "PDF" else "PNG"} successfully"
                Toast.makeText(context, "Saved successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showExportError("The destination could not be written: ${e.message ?: e.javaClass.simpleName}. Choose another save location and retry.")
            } finally { pending.dispose(); busy = false }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        finishExport(uri)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        finishExport(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            try {
                val grants = result.data?.flags ?: 0
                val take = grants and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(take and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "Selected folder is read-only" }
                context.contentResolver.takePersistableUriPermission(uri, take)
                exportFolder = uri.toString()
                appSettings.edit().putString("export_folder_uri", uri.toString()).apply()
                status = "Default export folder saved"
            } catch (e: Exception) {
                showExportError("Could not use selected folder: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    val exportPresetsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val snapshot = presets.toList()
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = PresetTransfer.encode(snapshot)
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(json)
                        } ?: error("Could not open the selected backup destination")
                    }
                    status = "Exported " + snapshot.size + " presets"
                    Toast.makeText(context, "Preset backup saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showExportError("Could not export presets: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    val importPresetsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val amount = stream.read(buffer)
                                if (amount < 0) break
                                if (amount == 0) continue
                                require(out.size() + amount <= PresetTransfer.MAX_JSON_BYTES) {
                                    "Preset backup exceeds the 2 MB safety limit"
                                }
                                out.write(buffer, 0, amount)
                            }
                            out.toString(Charsets.UTF_8.name())
                        } ?: error("Could not read the selected preset backup")
                    }
                    val merged = PresetTransfer.merge(json, presets)
                    if (merged.imported > 0) persist(next = merged.presets)
                    status = "Imported " + merged.imported + " presets · " +
                        merged.duplicatesSkipped + " duplicates skipped"
                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    showExportError("Could not import presets: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun requestExport(pdf: Boolean) {
        if (!ready) { status = "Review every page before export"; return }
        val pageIndices = if (pdf) pages.indices.toList() else listOf(pageIndex)
        val originals = pageIndices.map { pages[it] }
        val termSnapshot = allowed.toSet()
        val timeSnapshot = ranges.toList()
        val name = fileName.ifBlank { "document" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(45)
        val outputName = if (pdf) "$name-redacted.pdf" else "$name-page-${pageIndex + 1}.png"
        scope.launch {
            busy = true
            validationFindings = emptyList()
            exportError = null
            status = "Checking flattened output…"
            val bitmaps = mutableListOf<Bitmap>()
            var handedOff = false
            try {
                bitmaps += withContext(Dispatchers.Default) {
                    originals.map { SecureExporter.sanitize(it, termSnapshot, timeSnapshot) }
                }
                val scanner = LocalOcr()
                try {
                    val findings = mutableListOf<VerificationFinding>()
                    bitmaps.forEachIndexed { idx, bitmap ->
                        status = "Verifying page ${idx + 1}/${bitmaps.size}…"
                        scanner.findUnexpected(bitmap, originals[idx], termSnapshot, timeSnapshot).forEach {
                            findings += VerificationFinding(pageIndices[idx], it)
                        }
                    }
                    if (findings.isNotEmpty()) {
                        validationFindings = findings
                        status = "Found ${findings.size} areas to review"
                        return@launch
                    }
                } finally { scanner.close() }

                val pending = PreparedExport(bitmaps.toList(), pdf, outputName)
                val treeUri = exportFolder?.let(Uri::parse)
                if (treeUri != null) {
                    // A persisted folder is an automatic destination: never launch CreateDocument.
                    status = "Saving directly to default folder…"
                    val saved = withContext(Dispatchers.IO) {
                        val tree = DocumentFile.fromTreeUri(context, treeUri)
                            ?: error("Default folder is unavailable. Choose it again in Settings.")
                        if (!tree.canWrite()) error("Default folder is read-only. Choose another folder in Settings.")
                        val document = tree.createFile(if (pdf) "application/pdf" else "image/png", outputName)
                            ?: error("Could not create a file in the default folder")
                        try {
                            savePreparedTo(document.uri, pending)
                            document.name ?: outputName
                        } catch (e: Exception) {
                            // Delete incomplete output rather than leaving a misleading partial export.
                            document.delete()
                            throw e
                        }
                    }
                    status = "Saved automatically: $saved"
                    Toast.makeText(context, "Saved: $saved", Toast.LENGTH_SHORT).show()
                } else {
                    // Only users without a default folder see the filename/location picker.
                    preparedExport?.dispose()
                    preparedExport = pending
                    handedOff = true
                    if (pdf) pdfLauncher.launch(outputName) else pngLauncher.launch(outputName)
                }
            } catch (e: Exception) {
                showExportError("Export could not finish: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (!handedOff) bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                busy = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Back) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Redactor", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("OFFLINE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showSettings = true }, enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⚙", fontSize = 23.sp) }
                Button(
                    onClick = {
                        importLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                        })
                    },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                ) { Text("+ Import") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showPresets = true }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 9.dp)) {
                    Text("◫ ${active.name}  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { showWhitelist = true }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 9.dp)) {
                    Text("Allowed · ${allowed.size}  ✎", maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(22.dp)) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(status, modifier = Modifier.weight(1f).padding(start = if (busy) 7.dp else 0.dp)
                    .clickable(enabled = exportError != null || validationFindings.isNotEmpty()) {
                        if (exportError == null) status = "Review the flagged areas below"
                    }, fontSize = 12.sp,
                    color = if (status.contains("failed", true) || status.contains("blocked", true)) Color(0xFFFFA4A4) else Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF242D31)),
                contentAlignment = Alignment.Center
            ) {
                if (pages.isNotEmpty()) {
                    val page = pages[pageIndex]
                    RedactionPreview(
                        page = page, allowed = allowed, times = ranges, masksVisible = showMasks, tool = tool,
                        modifier = Modifier.fillMaxSize(),
                        onCover = { points ->
                            val before = pages[pageIndex]
                            val after = before.coverAt(points)
                            if (after == before) status = "No OCR word here · drag to cover a missed area"
                            else editCurrentPage { after }
                        },
                        onErase = { points -> editCurrentPage { it.eraseAt(points, allowed, ranges) } }
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(20.dp)) {
                        Text("▧", fontSize = 48.sp, color = Accent)
                        Text("Your preview appears here", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Images and PDFs stay on this device", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RedactionToolButton("Redact", PaintTool.COVER, tool == PaintTool.COVER, !busy) {
                    tool = PaintTool.COVER
                }
                RedactionToolButton("Erase", PaintTool.ERASE, tool == PaintTool.ERASE, !busy) {
                    tool = PaintTool.ERASE
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val undo = undoHistory.takeLastFor(pageIndex)
                    if (undo != null && !busy) {
                        pages = pages.toMutableList().apply { this[pageIndex] = undo.first }
                        reviewed = reviewed - pageIndex
                        undoHistory = undo.second
                        showMasks = true
                        status = "Undid last edit · review page again"
                    }
                }, enabled = undoHistory.canUndo(pageIndex) && !busy, contentPadding = PaddingValues(horizontal = 3.dp)) {
                    Text("Undo")
                }
                TextButton(onClick = { showMasks = !showMasks }, enabled = pages.isNotEmpty() && !busy,
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (showMasks) "Original" else "Masked") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pageIndex--; showMasks = true }, enabled = pageIndex > 0 && !busy,
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("‹") }
                Text(if (pages.isEmpty()) "No pages" else "Page ${pageIndex + 1}/${pages.size}",
                    modifier = Modifier.weight(1f), fontSize = 13.sp, color = Muted, maxLines = 1)
                OutlinedButton(onClick = { pageIndex++; showMasks = true }, enabled = pageIndex < pages.lastIndex && !busy,
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("›") }
                Button(
                    onClick = { reviewed = if (pageIndex in reviewed) reviewed - pageIndex else reviewed + pageIndex },
                    enabled = pages.isNotEmpty() && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = if (pageIndex in reviewed) Color(0xFF315B4F) else Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text(if (pageIndex in reviewed) "Reviewed" else "Review") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { requestExport(true) },
                    enabled = ready, modifier = Modifier.weight(1f)) { Text("Export PDF") }
                Button(onClick = { requestExport(false) },
                    enabled = ready, modifier = Modifier.weight(1f)) { Text("Export PNG") }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow screenshots", fontWeight = FontWeight.SemiBold)
                        Text("Off by default. Allow screenshots and screen recordings of Redactor.",
                            color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = allowScreenshots, onCheckedChange = { enabled ->
                        allowScreenshots = enabled
                        appSettings.edit().putBoolean("allow_screenshots", enabled).apply()
                    })
                }
                if (allowScreenshots) {
                    Text("Screenshots may include unredacted documents or visible information. Turn this off when finished.",
                        color = Color(0xFFFFC6A5), fontSize = 12.sp)
                }
                HorizontalDivider()
                Text("Default export folder", fontWeight = FontWeight.SemiBold)
                Text(
                    exportFolder?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name ?: "Previously chosen folder" }
                        ?: "Ask where to save each export",
                    color = Muted, fontSize = 13.sp
                )
                Text(if (exportFolder == null) "Export opens the save dialog so you can name the file."
                     else "Export saves immediately to this folder without opening another screen.",
                     color = Accent, fontSize = 12.sp)
                Button(onClick = {
                    folderLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    })
                }, modifier = Modifier.fillMaxWidth()) { Text("Choose folder") }
                if (exportFolder != null) {
                    OutlinedButton(onClick = {
                        exportFolder = null
                        appSettings.edit().remove("export_folder_uri").apply()
                        status = "Save location reset to Ask every time"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Ask every time instead") }
                }
                Text("Choose a folder on this device to keep exports local. Some external document providers may sync files to the cloud.",
                    color = Muted, fontSize = 12.sp)
                TextButton(onClick = { showSecurity = !showSecurity }) {
                    Text(if (showSecurity) "Privacy & limitations ▴" else "Privacy & limitations ▾")
                }
                if (showSecurity) {
                    Text("Redactor has no internet permission. Exported files contain new flattened pixels, but OCR can miss text. Always inspect outputs before sharing. Original files remain untouched.",
                        color = Muted, fontSize = 12.sp)
                }
                Button(onClick = { showSettings = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (validationFindings.isNotEmpty()) {
        val findings = validationFindings
        AlertDialog(
            onDismissRequest = { validationFindings = emptyList() },
            title = { Text("Additional text found") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The second offline scan found ${findings.size} unexpected text region(s). The file was NOT exported. These can include partial words or OCR artifacts. Allowed words are checked against their original locations.")
                    findings.forEach { item ->
                        Text("Page ${item.pageIndex + 1}: ‘${item.word.content}’", fontSize = 13.sp)
                    }
                    Text("Cover all highlighted regions, review affected pages again, then export. You can also dismiss and cover them manually.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = {
                val updated = pages.toMutableList()
                var nextHistory = undoHistory
                findings.groupBy { it.pageIndex }.forEach { (index, perPage) ->
                    val original = updated[index]
                    nextHistory = nextHistory.record(index, original)
                    val added = perPage.map { result ->
                        val b = result.word.bounds
                        val margin = maxOf(6f, b.height() * 0.25f)
                        RectF((b.left - margin).coerceAtLeast(0f), (b.top - margin).coerceAtLeast(0f),
                            (b.right + margin).coerceAtMost(original.bitmap.width.toFloat()),
                            (b.bottom + margin).coerceAtMost(original.bitmap.height.toFloat()))
                    }
                    updated[index] = original.copy(manualBoxes = original.manualBoxes + added)
                }
                pages = updated
                reviewed = reviewed - findings.map { it.pageIndex }.toSet()
                pageIndex = findings.first().pageIndex
                undoHistory = nextHistory
                showMasks = true
                validationFindings = emptyList()
                status = "Added ${findings.size} covers · review affected pages again"
            }) { Text("Cover found text") } },
            dismissButton = { TextButton(onClick = { validationFindings = emptyList() }) { Text("Review myself") } }
        )
    }

    if (exportError != null) {
        AlertDialog(onDismissRequest = { exportError = null },
            title = { Text("Export error") },
            text = { Text(exportError.orEmpty(), modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK") } })
    }

    if (showWhitelist) {
        ModalBottomSheet(onDismissRequest = { showWhitelist = false }) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allowed content · ${active.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Only the entries below may remain visible. Separate words or phrases using commas or new lines.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = active.allowedText,
                    onValueChange = { text -> editActive { it.copy(allowedText = text) } },
                    label = { Text("Allowed words and phrases") },
                    placeholder = { Text("Date, Approved, Appointment") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow time ranges", fontWeight = FontWeight.SemiBold)
                        Text("Create many allowed times with one rule", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = ranges.isNotEmpty(), onCheckedChange = { enabled ->
                        editActive { it.copy(timeRanges = if (enabled) listOf(TimeRangeRule()) else emptyList()) }
                    })
                }
                ranges.forEachIndexed { index, rule ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).clickable { rangeEditIndex = index }) {
                                Text("${TimeRangeRule.format(rule.startMinutes)} – ${TimeRangeRule.format(rule.endMinutes)}", fontSize = 14.sp)
                                Text("Every ${rule.everyMinutes} min · ${rule.count} times", color = Muted, fontSize = 12.sp)
                            }
                            TextButton(onClick = { rangeEditIndex = index }) { Text("Edit") }
                            TextButton(onClick = {
                                editActive { it.copy(timeRanges = it.timeRanges.filterIndexed { i, _ -> i != index }) }
                            }) { Text("Remove") }
                        }
                    }
                }
                TextButton(onClick = { rangeEditIndex = -1 }) { Text("+ Add another range") }
                HorizontalDivider()
                TextButton(onClick = { showSecurity = !showSecurity }) { Text(if (showSecurity) "Privacy & safety ▴" else "Privacy & safety ▾") }
                if (showSecurity) {
                    Text("No app internet permission. Output uses fresh image pixels, but OCR can miss text. Inspect each page before export. Erasing a mask reveals source content only at the selected location. Original documents and external backups remain untouched.",
                        color = Muted, fontSize = 12.sp)
                }
                Button(onClick = { showWhitelist = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showPresets) {
        ModalBottomSheet(onDismissRequest = { showPresets = false }) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.80f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Each preset stores its own allowed words and time rules.", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        showPresets = false
                        exportPresetsLauncher.launch("redactor-presets.json")
                    }, modifier = Modifier.weight(1f), enabled = !busy) {
                        Text("Export all")
                    }
                    OutlinedButton(onClick = {
                        showPresets = false
                        importPresetsLauncher.launch(arrayOf("application/json", "text/plain"))
                    }, modifier = Modifier.weight(1f), enabled = !busy) {
                        Text("Import backup")
                    }
                }
                Text("Backups include every preset's allowed words and time ranges. Choose local storage for privacy.",
                    color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newPresetName, onValueChange = { newPresetName = it },
                        label = { Text("New preset name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val created = RedactionPreset(id = UUID.randomUUID().toString(), name = newPresetName.trim())
                        newPresetName = ""
                        persist(presets + created, created.id, invalidate = true)
                        showPresets = false
                        showWhitelist = true
                    }, enabled = newPresetName.isNotBlank()) { Text("Create") }
                }
                presets.forEach { preset ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        if (renameId == preset.id) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = renameValue, onValueChange = { renameValue = it },
                                    label = { Text("Preset name") }, singleLine = true, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    persist(presets.map { if (it.id == preset.id) it.copy(name = renameValue.trim()) else it })
                                    renameId = null
                                }, enabled = renameValue.isNotBlank()) { Text("Save") }
                            }
                        } else {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(preset.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    if (preset.id == selectedId) Text("ACTIVE", fontSize = 10.sp, color = Accent)
                                }
                                Text("${preset.terms.size} entries · ${preset.timeRanges.sumOf { it.count }} generated times", color = Muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        persist(id = preset.id, invalidate = preset.id != selectedId)
                                        showPresets = false
                                    }) { Text("Select") }
                                    TextButton(onClick = {
                                        persist(id = preset.id, invalidate = preset.id != selectedId)
                                        showPresets = false
                                        showWhitelist = true
                                    }) { Text("Edit") }
                                    TextButton(onClick = { renameId = preset.id; renameValue = preset.name }) { Text("Rename") }
                                    TextButton(onClick = { deleteId = preset.id }, enabled = presets.size > 1) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (deleteId != null) {
        val doomed = presets.firstOrNull { it.id == deleteId }
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete preset?") },
            text = { Text("Remove ‘${doomed?.name.orEmpty()}’ and its saved rules? This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                val newList = presets.filterNot { it.id == deleteId }
                val newId = if (selectedId == deleteId) newList.first().id else selectedId
                persist(newList, newId, invalidate = newId != selectedId)
                deleteId = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }
        )
    }

    if (rangeEditIndex != null) {
        val index = rangeEditIndex!!
        RangeEditor(
            initial = ranges.getOrNull(index) ?: TimeRangeRule(),
            onDismiss = { rangeEditIndex = null },
            onSave = { rule ->
                editActive {
                    it.copy(timeRanges = if (index == -1) it.timeRanges + rule else it.timeRanges.toMutableList().apply { this[index] = rule })
                }
                rangeEditIndex = null
            }
        )
    }
}

@Composable
private fun RangeEditor(initial: TimeRangeRule, onDismiss: () -> Unit, onSave: (TimeRangeRule) -> Unit) {
    var start by remember(initial) { mutableStateOf(TimeRangeRule.format(initial.startMinutes)) }
    var end by remember(initial) { mutableStateOf(TimeRangeRule.format(initial.endMinutes)) }
    var every by remember(initial) { mutableIntStateOf(initial.everyMinutes) }
    val from = TimeRangeRule.parseClockTime(start)
    val to = TimeRangeRule.parseClockTime(end)
    val valid = from != null && to != null && from <= to
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allowed time range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("From") },
                    placeholder = { Text("9:00 AM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Until (inclusive)") },
                    placeholder = { Text("6:00 PM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Interval", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(5, 10, 15, 30, 60).forEach { n ->
                        FilterChip(selected = every == n, onClick = { every = n }, label = { Text("${n}m", fontSize = 11.sp) })
                    }
                }
                if (!valid) Text("Use a time such as 9:00 AM or 18:00. End must be after start.", color = Color(0xFFFFA4A4), fontSize = 12.sp)
                else Text("${(to!! - from!!) / every + 1} matching times will be allowed.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if (valid) onSave(TimeRangeRule(from!!, to!!, every)) }, enabled = valid) { Text("Save range") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RedactionToolButton(
    label: String,
    mode: PaintTool,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(37.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) Accent else Color(0xFF576168)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF292D32), contentColor = Fore
        ),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 1.dp)
    ) {
        Canvas(Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val ink = Fore
            if (mode == PaintTool.ERASE) {
                // Tilted eraser body, with a separate edge/diagonal.
                val eraser = Path().apply {
                    moveTo(0.15f * w, 0.64f * h)
                    lineTo(0.51f * w, 0.12f * h)
                    lineTo(0.90f * w, 0.43f * h)
                    lineTo(0.55f * w, 0.88f * h)
                    lineTo(0.33f * w, 0.88f * h)
                    close()
                }
                drawPath(eraser, ink, style = Stroke(width = 1.9.dp.toPx()))
                drawLine(ink, Offset(0.41f * w, 0.55f * h),
                    Offset(0.70f * w, 0.77f * h), strokeWidth = 1.9.dp.toPx())
            } else {
                // A solid redaction bar, rather than the former pencil glyph.
                drawRoundRect(ink, topLeft = Offset(0.08f * w, 0.43f * h),
                    size = Size(0.84f * w, 0.34f * h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                drawLine(ink, Offset(0.16f * w, 0.22f * h),
                    Offset(0.83f * w, 0.22f * h), strokeWidth = 1.5.dp.toPx())
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

private data class Viewport(val scale: Float, val left: Float, val top: Float) {
    fun toSource(point: Offset, width: Int, height: Int): PointF = PointF(
        ((point.x - left) / scale).coerceIn(0f, width.toFloat()),
        ((point.y - top) / scale).coerceIn(0f, height.toFloat())
    )
}

private fun viewport(viewW: Float, viewH: Float, bitmapW: Int, bitmapH: Int): Viewport {
    val scale = min(viewW / bitmapW, viewH / bitmapH)
    return Viewport(scale, (viewW - bitmapW * scale) / 2f, (viewH - bitmapH * scale) / 2f)
}

@Composable
private fun RedactionPreview(
    page: PageData,
    allowed: Set<String>,
    times: List<TimeRangeRule>,
    masksVisible: Boolean,
    tool: PaintTool,
    modifier: Modifier,
    onCover: (List<PointF>) -> Unit,
    onErase: (List<PointF>) -> Unit
) {
    val bitmap = page.bitmap
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val boxes = remember(page, allowed, times) { page.allBoxes(allowed, times) }
    val currentCover by rememberUpdatedState(onCover)
    val currentErase by rememberUpdatedState(onErase)
    // Key stroke feedback to the image, not PageData: erasing changes PageData
    // but should never interrupt the next gesture's visual trail.
    var stroke by remember(bitmap, tool) { mutableStateOf<List<Offset>>(emptyList()) }
    var completedStrokes by remember(bitmap, tool) { mutableIntStateOf(0) }
    LaunchedEffect(completedStrokes, bitmap, tool) {
        if (completedStrokes > 0) {
            delay(300)
            stroke = emptyList()
        }
    }

    Canvas(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .pointerInput(tool, bitmap) {
                // One gesture recognizer handles both taps and scribbles. Separate
                // detectors previously competed for touches on fast successive edits.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var points = listOf(down.position)
                    stroke = points
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.position != points.last()) {
                            points = points + change.position
                            stroke = points
                        }
                        if (!change.pressed) break
                        change.consume()
                    }
                    val v = viewport(size.width.toFloat(), size.height.toFloat(),
                        bitmap.width, bitmap.height)
                    val source = points.map { v.toSource(it, bitmap.width, bitmap.height) }
                    if (tool == PaintTool.COVER) currentCover(source) else currentErase(source)
                    completedStrokes++
                }
            }
    ) {
        val v = viewport(size.width, size.height, bitmap.width, bitmap.height)
        drawImage(
            image = image,
            dstOffset = IntOffset(v.left.roundToInt(), v.top.roundToInt()),
            dstSize = IntSize((bitmap.width * v.scale).roundToInt(), (bitmap.height * v.scale).roundToInt()),
            filterQuality = FilterQuality.Medium
        )
        for (box in boxes) {
            val p = Offset(v.left + box.left * v.scale, v.top + box.top * v.scale)
            val sz = Size(box.width() * v.scale, box.height() * v.scale)
            if (masksVisible) drawRect(Color.Black, topLeft = p, size = sz)
            else drawRect(Color(0xFFFF7474), topLeft = p, size = sz, style = Stroke(2.2f))
        }
        // Draw every live stroke, plus a brief 300ms confirmation after lifting.
        val trail = if (tool == PaintTool.ERASE) Color(0xFF68B7FF) else Color(0xFFFFC773)
        stroke.zipWithNext().forEach { (a, b) ->
            drawLine(trail.copy(alpha = 0.92f), a, b, strokeWidth = 5.dp.toPx())
        }
        stroke.forEach { drawCircle(trail.copy(alpha = 0.82f), radius = 3.dp.toPx(), center = it) }
    }
}
