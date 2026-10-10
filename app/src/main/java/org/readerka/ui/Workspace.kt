@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.readerka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.readerka.data.*
import java.util.UUID

private data class ExplorerRow(val id: String, val depth: Int, val folder: FolderItem? = null, val document: Document? = null, val entry: Entry? = null, val collection: String? = null)

/** Mouse drag only. Touch has long-press menus and explicit folder choice; no hidden action. */
private fun Modifier.mouseDrag(onStart: () -> Unit, onMove: (Offset) -> Unit, onDrop: () -> Unit, onCancel: () -> Unit = {}): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    pointerInput(Unit) { awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Mouse) return@awaitEachGesture
        var dragging = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (dragging && event.type == PointerEventType.Release) { change.consume(); drop(); released = true }
                    break
                }
                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) { dragging = true; start() }
                if (dragging) { change.consume(); move(change.position) }
            }
        } finally { if (dragging && !released) cancel() }
    } }
}

@Composable fun WorkspaceSidebar(data: LibrarySnapshot, active: String?, vm: LibraryViewModel, onImport: (String?) -> Unit, onOpened: () -> Unit, modifier: Modifier = Modifier, notes: Boolean = false, selectedCollection: String? = null, onCollection: (String?) -> Unit = {}) {
    var mode by rememberSaveable { mutableStateOf(if (notes) "notes" else "pdf") }
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<FolderItem?>(null) }
    var parent by remember { mutableStateOf<String?>(null) }
    var movingPdf by remember { mutableStateOf<Document?>(null) }
    var assigning by remember { mutableStateOf<Entry?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    val folders = if (mode == "pdf") data.documentFolders.map { FolderItem(it.id, it.name, it.parentId) } else data.collections.map { FolderItem(it.id, it.name, it.parentId) }
    LaunchedEffect(notes) { if (notes) mode = "notes" }
    LaunchedEffect(active, data.documentFolders) {
        val folder = data.documents.firstOrNull { it.id == active }?.folderId
        if (folder != null) expanded = (expanded + folder + FolderTree.ancestors(setOf(folder), data.documentFolders.map { it.id to it.parentId })).distinct()
    }
    val targets = remember(mode) { mutableMapOf<String?, Rect>() }
    var payload by remember { mutableStateOf<ExplorerRow?>(null) }
    var dropPoint by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    fun drop() {
        val folder = targets.entries.lastOrNull { it.value.contains(dropPoint) }?.key
        if (targets.values.any { it.contains(dropPoint) }) payload?.let { row ->
            if (row.document != null && mode == "pdf") vm.saveDocument(row.document.copy(folderId = folder), data.documentTags.filter { it.documentId == row.document.id }.mapNotNull { l -> data.tags.firstOrNull { it.id == l.tagId }?.name }.joinToString(", "))
            if (row.entry != null && mode == "notes" && folder != null) vm.batch(setOf(row.entry.id), collection = folder)
            if (row.folder != null && row.folder.id != folder && runCatching { FolderTree.validate(folders.map { it.id to if (it.id == row.folder.id) folder else it.parentId }) }.isSuccess) {
                if (mode == "pdf") vm.folder(DocumentFolder(row.folder.id, row.folder.name, folder)) else vm.collection(org.readerka.data.Collection(row.folder.id, row.folder.name, folder))
            }
        }
        dragging = false; payload = null
    }
    val rows = remember(data, mode, search, expanded) {
        buildList {
            fun visit(parent: String?, depth: Int) {
                folders.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }.forEach { f ->
                    add(ExplorerRow("f:${f.id}", depth, folder = f))
                    if (f.id in expanded || search.isNotBlank()) visit(f.id, depth + 1)
                }
                if (mode == "pdf") data.documents.filter { it.folderId == parent }.sortedBy { it.title.lowercase() }.forEach { add(ExplorerRow("d:${it.id}", depth, document = it)) }
                else data.entries.filter { e -> if (parent == null) data.memberships.none { it.entryId == e.id } else data.memberships.any { it.entryId == e.id && it.collectionId == parent } }.sortedBy { it.title.lowercase() }.forEach { add(ExplorerRow("e:$parent:${it.id}", depth, entry = it, collection = parent)) }
            }
            visit(null, 0)
        }.filter { search.isBlank() || it.folder != null || (it.document?.title ?: it.entry?.title ?: "").contains(search, true) }
    }
    Surface(modifier.testTag("workspace-sidebar"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Проводник", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                WorkspaceAction(Outline.Plus, if (mode == "pdf") "Добавить PDF" else "Заметка в выбранной папке", { if (mode == "pdf") onImport(selected) else { vm.newNote(collection = selected); onOpened() } })
                WorkspaceAction(Outline.Folder, "Создать папку", { parent = null; edited = null; editor = true })
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { mode = "pdf"; selected = null }, modifier = Modifier.weight(1f)) { Text("PDF", color = if (mode == "pdf") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { mode = "notes"; selected = selectedCollection }, modifier = Modifier.weight(1f)) { Text("Заметки", color = if (mode == "notes") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(if (mode == "pdf") "Папки библиотеки PDF" else "Общие папки · ссылки на записи", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            OutlinedTextField(search, { search = it }, singleLine = true, placeholder = { Text("Поиск в проводнике") }, modifier = Modifier.fillMaxWidth().padding(8.dp), leadingIcon = { Icon(Outline.Search, null, Modifier.size(18.dp)) })
            TextButton(onClick = { selected = null; if (mode == "pdf") vm.navigate("library") else { onCollection(null); vm.navigate("notes") }; onOpened() }, modifier = Modifier.fillMaxWidth().onGloballyPositioned { targets[null] = it.boundsInRoot() }) { Text(if (mode == "pdf") "Все документы" else "Все заметки") }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                if (rows.isEmpty()) item { Text("Пока пусто", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                items(rows, key = { it.id }) { row ->
                    DisposableEffect(row.folder?.id, targets) {
                        onDispose { row.folder?.id?.let { targets.remove(it) } }
                    }
                    var menu by remember { mutableStateOf(false) }
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    val label = row.folder?.name ?: row.document?.title ?: row.entry?.title.orEmpty()
                    val isActive = row.folder?.id?.let { it == selected } ?: (row.document?.id == active && row.document != null)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).onGloballyPositioned { c -> origin = c.boundsInRoot().topLeft; row.folder?.let { targets[it.id] = c.boundsInRoot() } }
                        .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .mouseDrag({ payload = row; dragging = true }, { dropPoint = origin + it }, ::drop, { dragging = false; payload = null })
                        .combinedClickable(onClick = {
                            when {
                                row.folder != null -> { selected = row.folder.id; expanded = if (row.folder.id in expanded) expanded - row.folder.id else expanded + row.folder.id }
                                row.document != null -> { vm.open(row.document); onOpened() }
                                row.entry != null -> { vm.openNote(row.entry); onOpened() }
                            }
                        }, onLongClick = { menu = true })
                        .padding(start = (8 + row.depth * 12).coerceAtMost(68).dp).testTag("explorer-${row.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (row.folder != null) { if (row.folder.id in expanded) Outline.Down else Outline.Right } else if (row.document != null) Outline.File else Outline.Note, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (rename == row.folder?.id && rename != null) {
                            BasicTextField(renameText, { renameText = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.weight(1f).padding(6.dp).testTag("rename-folder"))
                            WorkspaceAction(Outline.Save, "Сохранить имя", {
                                row.folder?.let { f -> if (renameText.isNotBlank()) { if (mode == "pdf") vm.folder(DocumentFolder(f.id, renameText.trim(), f.parentId)) else vm.collection(org.readerka.data.Collection(f.id, renameText.trim(), f.parentId)); rename = null } }
                            })
                        } else Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        Box {
                            WorkspaceAction(Outline.More, "Действия: $label", { menu = true }, Modifier.testTag("explorer-menu-${row.id}"))
                            DropdownMenu(menu, { menu = false }) {
                                row.folder?.let { f ->
                                    DropdownMenuItem(text = { Text("Создать подпапку") }, onClick = { menu = false; parent = f.id; edited = null; editor = true; expanded = (expanded + f.id).distinct() })
                                    if (mode == "notes") DropdownMenuItem(text = { Text("Создать заметку здесь") }, onClick = { menu = false; vm.newNote(collection = f.id); onOpened() })
                                    DropdownMenuItem(text = { Text("Переименовать на месте") }, onClick = { menu = false; rename = f.id; renameText = f.name })
                                    DropdownMenuItem(text = { Text("Переместить / удалить папку") }, onClick = { menu = false; edited = f; parent = f.parentId; editor = true })
                                }
                                row.document?.let { d ->
                                    DropdownMenuItem(text = { Text("Открыть справа") }, onClick = { menu = false; vm.openRight(WorkspaceTab(d.id, d.id, WorkspaceKind.Pdf)); onOpened() })
                                    DropdownMenuItem(text = { Text("Переместить") }, onClick = { menu = false; movingPdf = d })
                                }
                                row.entry?.let { e ->
                                    DropdownMenuItem(text = { Text("Редактировать") }, onClick = { menu = false; vm.editEntry(e); onOpened() })
                                    DropdownMenuItem(text = { Text("Открыть справа") }, onClick = { menu = false; vm.openRight(WorkspaceTab(e.id, e.id, WorkspaceKind.Note)); onOpened() })
                                    DropdownMenuItem(text = { Text("Добавить в папку") }, onClick = { menu = false; assigning = e })
                                    row.collection?.let { collection -> DropdownMenuItem(text = { Text("Убрать из этой папки") }, onClick = { menu = false; vm.removeFromCollection(collection, setOf(e.id)) }) }
                                }
                            }
                        }
                    }
                }
            }
            if (dragging) Text("Отпустите над папкой · запись остаётся одной", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
            HorizontalDivider()
            Text("${data.documents.size} PDF · ${data.entries.size} записей", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
    }
    if (editor) FolderEditor(edited, folders, parent, { editor = false }, { f -> if (mode == "pdf") vm.folder(DocumentFolder(f.id, f.name, f.parentId)) else vm.collection(org.readerka.data.Collection(f.id, f.name, f.parentId)) }, { vm.deleteFolder(it, mode == "notes") })
    movingPdf?.let { d -> AlertDialog(onDismissRequest = { movingPdf = null }, title = { Text("Переместить PDF") }, text = { FolderBrowser(data.documentFolders.map { FolderItem(it.id, it.name, it.parentId) }, d.folderId, { folder -> vm.saveDocument(d.copy(folderId = folder), data.documentTags.filter { it.documentId == d.id }.mapNotNull { l -> data.tags.firstOrNull { it.id == l.tagId }?.name }.joinToString(", ")); movingPdf = null }, Modifier.heightIn(max = 420.dp).fillMaxWidth(), "Корень библиотеки") }, confirmButton = { TextButton(onClick = { movingPdf = null }) { Text("Отмена") } }) }
    assigning?.let { e -> AlertDialog(onDismissRequest = { assigning = null }, title = { Text("Добавить в общие папки") }, text = { FolderMembershipChoice("Выбрать папки", data.collections.map { FolderItem(it.id, it.name, it.parentId) }, data.memberships.filter { it.entryId == e.id }.map { it.collectionId }.toSet(), { ids -> ids.forEach { vm.batch(setOf(e.id), collection = it) }; assigning = null }) }, confirmButton = { TextButton(onClick = { assigning = null }) { Text("Закрыть") } }) }
}

@Composable fun WorkspaceTabs(model: WorkspacePane, pane: Int, data: LibrarySnapshot, drafts: List<EntryDraft>, activePane: Boolean, vm: LibraryViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("document-tabs-$pane")) {
                model.tabs.forEach { tab ->
                    val selected = model.selectedId == tab.id
                    val title = if (tab.kind == WorkspaceKind.Pdf) data.documents.firstOrNull { it.id == tab.documentId }?.title else drafts.firstOrNull { it.entry.id == tab.documentId }?.entry?.title ?: data.entries.firstOrNull { it.id == tab.documentId }?.title
                    var menu by remember { mutableStateOf(false) }
                    var dragStart by remember { mutableStateOf<Float?>(null) }
                    var dragDelta by remember { mutableFloatStateOf(0f) }
                    Row(Modifier.mouseDrag({ dragStart = null; dragDelta = 0f }, { if (dragStart == null) dragStart = it.x; dragDelta = it.x - (dragStart ?: it.x) }, { if (kotlin.math.abs(dragDelta) > 60) vm.moveTab(pane, tab.id, if (dragDelta > 0) 1 else -1) }).width(248.dp).heightIn(min = 48.dp).background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .combinedClickable(onClick = { vm.selectTab(pane, tab.id) }, onLongClick = { menu = true }).testTag("workspace-tab-${tab.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (tab.pinned) Outline.Pin else if (tab.kind == WorkspaceKind.Pdf) Outline.File else Outline.Note, null, Modifier.padding(start = 10.dp).size(18.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(title?.ifBlank { "Без названия" } ?: "Документ недоступен", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                        Box {
                            WorkspaceAction(Outline.More, "Действия вкладки", { menu = true }, Modifier.testTag("tab-menu-${tab.id}"))
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(if (tab.pinned) "Открепить" else "Закрепить") }, onClick = { menu = false; vm.pinTab(pane, tab.id) })
                                DropdownMenuItem(text = { Text("Открыть справа") }, onClick = { menu = false; vm.openRight(tab, pane) })
                                DropdownMenuItem(text = { Text("Изменить порядок: левее") }, enabled = model.tabs.indexOf(tab) > 0, onClick = { menu = false; vm.moveTab(pane, tab.id, -1) })
                                DropdownMenuItem(text = { Text("Изменить порядок: правее") }, enabled = model.tabs.indexOf(tab) < model.tabs.lastIndex, onClick = { menu = false; vm.moveTab(pane, tab.id, 1) })
                                DropdownMenuItem(text = { Text("Закрыть вкладку") }, onClick = { menu = false; vm.closeTab(pane, tab.id) })
                            }
                        }
                        if (!tab.pinned) WorkspaceAction(Outline.Close, "Закрыть вкладку, сохранив работу", { vm.closeTab(pane, tab.id) }, Modifier.testTag("close-tab-${tab.id}"))
                    }
                    VerticalDivider(Modifier.height(48.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (activePane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}
