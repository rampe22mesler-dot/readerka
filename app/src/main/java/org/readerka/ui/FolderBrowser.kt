@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.readerka.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.readerka.R
import org.readerka.data.FolderTree

@Composable fun CataloguePath(folders: List<FolderItem>, selected: String?, root: String, onSelect: (String?) -> Unit) {
    val path = remember(folders, selected) {
        buildList {
            var current = selected
            while (current != null) {
                val folder = folders.firstOrNull { it.id == current } ?: break
                add(folder); current = folder.parentId
            }
        }.asReversed()
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) IconButton(onClick = { onSelect(folders.firstOrNull { it.id == selected }?.parentId) }, modifier = Modifier.testTag("catalogue-up")) { Icon(Outline.Left, "На уровень выше") }
        TextButton(onClick = { onSelect(null) }, modifier = Modifier.testTag("catalogue-root")) { Icon(Outline.Folder, null); Text(root) }
        path.forEach { folder ->
            Text("/")
            TextButton(onClick = { onSelect(folder.id) }, modifier = Modifier.testTag("catalogue-path-${folder.id}")) { Text(folder.name) }
        }
    }
}

/** Directory is an ordinary focusable/touchable catalogue item with explicit actions. */
@Composable fun CatalogueFolder(folder: FolderItem, detail: String, onOpen: () -> Unit, onCreate: () -> Unit, onEdit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> if (focused) { if (event.type == KeyEventType.KeyDown) onOpen(); true } else false
                    Key.F10 -> if (event.isShiftPressed) { if (event.type == KeyEventType.KeyDown) menu = true; true } else false
                    else -> false
                }
            }
            .then(if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, role = androidx.compose.ui.semantics.Role.Button)
            .testTag("catalogue-folder-${folder.id}").padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Outline.Folder, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Outline.Right, null, Modifier.size(20.dp))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("catalogue-folder-menu-${folder.id}")) { Icon(Outline.More, "Действия папки ${folder.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Открыть папку") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Создать подпапку") }, onClick = { menu = false; onCreate() })
                    DropdownMenuItem(text = { Text("Переименовать / переместить / удалить") }, onClick = { menu = false; onEdit() })
                }
            }
        }
    }
}

/** Shared tree for panels, folder filters and multiple memberships; only visible rows are composed. */
@Composable fun FolderBrowser(folders: List<FolderItem>, selected: String?, onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier, allLabel: String? = null, checked: Set<String>? = null,
    onToggle: (String) -> Unit = {}, onCreate: ((String?) -> Unit)? = null, onEdit: ((FolderItem) -> Unit)? = null) {
    val parents = remember(folders) { folders.map { it.id to it.parentId } }
    var expanded by rememberSaveable { mutableStateOf(FolderTree.ancestors(checked ?: setOfNotNull(selected), parents).toList()) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(selected, parents) {
        expanded = (expanded + FolderTree.ancestors(setOfNotNull(selected), parents)).distinct()
    }
    val rows = remember(folders, expanded, search) { FolderTree.visible(folders, { it.id }, { it.parentId }, { it.name }, expanded.toSet(), search) }
    Column(modifier.testTag("folder-browser")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_folders)) },
                singleLine = true, modifier = Modifier.weight(1f).testTag("folder-search"),
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Outline.Close, stringResource(R.string.clear_search)) } })
            if (onCreate != null) IconButton(onClick = { onCreate(null) }, modifier = Modifier.testTag("create-root-folder")) { Icon(Outline.Plus, stringResource(R.string.create_folder)) }
        }
        if (allLabel != null) TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth().testTag("all-folders")) {
            Text(allLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("folder-tree")) {
            if (rows.isEmpty()) item { Text(stringResource(if (search.isBlank()) R.string.empty_folders else R.string.no_search_results),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
            items(rows, key = { it.value.id }) { row ->
                val folder = row.value
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().background(if (checked == null && selected == folder.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .padding(start = (row.depth * 14).coerceAtMost(70).dp).testTag("folder-row-" + folder.id), verticalAlignment = Alignment.CenterVertically) {
                    if (row.hasChildren) IconButton(onClick = {
                        expanded = if (folder.id in expanded) expanded - folder.id else expanded + folder.id
                    }, modifier = Modifier.size(48.dp).testTag("expand-folder-" + folder.id)) {
                        Icon(if (folder.id in expanded || search.isNotBlank()) Outline.Down else Outline.Right,
                            stringResource(if (folder.id in expanded) R.string.collapse_folder else R.string.expand_folder), modifier = Modifier.size(20.dp))
                    } else Spacer(Modifier.width(40.dp))
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).combinedClickable(onLongClick = { if (onCreate != null || onEdit != null) menu = true }, onClick = {
                        if (checked != null) onToggle(folder.id) else { onSelect(folder.id); if (row.hasChildren) expanded = (expanded + folder.id).distinct() }
                    }).testTag("select-folder-" + folder.id), verticalAlignment = Alignment.CenterVertically) {
                        if (checked != null) Checkbox(folder.id in checked, onCheckedChange = null, modifier = Modifier.size(32.dp))
                        else Icon(painterResource(R.drawable.ic_folder), null, modifier = Modifier.size(18.dp))
                        Text(folder.name, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onCreate != null || onEdit != null) Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("folder-menu-" + folder.id)) { Icon(Outline.More, stringResource(R.string.folder_actions), modifier = Modifier.size(20.dp)) }
                        DropdownMenu(menu, { menu = false }) {
                            if (onCreate != null) DropdownMenuItem(text = { Text(stringResource(R.string.create_subfolder)) }, onClick = { menu = false; expanded = (expanded + folder.id).distinct(); onCreate(folder.id) })
                            if (onEdit != null) DropdownMenuItem(text = { Text(stringResource(R.string.edit_folder)) }, onClick = { menu = false; onEdit(folder) })
                        }
                    }
                }
            }
        }
    }
}

@Composable fun FolderChoice(label: String, selected: String?, folders: List<FolderItem>, emptyLabel: String,
    onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { opened = true }, modifier = modifier.fillMaxWidth()) {
        Text("$label: ${folders.firstOrNull { it.id == selected }?.name ?: emptyLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (opened) AlertDialog(onDismissRequest = { opened = false }, title = { Text(label) }, text = {
        FolderBrowser(folders, selected, { onSelect(it); opened = false }, Modifier.fillMaxWidth().height(420.dp), emptyLabel)
    }, confirmButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.close)) } })
}

@Composable fun FolderMembershipChoice(label: String, folders: List<FolderItem>, selected: Set<String>, onSelect: (Set<String>) -> Unit,
    modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(selected) }
    OutlinedButton(onClick = { pending = selected; opened = true }, modifier = modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.folder_count, selected.size), style = MaterialTheme.typography.labelMedium)
    }
    if (opened) AlertDialog(onDismissRequest = { opened = false }, title = { Text(label) }, text = {
        Column {
            Text(stringResource(R.string.multiple_folders_hint), style = MaterialTheme.typography.bodySmall)
            FolderBrowser(folders, null, {}, Modifier.fillMaxWidth().height(400.dp), checked = pending,
                onToggle = { pending = if (it in pending) pending - it else pending + it })
            TextButton(onClick = { pending = emptySet() }) { Text(stringResource(R.string.clear_selection)) }
        }
    }, confirmButton = { TextButton(onClick = { onSelect(pending); opened = false }) { Text(stringResource(R.string.apply_selection)) } },
        dismissButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.cancel)) } })
}
