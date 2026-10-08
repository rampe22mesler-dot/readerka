package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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

@Composable fun NotesScreen(data: LibrarySnapshot, vm: LibraryViewModel, wide: Boolean, onExport: (ExportKind, Set<String>?) -> Unit) {
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var filterDialog by remember { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf<NoteType?>(null) }
    var color by rememberSaveable { mutableStateOf<Marker?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(NoteSort.Newest) }
    var selectedList by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selected = selectedList.toSet()
    var editor by remember { mutableStateOf<Entry?>(null) }
    var folderEditor by remember { mutableStateOf(false) }
    var editedFolder by remember { mutableStateOf<FolderItem?>(null) }
    var delete by remember { mutableStateOf(false) }
    var tagsDialog by remember { mutableStateOf(false) }
    var batchTags by remember { mutableStateOf("") }
    val folders = data.collections.map { FolderItem(it.id, it.name, it.parentId) }
    val dateMillis = remember(date) { runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }
    val entries = remember(data, search, folder, source, type, color, tag, dateMillis, sort) { NoteQuery(search, folder, source, type, color, tag, dateMillis, sort).apply(data) }
    LaunchedEffect(data) {
        selectedList = selectedList.filter { id -> data.entries.any { it.id == id } }
        if (folder != null && folders.none { it.id == folder }) folder = null
    }
    Row(Modifier.fillMaxSize()) {
        if (wide || showFolders) {
            FolderPanel(R.string.my_collections, R.string.all_notes, folders, folder, { folder = it; if (!wide) showFolders = false },
                { editedFolder = null; folderEditor = true }, { editedFolder = it; folderEditor = true }, Modifier.width(if (wide) 240.dp else 200.dp).fillMaxHeight())
            VerticalDivider()
        }
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!wide) IconButton(onClick = { showFolders = !showFolders }) { Icon(Icons.Default.Menu, stringResource(R.string.collections)) }
                Text(folders.firstOrNull { it.id == folder }?.name ?: stringResource(R.string.all_notes), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = { editor = Entry(UUID.randomUUID().toString(), "", color = vm.preferences.value.defaultColor) }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.create_note)) }
            }
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_notes)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { filterDialog = true }) { Text(stringResource(R.string.filter)) }
                Choice(stringResource(R.string.sort), sort, NoteSort.entries, { stringResource(it.labelId()) }, { sort = it })
                if (!wide) TextButton(onClick = { editedFolder = null; folderEditor = true }) { Text(stringResource(R.string.create_folder)) }
                var exporting by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { exporting = true }) { Text(stringResource(R.string.export_selected)) }
                    DropdownMenu(exporting, { exporting = false }) {
                        for ((kind, label) in listOf(ExportKind.Markdown to R.string.export_markdown, ExportKind.Csv to R.string.export_csv)) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { exporting = false; onExport(kind, selected.ifEmpty { entries.map { it.id }.toSet() }) })
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Text(stringResource(R.string.selected_count, selected.size), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice<String?>(stringResource(R.string.add_to_collection), null, data.collections.map { it.id }, { id -> data.collections.firstOrNull { it.id == id }?.name ?: stringResource(R.string.any_value) }, { id -> id?.let { vm.batch(selected, collection = it) } })
                    if (folder != null) TextButton(onClick = { vm.removeFromCollection(folder!!, selected) }) { Text(stringResource(R.string.remove_from_collection)) }
                    Choice<NoteType?>(stringResource(R.string.type), null, NoteType.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { vm.batch(selected, type = it) })
                    Choice<Marker?>(stringResource(R.string.color), null, Marker.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { vm.batch(selected, color = it) })
                    TextButton(onClick = { tagsDialog = true }) { Text(stringResource(R.string.add_tags)) }
                    IconButton(onClick = { delete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    IconButton(onClick = { selectedList = emptyList() }) { Icon(Icons.Default.Close, stringResource(R.string.clear_selection)) }
                }
            }
            if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(if (data.entries.isEmpty()) R.string.empty_notes else R.string.no_search_results)) }
            else LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.id }) { e -> EntryCard(e, data, { editor = e }, { vm.source(e) }, e.id in selected, { selectedList = if (e.id in selected) selectedList - e.id else selectedList + e.id }) }
            }
        }
    }
    if (folderEditor) FolderEditor(editedFolder, folders, folder, { folderEditor = false }, { vm.collection(org.readerka.data.Collection(it.id, it.name, it.parentId)) }, { vm.deleteFolder(it, true) })
    editor?.let { EntryEditor(it, data, { editor = null }, vm::saveEntry) }
    if (delete) Confirm(R.string.delete_entries_title, R.string.delete_entries_body, { delete = false }, { vm.deleteEntries(selected); selectedList = emptyList(); delete = false })
    if (tagsDialog) AlertDialog(onDismissRequest = { tagsDialog = false }, title = { Text(stringResource(R.string.add_tags)) }, text = { OutlinedTextField(batchTags, { batchTags = it }, label = { Text(stringResource(R.string.tags)) }) },
        confirmButton = { TextButton(onClick = { vm.batch(selected, tags = batchTags); tagsDialog = false; batchTags = "" }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { tagsDialog = false }) { Text(stringResource(R.string.cancel)) } })
    if (filterDialog) AlertDialog(onDismissRequest = { filterDialog = false }, title = { Text(stringResource(R.string.filter)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.type), type, listOf(null) + NoteType.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { type = it })
            Choice(stringResource(R.string.color), color, listOf(null) + Marker.entries, { it?.let { stringResource(it.labelId()) } ?: stringResource(R.string.any_value) }, { color = it })
            Choice(stringResource(R.string.source), source, listOf(null) + data.documents.map { it.id }, { id -> data.documents.firstOrNull { it.id == id }?.title ?: stringResource(R.string.any_value) }, { source = it })
            Choice(stringResource(R.string.tag), tag, listOf(null) + data.tags.map { it.id }, { id -> data.tags.firstOrNull { it.id == id }?.name ?: stringResource(R.string.any_value) }, { tag = it })
            OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.created_after)) }, isError = date.isNotBlank() && dateMillis == null, supportingText = { if (date.isNotBlank() && dateMillis == null) Text(stringResource(R.string.invalid_date)) })
        }
    }, confirmButton = { TextButton(enabled = date.isBlank() || dateMillis != null, onClick = { filterDialog = false }) { Text(stringResource(R.string.close)) } })
}
