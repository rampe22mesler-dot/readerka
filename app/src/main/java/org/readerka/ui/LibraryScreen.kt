package org.readerka.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable fun LibraryScreen(data: LibrarySnapshot, vm: LibraryViewModel, wide: Boolean, onImport: (String?) -> Unit, workspace: Boolean = false, folder: String? = null, onFolder: (String?) -> Unit = {}) {
    var flat by rememberSaveable { mutableStateOf(false) }
    fun openFolder(id: String?) { flat = false; onFolder(id) }
    var search by rememberSaveable { mutableStateOf("") }
    var recent by rememberSaveable { mutableStateOf(true) }
    var foldersVisible by rememberSaveable { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var createParent by remember { mutableStateOf<String?>(null) }
    var editedFolder by remember { mutableStateOf<FolderItem?>(null) }
    var editedDocument by remember { mutableStateOf<Document?>(null) }
    val folders = data.documentFolders.map { FolderItem(it.id, it.name, it.parentId) }
    LaunchedEffect(folders) { if (folder != null && folders.none { it.id == folder }) onFolder(null) }
    val docs = remember(data, search, folder, recent, flat) {
        val tags = data.tags.associateBy { it.id }
        val descendants = folder?.let { FolderTree.descendants(it, folders.map { f -> f.id to f.parentId }) }
        data.documents.filter { d -> (flat || (if (search.isNotBlank()) descendants == null || d.folderId in descendants else d.folderId == folder)) &&
            (search.isBlank() || listOf(d.title, d.authors, d.originalName,
                data.documentTags.filter { it.documentId == d.id }.mapNotNull { tags[it.tagId]?.name }.joinToString(" ")).any { it.contains(search.trim(), true) })
        }.let { if (recent) it.sortedByDescending { d -> d.lastOpenedAt } else it.sortedBy { d -> d.title } }
    }
    val children = if (flat) emptyList() else folders.filter { it.parentId == folder }.sortedBy { it.name.lowercase() }
    androidx.activity.compose.BackHandler(folder != null || flat) { if (flat) flat = false else openFolder(folders.firstOrNull { it.id == folder }?.parentId) }
    LaunchedEffect(folder) { flat = false }
    Row(Modifier.fillMaxSize()) {
        if (!workspace && (wide || foldersVisible)) {
            FolderPanel(R.string.my_document_folders, R.string.all_documents, folders, folder,
                { openFolder(it); if (!wide) foldersVisible = false }, { createParent = it; editedFolder = null; folderDialog = true },
                { editedFolder = it; folderDialog = true }, Modifier.width(if (wide) 240.dp else 200.dp).fillMaxHeight())
            VerticalDivider()
        }
        Column(Modifier.weight(1f).padding(if (LocalCompact.current) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!workspace && !wide) IconButton(onClick = { foldersVisible = !foldersVisible }) { Icon(Outline.Menu, stringResource(R.string.my_document_folders)) }
                Text(if (flat) stringResource(R.string.all_documents) else folders.firstOrNull { it.id == folder }?.name ?: stringResource(R.string.all_documents), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onImport(if (flat) null else folder) }) { Icon(Outline.Plus, null); Text(stringResource(R.string.add_pdf)) }
            }
            CataloguePath(folders, if (flat) null else folder, "Библиотека PDF", ::openFolder)
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_documents)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Outline.Search, null) })
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { flat = !flat }, modifier = Modifier.testTag("catalogue-flat")) { Text(if (flat) "По папкам" else "Все документы") }
                FilterChip(recent, { recent = !recent }, label = { Text(stringResource(R.string.sort_recent)) })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { createParent = if (flat) null else folder; editedFolder = null; folderDialog = true }) { Text(stringResource(R.string.create_folder)) }
            }
            LazyColumn(Modifier.weight(1f).testTag("catalogue-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(children, key = { "folder:${it.id}" }) { child ->
                    CatalogueFolder(child, "${data.documents.count { it.folderId == child.id }} PDF · ${folders.count { it.parentId == child.id }} папок", { openFolder(child.id) }, { createParent = child.id; editedFolder = null; folderDialog = true }, { editedFolder = child; folderDialog = true })
                }
                if (docs.isEmpty()) item { Text(if (search.isNotBlank()) "Совпадений не найдено" else if (children.isNotEmpty()) "Откройте папку выше. Здесь нет PDF вне папок." else if (folder != null) "Папка пуста. Добавьте PDF в эту папку." else stringResource(R.string.empty_library), modifier = Modifier.padding(16.dp)) }
                items(docs, key = { it.id }) { doc ->
                    Card(Modifier.fillMaxWidth().clickable { vm.open(doc) }) {
                        Row(Modifier.padding(if (LocalCompact.current) 12.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(doc.title, style = MaterialTheme.typography.titleLarge)
                                if (doc.authors.isNotBlank()) Text(doc.authors, style = MaterialTheme.typography.bodyMedium)
                                Text(doc.originalName, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.page, doc.lastPage + 1, doc.pageCount), style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { editedDocument = doc }) { Icon(Outline.Edit, stringResource(R.string.edit)) }
                        }
                    }
                }
            }
        }
    }
    if (folderDialog) FolderEditor(editedFolder, folders, createParent, { folderDialog = false }, { vm.folder(DocumentFolder(it.id, it.name, it.parentId)) }, { vm.deleteFolder(it, false) })
    editedDocument?.let { DocumentEditor(it, data, { editedDocument = null }, vm::saveDocument) }
}
