package org.readerka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.readerka.R
import org.readerka.data.*

@Composable fun ReaderPanel(document: Document, entries: List<Entry>, bookmarks: List<Bookmark>, data: LibrarySnapshot, vm: LibraryViewModel,
    bookmarksTab: Boolean, onTab: (Boolean) -> Unit, onEdit: (Entry) -> Unit, onSource: (Entry) -> Unit,
    onBookmark: (Bookmark) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val kind = if (bookmarksTab) ReaderFolderKind.Bookmarks else ReaderFolderKind.Quotes
    val folders = data.readerFolders.filter { it.documentId == document.id && it.kind == kind }
    var quoteFolder by rememberSaveable(document.id) { mutableStateOf<String?>(null) }
    var bookmarkFolder by rememberSaveable(document.id) { mutableStateOf<String?>(null) }
    val chosen = if (bookmarksTab) bookmarkFolder else quoteFolder
    val selected = chosen?.takeIf { id -> folders.any { it.id == id } }
    var folderEditor by remember { mutableStateOf<FolderItem?>(null) }
    var createParent by remember { mutableStateOf<String?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var bookmarkEditor by remember { mutableStateOf<Bookmark?>(null) }
    var deleteBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var deleteEntry by remember { mutableStateOf<Entry?>(null) }
    val folderItems = folders.map { FolderItem(it.id, it.name, it.parentId) }
    fun included(id: String?): Boolean {
        if (selected == null) return true
        var current = id
        val parents = folders.associate { it.id to it.parentId }
        while (current != null) { if (current == selected) return true; current = parents[current] }
        return false
    }
    val visibleEntries = entries.filter { e -> selected == null || data.readerMemberships.any { it.entryId == e.id && included(it.folderId) } }
        .sortedWith(compareBy({ it.anchor?.page ?: Int.MAX_VALUE }, { it.anchor?.start ?: 0 }))
    val visibleBookmarks = bookmarks.filter { included(it.folderId) }
    val visibleIds = visibleBookmarks.map { it.id }.toSet()
    val orderedBookmarks = FolderTree.ordered(visibleBookmarks, { it.id }, { it.parentId?.takeIf { parent -> parent in visibleIds } })
    Column(modifier.padding(8.dp).testTag("reader-panel")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.structure_in_pdf), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close_reader_panel)) }
        }
        Row {
            FilterChip(!bookmarksTab, { onTab(false) }, label = { Text(stringResource(R.string.quotes_in_pdf)) })
            Spacer(Modifier.width(4.dp))
            FilterChip(bookmarksTab, { onTab(true) }, label = { Text(stringResource(R.string.bookmarks)) })
        }
        key(document.id, kind) {
            FolderBrowser(folderItems, selected, { if (bookmarksTab) bookmarkFolder = it else quoteFolder = it },
                Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 230.dp).weight(.42f),
                stringResource(if (bookmarksTab) R.string.all_bookmarks_pdf else R.string.all_quotes_pdf),
                onCreate = { createParent = it; createFolder = true }, onEdit = { folderEditor = it })
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (!bookmarksTab) {
                if (visibleEntries.isEmpty()) item { Text(stringResource(R.string.no_quotes_pdf), modifier = Modifier.padding(12.dp)) }
                items(visibleEntries, key = { it.id }) { e ->
                    Column {
                        EntryCard(e, data, { onEdit(e) }, { onSource(e) })
                        TextButton(onClick = { deleteEntry = e }, modifier = Modifier.testTag("delete-reader-quote-" + e.id)) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                if (visibleBookmarks.isEmpty()) item { Text(stringResource(R.string.no_bookmarks_pdf), modifier = Modifier.padding(12.dp)) }
                items(orderedBookmarks, key = { it.id }) { b ->
                    val depth = FolderTree.depth(b.id, visibleBookmarks.map { it.id to it.parentId?.takeIf { parent -> parent in visibleIds } })
                    Column(Modifier.padding(start = (depth * 14).coerceAtMost(84).dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        ListItem(headlineContent = { Text(b.title) }, supportingContent = {
                            Column {
                                Text(stringResource(R.string.source_page, b.page + 1))
                                if (b.comment.isNotBlank()) Text(b.comment)
                            }
                        }, modifier = Modifier.clickable { onBookmark(b) }.testTag("reader-bookmark-" + b.id))
                        Row {
                            TextButton(onClick = { onBookmark(b) }) { Text(stringResource(R.string.go)) }
                            IconButton(onClick = { bookmarkEditor = b }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                            IconButton(onClick = { deleteBookmark = b }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    if (createFolder || folderEditor != null) FolderEditor(folderEditor, folderItems, createParent, { createFolder = false; folderEditor = null },
        { vm.readerFolder(ReaderFolder(it.id, document.id, kind, it.name, it.parentId)) }, vm::deleteReaderFolder)
    bookmarkEditor?.let { b -> BookmarkEditor(b, data, { bookmarkEditor = null }, { vm.saveBookmark(it) }) }
    deleteBookmark?.let { b -> Confirm(R.string.delete_bookmark_title, R.string.delete_bookmark_body, { deleteBookmark = null }, { vm.deleteBookmark(b.id); deleteBookmark = null }) }
    deleteEntry?.let { e -> Confirm(R.string.delete_entries_title, R.string.delete_entries_body, { deleteEntry = null }, { vm.deleteEntries(setOf(e.id)); deleteEntry = null }) }
}

@Composable fun BookmarkEditor(bookmark: Bookmark, data: LibrarySnapshot, onDismiss: () -> Unit, onSave: (Bookmark) -> Unit) {
    var title by remember(bookmark.id) { mutableStateOf(bookmark.title) }
    var comment by remember(bookmark.id) { mutableStateOf(bookmark.comment) }
    var parent by remember(bookmark.id) { mutableStateOf(bookmark.parentId) }
    var folder by remember(bookmark.id) { mutableStateOf(bookmark.folderId) }
    val bookmarks = data.bookmarks.filter { it.documentId == bookmark.documentId }
    val folders = data.readerFolders.filter { it.documentId == bookmark.documentId && it.kind == ReaderFolderKind.Bookmarks }
    val allowed = bookmarks.filter { b -> runCatching {
        FolderTree.validate(bookmarks.filterNot { it.id == bookmark.id }.map { it.id to it.parentId } + (bookmark.id to b.id))
    }.isSuccess }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.bookmark_details)) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.source_page, bookmark.page + 1))
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.testTag("bookmark-title"))
            OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.comment)) })
            FolderChoice(stringResource(R.string.parent_bookmark), parent,
                allowed.map { FolderItem(it.id, it.title, it.parentId?.takeIf { id -> allowed.any { b -> b.id == id } }) },
                stringResource(R.string.no_parent_bookmark), { parent = it; if (it != null) folder = bookmarks.first { b -> b.id == it }.folderId })
            FolderChoice(stringResource(R.string.folder), folder, folders.map { FolderItem(it.id, it.name, it.parentId) }, stringResource(R.string.no_reader_folder), { folder = it })
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank(), onClick = { onSave(bookmark.copy(title = title.trim(), comment = comment, parentId = parent, folderId = folder)); onDismiss() }) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
