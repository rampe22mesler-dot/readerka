@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.readerka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.readerka.R
import org.readerka.data.*
import java.util.UUID

fun NoteType.labelId() = when (this) {
    NoteType.Quote -> R.string.type_quote; NoteType.Summary -> R.string.type_summary
    NoteType.Question -> R.string.type_question; NoteType.Objection -> R.string.type_objection
    NoteType.Agreement -> R.string.type_agreement; NoteType.Idea -> R.string.type_idea
    NoteType.Connection -> R.string.type_connection; NoteType.GeneralNote -> R.string.type_general
}
fun Marker.labelId() = when (this) {
    Marker.Yellow -> R.string.color_yellow; Marker.Green -> R.string.color_green; Marker.Blue -> R.string.color_blue
    Marker.Pink -> R.string.color_pink; Marker.Purple -> R.string.color_purple; Marker.Orange -> R.string.color_orange
}
fun NoteSort.labelId() = when (this) {
    NoteSort.Newest -> R.string.sort_newest; NoteSort.Oldest -> R.string.sort_oldest; NoteSort.Source -> R.string.sort_title
    NoteSort.Author -> R.string.sort_author; NoteSort.Page -> R.string.sort_page; NoteSort.Type -> R.string.sort_type
}

@Composable fun <T> Choice(label: String, selected: T, options: List<T>, name: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${name(selected)}", maxLines = 2)
        }
        DropdownMenu(expanded, { expanded = false }) {
            for (option in options) DropdownMenuItem(text = { Text(name(option)) }, onClick = { expanded = false; onSelect(option) })
        }
    }
}

@Composable fun ColorPalette(selected: Marker?, onSelect: (Marker) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (color in Marker.entries) {
            val label = stringResource(color.labelId())
            IconButton(onClick = { onSelect(color) }, modifier = Modifier.semantics { contentDescription = label }) {
                Box(Modifier.size(30.dp).background(Color(color.argb), CircleShape), contentAlignment = Alignment.Center) {
                    if (selected == color) Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF26382C), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable fun Confirm(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete")) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

data class FolderItem(val id: String, val name: String, val parentId: String?)
@Composable fun FolderPanel(title: Int, allTitle: Int, folders: List<FolderItem>, selected: String?, onSelect: (String?) -> Unit, onCreate: (String?) -> Unit, onEdit: (FolderItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
        FolderBrowser(folders, selected, onSelect, Modifier.weight(1f).fillMaxWidth(), stringResource(allTitle), onCreate = onCreate, onEdit = onEdit)
    }
}

@Composable fun FolderEditor(initial: FolderItem?, folders: List<FolderItem>, defaultParent: String?, onDismiss: () -> Unit, onSave: (FolderItem) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var parent by remember { mutableStateOf(if (initial != null) initial.parentId else defaultParent) }
    var delete by remember { mutableStateOf(false) }
    val allowed = folders.filter { f ->
        if (initial == null) true else runCatching { FolderTree.validate(folders.filterNot { it.id == initial.id }.map { it.id to it.parentId } + (initial.id to f.id)) }.isSuccess
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.create_folder else R.string.edit)) },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true)
            FolderChoice(stringResource(R.string.parent_folder), parent, allowed, stringResource(R.string.root_folder), { parent = it })
            if (initial != null) TextButton(onClick = { delete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(FolderItem(initial?.id ?: UUID.randomUUID().toString(), name.trim(), parent)); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
    if (delete) Confirm(R.string.delete_folder_title, R.string.delete_folder_body, { delete = false }, { initial?.let { onDelete(it.id) }; onDismiss() })
}

@Composable fun DocumentEditor(document: Document, data: LibrarySnapshot, onDismiss: () -> Unit, onSave: (Document, String) -> Unit) {
    var title by remember { mutableStateOf(document.title) }; var authors by remember { mutableStateOf(document.authors) }
    var year by remember { mutableStateOf(document.year) }; var publisher by remember { mutableStateOf(document.publisher) }
    var doi by remember { mutableStateOf(document.doi) }; var url by remember { mutableStateOf(document.url) }
    var description by remember { mutableStateOf(document.description) }; var folder by remember { mutableStateOf(document.folderId) }
    var tags by remember { mutableStateOf(data.documentTags.filter { it.documentId == document.id }.mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }.joinToString(", ")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit)) }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.original_filename, document.originalName))
            for ((label, value, change) in listOf<Triple<Int, String, (String) -> Unit>>(
                Triple(R.string.title, title) { title = it }, Triple(R.string.authors, authors) { authors = it },
                Triple(R.string.year, year) { year = it }, Triple(R.string.publisher, publisher) { publisher = it },
                Triple(R.string.doi, doi) { doi = it }, Triple(R.string.url, url) { url = it }, Triple(R.string.description, description) { description = it }, Triple(R.string.tags, tags) { tags = it })) {
                OutlinedTextField(value, change, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
            }
            FolderChoice(stringResource(R.string.folder), folder, data.documentFolders.map { FolderItem(it.id, it.name, it.parentId) }, stringResource(R.string.root_folder), { folder = it })
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(document.copy(title = title.trim(), authors = authors, year = year, publisher = publisher, doi = doi, url = url, description = description, folderId = folder), tags); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable fun EntryCard(entry: Entry, data: LibrarySnapshot, onEdit: () -> Unit, onSource: () -> Unit, selected: Boolean? = null, onSelect: (() -> Unit)? = null, compact: Boolean = false, onRight: (() -> Unit)? = null) {
    val document = data.documents.firstOrNull { it.id == entry.documentId }
    val tagNames = data.entryTags.filter { it.entryId == entry.id }.mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }
    val folderNames = data.memberships.filter { it.entryId == entry.id }.mapNotNull { m -> data.collections.firstOrNull { it.id == m.collectionId }?.name }
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp).combinedClickable(onClick = onEdit, onLongClick = { menu = true }), colors = CardDefaults.cardColors(containerColor = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row {
            Box(Modifier.width(3.dp).heightIn(min = if (compact) 72.dp else 110.dp).background(Color(entry.color.argb)))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box {
                        WorkspaceAction(Outline.More, "Действия записи", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Открыть запись") }, onClick = { menu = false; onEdit() })
                            if (onRight != null) DropdownMenuItem(text = { Text("Открыть справа") }, onClick = { menu = false; onRight() })
                            if (document != null) DropdownMenuItem(text = { Text("Открыть источник") }, onClick = { menu = false; onSource() })
                            if (onSelect != null) DropdownMenuItem(text = { Text("Выбрать запись") }, onClick = { menu = false; onSelect() })
                        }
                    }
                    if (onSelect != null) {
                        val description = stringResource(R.string.select_entry, entry.title)
                        Checkbox(selected == true, { onSelect() }, modifier = Modifier.semantics { contentDescription = description })
                    }
                }
                if (entry.quote.isNotBlank()) Text(entry.quote, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (entry.comment.isNotBlank()) Text(entry.comment, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (document != null) Text(listOf(document.authors, document.title).filter { it.isNotBlank() }.joinToString(" — "), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.anchor?.let { Text(stringResource(R.string.source_page, it.page + 1), style = MaterialTheme.typography.labelMedium) }
                Text(stringResource(entry.type.labelId()) + if (tagNames.isEmpty()) "" else " · " + tagNames.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelMedium)
                if (folderNames.isNotEmpty()) Text(folderNames.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!compact) Row {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                    if (document != null) TextButton(onClick = onSource) { Text(stringResource(R.string.open_source)) }
                }
            }
        }
    }
}
