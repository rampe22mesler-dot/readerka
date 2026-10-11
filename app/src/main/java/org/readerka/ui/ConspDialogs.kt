package org.readerka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.readerka.data.*

@Composable fun CreateConspDialog(data: LibrarySnapshot, initialBook: String?, collection: String?, vm: LibraryViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ConspTemplate.Blank) }
    var book by remember { mutableStateOf(initialBook) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Создать конспект") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Название конспекта") }, modifier = Modifier.testTag("new-consp-title"))
            Choice("Шаблон", template, ConspTemplate.entries, { it.title }, { template = it })
            SearchChoice("Книга", book, data.documents.map { SearchOption(it.id, it.title, it.authors) }, "Поиск книг", { book = it })
            Text("Связи с другими книгами, темы и теги можно добавить в свойствах.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { vm.newConsp(book, collection, title.trim(), RichText.fromMarkdown(template.source)); onDismiss() }, modifier = Modifier.testTag("create-consp-confirm")) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable fun ConspQuoteDialog(text: String, document: Document, page: Int, data: LibrarySnapshot, vm: LibraryViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val state by vm.workspace.collectAsState()
    val choices = (data.entries + state.drafts.map { it.entry }).distinctBy { it.id }.filter { it.kind == EntryKind.Consp && it.title.contains(query, true) }
        .sortedWith(compareByDescending<Entry> { it.id == state.lastConspByBook[document.id] }.thenByDescending { document.id in it.sourceBooks() }.thenByDescending { it.updatedAt })
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить в конспект") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Найти конспект") })
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(choices, key = { it.id }) { entry -> ListItem(headlineContent = { Text(entry.title) }, supportingContent = { Text(if (document.id in entry.sourceBooks()) "Связан с этой книгой" else "Другой конспект") }, modifier = Modifier.clickable { vm.appendToConsp(entry.id, text, document, page); onDismiss() }.testTag("append-consp-${entry.id}")) }
            }
            if (choices.isEmpty()) Text("Конспектов пока нет. Создайте новый ниже.")
        }
    }, confirmButton = { TextButton(onClick = { vm.createFromQuote(text, document, page); onDismiss() }) { Text("Создать конспект из выделения…") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable fun BookConspPanel(document: Document, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int, onClose: () -> Unit, modifier: Modifier, showHeader: Boolean = true) {
    var creating by remember { mutableStateOf(false) }
    Column(modifier.padding(8.dp).testTag("book-consp-panel")) {
        if (showHeader) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Конспекты · ${document.title}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onClose) { Text("Скрыть") }
        }
        TextButton(onClick = { creating = true }) { Text("Создать конспект по книге") }
        val entries = data.entries.filter { it.kind == EntryKind.Consp && document.id in it.sourceBooks() }
        if (entries.isEmpty()) Text("Связанных конспектов пока нет. Добавьте книгу в свойствах существующего конспекта или создайте новый.")
        LazyColumn(Modifier.weight(1f)) { items(entries, key = { it.id }) { entry ->
            ListItem(headlineContent = { Text(entry.title) }, supportingContent = { Text("${entry.sources.size} источников · ${entry.richText().text.take(100)}") }, modifier = Modifier.clickable { vm.layout(split = true); vm.openNote(entry, 1 - pane); onClose() }.testTag("book-consp-${entry.id}"))
        } }
    }
    if (creating) CreateConspDialog(data, document.id, null, vm) { creating = false }
}
