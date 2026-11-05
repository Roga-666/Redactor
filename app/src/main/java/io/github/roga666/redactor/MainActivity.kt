package io.github.roga666.redactor

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
    var presets by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(store.selectedId()) }
    val active = presets.firstOrNull { it.id == selectedId } ?: presets.first()
    val allowed = remember(active.allowedText) { active.terms }
    val ranges = active.timeRanges

    var pages by remember { mutableStateOf<List<PageData>>(emptyList()) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var reviewed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var undoPage by remember { mutableStateOf<Pair<Int, PageData>?>(null) }
    var tool by remember { mutableStateOf(PaintTool.COVER) }
    var showMasks by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import a file to begin") }
    var fileName by remember { mutableStateOf("document") }
    var showWhitelist by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
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
            undoPage = null
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
        undoPage = pageIndex to before
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
                undoPage = null
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
                        status = "Scan complete · inspect every page"
                    } finally { scanner.close() }
                } catch (e: Exception) {
                    pages = emptyList()
                    status = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                } finally { busy = false }
            }
        }
    }

    fun export(uri: Uri, pdf: Boolean) {
        if (!ready) { status = "Review every page before export"; return }
        val originals = if (pdf) pages else listOf(pages[pageIndex])
        val termSnapshot = allowed.toSet()
        val timeSnapshot = ranges.toList()
        scope.launch {
            busy = true
            status = "Flattening and verifying offline…"
            val output = mutableListOf<Bitmap>()
            try {
                output += withContext(Dispatchers.Default) {
                    originals.map { SecureExporter.sanitize(it, termSnapshot, timeSnapshot) }
                }
                val scanner = LocalOcr()
                try {
                    output.forEachIndexed { idx, bitmap ->
                        val unexpected = scanner.findUnexpected(bitmap, originals[idx], termSnapshot, timeSnapshot)
                        if (unexpected.isNotEmpty()) {
                            val examples = unexpected.take(3).joinToString { "‘${it.content.take(16)}’" }
                            error("Output page ${idx + 1} still contains unapproved text ($examples). Cover it and retry")
                        }
                    }
                } finally { scanner.close() }
                withContext(Dispatchers.IO) {
                    if (pdf) SecureExporter.writeImageOnlyPdf(context.contentResolver, uri, output)
                    else SecureExporter.writePng(context.contentResolver, uri, output.single())
                }
                status = "Export complete · ${if (pdf) "PDF" else "PNG"}"
            } catch (e: Exception) {
                status = "Export blocked: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                output.forEach { if (!it.isRecycled) it.recycle() }
                busy = false
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) export(uri, true)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) export(uri, false)
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
                Text(status, modifier = Modifier.padding(start = if (busy) 7.dp else 0.dp), fontSize = 12.sp,
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
                        onCover = { rect -> editCurrentPage { it.copy(manualBoxes = it.manualBoxes + rect) } },
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
                FilterChip(selected = tool == PaintTool.COVER, onClick = { tool = PaintTool.COVER }, enabled = !busy,
                    label = { Text("✎ Cover") })
                FilterChip(selected = tool == PaintTool.ERASE, onClick = { tool = PaintTool.ERASE }, enabled = !busy,
                    label = { Text("⌫ Erase") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val state = undoPage
                    if (state != null && state.first == pageIndex && !busy) {
                        pages = pages.toMutableList().apply { this[pageIndex] = state.second }
                        reviewed = reviewed - pageIndex
                        undoPage = null
                    }
                }, enabled = undoPage?.first == pageIndex && !busy, contentPadding = PaddingValues(horizontal = 3.dp)) { Text("Undo") }
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
                ) { Text(if (pageIndex in reviewed) "✓ Reviewed" else "Review ✓") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { pdfLauncher.launch("${fileName.ifBlank { "document" }}-redacted.pdf") },
                    enabled = ready, modifier = Modifier.weight(1f)) { Text("Export PDF") }
                Button(onClick = { pngLauncher.launch("${fileName.ifBlank { "document" }}-page-${pageIndex + 1}.png") },
                    enabled = ready, modifier = Modifier.weight(1f)) { Text("Export PNG") }
            }
        }
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
    onCover: (RectF) -> Unit,
    onErase: (List<PointF>) -> Unit
) {
    val bitmap = page.bitmap
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val boxes = remember(page, allowed, times) { page.allBoxes(allowed, times) }
    val currentCover by rememberUpdatedState(onCover)
    val currentErase by rememberUpdatedState(onErase)
    var started by remember(page, tool) { mutableStateOf<Offset?>(null) }
    var current by remember(page, tool) { mutableStateOf<Offset?>(null) }
    var stroke by remember(page, tool) { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .pointerInput(tool, bitmap) {
                if (tool == PaintTool.ERASE) {
                    detectTapGestures(onTap = { point ->
                        val v = viewport(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                        currentErase(listOf(v.toSource(point, bitmap.width, bitmap.height)))
                    })
                }
            }
            .pointerInput(tool, bitmap) {
                detectDragGestures(
                    onDragStart = { started = it; current = it; stroke = listOf(it) },
                    onDrag = { event, _ ->
                        current = event.position
                        stroke = stroke + event.position
                        event.consume()
                    },
                    onDragEnd = {
                        val first = started
                        val last = current
                        if (first != null && last != null) {
                            val v = viewport(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                            if (tool == PaintTool.COVER) {
                                val a = v.toSource(first, bitmap.width, bitmap.height)
                                val b = v.toSource(last, bitmap.width, bitmap.height)
                                if (abs(a.x - b.x) >= 5f && abs(a.y - b.y) >= 5f) {
                                    currentCover(RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)))
                                }
                            } else {
                                currentErase(stroke.map { v.toSource(it, bitmap.width, bitmap.height) })
                            }
                        }
                        started = null; current = null; stroke = emptyList()
                    },
                    onDragCancel = { started = null; current = null; stroke = emptyList() }
                )
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
        if (started != null && current != null) {
            if (tool == PaintTool.COVER) {
                drawRect(Color(0xFFFFC773), topLeft = Offset(minOf(started!!.x, current!!.x), minOf(started!!.y, current!!.y)),
                    size = Size(abs(started!!.x - current!!.x), abs(started!!.y - current!!.y)), style = Stroke(3f))
            } else {
                stroke.forEach { drawCircle(Accent.copy(alpha = 0.55f), radius = 7f, center = it) }
            }
        }
    }
}
