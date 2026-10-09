package org.readerka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.readerka.R
import org.readerka.data.*

private sealed interface TreeRow {
    data class Folder(val value: DocumentFolder, val depth: Int) : TreeRow
    data class Pdf(val value: Document, val depth: Int) : TreeRow
}

@Composable fun WorkspaceSidebar(data: LibrarySnapshot, active: String?, vm: LibraryViewModel, onImport: (String?) -> Unit, onOpened: () -> Unit, modifier: Modifier = Modifier, notes: Boolean = false, selectedCollection: String? = null, onCollection: (String?) -> Unit = {}) {
    if (notes) {
        CollectionSidebar(data, vm, selectedCollection, { onCollection(it); onOpened() }, modifier)
        return
    }
    var expanded by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var create by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<FolderItem?>(null) }
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = data.documentFolders.map { FolderItem(it.id, it.name, it.parentId) }
    LaunchedEffect(active, data.documentFolders) {
        val ancestors = mutableListOf<String>()
        var current = data.documents.firstOrNull { it.id == active }?.folderId
        while (current != null && current !in ancestors) { ancestors += current; current = data.documentFolders.firstOrNull { it.id == current }?.parentId }
        expanded = ArrayList((expanded + ancestors).distinct())
    }
    val rows = remember(data.documents, data.documentFolders, expanded) {
        buildList<TreeRow> {
            fun visit(id: String?, depth: Int) {
                for (folder in data.documentFolders.filter { it.parentId == id }.sortedBy { it.name.lowercase() }) {
                    add(TreeRow.Folder(folder, depth))
                    if (folder.id in expanded) visit(folder.id, depth + 1)
                }
                data.documents.filter { it.folderId == id }.sortedBy { it.title.lowercase() }.forEach { add(TreeRow.Pdf(it, depth)) }
            }
            visit(null, 0)
        }
    }
    Surface(modifier.testTag("workspace-sidebar"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workspace_files), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onImport(parent) }) { Icon(Icons.Default.Add, stringResource(R.string.add_pdf), modifier = Modifier.size(20.dp)) }
                IconButton(onClick = { parent = null; create = true; edited = null }) { Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.create_folder), modifier = Modifier.size(20.dp)) }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { vm.navigate("library"); onOpened() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.all_documents), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                if (rows.isEmpty()) item { Text(stringResource(R.string.empty_workspace), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                items(rows, key = { when (it) { is TreeRow.Folder -> "f:" + it.value.id; is TreeRow.Pdf -> "d:" + it.value.id } }) { row ->
                    when (row) {
                        is TreeRow.Folder -> Row(Modifier.fillMaxWidth().clickable {
                            parent = row.value.id
                            expanded = ArrayList(if (row.value.id in expanded) expanded - row.value.id else expanded + row.value.id)
                        }.padding(start = (8 + row.depth * 14).coerceAtMost(78).dp).testTag("workspace-folder-" + row.value.id), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (row.value.id in expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
                            Text(row.value.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                            var actions by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { actions = true }, modifier = Modifier.size(40.dp).testTag("workspace-folder-menu-" + row.value.id)) { Icon(Icons.Default.MoreVert, stringResource(R.string.folder_actions), modifier = Modifier.size(16.dp)) }
                                DropdownMenu(actions, { actions = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.create_subfolder)) }, onClick = { actions = false; parent = row.value.id; edited = null; create = true; expanded = (expanded + row.value.id).distinct() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_folder)) }, onClick = { actions = false; edited = FolderItem(row.value.id, row.value.name, row.value.parentId); create = true })
                                }
                            }
                        }
                        is TreeRow.Pdf -> Row(Modifier.padding(horizontal = 6.dp, vertical = 1.dp).fillMaxWidth()
                            .background(if (active == row.value.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(6.dp))
                            .clickable { vm.open(row.value); onOpened() }.padding(start = (14 + row.depth * 14).coerceAtMost(84).dp, end = 8.dp, top = 11.dp, bottom = 11.dp)
                            .testTag("workspace-document-" + row.value.id), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.List, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Text(row.value.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.workspace_counts, data.documents.size, data.entries.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
        }
    }
    if (create) FolderEditor(edited, folders, parent, { create = false }, { vm.folder(DocumentFolder(it.id, it.name, it.parentId)) }, { vm.deleteFolder(it, false) })
}

@Composable private fun CollectionSidebar(data: LibrarySnapshot, vm: LibraryViewModel, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier) {
    var create by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<FolderItem?>(null) }
    var parent by remember { mutableStateOf<String?>(null) }
    val folders = data.collections.map { FolderItem(it.id, it.name, it.parentId) }
    Surface(modifier.testTag("workspace-sidebar"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        FolderPanel(R.string.my_collections, R.string.all_notes, folders, selected, onSelect,
            { parent = it; edited = null; create = true }, { edited = it; create = true }, Modifier.fillMaxSize())
    }
    if (create) FolderEditor(edited, folders, parent, { create = false }, { vm.collection(org.readerka.data.Collection(it.id, it.name, it.parentId)) }, { vm.deleteFolder(it, true) })
}

@Composable fun DocumentTabs(documents: List<Document>, selected: String?, enabled: Boolean, onOpen: (Document) -> Unit, onClose: (String) -> Unit) {
    val scroll = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).testTag("document-tabs")) {
            documents.forEach { document ->
                val active = selected == document.id
                Surface(color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.width(210.dp)) {
                        Row(Modifier.fillMaxWidth().height(44.dp)
                            .clickable(enabled = enabled) { onOpen(document) }.padding(start = 12.dp).testTag("document-tab-" + document.id), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.List, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Text(document.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            IconButton(enabled = enabled, onClick = { onClose(document.id) }, modifier = Modifier.size(40.dp).testTag("close-tab-" + document.id)) { Icon(Icons.Default.Close, stringResource(R.string.close_document), modifier = Modifier.size(16.dp)) }
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                VerticalDivider(Modifier.height(46.dp))
            }
        }
    }
}
