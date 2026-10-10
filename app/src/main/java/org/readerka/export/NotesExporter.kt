package org.readerka.export

import org.readerka.data.*

object NotesExporter {
    fun markdown(data: LibrarySnapshot): String = buildString {
        append("# Readerka\n\n")
        for (e in data.entries) {
            append("<a id=\"entry-${e.id}\"></a>\n\n## ${e.title}\n\n")
            if (e.anchor != null) append(e.quote.lines().joinToString("\n") { "> $it" } + "\n\n### Мой комментарий\n")
            append(rewriteResearchLinks(e.comment, { id, block -> if (data.entries.any { it.id == id }) "#" + (block ?: "entry-$id") else null }, { _, _ -> null }) + "\n\n")
            if (e.kind == EntryKind.Consp) append("**Связанные книги:** " + data.documents.filter { it.id in e.sourceBooks() }.joinToString("; ") { it.title } + "\n\n")
            data.documents.firstOrNull { it.id == e.documentId }?.let { d -> append("**Источник:** ${d.authors} — ${d.title}; `${d.originalName}`" + (e.anchor?.let { ", страница ${it.page + 1}" } ?: "") + "\n\n") }
            append("**Тип:** ${e.type.name}; **Цвет:** ${e.color.name}\n\n")
            append("**Теги:** " + data.entryTags.filter { it.entryId == e.id }.mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }.joinToString(", ") + "\n\n---\n\n")
        }
    }
    fun csv(data: LibrarySnapshot): String {
        fun field(value: String) = "\"${value.replace("\"", "\"\"")}\""
        return "\uFEFFid,title,quote,comment,type,color,source_pdf,source_page,tags,kind,source_ids\r\n" + data.entries.joinToString("\r\n") { e ->
            val d = data.documents.firstOrNull { it.id == e.documentId }
            listOf(e.id, e.title, e.quote, e.comment, e.type.name, e.color.name, d?.originalName ?: "", e.anchor?.let { (it.page + 1).toString() } ?: "",
                data.entryTags.filter { it.entryId == e.id }.mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }.joinToString("; "), e.kind.name, e.sources.joinToString("; ")).joinToString(",", transform = ::field)
        } + "\r\n"
    }
}

/** Rewrite only actual Markdown destinations, leaving quoted/code prose untouched. */
fun rewriteResearchLinks(source: String, entry: (String, String?) -> String?, document: (String, Int) -> String?): String =
    Regex("(?<=\\]\\()readerka://(entry|document)/([A-Za-z0-9_-]+)(?:\\?([A-Za-z0-9_=&-]+))?(?=\\))").replace(source) { match ->
        val args = match.groupValues[3].split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
        (if (match.groupValues[1] == "entry") entry(match.groupValues[2], args["block"]) else document(match.groupValues[2], args["page"]?.toIntOrNull() ?: 1)) ?: match.value
    }
