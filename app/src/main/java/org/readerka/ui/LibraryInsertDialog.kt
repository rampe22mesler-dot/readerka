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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.readerka.data.*

enum class InsertMode(val label: String) { Inline("Ссылка на текст"), Card("Ссылка блоком"), Content("Вставить содержимое") }

@Composable fun LibraryInsertDialog(data: LibrarySnapshot, current: Entry, selectedText: String, initialMode: InsertMode = InsertMode.Content,
    onDismiss: () -> Unit, onInsert: (RichText, String?) -> Unit) {
    var mode by remember { mutableStateOf(initialMode) }
    var search by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(current.sourceBooks().isNotEmpty()) }
    var kind by remember { mutableStateOf<EntryKind?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    var book by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<Entry?>(null) }
    var fragment by remember { mutableStateOf(TextFieldValue()) }
    var quoteComment by remember { mutableStateOf(false) }
    var useFragment by remember { mutableStateOf(false) }
    var block by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf(selectedText) }
    var plain by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(true) }
    var pdf by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("1") }
    val entries = NoteQuery(search, folder, book, tagId = tag).apply(data).filter { it.id != current.id && (kind == null || it.kind == kind) && (!scope || it.sourceBooks().any { id -> id in current.sourceBooks() }) }
    val chosen = data.entries.filter { it.id in selected }
    val pdfDocument = data.documents.firstOrNull { it.id == pdf }
    val validPage = page.toIntOrNull()?.let { it in 1..(pdfDocument?.pageCount ?: 0) } == true
    val canInsert = if (pdf != null) mode != InsertMode.Content && validPage else chosen.isNotEmpty() && (mode != InsertMode.Inline || chosen.size == 1)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Вставить из библиотеки") }, text = {
        Column(Modifier.heightIn(max = 570.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Способ", mode, InsertMode.entries, { it.label }, { mode = it })
            OutlinedTextField(search, { search = it }, label = { Text("Поиск записей") }, modifier = Modifier.testTag("insert-search"))
            if (current.sourceBooks().isNotEmpty()) Row { Checkbox(scope, { scope = it }, modifier = Modifier.testTag("insert-related-scope")); Text("Материалы связанных книг") }
            Choice("Тип", kind, listOf(null) + EntryKind.entries, { when (it) { EntryKind.Quote -> "Цитата"; EntryKind.Note -> "Заметка"; EntryKind.Consp -> "Конспект"; null -> "Все" } }, { kind = it; folder = null })
            FolderChoice("Тема / папка", folder, data.collections.filter { kind == null || it.scope == kind!!.collectionScope() }.map { FolderItem(it.id, (if (it.parentId == null) (if (it.scope == CollectionScope.Consp) "Конспекты · " else "Заметки и цитаты · ") else "") + it.name, it.parentId) }, "Все темы", { folder = it })
            SearchChoice("Тег", tag, data.tags.map { SearchOption(it.id, it.name) }, "Поиск тегов", { tag = it })
            SearchChoice("Книга", book, data.documents.map { SearchOption(it.id, it.title) }, "Поиск книг", { book = it })
            Column {
                entries.take(100).forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(entry.id in selected, { checked -> selected = if (checked) { if (mode == InsertMode.Inline) setOf(entry.id) else selected + entry.id } else selected - entry.id; pdf = null }, Modifier.testTag("insert-select-${entry.id}"))
                        TextButton(onClick = { preview = entry; fragment = TextFieldValue(entry.libraryContent(quoteComment).text); block = null; useFragment = false }, modifier = Modifier.weight(1f)) { Text(entry.title) }
                    }
                }
                if (entries.size > 100) Text("Уточните поиск: показаны первые 100 записей.")
                if (entries.isEmpty()) Text("Нет подходящих записей")
            }
            preview?.let { entry ->
                HorizontalDivider(); Text("Предпросмотр · ${entry.title}", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(fragment, { fragment = it }, readOnly = true, label = { Text("Текст · можно выделить фрагмент") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
                if (mode == InsertMode.Content && chosen.size <= 1) Row { Checkbox(useFragment, { useFragment = it }, enabled = !fragment.selection.collapsed); Text("Вставить выделенный фрагмент") }
                if (mode != InsertMode.Content) Choice("Назначение ссылки", block, listOf(null) + (if (entry.anchor != null && entry.documentId != null) listOf("source-page") else emptyList()) + entry.richText().blocks.filter { entry.richText().blockEnd(it) > it.start }.map { it.id }, { id ->
                    if (id == null) "Вся запись" else if (id == "source-page") "Страница источника · ${(entry.anchor?.page ?: 0) + 1}" else entry.richText().blocks.firstOrNull { it.id == id }?.let { entry.richText().text.substring(it.start, entry.richText().blockEnd(it)).take(60) }.orEmpty()
                }, { block = it })
            }
            if (mode != InsertMode.Content) {
                OutlinedTextField(label, { label = it }, label = { Text("Подпись ссылки (необязательно)") }, modifier = Modifier.testTag("insert-label"))
                SearchChoice("Ссылка на PDF", pdf, data.documents.map { SearchOption(it.id, it.title) }, "Поиск PDF", { pdf = it; if (it != null) selected = emptySet() })
                if (pdf != null) OutlinedTextField(page, { page = it }, label = { Text("Страница PDF") }, isError = !validPage)
            } else {
                if (chosen.any { it.quote.isNotBlank() }) Row {
                    Checkbox(quoteComment, { quoteComment = it; preview?.let { entry -> fragment = TextFieldValue(entry.libraryContent(it).text); useFragment = false } }); Text("Включить комментарий к цитате")
                }
                Row { Checkbox(plain, { plain = it }); Text("Только текст") }
                Row { Checkbox(source, { source = it }); Text("Добавить ссылку на источник") }
            }
        }
    }, confirmButton = { TextButton(enabled = canInsert, onClick = {
        val targets = if (pdfDocument != null) listOf(Triple(pdfDocument.title, ResearchLinks.document(pdfDocument.id, page.toInt()), null)) else chosen.map { e ->
            val targetBlock = block.takeIf { preview?.id == e.id }
            val target = if ((targetBlock == "source-page" || mode == InsertMode.Content) && e.anchor != null && e.documentId != null) ResearchLinks.document(requireNotNull(e.documentId), requireNotNull(e.anchor).page + 1) + "&entry=" + e.id else ResearchLinks.entry(e.id, targetBlock)
            Triple(e.title, target, e)
        }
        var result = RichText()
        targets.forEachIndexed { index, (title, target, entry) ->
            if (index > 0) result = result.replace(result.text.length, result.text.length, RichText.plain("\n\n"))
            val caption = label.ifBlank { title }
            val value = when (mode) {
                InsertMode.Inline -> RichText.plain(caption).style(0, caption.length, TextStyle.Link, target)
                InsertMode.Card -> RichText.plain(caption).paragraph(0, caption.length, BlockStyle.LinkCard, target = target)
                InsertMode.Content -> {
                    val original = entry!!.libraryContent(quoteComment)
                    val value = if (useFragment && preview?.id == entry.id && !fragment.selection.collapsed) original.fragment(fragment.selection.min, fragment.selection.max) else original
                    var content = if (plain) RichText.plain(value.text) else value
                    if (source) {
                        val suffix = RichText.plain("\n\n" + title).style(2, 2 + title.length, TextStyle.Link, target)
                        content = content.replace(content.text.length, content.text.length, suffix)
                    }
                    content
                }
            }
            result = result.replace(result.text.length, result.text.length, value)
        }
        onInsert(result, if (mode == InsertMode.Inline) targets.first().second else null)
        onDismiss()
    }, modifier = Modifier.testTag("insert-confirm")) { Text("Вставить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
