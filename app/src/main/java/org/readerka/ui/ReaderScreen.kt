package org.readerka.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
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

/** The translator is external; Readerka itself remains offline and needs no network permission. */
fun translationIntent(text: String): Intent = Intent(Intent.ACTION_VIEW,
    Uri.Builder().scheme("https").authority("translate.google.com").appendPath("")
        .appendQueryParameter("sl", "auto").appendQueryParameter("tl", "ru")
        .appendQueryParameter("text", text).appendQueryParameter("op", "translate").build())

@OptIn(FlowPreview::class)
@Composable fun ReaderScreen(session: ReaderSession?, data: LibrarySnapshot, vm: LibraryViewModel, wide: Boolean, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
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
        var edited by remember { mutableStateOf<Entry?>(null) }
        var showPanel by rememberSaveable { mutableStateOf(false) }
        var bookmarksTab by rememberSaveable { mutableStateOf(false) }
        var fitPage by rememberSaveable { mutableStateOf(false) }
        var quickPages by rememberSaveable { mutableStateOf(false) }
        var jump by remember { mutableStateOf(false) }
        var bookmark by remember { mutableStateOf<Bookmark?>(null) }
        var pageInput by remember { mutableStateOf("") }
        var search by remember { mutableStateOf(false) }
        val currentPage by remember { derivedStateOf { list.firstVisibleItemIndex.coerceIn(0, session.engine.pageCount - 1) } }
        val docEntries = data.entries.filter { it.documentId == session.document.id }
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
            list.scrollToItem(session.page, offset)
        }
        LaunchedEffect(list, session.engine, fitPage, viewport) {
            if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.debounce(350).distinctUntilChanged().collect { (page, offset) ->
                vm.position(session.document, page, (offset / pageHeight(page) * 10_000).toInt().coerceIn(0, 10_000))
            }
        }
        fun go(page: Int) {
            selection?.view?.clearSelection(); selection = null
            // A focused native canvas otherwise asks the lazy list to reveal its old item
            // while the explicit page jump is being measured.
            (activity?.currentFocus as? PdfPageView)?.clearFocus()
            vm.open(session.document, page = page.coerceIn(0, session.engine.pageCount - 1))
        }
        val keyHandler by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
            if (edited != null || bookmark != null || jump || search) false
            else {
                val target = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> currentPage + 1
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> currentPage - 1
                    KeyEvent.KEYCODE_MOVE_HOME -> 0
                    KeyEvent.KEYCODE_MOVE_END -> session.engine.pageCount - 1
                    else -> null
                }
                if (target == null || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else { if (event.action == KeyEvent.ACTION_DOWN) go(target); true }
            }
        })
        DisposableEffect(activity, session.engine) {
            val window = activity?.window
            val previous = window?.callback
            val handler = previous?.let {
                object : Window.Callback by it {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyHandler(event) || it.dispatchKeyEvent(event)
                }
            }
            if (handler != null) window.callback = handler
            onDispose { if (window?.callback === handler) window?.callback = previous }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(session.document.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(8.dp), maxLines = 1)
                TextButton(onClick = { quickPages = !quickPages }, modifier = Modifier.testTag("reader-page-counter")) { Text(stringResource(R.string.page, currentPage + 1, session.engine.pageCount)) }
                IconButton(onClick = { onFullscreen(!fullscreen) }) { Icon(if (fullscreen) Icons.Default.Close else Icons.Default.MoreVert, stringResource(R.string.fullscreen)) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showPanel = !showPanel || bookmarksTab; bookmarksTab = false }, modifier = Modifier.testTag("reader-quotes-button")) { Text(stringResource(R.string.quotes_in_pdf)) }
                TextButton(onClick = { showPanel = !showPanel || !bookmarksTab; bookmarksTab = true }, modifier = Modifier.testTag("reader-bookmarks-button")) { Text(stringResource(R.string.bookmarks)) }
                IconButton(onClick = {
                    bookmark = Bookmark(UUID.randomUUID().toString(), session.document.id, currentPage, context.getString(R.string.source_page, currentPage + 1), offset = if (session.page == currentPage) session.offset else 0)
                }) { Icon(Icons.Default.Star, stringResource(R.string.bookmark)) }
                IconButton(onClick = { search = true }) { Icon(Icons.Default.Search, stringResource(R.string.search_pdf)) }
                TextButton(onClick = { fitPage = !fitPage; go(currentPage) }) { Text(stringResource(if (fitPage) R.string.fit_width else R.string.fit_page)) }
            }
            if (!fullscreen && selection == null) Text(stringResource(R.string.reader_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 2)
            selection?.let { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(s.text, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            runCatching { context.startActivity(translationIntent(s.text)) }
                                .onFailure { scope.launch { vm.messages.emit(context.getString(R.string.translation_unavailable)) } }
                        }) { Text(stringResource(R.string.translate)) }
                        TextButton(onClick = {
                            edited = Entry(UUID.randomUUID().toString(), s.text.lineSequence().first().take(80), s.text,
                                documentId = session.document.id, anchor = s.anchor, type = NoteType.Quote, color = vm.preferences.value.defaultColor)
                        }) { Text(stringResource(R.string.comment)) }
                        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.source_quote), s.text)) }) { Text(stringResource(R.string.copy)) }
                        IconButton(onClick = { s.view.clearSelection(); selection = null }) { Icon(Icons.Default.Close, stringResource(R.string.clear_selection)) }
                        ColorPalette(null) { color -> vm.highlight(s.text, s.anchor, color); s.view.clearSelection(); selection = null }
                    }
                }
            }
            Row(Modifier.weight(1f)) {
                if (!wide && showPanel) {
                    ReaderPanel(session.document, docEntries, docBookmarks, data, vm, bookmarksTab, { bookmarksTab = it },
                        { edited = it }, { vm.source(it); showPanel = false }, { vm.openBookmark(it); showPanel = false }, { showPanel = false }, Modifier.fillMaxSize())
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewport = it }) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("pdf-pages"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(session.engine.pageCount, key = { it }) { index ->
                                PdfPage(session, index, maxWidth, maxHeight, fitPage, docEntries.filter { it.anchor?.page == index },
                                    { text, anchor, view -> selection?.view?.takeIf { it !== view }?.clearSelection(); selection = Selection(text, anchor, view) },
                                    { edited = it }, { quickPages = !quickPages }, { keyHandler(it) })
                            }
                        }
                    }
                    if (wide && showPanel) {
                        VerticalDivider()
                        ReaderPanel(session.document, docEntries, docBookmarks, data, vm, bookmarksTab, { bookmarksTab = it },
                            { edited = it }, { vm.source(it) }, { vm.openBookmark(it) }, { showPanel = false }, Modifier.width(350.dp).fillMaxHeight())
                    }
                }
            }
            if (quickPages) {
                PageStrip(currentPage, session.engine.pageCount, ::go, {
                    pageInput = (currentPage + 1).toString(); jump = true
                }, { quickPages = false })
            }
        }
        if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text(stringResource(R.string.jump_to_page)) }, text = { OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.page_number)) }) },
            confirmButton = { TextButton(enabled = (pageInput.toIntOrNull() ?: 0) in 1..session.engine.pageCount, onClick = { go(pageInput.toInt() - 1); jump = false }) { Text(stringResource(R.string.go)) } }, dismissButton = { TextButton(onClick = { jump = false }) { Text(stringResource(R.string.cancel)) } })
        bookmark?.let { b -> BookmarkEditor(b, data, { bookmark = null }, { vm.saveBookmark(it) }) }
        edited?.let { e ->
            EntryEditor(e, data, { edited = null }, { entry, tags, members, readerMembers ->
                vm.saveEntry(entry, tags, members, readerMembers); selection?.view?.clearSelection(); selection = null
            }, if (data.entries.any { it.id == e.id }) ({
                vm.deleteEntries(setOf(e.id)); selection?.view?.clearSelection(); selection = null
            }) else null)
        }
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
                IconButton(enabled = page > 0, onClick = { onPage(page - 1) }) { Icon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.previous_page)) }
                TextButton(onClick = onNumber, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.page, preview.roundToInt() + 1, count)) }
                IconButton(enabled = page < count - 1, onClick = { onPage(page + 1) }) { Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.next_page)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            Slider(value = preview, onValueChange = { dragging = true; preview = it }, enabled = count > 1,
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(), onValueChangeFinished = {
                    onPage(preview.roundToInt()); dragging = false
                }, modifier = Modifier.fillMaxWidth().testTag("page-slider"))
        }
    }
}

@Composable private fun PdfPage(session: ReaderSession, index: Int, availableWidth: androidx.compose.ui.unit.Dp, availableHeight: androidx.compose.ui.unit.Dp, fitPage: Boolean,
    highlights: List<Entry>, onSelection: (String, Anchor, PdfPageView) -> Unit, onHighlight: (Entry) -> Unit, onCenterTap: () -> Unit, onKey: (KeyEvent) -> Boolean) {
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
        Text(stringResource(R.string.source_page, index + 1), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).testTag("pdf-page-$index")) {
            val bitmap = image
            if (bitmap == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (failed) {
                        Text(stringResource(R.string.page_failed))
                        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry_page)) }
                    } else { CircularProgressIndicator(); Text(stringResource(R.string.loading_page)) }
                }
            } else AndroidView(factory = { context -> PdfPageView(context) },
                onReset = { it.reset() }, onRelease = { it.reset() },
                update = { view ->
                    view.bitmap = bitmap; view.pageText = text; view.highlights = highlights
                    view.onSelection = { quote, anchor -> onSelection(quote, anchor, view) }
                    view.onHighlight = onHighlight; view.onCenterTap = onCenterTap
                    view.onNavigationKey = onKey
                }, modifier = Modifier.fillMaxSize())
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
