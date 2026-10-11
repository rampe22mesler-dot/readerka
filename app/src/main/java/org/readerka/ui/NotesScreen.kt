package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.readerka.R
import org.readerka.data.*
import org.readerka.export.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable fun NotesScreen(data: LibrarySnapshot, vm: LibraryViewModel, folder: String?, onFolder: (String?) -> Unit, onShowFolders: () -> Unit, onExport: (ExportKind, Set<String>?) -> Unit, consp: Boolean = false) {
    var flat by rememberSaveable { mutableStateOf(false) }
    val kind = if (consp) EntryKind.Consp else EntryKind.Note
    val rootLabel = if (consp) "Конспекты" else "Заметки и цитаты"
    var createConsp by remember { mutableStateOf(false) }
    var compactRows by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var filterDialog by remember { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf<NoteType?>(null) }
    var color by rememberSaveable { mutableStateOf<Marker?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(NoteSort.Newest) }
    var selectedList by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selected = selectedList.toSet()
    val workspace by vm.workspace.collectAsState()
    var folderEditor by remember { mutableStateOf(false) }
    var createParent by remember { mutableStateOf<String?>(null) }
    var editedFolder by remember { mutableStateOf<FolderItem?>(null) }
    var delete by remember { mutableStateOf(false) }
    var tagsDialog by remember { mutableStateOf(false) }
    var batchTags by remember { mutableStateOf("") }
    fun openFolder(id: String?) { flat = false; selectedList = emptyList(); onFolder(id) }
    val folders = data.collectionsFor(kind).map { FolderItem(it.id, it.name, it.parentId) }
    val dateMillis = remember(date) { runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }
    val entries = remember(data, search, folder, source, type, color, tag, dateMillis, sort, consp, flat) { catalogueEntries(data, kind, folder, flat, NoteQuery(search, documentId = source, type = type, color = color, tagId = tag, createdAfter = dateMillis, sort = sort)) }
    val children = if (flat) emptyList() else folders.filter { it.parentId == folder }.sortedBy { it.name.lowercase() }
    androidx.activity.compose.BackHandler(folder != null || flat) { if (flat) flat = false else openFolder(folders.firstOrNull { it.id == folder }?.parentId) }
    LaunchedEffect(folder) { flat = false; selectedList = emptyList() }
    LaunchedEffect(data) {
        selectedList = selectedList.filter { id -> data.entries.any { it.id == id } }
        if (source != null && data.documents.none { it.id == source }) source = null
        if (tag != null && data.tags.none { it.id == tag }) tag = null
        if (folder != null && folders.none { it.id == folder }) onFolder(null)
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(if (LocalCompact.current) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShowFolders) { Icon(Outline.Menu, stringResource(R.string.collections)) }
                Text(if (flat) (if (consp) "Все конспекты" else stringResource(R.string.all_notes)) else folders.firstOrNull { it.id == folder }?.name ?: if (consp) "Конспекты" else stringResource(R.string.all_notes), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (consp) createConsp = true else vm.newNote(collection = if (flat) null else folder) }) { Icon(Outline.Plus, null); Text(if (consp) "Создать конспект" else stringResource(R.string.create_note)) }
            }
            CataloguePath(folders, if (flat) null else folder, rootLabel, ::openFolder)
            OutlinedTextField(search, { search = it }, label = { Text(if (consp) "Поиск конспектов, тем и тегов" else stringResource(R.string.search_notes)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { flat = !flat; selectedList = emptyList() }, modifier = Modifier.testTag("catalogue-flat")) { Text(if (flat) "По папкам" else if (consp) "Все конспекты" else "Все записи") }
                TextButton(onClick = { compactRows = !compactRows }) { Text(if (compactRows) "Развёрнутый вид" else "Компактный вид") }
                TextButton(onClick = { filterDialog = true }) { Text(stringResource(R.string.filter)) }
                Choice(stringResource(R.string.sort), sort, NoteSort.entries, { stringResource(it.labelId()) }, { sort = it })
                TextButton(onClick = { createParent = if (flat) null else folder; editedFolder = null; folderEditor = true }) { Text(stringResource(R.string.create_folder)) }
                var exporting by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { exporting = true }) { Text(stringResource(R.string.export_selected)) }
                    DropdownMenu(exporting, { exporting = false }) {
                        for ((kind, label) in listOf(ExportKind.Markdown to R.string.export_markdown, ExportKind.Csv to R.string.export_csv)) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { exporting = false; onExport(kind, selected.ifEmpty { entries.map { it.id }.toSet() }) })
                    }
                }
            }
            val activeFilters = listOfNotNull(type?.let { stringResource(it.labelId()) }, color?.let { stringResource(it.labelId()) }, data.documents.firstOrNull { it.id == source }?.title, data.tags.firstOrNull { it.id == tag }?.name, date.takeIf { it.isNotBlank() })
            if (activeFilters.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(activeFilters.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { type = null; color = null; source = null; tag = null; date = "" }) { Text("Сбросить фильтры") }
            }
            if (selected.isNotEmpty()) {
                Text(stringResource(R.string.selected_count, selected.size), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderMembershipChoice(stringResource(R.string.add_to_collection), folders, emptySet(), { ids -> ids.forEach { vm.batch(selected, collection = it) } }, Modifier.width(230.dp))
                    if (folder != null) TextButton(onClick = { vm.removeFromCollection(folder, selected) }) { Text(stringResource(R.string.remove_from_collection)) }
                    Choice<NoteType?>(stringResource(R.string.type), null, NoteType.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { vm.batch(selected, type = it) })
                    Choice<Marker?>(stringResource(R.string.color), null, Marker.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { vm.batch(selected, color = it) })
                    TextButton(onClick = { tagsDialog = true }) { Text(stringResource(R.string.add_tags)) }
                    IconButton(onClick = { delete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    IconButton(onClick = { selectedList = emptyList() }) { Icon(Outline.Close, stringResource(R.string.clear_selection)) }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("catalogue-list")) {
                items(children, key = { "folder:${it.id}" }) { child ->
                    val count = data.memberships.filter { it.collectionId == child.id }.map { it.entryId }.distinct().size
                    val nested = folders.count { it.parentId == child.id }
                    CatalogueFolder(child, "$count записей · $nested папок", { openFolder(child.id) }, { createParent = child.id; editedFolder = null; folderEditor = true }, { editedFolder = child; createParent = child.parentId; folderEditor = true })
                }
                if (entries.isEmpty()) item {
                    Text(if (search.isNotBlank() || activeFilters.isNotEmpty()) "Совпадений не найдено" else if (children.isNotEmpty()) "Откройте папку выше. Здесь нет записей вне папок." else if (folder != null) "Папка пуста. Создайте запись или добавьте существующую через её свойства." else "Пока нет записей. Создайте запись или папку.", modifier = Modifier.padding(16.dp))
                }
                items(entries, key = { "entry:${it.id}" }) { e -> EntryCard(e, data, { vm.openNote(e) }, { vm.source(e) }, e.id in selected, { selectedList = if (e.id in selected) selectedList - e.id else selectedList + e.id }, compact = compactRows, onRight = { vm.openRight(WorkspaceTab(e.id, e.id, WorkspaceKind.Note)) }) }
            }
        }
    }
    if (createConsp) CreateConspDialog(data, null, if (flat) null else folder, vm) { createConsp = false }
    if (folderEditor) FolderEditor(editedFolder, folders, createParent, { folderEditor = false }, { vm.collection(org.readerka.data.Collection(it.id, it.name, it.parentId, kind.collectionScope())) }, { vm.deleteFolder(it, true) })

    if (delete) Confirm(R.string.delete_entries_title, R.string.delete_entries_body, { delete = false }, { vm.deleteEntries(selected); selectedList = emptyList(); delete = false })
    if (tagsDialog) AlertDialog(onDismissRequest = { tagsDialog = false }, title = { Text(stringResource(R.string.add_tags)) }, text = { OutlinedTextField(batchTags, { batchTags = it }, label = { Text(stringResource(R.string.tags)) }) },
        confirmButton = { TextButton(onClick = { vm.batch(selected, tags = batchTags); tagsDialog = false; batchTags = "" }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { tagsDialog = false }) { Text(stringResource(R.string.cancel)) } })
    if (filterDialog) AlertDialog(onDismissRequest = { filterDialog = false }, title = { Text(stringResource(R.string.filter)) }, text = {
        Column(Modifier.heightIn(max = 530.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.type), type, listOf(null) + NoteType.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { type = it })
            Choice(stringResource(R.string.color), color, listOf(null) + Marker.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { color = it })
            val recentIds = data.documents.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }.take(5).map { it.id }.toSet()
            val sources = data.documents.sortedWith(compareByDescending<Document> { it.id in recentIds }.thenByDescending { if (it.id in recentIds) it.lastOpenedAt else 0 }.thenBy { it.title.lowercase() })
                .map { SearchOption(it.id, it.title, it.authors, it.id in recentIds) }
            SearchChoice(stringResource(R.string.source), source, sources, stringResource(R.string.search_sources), { source = it })
            SearchChoice(stringResource(R.string.tag), tag, data.tags.sortedBy { it.name.lowercase() }.map { SearchOption(it.id, it.name) }, stringResource(R.string.search_tags), { tag = it })
            OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.created_after)) }, isError = date.isNotBlank() && dateMillis == null, supportingText = { if (date.isNotBlank() && dateMillis == null) Text(stringResource(R.string.invalid_date)) })
            TextButton(onClick = { type = null; color = null; source = null; tag = null; date = "" }) { Text(stringResource(R.string.reset_filters)) }
        }
    }, confirmButton = { TextButton(enabled = date.isBlank() || dateMillis != null, onClick = { filterDialog = false }) { Text(stringResource(R.string.close)) } })
}
