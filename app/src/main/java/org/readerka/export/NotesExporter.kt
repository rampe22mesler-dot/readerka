package org.readerka.export

import org.readerka.data.LibrarySnapshot

object NotesExporter {
    fun markdown(data: LibrarySnapshot): String = buildString {
        append("# Readerka\n\n")
        for (e in data.entries) {
            append("## ${e.title}\n\n")
            if (e.anchor != null) append(e.quote.lines().joinToString("\n") { "> $it" } + "\n\n### Мой комментарий\n")
            append(e.comment + "\n\n")
            data.documents.firstOrNull { it.id == e.documentId }?.let { d -> append("**Источник:** ${d.authors} — ${d.title}; `${d.originalName}`" + (e.anchor?.let { ", страница ${it.page + 1}" } ?: "") + "\n\n") }
            append("**Тип:** ${e.type.name}; **Цвет:** ${e.color.name}\n\n")
            append("**Теги:** " + data.entryTags.filter { it.entryId == e.id }.mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }.joinToString(", ") + "\n\n---\n\n")
        }
    }
    fun csv(data: LibrarySnapshot): String {
        fun field(value: String) = "\"${value.replace("\"", "\"\"")}\""
        return "\uFEFFid,title,quote,comment,type,color,source_pdf,source_page,tags\r\n" + data.entries.joinToString("\r\n") { e ->
            val d = data.documents.firstOrNull { it.id == e.documentId }
            listOf(e.id, e.title, e.quote, e.comment, e.type.name, e.color.name, d?.originalName ?: "", e.anchor?.let { (it.page + 1).toString() } ?: "",
                data.entryTags.filter { it.entryId == e.id }.mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }.joinToString("; ")).joinToString(",", transform = ::field)
        } + "\r\n"
    }
}
