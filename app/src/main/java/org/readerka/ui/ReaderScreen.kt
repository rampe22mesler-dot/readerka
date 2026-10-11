package org.readerka.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.readerka.R
import org.readerka.data.*
import org.readerka.pdf.*
import java.util.UUID
import kotlin.math.roundToInt

private data class Selection(val text: String, val anchor: Anchor, val view: PdfPageView)
private data class PdfMatch(val text: String, val anchor: Anchor)

@OptIn(FlowPreview::class)
@Composable fun ReaderScreen(session: ReaderSession?, data: LibrarySnapshot, vm: LibraryViewModel, wide: Boolean, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onWorkspaceShortcut: (KeyEvent) -> Boolean = { false }, allowPageKeys: Boolean = true) {
    if (session == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_document_open)) }; return }
    key(session.engine) {
        val list = rememberLazyListState(session.page)
        val context = LocalContext.current
        val activity = remember(context) {
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()
        }
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        var selection by remember { mutableStateOf<Selection?>(null) }
        val workspace by vm.workspace.collectAsState()
        var positioned by remember(session.token) { mutableStateOf(false) }
        var showPanel by rememberSaveable { mutableStateOf(false) }
        var conspTab by rememberSaveable { mutableStateOf(false) }
        var createConsp by remember { mutableStateOf(false) }
        var addConsp by remember { mutableStateOf<Selection?>(null) }
        var bookmarksTab by rememberSaveable { mutableStateOf(false) }
        var fitPage by rememberSaveable { mutableStateOf(false) }
        var quickPages by rememberSaveable { mutableStateOf(false) }
        var jump by remember { mutableStateOf(false) }
        var bookmark by remember { mutableStateOf<Bookmark?>(null) }
        var pageInput by remember { mutableStateOf("") }
        var search by remember { mutableStateOf(false) }
        var properties by remember { mutableStateOf(false) }
        var translation by rememberSaveable { mutableStateOf<String?>(null) }
        val requestedTool by vm.readerTool.collectAsState()
        val currentPage by remember { derivedStateOf { list.firstVisibleItemIndex.coerceIn(0, session.engine.pageCount - 1) } }
        LaunchedEffect(requestedTool, allowPageKeys) {
            if (!allowPageKeys) return@LaunchedEffect
            when (requestedTool) {
                ReaderTool.Search -> search = true
                ReaderTool.Quotes -> { showPanel = true; bookmarksTab = false; conspTab = false }
                ReaderTool.Bookmarks -> { showPanel = true; bookmarksTab = true; conspTab = false }
                ReaderTool.Consp -> { showPanel = true; conspTab = true }
                ReaderTool.Page -> { pageInput = (currentPage + 1).toString(); jump = true }
                null -> return@LaunchedEffect
            }
            vm.consumeReaderTool()
        }
        val docEntries = data.entries.filter { it.documentId == session.document.id && it.kind != EntryKind.Consp }
        val docBookmarks = data.bookmarks.filter { it.documentId == session.document.id }
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        suspend fun pageHeight(index: Int): Float {
            val dim = withContext(Dispatchers.IO) { session.engine.dimensions(index) }
            val width = if (fitPage) minOf(viewport.width.toFloat(), (viewport.height - with(density) { 24.dp.toPx() }).coerceAtLeast(1f) * dim.first / dim.second) else viewport.width.toFloat()
            return width * dim.second / dim.first + with(density) { 24.dp.toPx() }
        }
        // Keep scrolling effects outside BoxWithConstraints' subcomposition/measurement.
        LaunchedEffect(session.token, viewport.width > 0 && viewport.height > 0) {
            if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
            val height = pageHeight(session.page)
            val offset = session.anchor?.rects?.firstOrNull()?.top?.let { (it * (height - with(density) { 24.dp.toPx() }) - with(density) { 64.dp.toPx() }).toInt().coerceAtLeast(0) }
                ?: (session.offset * height / 10_000).toInt()
            withFrameNanos { }
            // Remeasurement touches AndroidViews: always return to the Android UI dispatcher
            // after asynchronous PDF dimensions and a frame-clock suspension.
            withContext(Dispatchers.Main) { list.scrollToItem(session.page, offset); positioned = true }
        }
        LaunchedEffect(list, session.engine, fitPage, viewport) {
            if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
            snapshotFlow { if (positioned) list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset else null }.filterNotNull()
                .map { (page, offset) -> page to (offset / pageHeight(page) * 10_000).toInt().coerceIn(0, 10_000) }
                .distinctUntilChanged().onEach { (page, offset) -> vm.trackPosition(session.document.id, page, offset, session.pane, session.tabId) }
                .debounce(350).collect { (page, offset) -> vm.position(session.document, page, offset) }
        }
        fun go(page: Int) {
            selection?.view?.clearSelection(); selection = null
            // A focused native canvas otherwise asks the lazy list to reveal its old item
            // while the explicit page jump is being measured.
            (activity?.currentFocus as? PdfPageView)?.clearFocus()
            vm.open(session.document, page = page.coerceIn(0, session.engine.pageCount - 1), recordHistory = false, pane = session.pane, tabId = session.tabId)
        }
        val keyHandler by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
            if (!allowPageKeys || bookmark != null || jump || search || translation != null) false
            else {
                val target = AppShortcuts.pdfKeys[event.keyCode]?.let { when (it) { Int.MIN_VALUE -> 0; Int.MAX_VALUE -> session.engine.pageCount - 1; else -> currentPage + it } }
                if (target == null || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else { if (event.action == KeyEvent.ACTION_DOWN) go(target); true }
            }
        })
        DisposableEffect(session.engine, allowPageKeys) {
            val handler: (KeyEvent) -> Boolean = { keyHandler(it) }
            if (allowPageKeys) vm.readerKeyHandler = handler
            onDispose { if (vm.readerKeyHandler === handler) vm.readerKeyHandler = null }
        }
        val selectionActions: @Composable () -> Unit = { selection?.let { s ->
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.medium) { Column(Modifier.widthIn(max = 680.dp).padding(8.dp)) {
                    Text(s.text, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.source_quote), s.text))
                            translation = s.text
                        }) { Text(stringResource(R.string.translate)) }
                        TextButton(onClick = {
                            vm.editBeside(Entry(UUID.randomUUID().toString(), s.text.lineSequence().first().take(80), s.text,
                                documentId = session.document.id, anchor = s.anchor, type = NoteType.Quote, color = vm.preferences.value.defaultColor), session.pane)
                        }) { Text(stringResource(R.string.comment)) }
                        TextButton(onClick = { vm.focusPane(session.pane); addConsp = s }) { Text("Добавить в конспект…") }
                        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.source_quote), s.text)) }) { Text(stringResource(R.string.copy)) }
                        IconButton(onClick = { s.view.clearSelection(); selection = null }) { Icon(Outline.Close, stringResource(R.string.clear_selection)) }
                        ColorPalette(null) { color -> vm.highlight(s.text, s.anchor, color, session.document); s.view.clearSelection(); selection = null }
                    }
                }
            } } }
        Column(Modifier.fillMaxSize()) {
            var help by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkspaceAction(Outline.Note, "Цитаты в PDF", { showPanel = !showPanel || bookmarksTab || conspTab; bookmarksTab = false; conspTab = false }, Modifier.testTag("reader-quotes-button"), selected = showPanel && !bookmarksTab && !conspTab)
                WorkspaceAction(Outline.Bookmark, "Закладки", { showPanel = !showPanel || !bookmarksTab || conspTab; bookmarksTab = true; conspTab = false }, Modifier.testTag("reader-bookmarks-button"), selected = showPanel && bookmarksTab && !conspTab)
                WorkspaceAction(Outline.Plus, "Добавить закладку", { bookmark = Bookmark(UUID.randomUUID().toString(), session.document.id, currentPage, context.getString(R.string.source_page, currentPage + 1), offset = session.offset) })
                WorkspaceAction(Outline.Edit, "Конспекты книги", { showPanel = !showPanel || !conspTab; conspTab = true }, Modifier.testTag("reader-consp-button"), selected = showPanel && conspTab)
                WorkspaceAction(Outline.Plus, "Создать конспект", { vm.focusPane(session.pane); createConsp = true }, Modifier.testTag("reader-create-consp"))
                WorkspaceAction(Outline.Search, "Поиск в PDF", { search = true })
                WorkspaceAction(Outline.Settings, "Свойства PDF", { properties = true })
                TextButton(onClick = { quickPages = !quickPages }, modifier = Modifier.heightIn(min = 48.dp).testTag("reader-page-counter")) { Text(stringResource(R.string.page, currentPage + 1, session.engine.pageCount)) }
                TextButton(onClick = { fitPage = !fitPage; go(currentPage) }) { Text(stringResource(if (fitPage) R.string.fit_width else R.string.fit_page)) }
                WorkspaceAction(Outline.Split, "Открыть справа", { vm.openRight(WorkspaceTab(session.tabId, session.document.id, WorkspaceKind.Pdf, page = currentPage, offset = session.offset), session.pane) })
                WorkspaceAction(Outline.Fullscreen, if (fullscreen) "Выйти из полного экрана" else "Полный экран", { onFullscreen(!fullscreen) })
                var menu by remember { mutableStateOf(false) }
                Box {
                    WorkspaceAction(Outline.More, "Действия PDF", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Справка по чтению") }, onClick = { menu = false; help = true })

                    }
                }
            }
            HorizontalDivider()
            if (help) HelpDialog { help = false }
            if (createConsp) CreateConspDialog(data, session.document.id, null, vm) { createConsp = false }
            addConsp?.let { selected -> ConspQuoteDialog(selected.text, session.document, selected.anchor.page, data, vm) { addConsp = null } }
            Row(Modifier.weight(1f)) {
                if (!wide && showPanel && conspTab) {
                    BookConspPanel(session.document, data, vm, session.pane, { showPanel = false }, Modifier.fillMaxSize())
                } else if (!wide && showPanel) {
                    ReaderPanel(session.document, docEntries, docBookmarks, data, vm, bookmarksTab, { bookmarksTab = it },
                        { vm.editBeside(it, session.pane) }, { vm.source(it); showPanel = false }, { vm.openBookmark(it); showPanel = false }, { showPanel = false }, Modifier.fillMaxSize())
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewport = it }) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("pdf-pages"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(session.engine.pageCount, key = { it }) { index ->
                                PdfPage(session, index, maxWidth, maxHeight, fitPage, docEntries.filter { it.anchor?.page == index },
                                    { text, anchor, view -> selection?.view?.takeIf { it !== view }?.clearSelection(); selection = Selection(text, anchor, view) },
                                    { vm.editBeside(it, session.pane) }, { quickPages = !quickPages }, { keyHandler(it) }, { vm.focusPane(session.pane) })
                            }
                        }
                        Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).testTag("selection-actions")) { selectionActions() }
                    }
                    if (wide && showPanel) {
                        ResizeHandle("resize-structure", { vm.layout(structureWidth = vm.workspace.value.structureWidth - it) })
                        if (conspTab) BookConspPanel(session.document, data, vm, session.pane, { showPanel = false }, Modifier.width(workspace.structureWidth.dp).fillMaxHeight())
                        else ReaderPanel(session.document, docEntries, docBookmarks, data, vm, bookmarksTab, { bookmarksTab = it },
                            { vm.editEntry(it, 1 - session.pane); vm.layout(split = true) }, { vm.source(it) }, { vm.openBookmark(it) }, { showPanel = false }, Modifier.width(workspace.structureWidth.dp).fillMaxHeight())
                    }
                }
            }
            if (quickPages) {
                PageStrip(currentPage, session.engine.pageCount, ::go, {
                    pageInput = (currentPage + 1).toString(); jump = true
                }, { quickPages = false })
            }
        }
        translation?.let { TranslationOverlay(it) { translation = null } }
        if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text(stringResource(R.string.jump_to_page)) }, text = { OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.page_number)) }) },
            confirmButton = { TextButton(enabled = (pageInput.toIntOrNull() ?: 0) in 1..session.engine.pageCount, onClick = { go(pageInput.toInt() - 1); jump = false }) { Text(stringResource(R.string.go)) } }, dismissButton = { TextButton(onClick = { jump = false }) { Text(stringResource(R.string.cancel)) } })
        bookmark?.let { b -> BookmarkEditor(b, data, { bookmark = null }, { vm.saveBookmark(it) }) }

        if (properties) DocumentEditor(data.documents.firstOrNull { it.id == session.document.id } ?: session.document, data, { properties = false }, vm::saveDocument)
        if (search) PdfSearchDialog(session, { search = false }, { anchor -> vm.open(session.document, anchor); search = false })
    }
}

@Composable private fun PageStrip(page: Int, count: Int, onPage: (Int) -> Unit, onNumber: () -> Unit, onClose: () -> Unit) {
    var preview by remember { mutableFloatStateOf(page.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(page) { if (!dragging) preview = page.toFloat() }
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().testTag("page-strip")) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = page > 0, onClick = { onPage(page - 1) }) { Icon(Outline.Left, stringResource(R.string.previous_page)) }
                TextButton(onClick = onNumber, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.page, preview.roundToInt() + 1, count)) }
                IconButton(enabled = page < count - 1, onClick = { onPage(page + 1) }) { Icon(Outline.Right, stringResource(R.string.next_page)) }
                IconButton(onClick = onClose) { Icon(Outline.Close, stringResource(R.string.close)) }
            }
            Slider(value = preview, onValueChange = { dragging = true; preview = it }, enabled = count > 1,
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(), onValueChangeFinished = {
                    onPage(preview.roundToInt()); dragging = false
                }, modifier = Modifier.fillMaxWidth().testTag("page-slider"))
        }
    }
}

@Composable private fun PdfPage(session: ReaderSession, index: Int, availableWidth: androidx.compose.ui.unit.Dp, availableHeight: androidx.compose.ui.unit.Dp, fitPage: Boolean,
    highlights: List<Entry>, onSelection: (String, Anchor, PdfPageView) -> Unit, onHighlight: (Entry) -> Unit, onCenterTap: () -> Unit, onKey: (KeyEvent) -> Boolean, onFocus: () -> Unit) {
    val density = LocalDensity.current
    val dimensions by produceState<Pair<Int, Int>?>(null, session.engine, index) {
        value = withContext(Dispatchers.IO) { runCatching { session.engine.dimensions(index) }.getOrNull() }
    }
    val ratio = dimensions?.let { it.first.toFloat() / it.second } ?: .707f
    val pageWidth = if (fitPage) minOf(availableWidth, (availableHeight - 24.dp).coerceAtLeast(1.dp) * ratio) else availableWidth
    val renderWidth = with(density) { pageWidth.toPx().toInt().coerceIn(64, 2200) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember(session.engine, index, renderWidth, retry) { mutableStateOf(false) }
    val image by produceState<android.graphics.Bitmap?>(null, session.engine, index, renderWidth, retry) {
        try {
            // Rendering has a separate lock and bounded cache. Text extraction never delays the image.
            value = withContext(Dispatchers.IO) { session.engine.render(index, renderWidth) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
    }
    val text by produceState<PageText?>(null, session.engine, index, retry) {
        try { value = withContext(Dispatchers.IO) { session.engine.text(index) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* The page remains readable without its text layer. */ }
    }
    Column(Modifier.width(pageWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).testTag("pdf-page-$index")) {
            val bitmap = image
            if (bitmap == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (failed) {
                        Text(stringResource(R.string.page_failed))
                        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry_page)) }
                    } else { CircularProgressIndicator(); Text(stringResource(R.string.loading_page)) }
                }
            } else {
                // PDF pixels do not depend on the chrome theme. Keep the live native canvas
                // across theme changes so lazy-view release cannot clear the visible page.
                AndroidView(factory = { context -> PdfPageView(context) },
                onReset = { it.reset() }, onRelease = { it.reset() },
                update = { view ->
                    view.bitmap = bitmap; view.pageText = text; view.highlights = highlights
                    view.onSelection = { quote, anchor -> onSelection(quote, anchor, view) }
                    view.onHighlight = onHighlight; view.onCenterTap = onCenterTap
                    view.onNavigationKey = onKey
                    view.onPaneFocus = onFocus
                }, modifier = Modifier.fillMaxSize().clipToBounds())
            }
            if (bitmap != null && text?.glyphs?.isEmpty() == true) Text(stringResource(R.string.no_text_layer),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
    }
}

@Composable private fun PdfSearchDialog(session: ReaderSession, onDismiss: () -> Unit, onResult: (Anchor) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PdfMatch>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.search_pdf)) }, text = {
        Column(Modifier.heightIn(max = 400.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search)) })
            TextButton(enabled = query.isNotBlank(), onClick = {
                job?.cancel(); job = scope.launch {
                    searching = true
                    try {
                        results = withContext(Dispatchers.IO) {
                            val found = mutableListOf<PdfMatch>()
                            for (page in 0 until session.engine.pageCount) {
                                ensureActive(); val text = session.engine.text(page)
                                for (anchor in text.matches(query)) found += PdfMatch(text.text.substring((anchor.start - 40).coerceAtLeast(0), (anchor.end + 80).coerceAtMost(text.text.length)).trim(), anchor)
                            }; found
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { results = emptyList() }
                    finally { searching = false }
                }
            }) { Text(stringResource(R.string.search)) }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (results?.isEmpty() == true) Text(stringResource(R.string.no_search_results))
            LazyColumn { items(results.orEmpty()) { r -> ListItem(headlineContent = { Text(stringResource(R.string.search_result, r.anchor.page + 1, r.text)) }, modifier = Modifier.clickable { onResult(r.anchor) }) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
}
