package org.readerka.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.readerka.R
import org.readerka.data.*
import org.readerka.pdf.*
import java.util.UUID

private data class Selection(val text: String, val anchor: Anchor, val view: PdfPageView)
private data class PageLoad(val text: PageText, val bitmap: android.graphics.Bitmap)
private data class PdfMatch(val text: String, val anchor: Anchor)

@OptIn(FlowPreview::class)
@Composable fun ReaderScreen(session: ReaderSession?, data: LibrarySnapshot, vm: LibraryViewModel, wide: Boolean, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    if (session == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_document_open)) }; return }
    key(session.engine) {
        val list = rememberLazyListState(session.page)
        val context = LocalContext.current
        val density = LocalDensity.current
        var selection by remember { mutableStateOf<Selection?>(null) }
        var edited by remember { mutableStateOf<Entry?>(null) }
        var showPanel by rememberSaveable { mutableStateOf(false) }
        var bookmarksTab by rememberSaveable { mutableStateOf(false) }
        var fitPage by rememberSaveable { mutableStateOf(false) }
        var jump by remember { mutableStateOf(false) }
        var bookmark by remember { mutableStateOf(false) }
        var pageInput by remember { mutableStateOf("") }
        var bookmarkTitle by remember { mutableStateOf("") }
        var bookmarkComment by remember { mutableStateOf("") }
        var search by remember { mutableStateOf(false) }
        val currentPage by remember { derivedStateOf { list.firstVisibleItemIndex.coerceIn(0, session.engine.pageCount - 1) } }
        val docEntries = data.entries.filter { it.documentId == session.document.id }
        val docBookmarks = data.bookmarks.filter { it.documentId == session.document.id }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(session.document.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(max = 230.dp).padding(8.dp), maxLines = 1)
                TextButton(onClick = { pageInput = (currentPage + 1).toString(); jump = true }) { Text(stringResource(R.string.page, currentPage + 1, session.engine.pageCount)) }
                IconButton(onClick = { search = true }) { Icon(Icons.Default.Search, stringResource(R.string.search_pdf)) }
                IconButton(onClick = { bookmarkTitle = (currentPage + 1).toString(); bookmarkComment = ""; bookmark = true }) { Icon(Icons.Default.Star, stringResource(R.string.bookmark)) }
                TextButton(onClick = { fitPage = !fitPage }) { Text(stringResource(if (fitPage) R.string.fit_width else R.string.fit_page)) }
                IconButton(onClick = { showPanel = !showPanel }) { Icon(Icons.Default.Menu, stringResource(if (showPanel) R.string.reading_mode else R.string.research_mode)) }
                TextButton(onClick = { onFullscreen(!fullscreen) }) { Text(stringResource(if (fullscreen) R.string.close else R.string.fullscreen)) }
            }
            if (!fullscreen && selection == null) Text(stringResource(R.string.selection_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 2)
            selection?.let { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(s.text, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        ColorPalette(null) { color -> vm.highlight(s.text, s.anchor, color); s.view.clearSelection(); selection = null }
                        TextButton(onClick = {
                            edited = Entry(UUID.randomUUID().toString(), s.text.lineSequence().first().take(80), s.text,
                                documentId = session.document.id, anchor = s.anchor, type = NoteType.Quote, color = vm.preferences.value.defaultColor)
                        }) { Text(stringResource(R.string.comment)) }
                        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.source_quote), s.text)) }) { Text(stringResource(R.string.copy)) }
                        IconButton(onClick = { s.view.clearSelection(); selection = null }) { Icon(Icons.Default.Close, stringResource(R.string.clear_selection)) }
                    }
                }
            }
            Row(Modifier.weight(1f)) {
                if (!wide && showPanel) {
                    ReaderPanel(docEntries, docBookmarks, data, bookmarksTab, { bookmarksTab = it }, { edited = it }, { vm.source(it) }, { vm.open(session.document, page = it.page); showPanel = false }, vm::deleteBookmark, Modifier.fillMaxSize())
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val widthPx = with(density) { maxWidth.toPx() }
                        val heightPx = with(density) { maxHeight.toPx() }
                        suspend fun pageHeight(index: Int): Float {
                            val dim = withContext(Dispatchers.IO) { session.engine.dimensions(index) }
                            val width = if (fitPage) minOf(widthPx, (heightPx - with(density) { 32.dp.toPx() }) * dim.first / dim.second) else widthPx
                            return width * dim.second / dim.first + with(density) { 32.dp.toPx() }
                        }
                        LaunchedEffect(session.token, widthPx, fitPage) {
                            val height = pageHeight(session.page)
                            val offset = session.anchor?.rects?.firstOrNull()?.top?.let { (it * height - with(density) { 64.dp.toPx() }).toInt().coerceAtLeast(0) }
                                ?: (session.offset * height / 10_000).toInt()
                            list.scrollToItem(session.page, offset)
                        }
                        LaunchedEffect(list, session.engine, fitPage, widthPx) {
                            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.debounce(500).distinctUntilChanged().collect { (page, offset) ->
                                vm.position(session.document, page, (offset / pageHeight(page) * 10_000).toInt())
                            }
                        }
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(session.engine.pageCount, key = { it }) { index ->
                                PdfPage(session, index, maxWidth, maxHeight, fitPage, docEntries.filter { it.anchor?.page == index },
                                    { text, anchor, view -> selection = Selection(text, anchor, view) }, { edited = it })
                            }
                        }
                    }
                    if (wide && showPanel) {
                        VerticalDivider()
                        ReaderPanel(docEntries, docBookmarks, data, bookmarksTab, { bookmarksTab = it }, { edited = it }, { vm.source(it) }, { vm.open(session.document, page = it.page) }, vm::deleteBookmark, Modifier.width(320.dp).fillMaxHeight())
                    }
                }
            }
        }
        if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text(stringResource(R.string.jump_to_page)) }, text = { OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.page_number)) }) },
            confirmButton = { TextButton(enabled = (pageInput.toIntOrNull() ?: 0) in 1..session.engine.pageCount, onClick = { vm.open(session.document, page = pageInput.toInt() - 1); jump = false }) { Text(stringResource(R.string.go)) } }, dismissButton = { TextButton(onClick = { jump = false }) { Text(stringResource(R.string.cancel)) } })
        if (bookmark) AlertDialog(onDismissRequest = { bookmark = false }, title = { Text(stringResource(R.string.bookmark)) }, text = { Column {
            OutlinedTextField(bookmarkTitle, { bookmarkTitle = it }, label = { Text(stringResource(R.string.title)) })
            OutlinedTextField(bookmarkComment, { bookmarkComment = it }, label = { Text(stringResource(R.string.comment)) })
        } }, confirmButton = { TextButton(enabled = bookmarkTitle.isNotBlank(), onClick = { vm.bookmark(currentPage, bookmarkTitle, bookmarkComment); bookmark = false }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { bookmark = false }) { Text(stringResource(R.string.cancel)) } })
        edited?.let { e -> EntryEditor(e, data, { edited = null }, { entry, tags, members -> vm.saveEntry(entry, tags, members); selection?.view?.clearSelection(); selection = null }) }
        if (search) PdfSearchDialog(session, { search = false }, { anchor -> vm.open(session.document, anchor); search = false })
    }
}

@Composable private fun PdfPage(session: ReaderSession, index: Int, availableWidth: androidx.compose.ui.unit.Dp, availableHeight: androidx.compose.ui.unit.Dp, fitPage: Boolean,
    highlights: List<Entry>, onSelection: (String, Anchor, PdfPageView) -> Unit, onHighlight: (Entry) -> Unit) {
    val density = LocalDensity.current
    val dimensions by produceState<Pair<Int, Int>?>(null, session.engine, index) {
        value = withContext(Dispatchers.IO) { runCatching { session.engine.dimensions(index) }.getOrNull() }
    }
    val ratio = dimensions?.let { it.first.toFloat() / it.second } ?: .707f
    val pageWidth = if (fitPage) minOf(availableWidth, (availableHeight - 32.dp) * ratio) else availableWidth
    val renderWidth = with(density) { pageWidth.toPx().toInt().coerceIn(64, 2200) }
    var failed by remember(session.engine, index) { mutableStateOf(false) }
    val load by produceState<PageLoad?>(null, session.engine, index, renderWidth) {
        try { value = withContext(Dispatchers.IO) { PageLoad(session.engine.text(index), session.engine.render(index, renderWidth)) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
    }
    Column(Modifier.width(pageWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.source_page, index + 1), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
            val page = load
            if (page == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(if (failed) R.string.page_failed else R.string.loading_page)) }
            else AndroidView(factory = { context -> PdfPageView(context).apply { this.onSelection = { text, anchor -> onSelection(text, anchor, this) } } },
                update = { view -> view.bitmap = page.bitmap; view.pageText = page.text; view.highlights = highlights; view.onHighlight = onHighlight }, modifier = Modifier.fillMaxSize())
        }
        if (load?.text?.glyphs?.isEmpty() == true) Text(stringResource(R.string.no_text_layer), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
    }
}

@Composable private fun ReaderPanel(entries: List<Entry>, bookmarks: List<Bookmark>, data: LibrarySnapshot, bookmarksTab: Boolean, onTab: (Boolean) -> Unit, onEdit: (Entry) -> Unit, onSource: (Entry) -> Unit, onBookmark: (Bookmark) -> Unit, onDelete: (String) -> Unit, modifier: Modifier) {
    Column(modifier.padding(8.dp)) {
        Row { FilterChip(!bookmarksTab, { onTab(false) }, label = { Text(stringResource(R.string.highlights)) }); Spacer(Modifier.width(4.dp)); FilterChip(bookmarksTab, { onTab(true) }, label = { Text(stringResource(R.string.bookmarks)) }) }
        LazyColumn {
            if (!bookmarksTab) items(entries, key = { it.id }) { e -> EntryCard(e, data, { onEdit(e) }, { onSource(e) }) }
            else items(bookmarks, key = { it.id }) { b -> ListItem(headlineContent = { Text(b.title) }, supportingContent = { Text(stringResource(R.string.source_page, b.page + 1) + if (b.comment.isBlank()) "" else "\n${b.comment}") }, trailingContent = { IconButton(onClick = { onDelete(b.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }, modifier = Modifier.clickable { onBookmark(b) }) }
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
