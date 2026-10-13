package org.readerka.ui

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.readerka.R
import org.readerka.data.*
import org.readerka.data.TextStyle

@Composable fun NoteDocument(id: String, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int) {
    val workspace by vm.workspace.collectAsState()
    val draft = workspace.drafts.firstOrNull { it.entry.id == id }
    val entry = draft?.entry ?: data.entries.firstOrNull { it.id == id }
    if (entry == null) { Text("Документ недоступен", modifier = Modifier.padding(24.dp)); return }
    if (draft != null) DraftEditor(draft, data, vm, inline = true, onEditorFocus = { if (it) vm.focusPane(pane) }, pane = pane)
    else {
        var menuLink by remember { mutableStateOf<String?>(null) }
        var outline by remember { mutableStateOf(false) }
        val scroll = rememberNoteScroll(pane, id, vm)
        val rich = remember(entry) { entry.richText() }
        val scope = rememberCoroutineScope()
        val positions = remember(id) { mutableStateMapOf<String, Int>() }
        val requested by vm.requestedBlock.collectAsState()
        LaunchedEffect(requested, positions.size) { if (requested?.first == id) { positions[requested!!.second]?.let { scroll.scrollTo(it); vm.requestedBlock.value = null } } }
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((if (entry.kind == EntryKind.Consp) "Конспект" else "Заметка") + " · сохранена", modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.labelMedium)
                WorkspaceAction(Outline.Menu, "Оглавление", { outline = true })
                WorkspaceAction(Outline.Edit, "Редактировать", { vm.editEntry(entry, pane) })
                WorkspaceAction(Outline.Split, "Открыть справа", { vm.openRight(WorkspaceTab(id, id, WorkspaceKind.Note), pane) })
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(entry.title, style = MaterialTheme.typography.headlineMedium)
                if (entry.quote.isNotBlank()) { Text("Исходная цитата", style = MaterialTheme.typography.labelLarge); Text(entry.quote) }
                RichPreview(rich, { vm.openLink(it, pane) }, { menuLink = it }, onBlockPosition = { block, y -> positions[block] = y })
                val sources = data.documents.filter { it.id in entry.sourceBooks() }
                if (sources.isNotEmpty()) { Text("Связанные книги"); sources.forEach { book -> TextButton(onClick = { vm.openLink(ResearchLinks.document(book.id, (entry.anchor?.takeIf { book.id == entry.documentId }?.page ?: 0) + 1), pane) }) { Text(book.title) } } }
                Backlinks(entry, data, vm, pane)
                if (entry.kind == EntryKind.Note) TextButton(onClick = { vm.convertToConsp(entry) }) { Text("Преобразовать заметку в конспект") }
            }
        }
        if (outline) OutlineDialog(rich, { outline = false }) { block -> scope.launch { scroll.animateScrollTo(positions[block.id] ?: 0) } }
        menuLink?.let { link -> LinkActions(link, data, vm, pane, { menuLink = null }) }
    }
}

@Composable fun DraftEditor(draft: EntryDraft, data: LibrarySnapshot, vm: LibraryViewModel, inline: Boolean = false, onEditorFocus: (Boolean) -> Unit = {}, pane: Int = vm.workspace.value.activePane) {
    val status by vm.draftStatus.collectAsState()
    val saving by vm.editorSaving.collectAsState()
    val workspace by vm.workspace.collectAsState()
    val entry = draft.entry
    val quoteHistory = remember(entry.id) { vm.quoteHistory(entry.id) }
    var quoteFocused by remember(entry.id) { mutableStateOf(false) }
    var quoteValue by remember(entry.id) { mutableStateOf(TextFieldValue(entry.quote,
        if (draft.focusedField == "quote") TextRange(draft.cursorStart.coerceIn(0, entry.quote.length), draft.cursorEnd.coerceIn(0, entry.quote.length)) else TextRange.Zero)) }
    LaunchedEffect(entry.quote) { if (quoteValue.text != entry.quote) quoteValue = quoteValue.copy(text = entry.quote, selection = TextRange(quoteValue.selection.start.coerceIn(0, entry.quote.length), quoteValue.selection.end.coerceIn(0, entry.quote.length))) }
    fun changeQuote(value: TextFieldValue, record: Boolean = true) {
        if (record && value.text != quoteValue.text) { quoteHistory.undo.add(quoteValue.text to quoteValue.selection); quoteHistory.redo.clear(); if (quoteHistory.undo.size > 100) quoteHistory.undo.removeAt(0) }
        quoteValue = value
        val latest = vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id } ?: draft
        vm.updateDraft(latest.copy(entry = latest.entry.copy(quote = value.text), cursorStart = value.selection.start, cursorEnd = value.selection.end, focusedField = "quote"))
    }
    fun undoQuote(forward: Boolean) {
        val from = if (forward) quoteHistory.redo else quoteHistory.undo
        val to = if (forward) quoteHistory.undo else quoteHistory.redo
        if (from.isNotEmpty()) { val prior = from.removeAt(from.lastIndex); to.add(quoteValue.text to quoteValue.selection); changeQuote(TextFieldValue(prior.first, prior.second), false) }
    }
    val rich = remember(entry.content, entry.comment) { entry.richText() }
    var selection by remember(entry.id) { mutableStateOf(TextRange(draft.cursorStart.coerceIn(0, rich.text.length), draft.cursorEnd.coerceIn(0, rich.text.length))) }
    var bookSearch by remember { mutableStateOf("") }
    var properties by rememberSaveable(entry.id) { mutableStateOf(false) }
    var preview by rememberSaveable(entry.id) { mutableStateOf(false) }
    var rawMarkdown by rememberSaveable(entry.id) { mutableStateOf(false) }
    var libraryInsert by remember { mutableStateOf<InsertMode?>(null) }
    var outline by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var menuLink by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember(entry.id) { FocusRequester() }
    val tagsFocus = remember(entry.id) { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var bodyViewport by remember { mutableStateOf(Rect.Zero) }
    var nativeView by remember { mutableStateOf<ResearchEditText?>(null) }
    val undo = remember(entry.id) { vm.historyForEntry(entry.id).undo }
    val redo = remember(entry.id) { vm.historyForEntry(entry.id).redo }
    var typingStyles by remember(entry.id) { mutableStateOf(setOf<TextStyle>()) }
    val scroll = rememberNoteScroll(pane, entry.id, vm)
    val scope = rememberCoroutineScope()
    val requested by vm.requestedBlock.collectAsState()
    fun write(value: RichText, range: TextRange, record: Boolean = true) {
        if (record && value != rich) { undo += rich to selection; if (undo.size > 100) undo.removeAt(0); redo.clear() }
        selection = range
        val latest = vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id } ?: draft
        vm.updateDraft(latest.copy(entry = latest.entry.withContent(value), cursorStart = range.start, cursorEnd = range.end))
    }
    fun format(style: TextStyle) {
        if (selection.collapsed) typingStyles = if (style in typingStyles) typingStyles - style else typingStyles + style
        else write(rich.style(selection.min, selection.max, style), selection)
        nativeView?.requestFocus()
    }
    fun undoEdit(forward: Boolean) {
        if (draft.focusedField == "quote") { undoQuote(forward); return }
        val from = if (forward) redo else undo; val to = if (forward) undo else redo
        if (from.isNotEmpty()) { val previous = from.removeAt(from.lastIndex); to += rich to selection; write(previous.first, previous.second, false) }
    }
    val keyHandler by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
        val action = AppShortcuts.match(event, editorContext = true)?.action
        val recognized = action != null && (
            action in setOf("preview", "properties", "find") ||
            (!preview && !rawMarkdown && ((quoteFocused && action in setOf("undo", "redo")) || nativeView?.hasFocus() == true)))
        if (recognized && event.action == KeyEvent.ACTION_DOWN) when (action) {
            "bold" -> format(TextStyle.Bold)
            "italic" -> format(TextStyle.Italic)
            "underline" -> format(TextStyle.Underline)
            "undo" -> undoEdit(false)
            "redo" -> undoEdit(true)
            "link" -> libraryInsert = InsertMode.Inline
            "find" -> find = !find
            "insert" -> libraryInsert = InsertMode.Content
            "preview" -> preview = !preview
            "properties" -> properties = !properties
            "heading-1", "heading-2", "heading-3" -> write(rich.paragraph(selection.min, selection.max, BlockStyle.Heading, action!!.last().digitToInt()), selection)
            "paragraph" -> write(rich.paragraph(selection.min, selection.max, BlockStyle.Paragraph), selection)
        }
        recognized
    })
    DisposableEffect(entry.id, pane, workspace.activePane, preview) {
        val handler: (KeyEvent) -> Boolean = { keyHandler(it) }
        if (workspace.activePane == pane) vm.editorKeyHandler = handler
        onDispose { if (vm.editorKeyHandler === handler) vm.editorKeyHandler = null }
    }
    LaunchedEffect(imeVisible, nativeView, entry.id, workspace.activePane) {
        if (imeVisible && !preview && workspace.activePane == pane && nativeView?.hasWindowFocus() == true) {
            withFrameNanos { }
            when (draft.focusedField) {
                "body" -> nativeView?.requestFocus()
                "title" -> titleFocus.requestFocus()
                "tags" -> if (properties) tagsFocus.requestFocus()
            }
        }
    }
    LaunchedEffect(rich.text.length) { selection = TextRange(selection.start.coerceIn(0, rich.text.length), selection.end.coerceIn(0, rich.text.length)) }
    LaunchedEffect(selection, entry.id, bodyViewport) {
        delay(250)
        vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id }?.let { latest ->
            if (latest.focusedField == "body" && (latest.cursorStart != selection.start || latest.cursorEnd != selection.end)) vm.updateDraft(latest.copy(cursorStart = selection.start, cursorEnd = selection.end))
        }
        nativeView?.let { editor ->
            if (editor.hasFocus() && bodyViewport.height > 0) {
                withFrameNanos { }
                editor.layout?.let { layout ->
                    val line = layout.getLineForOffset(selection.end.coerceIn(0, editor.length()))
                    val position = IntArray(2); editor.getLocationInWindow(position)
                    val top = position[1] + editor.totalPaddingTop + layout.getLineTop(line) - editor.scrollY
                    val bottom = position[1] + editor.totalPaddingTop + layout.getLineBottom(line) - editor.scrollY
                    val delta = when { bottom > bodyViewport.bottom - 12 -> bottom - bodyViewport.bottom + 12; top < bodyViewport.top + 12 -> top - bodyViewport.top - 12; else -> 0f }
                    if (delta != 0f) scroll.scrollTo((scroll.value + delta).toInt().coerceIn(0, scroll.maxValue))
                }
            }
        }
    }
    LaunchedEffect(requested, nativeView) { if (requested?.first == entry.id) rich.blocks.firstOrNull { it.id == requested!!.second }?.let { selection = TextRange(it.start); vm.requestedBlock.value = null; nativeView?.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().testTag("entry-editor")) {
        Column(Modifier.imePadding().onFocusChanged { onEditorFocus(it.hasFocus) }.focusGroup()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (entry.kind) { EntryKind.Consp -> "Конспект"; EntryKind.Quote -> "Комментарий к цитате"; EntryKind.Note -> "Заметка" }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                WorkspaceAction(Outline.Menu, "Оглавление", { outline = true }, Modifier.testTag("note-outline"))
                WorkspaceAction(Outline.Settings, "Свойства", { properties = !properties }, Modifier.testTag("note-properties"), selected = properties)
                WorkspaceAction(if (preview) Outline.Edit else Outline.Preview, if (preview) "Редактировать" else "Режим чтения", { preview = !preview }, Modifier.testTag("markdown-preview"))
                WorkspaceAction(Outline.Close, stringResource(R.string.close_editor), { vm.focusPane(pane); vm.closeEditor() })
            }
            if (!preview && !rawMarkdown) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { format(TextStyle.Bold) }, modifier = Modifier.heightIn(min = 48.dp).testTag("format-bold").semantics { contentDescription = "Жирный" }) { Text("Ж", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                TextButton(onClick = { format(TextStyle.Italic) }, modifier = Modifier.heightIn(min = 48.dp).testTag("format-italic").semantics { contentDescription = "Курсив" }) { Text("К", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
                TextButton(onClick = { format(TextStyle.Underline) }, modifier = Modifier.heightIn(min = 48.dp).testTag("format-underline").semantics { contentDescription = "Подчёркивание" }) { Text("Ч", textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }
                var blockMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { blockMenu = true }) { Text("Абзац / заголовок") }
                    DropdownMenu(blockMenu, { blockMenu = false }) {
                        listOf(BlockStyle.Paragraph to "Обычный текст", BlockStyle.Heading to "Заголовок 1", BlockStyle.Heading to "Заголовок 2", BlockStyle.Heading to "Заголовок 3", BlockStyle.Bullet to "Список", BlockStyle.Ordered to "Нумерованный список", BlockStyle.Quote to "Цитатный блок", BlockStyle.Code to "Код").forEachIndexed { index, (style, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { write(rich.paragraph(selection.min, selection.max, style, if (index in 1..3) index else 1), selection); blockMenu = false })
                        }
                    }
                }
                TextButton(onClick = { libraryInsert = InsertMode.Inline }, modifier = Modifier.testTag("insert-link")) { Text("Ссылка") }
                TextButton(onClick = { libraryInsert = InsertMode.Content }, modifier = Modifier.testTag("insert-library")) { Text("Вставить из библиотеки") }
                TextButton(onClick = { undoEdit(false) }) { Text("Отменить") }
                TextButton(onClick = { undoEdit(true) }) { Text("Повторить") }
                TextButton(onClick = { find = !find }) { Text("Найти") }
                val existingLink = rich.marks.firstOrNull { it.style == TextStyle.Link && selection.start in it.start..it.end }?.value ?: rich.blockAt(selection.start).takeIf { it.style == BlockStyle.LinkCard }?.target
                if (existingLink != null) TextButton(onClick = { menuLink = existingLink }) { Text("Действия ссылки") }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { bodyViewport = it.boundsInWindow() }) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (LocalCompact.current) 16.dp else 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(entry.title, { vm.updateDraft(draft.copy(entry = entry.copy(title = it))) }, enabled = !saving,
                    placeholder = { Text(if (entry.kind == EntryKind.Consp) "Название конспекта" else "Название заметки") }, textStyle = MaterialTheme.typography.headlineMedium,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).onFocusChanged { if (it.isFocused) vm.focusEditorField(entry.id, "title") }.testTag("entry-title"))
                if (entry.kind == EntryKind.Quote || entry.quote.isNotBlank()) {
                    Text(entry.anchor?.let { "Цитата · страница ${it.page + 1}" } ?: "Цитата", style = MaterialTheme.typography.labelMedium)
                    if (preview) Text(entry.quote)
                    else OutlinedTextField(quoteValue, { changeQuote(it) }, enabled = !saving,
                        label = { Text("Текст цитаты") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("entry-quotation")
                            .onFocusChanged { quoteFocused = it.isFocused; if (it.isFocused) { vm.focusPane(pane); vm.focusEditorField(entry.id, "quote") } })
                    Text("Комментарий", style = MaterialTheme.typography.labelMedium)
                }
                if (find) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("Найти в документе") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { val index = rich.text.indexOf(query, (selection.max + 1).coerceAtMost(rich.text.length), true).let { if (it < 0) rich.text.indexOf(query, ignoreCase = true) else it }; if (index >= 0 && query.isNotEmpty()) { selection = TextRange(index, index + query.length); nativeView?.requestFocus() } }) { Text("Далее") }
                }
                when {
                    preview -> RichPreview(rich, { vm.openLink(it, pane) }, { menuLink = it }, Modifier.testTag("markdown-rendered"))
                    rawMarkdown -> OutlinedTextField(entry.comment, { value -> vm.updateDraft(draft.copy(entry = entry.copy(comment = value, content = null))) }, label = { Text("Markdown") }, modifier = Modifier.fillMaxWidth().testTag("markdown-source"))
                    else -> RichEditor(rich, selection, { next, range ->
                        var formatted = next
                        if (next.text.length > rich.text.length && typingStyles.isNotEmpty()) {
                            val start = (range.end - (next.text.length - rich.text.length)).coerceAtLeast(0)
                            typingStyles.forEach { style -> if (formatted.marks.none { it.style == style && it.start <= start && it.end >= range.end }) formatted = formatted.style(start, range.end.coerceAtMost(next.text.length), style) }
                        }
                        write(formatted, range)
                    }, { selection = it }, { vm.focusPane(pane); vm.focusEditorField(entry.id, "body") }, { nativeView = it })
                }
                if (properties) {
                    HorizontalDivider(); Text("Свойства записи", style = MaterialTheme.typography.titleSmall)
                    if (entry.kind == EntryKind.Consp) {
                        Text("Связанные книги · можно выбрать несколько или убрать все")
                        OutlinedTextField(bookSearch, { bookSearch = it }, label = { Text("Найти книгу для связи") }, modifier = Modifier.fillMaxWidth())
                        data.documents.filter { bookSearch.isBlank() || it.title.contains(bookSearch, true) || it.authors.contains(bookSearch, true) }.forEach { book -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(book.id in entry.sources, { checked -> vm.updateDraft(draft.copy(entry = entry.copy(sources = if (checked) (entry.sources + book.id).distinct() else entry.sources - book.id))) }, Modifier.testTag("consp-source-${book.id}")); Text(book.title, modifier = Modifier.weight(1f))
                        } }
                    } else {
                        data.documents.firstOrNull { it.id == entry.documentId }?.let { Text("Источник · " + it.title) }
                        Choice(stringResource(R.string.type), entry.type, NoteType.entries, { stringResource(it.labelId()) }, { vm.updateDraft(draft.copy(entry = entry.copy(type = it))) })
                        if (entry.kind == EntryKind.Note) TextButton(onClick = { vm.convertToConsp(entry) }) { Text("Преобразовать заметку в конспект") }
                    }
                    ColorPalette(entry.color) { vm.updateDraft(draft.copy(entry = entry.copy(color = it))) }
                    OutlinedTextField(draft.tags, { vm.updateDraft(draft.copy(tags = it)) }, enabled = !saving, label = { Text(stringResource(R.string.tags)) }, modifier = Modifier.fillMaxWidth().focusRequester(tagsFocus).onFocusChanged { if (it.isFocused) vm.focusEditorField(entry.id, "tags") }.testTag("entry-tags"))
                    FolderMembershipChoice(if (draft.entry.kind == EntryKind.Consp) "Папки конспектов" else "Папки заметок и цитат", data.collectionsFor(draft.entry.kind).map { FolderItem(it.id, it.name, it.parentId) }, draft.collections, { vm.updateDraft(draft.copy(collections = it)) }, Modifier.testTag("entry-collections"))
                    if (entry.kind == EntryKind.Quote && entry.documentId != null) FolderMembershipChoice("Папки цитат внутри PDF", data.readerFolders.filter { it.documentId == entry.documentId && it.kind == ReaderFolderKind.Quotes }.map { FolderItem(it.id, it.name, it.parentId) }, draft.readerFolders, { vm.updateDraft(draft.copy(readerFolders = it)) }, Modifier.testTag("entry-reader-folders"))
                    TextButton(onClick = { history = true }) { Text("История изменений") }
                    TextButton(onClick = { vm.checkpointDraft(entry.id, "Версия пользователя") }) { Text("Сохранить контрольную версию") }
                    TextButton(onClick = { rawMarkdown = !rawMarkdown; preview = false }) { Text(if (rawMarkdown) "Визуальный редактор" else "Исходный Markdown") }
                    Backlinks(entry, data, vm, pane)
                    TextButton(enabled = !saving, onClick = { discard = true }) { Text("Удалить черновик") }
                    if (data.entries.any { it.id == entry.id }) TextButton(enabled = !saving, onClick = { delete = true }, modifier = Modifier.testTag("delete-quote")) { Text("Удалить запись", color = MaterialTheme.colorScheme.error) }
                }
            }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f).testTag("draft-status"))
                TextButton(enabled = !saving && entry.title.isNotBlank(), onClick = { vm.saveEditor(entry.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("save-entry")) { Text("Сохранить") }
            }
        }
    }
    libraryInsert?.let { mode -> LibraryInsertDialog(data, entry, rich.text.substring(selection.min.coerceIn(0, rich.text.length), selection.max.coerceIn(0, rich.text.length)), mode, { libraryInsert = null }) { fragment, link ->
        scope.launch {
            vm.checkpointDraft(entry.id, "До вставки из библиотеки").join()
            val next = if (link != null && !selection.collapsed) if (fragment.text == rich.text.substring(selection.min, selection.max)) rich.style(selection.min, selection.max, TextStyle.Link, link) else rich.replace(selection.min, selection.max, fragment) else rich.replace(selection.min, selection.max, if (fragment.blocks.any { it.style == BlockStyle.LinkCard } && rich.text.isNotEmpty()) RichText.plain("\n").replace(1, 1, fragment).let { it.replace(it.text.length, it.text.length, RichText.plain("\n")) } else fragment)
            write(next, if (link != null && !selection.collapsed) selection else TextRange((selection.min + next.text.length - rich.text.length + selection.max - selection.min).coerceIn(0, next.text.length)))
            nativeView?.requestFocus()
        }
    } }
    if (outline) OutlineDialog(rich, { outline = false }) { block -> selection = TextRange(block.start); nativeView?.requestFocus(); scope.launch { nativeView?.layout?.let { scroll.animateScrollTo(it.getLineTop(it.getLineForOffset(block.start))) } } }
    if (history) VersionDialog(entry.id, data, { history = false }) { vm.restoreVersion(it); history = false }
    menuLink?.let { link -> LinkActions(link, data, vm, pane, { menuLink = null }, onRemove = {
        val marks = rich.marks.filterNot { it.style == TextStyle.Link && it.value == link && selection.start in it.start..it.end }
        write(rich.copy(marks = marks, blocks = rich.blocks.map { if (it.id == rich.blockAt(selection.start).id && it.style == BlockStyle.LinkCard && it.target == link) it.copy(style = BlockStyle.Paragraph, target = "") else it }), selection)
    }, onEdit = {
        val mark = rich.marks.firstOrNull { it.style == TextStyle.Link && it.value == link && selection.start in it.start..it.end }
        if (mark != null) { selection = TextRange(mark.start, mark.end); libraryInsert = InsertMode.Inline }
        else { val block = rich.blockAt(selection.start); selection = TextRange(block.start, rich.blockEnd(block)); libraryInsert = InsertMode.Card }
    }) }
    if (delete) Confirm(R.string.delete_entries_title, R.string.delete_entries_body, { delete = false }, { vm.deleteEntries(setOf(entry.id)); delete = false })
    if (discard) Confirm(R.string.discard_draft, R.string.discard_draft_body, { discard = false }, { vm.discardDraft(entry.id); discard = false })
}

@Composable private fun Backlinks(entry: Entry, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int) {
    val backlinks = ResearchLinks.backlinks(data, entry.id)
    if (backlinks.isNotEmpty()) { Text("Где используется", style = MaterialTheme.typography.titleSmall); backlinks.forEach { source -> TextButton(onClick = { vm.openLink(ResearchLinks.entry(source.id), pane) }) { Text(source.title) } } }
}
@Composable private fun OutlineDialog(rich: RichText, onDismiss: () -> Unit, onSelect: (TextBlock) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Оглавление") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            val headings = rich.blocks.filter { it.style == BlockStyle.Heading }
            if (headings.isEmpty()) Text("Добавьте заголовки через панель форматирования.")
            headings.forEach { block -> TextButton(onClick = { onSelect(block); onDismiss() }, modifier = Modifier.padding(start = ((block.level - 1) * 12).dp)) { Text(rich.text.substring(block.start, rich.blockEnd(block))) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
@Composable private fun VersionDialog(id: String, data: LibrarySnapshot, onDismiss: () -> Unit, onRestore: (NoteVersion) -> Unit) {
    var selected by remember { mutableStateOf<NoteVersion?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("История изменений") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            val versions = data.noteVersions.filter { it.entryId == id }.sortedByDescending { it.createdAt }
            if (versions.isEmpty()) Text("Контрольных версий пока нет.")
            versions.forEach { version -> TextButton(onClick = { selected = version }) { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(version.createdAt)) + " · " + version.label) } }
            selected?.let { version -> val draft = kotlinx.serialization.json.Json.decodeFromString<EntryDraft>(version.snapshot); Text(draft.entry.title, style = MaterialTheme.typography.titleSmall); RichPreview(draft.entry.richText(), {}, {}) }
        }
    }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let(onRestore) }) { Text("Восстановить выбранную") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
@Composable private fun LinkActions(link: String, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null, onEdit: (() -> Unit)? = null) {
    var preview by remember { mutableStateOf(false) }
    val id = android.net.Uri.parse(link).pathSegments.firstOrNull()
    val source = data.entries.firstOrNull { it.id == id }
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(source?.title ?: data.documents.firstOrNull { it.id == id }?.title ?: "Ссылка") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.openLink(link, pane); onDismiss() }) { Text("Открыть") }
            TextButton(onClick = { vm.layout(split = true); vm.openLink(link, 1 - pane, false); onDismiss() }) { Text("Открыть справа") }
            if (source != null) TextButton(onClick = { preview = !preview }) { Text("Предпросмотр") }
            if (preview && source != null) RichPreview(source.richText(), { vm.openLink(it, pane) }, {})
            TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Копировать ссылку") }
            onEdit?.let { TextButton(onClick = { it(); onDismiss() }) { Text("Изменить ссылку") } }
            onRemove?.let { TextButton(onClick = { it(); onDismiss() }) { Text("Убрать ссылку, оставить текст") } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@OptIn(FlowPreview::class)
@Composable private fun rememberNoteScroll(pane: Int, id: String, vm: LibraryViewModel): ScrollState {
    val initial = remember(pane, id) { vm.workspace.value.panes[pane].tabs.firstOrNull { it.kind == WorkspaceKind.Note && it.documentId == id }?.offset ?: 0 }
    val scroll = rememberScrollState(initial)
    LaunchedEffect(pane, id, scroll) { withFrameNanos { }; scroll.scrollTo(initial); snapshotFlow { scroll.value }.distinctUntilChanged().debounce(350).collect { vm.trackNotePosition(pane, id, it) } }
    return scroll
}
