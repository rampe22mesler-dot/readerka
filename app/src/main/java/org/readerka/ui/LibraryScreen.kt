package org.readerka.ui

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

@Composable fun LibraryScreen(data: LibrarySnapshot, vm: LibraryViewModel, wide: Boolean, onImport: (String?) -> Unit, workspace: Boolean = false) {
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var recent by rememberSaveable { mutableStateOf(true) }
    var foldersVisible by rememberSaveable { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var editedFolder by remember { mutableStateOf<FolderItem?>(null) }
    var editedDocument by remember { mutableStateOf<Document?>(null) }
    val folders = data.documentFolders.map { FolderItem(it.id, it.name, it.parentId) }
    LaunchedEffect(folders) { if (folder != null && folders.none { it.id == folder }) folder = null }
    val docs = remember(data, search, folder, recent) {
        val tags = data.tags.associateBy { it.id }
        data.documents.filter { d -> (folder == null || d.folderId == folder) &&
            (search.isBlank() || listOf(d.title, d.authors, d.originalName,
                data.documentTags.filter { it.documentId == d.id }.mapNotNull { tags[it.tagId]?.name }.joinToString(" ")).any { it.contains(search.trim(), true) })
        }.let { if (recent) it.sortedByDescending { d -> d.lastOpenedAt } else it.sortedBy { d -> d.title } }
    }
    Row(Modifier.fillMaxSize()) {
        if (!workspace && (wide || foldersVisible)) {
            FolderPanel(R.string.my_document_folders, R.string.all_documents, folders, folder,
                { folder = it; if (!wide) foldersVisible = false }, { editedFolder = null; folderDialog = true },
                { editedFolder = it; folderDialog = true }, Modifier.width(if (wide) 240.dp else 200.dp).fillMaxHeight())
            VerticalDivider()
        }
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!workspace && !wide) IconButton(onClick = { foldersVisible = !foldersVisible }) { Icon(Icons.Default.Menu, stringResource(R.string.my_document_folders)) }
                Text(folders.firstOrNull { it.id == folder }?.name ?: stringResource(R.string.all_documents), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = { onImport(folder) }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.add_pdf)) }
            }
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_documents)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(recent, { recent = !recent }, label = { Text(stringResource(R.string.sort_recent)) })
                Spacer(Modifier.width(8.dp))
                if (!workspace && !wide) TextButton(onClick = { editedFolder = null; folderDialog = true }) { Text(stringResource(R.string.create_folder)) }
            }
            if (docs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(if (data.documents.isEmpty()) R.string.empty_library else R.string.no_search_results), style = MaterialTheme.typography.titleMedium) }
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(docs, key = { it.id }) { doc ->
                    Card(Modifier.fillMaxWidth().clickable { vm.open(doc) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(doc.title, style = MaterialTheme.typography.titleLarge)
                                if (doc.authors.isNotBlank()) Text(doc.authors, style = MaterialTheme.typography.bodyMedium)
                                Text(doc.originalName, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.page, doc.lastPage + 1, doc.pageCount), style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { editedDocument = doc }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                        }
                    }
                }
            }
        }
    }
    if (folderDialog) FolderEditor(editedFolder, folders, folder, { folderDialog = false }, { vm.folder(DocumentFolder(it.id, it.name, it.parentId)) }, { vm.deleteFolder(it, false) })
    editedDocument?.let { DocumentEditor(it, data, { editedDocument = null }, vm::saveDocument) }
}
