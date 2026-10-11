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
        var history by remember { mutableStateOf(false) }
        var readMenu by remember { mutableStateOf(false) }
        val readHandler by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
            val action = AppShortcuts.match(event)?.action
            val known = action in setOf("outline", "preview", "properties", "history")
            if (known && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (action) {
                "outline" -> outline = true
                "history" -> history = true
                "preview" -> { vm.requestedEditorAction.value = entry.id to "focus-body"; vm.editEntry(entry, pane) }
                "properties" -> { vm.requestedEditorAction.value = entry.id to "properties"; vm.editEntry(entry, pane) }
            }
            known
        })
        DisposableEffect(entry.id, pane, workspace.activePane) {
            val handler: (KeyEvent) -> Boolean = { readHandler(it) }
            if (workspace.activePane == pane) vm.editorKeyHandler = handler
            onDispose { if (vm.editorKeyHandler === handler) vm.editorKeyHandler = null }
        }
        val scroll = rememberNoteScroll(pane, id, vm)
        val rich = remember(entry) { entry.richText() }
        val scope = rememberCoroutineScope()
        val positions = remember(id) { mutableStateMapOf<String, Int>() }
        val requested by vm.requestedBlock.collectAsState()
        LaunchedEffect(requested, positions.size) { if (requested?.first == id) { positions[requested!!.second]?.let { scroll.scrollTo(it); vm.requestedBlock.value = null } } }
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((when (entry.kind) { EntryKind.Consp -> "Конспект"; EntryKind.Quote -> "Цитата"; EntryKind.Note -> "Заметка" }) + " · сохранена", modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.labelMedium)
                WorkspaceAction(Outline.Menu, "Оглавление", { outline = true })
                WorkspaceAction(Outline.Edit, "Редактировать", { vm.editEntry(entry, pane) })
                Box {
                    WorkspaceAction(Outline.More, "Действия документа", { readMenu = true })
                    DropdownMenu(readMenu, { readMenu = false }) {
                        ShortcutMenuItem("properties", { readMenu = false; vm.requestedEditorAction.value = entry.id to "properties"; vm.editEntry(entry, pane) })
                        ShortcutMenuItem("history", { readMenu = false; history = true })
                        ShortcutMenuItem("right", { readMenu = false; vm.openRight(WorkspaceTab(id, id, WorkspaceKind.Note), pane) })
                    }
                }
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
        if (history) VersionDialog(entry.id, data, { history = false }) { vm.restoreVersion(it); history = false }
        menuLink?.let { link -> LinkActions(link, data, vm, pane, { menuLink = null }) }
    }
}

@Composable fun DraftEditor(draft: EntryDraft, data: LibrarySnapshot, vm: LibraryViewModel, inline: Boolean = false, onEditorFocus: (Boolean) -> Unit = {}, pane: Int = vm.workspace.value.activePane) {
    val status by vm.draftStatus.collectAsState()
    val saving by vm.editorSaving.collectAsState()
    val workspace by vm.workspace.collectAsState()
    val entry = draft.entry
    val rich = remember(entry.content, entry.comment) { entry.richText() }
    var selection by remember(entry.id) { mutableStateOf(TextRange(draft.cursorStart.coerceIn(0, rich.text.length), draft.cursorEnd.coerceIn(0, rich.text.length))) }
    var template by remember { mutableStateOf(ConspTemplate.Book) }
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
    var styleMenu by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var insertMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
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
    fun currentContent() = vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id }?.entry?.richText() ?: rich
    fun write(value: RichText, range: TextRange, record: Boolean = true) {
        val previous = currentContent()
        if (record && value != previous) { undo += previous to selection; if (undo.size > 100) undo.removeAt(0); redo.clear() }
        selection = range
        val latest = vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id } ?: draft
        vm.updateDraft(latest.copy(entry = latest.entry.withContent(value), cursorStart = range.start, cursorEnd = range.end))
    }
    fun format(style: TextStyle) {
        if (selection.collapsed) typingStyles = if (style in typingStyles) typingStyles - style else typingStyles + style
        else write(currentContent().style(selection.min, selection.max, style), selection)
        nativeView?.requestFocus()
    }
    fun undoEdit(forward: Boolean) {
        val from = if (forward) redo else undo; val to = if (forward) undo else redo
        if (from.isNotEmpty()) { val previous = from.removeAt(from.lastIndex); to += currentContent() to selection; write(previous.first, previous.second, false) }
    }
    fun applyBlock(style: BlockStyle, level: Int = 1) {
        write(currentContent().paragraph(selection.min, selection.max, style, level), selection)
        styleMenu = false; nativeView?.requestFocus()
    }
    fun currentLink() = rich.marks.firstOrNull { it.style == TextStyle.Link && selection.start in it.start until it.end }?.value
        ?: rich.blockAt(selection.start).takeIf { it.style == BlockStyle.LinkCard }?.target
    val keyHandler by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
        val action = AppShortcuts.match(event)?.action
        val general = action in setOf("outline", "properties", "search", "preview", "history", "checkpoint", "markdown", "convert", "discard", "delete")
        val editable = !preview && !rawMarkdown && action in (AppShortcuts.editor.map { it.action } + "search")
        val escape = action == "escape" && (properties || find || styleMenu || formatMenu || insertMenu || moreMenu || saveMenu)
        val recognized = general || editable || escape
        if (recognized && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (action) {
            "bold" -> format(TextStyle.Bold)
            "italic" -> format(TextStyle.Italic)
            "underline" -> format(TextStyle.Underline)
            "undo" -> undoEdit(false)
            "redo" -> undoEdit(true)
            "link" -> libraryInsert = InsertMode.Inline
            "insert-card" -> libraryInsert = InsertMode.Card
            "insert-content" -> libraryInsert = InsertMode.Content
            "search" -> { find = !find; preview = false; rawMarkdown = false }
            "paragraph" -> applyBlock(BlockStyle.Paragraph)
            "heading-1", "heading-2", "heading-3" -> applyBlock(BlockStyle.Heading, action!!.last().digitToInt())
            "bullet" -> applyBlock(BlockStyle.Bullet)
            "ordered" -> applyBlock(BlockStyle.Ordered)
            "quote-block" -> applyBlock(BlockStyle.Quote)
            "code-block" -> applyBlock(BlockStyle.Code)
            "outline" -> outline = true
            "properties" -> properties = !properties
            "preview" -> preview = !preview
            "history" -> history = true
            "checkpoint" -> vm.checkpointDraft(entry.id, "Версия пользователя")
            "markdown" -> { rawMarkdown = !rawMarkdown; preview = false }
            "link-actions" -> menuLink = currentLink()
            "convert" -> if (entry.kind == EntryKind.Note) vm.convertToConsp(entry)
            "discard" -> discard = true
            "delete" -> if (data.entries.any { it.id == entry.id }) delete = true
            "escape" -> { properties = false; find = false; styleMenu = false; formatMenu = false; insertMenu = false; moreMenu = false; saveMenu = false }
        }
        recognized
    })
    DisposableEffect(entry.id, pane, workspace.activePane, preview) {
        val handler: (KeyEvent) -> Boolean = { keyHandler(it) }
        if (workspace.activePane == pane) vm.editorKeyHandler = handler
        onDispose { if (vm.editorKeyHandler === handler) vm.editorKeyHandler = null }
    }
    val editorAction by vm.requestedEditorAction.collectAsState()
    LaunchedEffect(editorAction, entry.id, nativeView) {
        if (editorAction?.first == entry.id && (editorAction?.second != "focus-body" || nativeView != null)) {
            if (editorAction?.second == "focus-body") nativeView?.requestFocus()
            AppShortcuts.all.firstOrNull { it.action == editorAction!!.second }?.let { binding ->
                keyHandler(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, binding.code, 0, (if (binding.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (binding.alt) KeyEvent.META_ALT_ON else 0) or (if (binding.shift) KeyEvent.META_SHIFT_ON else 0)))
            }
            vm.requestedEditorAction.value = null
        }
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
            if (latest.cursorStart != selection.start || latest.cursorEnd != selection.end) vm.updateDraft(latest.copy(cursorStart = selection.start, cursorEnd = selection.end))
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
    val propertiesContent: @Composable (Boolean) -> Unit = { side ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Свойства", style = MaterialTheme.typography.titleLarge)
            if (entry.kind == EntryKind.Consp) {
                Text("Источники · выберите книги или оставьте конспект тематическим", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(bookSearch, { bookSearch = it }, label = { Text("Найти книгу") }, modifier = Modifier.fillMaxWidth())
                data.documents.filter { bookSearch.isBlank() || it.title.contains(bookSearch, true) || it.authors.contains(bookSearch, true) }.forEach { book -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(book.id in entry.sources, { checked -> vm.updateDraft(draft.copy(entry = entry.copy(sources = if (checked) (entry.sources + book.id).distinct() else entry.sources - book.id))) }, Modifier.testTag("consp-source-${book.id}")); Text(book.title, modifier = Modifier.weight(1f))
                } }
            } else {
                data.documents.firstOrNull { it.id == entry.documentId }?.let { Text("Источник · " + it.title) }
                Choice(stringResource(R.string.type), entry.type, NoteType.entries, { stringResource(it.labelId()) }, { vm.updateDraft(draft.copy(entry = entry.copy(type = it))) })
                if (entry.kind == EntryKind.Note) TextButton(onClick = { vm.convertToConsp(entry) }) { Text("Преобразовать заметку в конспект") }
            }
            if (entry.kind == EntryKind.Consp) {
                Choice("Шаблон структуры", template, ConspTemplate.entries.filter { it != ConspTemplate.Blank }, { it.title }, { template = it })
                TextButton(onClick = {
                    scope.launch { vm.checkpointDraft(entry.id, "До вставки шаблона").join(); val current = vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id }?.entry?.richText() ?: rich; val separated = current.replace(current.text.length, current.text.length, RichText.plain(if (current.text.isNotBlank()) "\n\n" else "")); val value = separated.replace(separated.text.length, separated.text.length, RichText.fromMarkdown(template.source)); write(value, TextRange(value.text.length)) }
                }) { Text("Добавить структуру в конец") }
            }
            FolderMembershipChoice("Темы / общие папки", data.collections.map { FolderItem(it.id, it.name, it.parentId) }, draft.collections, { vm.updateDraft(draft.copy(collections = it)) }, Modifier.testTag("entry-collections"))
            OutlinedTextField(draft.tags, { vm.updateDraft(draft.copy(tags = it)) }, enabled = !saving, label = { Text(stringResource(R.string.tags)) }, modifier = Modifier.fillMaxWidth().focusRequester(tagsFocus).onFocusChanged { if (it.isFocused) vm.focusEditorField(entry.id, "tags") }.testTag("entry-tags"))
            ColorPalette(entry.color) { vm.updateDraft(draft.copy(entry = entry.copy(color = it))) }
            if (entry.kind == EntryKind.Quote && entry.documentId != null) FolderMembershipChoice("Папки цитат внутри PDF", data.readerFolders.filter { it.documentId == entry.documentId && it.kind == ReaderFolderKind.Quotes }.map { FolderItem(it.id, it.name, it.parentId) }, draft.readerFolders, { vm.updateDraft(draft.copy(readerFolders = it)) }, Modifier.testTag("entry-reader-folders"))
            Backlinks(entry, data, vm, pane)
            if (side) TextButton(onClick = { properties = false }, modifier = Modifier.testTag("close-note-properties")) { Text("Готово") }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val sideProperties = maxWidth >= 850.dp
      Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().testTag("entry-editor")) {
        Column(Modifier.imePadding().onFocusChanged { onEditorFocus(it.hasFocus) }.focusGroup()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkspaceAction(Outline.Menu, "Оглавление · " + AppShortcuts.keys("outline"), { outline = true }, Modifier.testTag("note-outline"))
                if (!preview && !rawMarkdown) {
                    val block = rich.blockAt(selection.min)
                    val styleLabel = when (block.style) { BlockStyle.Heading -> "Заголовок ${block.level}"; BlockStyle.Bullet -> "Список"; BlockStyle.Ordered -> "Нумерация"; BlockStyle.Quote -> "Цитата"; BlockStyle.Code -> "Код"; else -> "Абзац" }
                    Box {
                        TextButton(onClick = { styleMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-style").semantics { contentDescription = "Стиль: $styleLabel" }) { Text(styleLabel); Icon(Outline.Down, null, Modifier.size(16.dp)) }
                        DropdownMenu(styleMenu, { styleMenu = false }) {
                            listOf("paragraph" to BlockStyle.Paragraph, "heading-1" to BlockStyle.Heading, "heading-2" to BlockStyle.Heading, "heading-3" to BlockStyle.Heading, "bullet" to BlockStyle.Bullet, "ordered" to BlockStyle.Ordered, "quote-block" to BlockStyle.Quote, "code-block" to BlockStyle.Code).forEach { (action, style) ->
                                ShortcutMenuItem(action, { applyBlock(style, if (action.startsWith("heading-")) action.last().digitToInt() else 1) })
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { formatMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-format")) { Text("Оформление") }
                        DropdownMenu(formatMenu, { formatMenu = false }) {
                            listOf("bold" to TextStyle.Bold, "italic" to TextStyle.Italic, "underline" to TextStyle.Underline).forEach { (action, style) ->
                                val active = if (selection.collapsed) style in typingStyles else rich.marks.any { it.style == style && it.start <= selection.min && it.end >= selection.max }
                                ShortcutMenuItem(action, { format(style); formatMenu = false }, label = (if (active) "✓ " else "") + AppShortcuts.label(action), modifier = Modifier.testTag("menu-format-$action"))
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { insertMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-insert")) { Icon(Outline.Plus, null, Modifier.size(18.dp)); Text("Вставить") }
                        DropdownMenu(insertMenu, { insertMenu = false }) {
                            ShortcutMenuItem("link", { insertMenu = false; libraryInsert = InsertMode.Inline }, label = "Ссылка на слово / фразу", modifier = Modifier.testTag("insert-link"))
                            ShortcutMenuItem("insert-card", { insertMenu = false; libraryInsert = InsertMode.Card }, label = "Ссылка блоком", modifier = Modifier.testTag("insert-card"))
                            ShortcutMenuItem("insert-content", { insertMenu = false; libraryInsert = InsertMode.Content }, modifier = Modifier.testTag("insert-library"))
                        }
                    }
                } else Text(if (preview) "Режим чтения" else "Исходный Markdown", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                Box {
                    WorkspaceAction(Outline.More, "Действия документа", { moreMenu = true }, Modifier.testTag("editor-more"))
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        ShortcutMenuItem("preview", { moreMenu = false; preview = !preview }, label = if (preview) "Редактировать" else "Режим чтения", modifier = Modifier.testTag("markdown-preview"))
                        ShortcutMenuItem("properties", { moreMenu = false; properties = !properties }, modifier = Modifier.testTag("note-properties-menu"))
                        ShortcutMenuItem("search", { moreMenu = false; find = !find; preview = false; rawMarkdown = false })
                        ShortcutMenuItem("undo", { moreMenu = false; undoEdit(false) }, enabled = undo.isNotEmpty() && !preview && !rawMarkdown)
                        ShortcutMenuItem("redo", { moreMenu = false; undoEdit(true) }, enabled = redo.isNotEmpty() && !preview && !rawMarkdown)
                        ShortcutMenuItem("link-actions", { moreMenu = false; menuLink = currentLink() }, enabled = currentLink() != null)
                        ShortcutMenuItem("history", { moreMenu = false; history = true })
                        ShortcutMenuItem("markdown", { moreMenu = false; rawMarkdown = !rawMarkdown; preview = false })
                        ShortcutMenuItem("discard", { moreMenu = false; discard = true }, enabled = !saving)
                        ShortcutMenuItem("delete", { moreMenu = false; delete = true }, enabled = !saving && data.entries.any { it.id == entry.id }, modifier = Modifier.testTag("delete-quote"))
                    }
                }
            }
            if (!preview && !rawMarkdown && !selection.collapsed) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("selection-format-actions"), verticalAlignment = Alignment.CenterVertically) {
                listOf(TextStyle.Bold to "Ж", TextStyle.Italic to "К", TextStyle.Underline to "Ч").forEach { (style, text) ->
                    val active = rich.marks.any { it.style == style && it.start <= selection.min && it.end >= selection.max }
                    FilterChip(active, { format(style) }, label = { Text(text, fontWeight = if (style == TextStyle.Bold) androidx.compose.ui.text.font.FontWeight.Bold else null, fontStyle = if (style == TextStyle.Italic) androidx.compose.ui.text.font.FontStyle.Italic else null, textDecoration = if (style == TextStyle.Underline) androidx.compose.ui.text.style.TextDecoration.Underline else null) }, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp).testTag("format-${style.name.lowercase()}").semantics { contentDescription = when (style) { TextStyle.Bold -> "Жирный"; TextStyle.Italic -> "Курсив"; else -> "Подчёркивание" } })
                }
                TextButton(onClick = { libraryInsert = InsertMode.Inline }, modifier = Modifier.heightIn(min = 48.dp).testTag("selection-insert-link")) { Text("Ссылка") }
            }
            HorizontalDivider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
              Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { bodyViewport = it.boundsInWindow() }) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (LocalCompact.current) 16.dp else 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(entry.title, { vm.updateDraft(draft.copy(entry = entry.copy(title = it))) }, enabled = !saving,
                        placeholder = { Text(if (entry.kind == EntryKind.Consp) "Название конспекта" else "Название заметки") }, textStyle = MaterialTheme.typography.headlineMedium,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).onFocusChanged { if (it.isFocused) vm.focusEditorField(entry.id, "title") }.testTag("entry-title"))
                    if (!imeVisible) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { properties = !properties }, modifier = Modifier.testTag("note-properties")) { Text("Источники: ${entry.sourceBooks().size}") }
                        TextButton(onClick = { properties = !properties }) { Text(data.collections.firstOrNull { it.id in draft.collections }?.name?.let { "Тема: " + it + if (draft.collections.size > 1) " +${draft.collections.size - 1}" else "" } ?: "Добавить тему") }
                        TextButton(onClick = { properties = !properties }) { Text("Теги: ${draft.tags.split(',').count { it.isNotBlank() }}") }
                    }
                    if (entry.anchor != null) { Text("Исходная цитата · страница ${entry.anchor.page + 1}", style = MaterialTheme.typography.labelMedium); Text(entry.quote) }
                    if (find) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, label = { Text("Найти в документе") }, modifier = Modifier.weight(1f).testTag("editor-search-field"))
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
                        }, { selection = it }, { vm.focusPane(pane); vm.focusEditorField(entry.id, "body") }, { nativeView = it }, label = when (entry.kind) { EntryKind.Consp -> "Текст конспекта"; EntryKind.Note -> "Текст заметки"; EntryKind.Quote -> "Комментарий к цитате" })
                    }
                }
              }
              if (properties && sideProperties) Surface(Modifier.width(340.dp).fillMaxHeight().testTag("note-properties-panel"), color = MaterialTheme.colorScheme.surfaceContainerLow) { propertiesContent(true) }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.kind != EntryKind.Consp) {
                    Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f).testTag("draft-status"))
                    TextButton(enabled = !saving && entry.title.isNotBlank(), onClick = { vm.saveEditor(entry.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("save-entry")) { Text("Сохранить") }
                } else {
                    val saved = data.entries.firstOrNull { it.id == entry.id }
                    val savedTags = data.entryTags.filter { it.entryId == entry.id }.mapNotNull { link -> data.tags.firstOrNull { it.id == link.tagId }?.name?.lowercase(java.util.Locale.ROOT) }.sorted()
                    val unchanged = saved?.copy(updatedAt = entry.updatedAt) == entry && savedTags == draft.tags.split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().sorted() && data.memberships.filter { it.entryId == entry.id }.map { it.collectionId }.toSet() == draft.collections.intersect(data.collections.map { it.id }.toSet())
                    Text("Конспект", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Box {
                        TextButton(onClick = { saveMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("entry-save-status")) { Text(when { saving -> "Сохранение…"; unchanged -> "Сохранено · История"; entry.title.isBlank() -> "Укажите название · История"; else -> "Изменения · История" }) }
                        DropdownMenu(saveMenu, { saveMenu = false }) {
                            ShortcutMenuItem("history", { saveMenu = false; history = true })
                            ShortcutMenuItem("save", { saveMenu = false; vm.saveEditor(entry.id) }, enabled = !saving && entry.title.isNotBlank(), label = "Сохранить сейчас", modifier = Modifier.testTag("save-entry"))
                            ShortcutMenuItem("checkpoint", { saveMenu = false; vm.checkpointDraft(entry.id, "Версия пользователя") })
                        }
                    }
                }
            }
        }
      }
      if (properties && !sideProperties) AlertDialog(onDismissRequest = { properties = false }, title = {}, text = { Box(Modifier.heightIn(max = 520.dp).testTag("note-properties-panel")) { propertiesContent(false) } }, confirmButton = { TextButton(onClick = { properties = false }, modifier = Modifier.testTag("close-properties-dialog")) { Text("Готово") } })
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
