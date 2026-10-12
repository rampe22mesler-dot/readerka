package org.readerka.export

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readerka.data.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportReport(val entries: Int, val documents: Int, val missingPdf: List<String>)

/** Platform-independent, one-way export. It never modifies the snapshot or its PDF inputs. */
class VaultExporter {
    companion object {
        fun safeName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]%\\p{Cntrl}]"), "_")
            .trim().trimEnd('.').take(100).ifBlank { "Без названия" }
        private fun stableName(name: String, id: String) = "${safeName(name)} — ${safeName(id)}"
        private fun yaml(value: String) = Json.encodeToString(value)
        private fun wiki(path: String, label: String? = null) = "[[${path.removeSuffix(".md")}${label?.let { "|${it.replace('|', ' ')}" } ?: ""}]]"
        private fun md(text: String) = buildString {
            for (char in text) { if (char in "\\`*_{}[]<>#!|+-.()") append('\\'); append(char) }
        }
        private fun heading(text: String) = md(text.replace('\n', ' ').replace('\r', ' '))
        private fun quote(text: String) = text.lines().joinToString("\n") { "> ${md(it)}" }
    }
    fun write(output: OutputStream, data: LibrarySnapshot, includePdf: Boolean,
        pdf: (Document) -> InputStream?, progress: (Int, Int) -> Unit = { _, _ -> }): ExportReport {
        val root = "Readerka Vault/"
        val docs = data.documents.associateBy { it.id }
        fun folderPath(id: String?, names: Map<String, Pair<String, String?>>): String {
            val parts = mutableListOf<String>(); var current = id; val visited = mutableSetOf<String>()
            while (current != null) {
                require(visited.add(current)) { "Folder cycle" }
                val item = names[current] ?: error("Missing folder")
                parts += stableName(item.first, current); current = item.second
            }
            return parts.asReversed().joinToString("/")
        }
        val folders = data.documentFolders.associate { it.id to (it.name to it.parentId) }
        val collections = data.collections.associate { it.id to (it.name to it.parentId) }
        val collectionPaths = data.collections.associate { it.id to "Коллекции/${if (it.scope == CollectionScope.Consp) "Конспекты" else "Заметки и цитаты"}/${folderPath(it.id, collections)}" }
        val sourcePaths = data.documents.associate { it.id to "Источники/${stableName(it.title, it.id)}.md" }
        val notePaths = data.entries.associate { it.id to if (it.kind == EntryKind.Consp) "Конспекты/${stableName(it.title, it.id)}.md" else "Заметки/${if (it.anchor != null) "Цитаты" else "Самостоятельные"}/${stableName(it.title, it.id)}.md" }
        val desiredPdfPaths = data.documents.associate { d ->
            val base = "Библиотека/" + folderPath(d.folderId, folders).let { if (it.isEmpty()) "" else "$it/" }
            d.id to base + safeName(d.originalName).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        }
        val pdfPaths = desiredPdfPaths.mapValues { (id, path) ->
            if (desiredPdfPaths.values.count { it.equals(path, ignoreCase = true) } > 1)
                path.dropLast(4) + " — ${safeName(id)}.pdf" else path
        }
        val readerFolders = data.readerFolders.associate { it.id to (it.name to it.parentId) }
        val readerPaths = data.readerFolders.associate { f ->
            val section = if (f.kind == ReaderFolderKind.Quotes) "Цитаты" else "Закладки"
            f.id to "Структура PDF/" + stableName(docs.getValue(f.documentId).title, f.documentId) + "/" + section + "/" + folderPath(f.id, readerFolders)
        }
        val bookmarkNames = data.bookmarks.associate { it.id to (it.title to it.parentId) }
        val bookmarkPaths = data.bookmarks.associate { b ->
            val base = "Закладки/" + stableName(docs.getValue(b.documentId).title, b.documentId) + "/"
            val folder = folderPath(b.folderId, readerFolders).let { if (it.isEmpty()) "" else "$it/" }
            b.id to base + folder + folderPath(b.id, bookmarkNames) + ".md"
        }
        val tags = data.tags.associateBy { it.id }
        val available = mutableSetOf<String>()
        val missing = mutableListOf<String>()
        val written = mutableSetOf<String>()
        var done = 0
        val total = data.documents.size * 2 + data.entries.size + data.collections.size + data.memberships.size + data.bookmarks.size + data.readerFolders.size + 1
        ZipOutputStream(output.buffered()).use { zip ->
            fun text(path: String, content: String) {
                require(written.add(path)) { "Duplicate export path: $path" }
                zip.putNextEntry(ZipEntry(root + path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                progress(++done, total)
            }
            fun directory(path: String) {
                if (written.add("$path/")) { zip.putNextEntry(ZipEntry("$root$path/")); zip.closeEntry() }
            }
            for (path in listOf("Библиотека", "Источники", "Заметки/Цитаты", "Заметки/Самостоятельные", "Конспекты", "Коллекции", "Закладки")) directory(path)
            for (f in data.documentFolders) directory("Библиотека/${folderPath(f.id, folders)}")
            for (d in data.documents) {
                if (includePdf) {
                    val input = pdf(d)
                    if (input == null) missing += d.originalName
                    else input.use {
                        val path = pdfPaths.getValue(d.id)
                        require(written.add(path)); zip.putNextEntry(ZipEntry(root + path))
                        it.copyTo(zip); zip.closeEntry(); available += d.id
                    }
                }
                progress(++done, total)
            }
            fun pdfLink(d: Document, page: Int? = null): String = if (d.id in available)
                wiki(pdfPaths.getValue(d.id) + (page?.let { "#page=${it + 1}" } ?: ""), d.originalName.replace(Regex("[\\[\\]#^]"), " "))
                else "`${d.originalName.replace('`', '\'')}`" + (page?.let { " · ${it + 1}" } ?: "")
            for (d in data.documents) {
                val documentTags = data.documentTags.filter { it.documentId == d.id }.mapNotNull { tags[it.tagId]?.name }
                val content = buildString {
                    append("---\nid: ${yaml(d.id)}\nsource_pdf: ${yaml(d.originalName)}\nsource_title: ${yaml(d.title)}\nauthors: ${yaml(d.authors)}\nyear: ${yaml(d.year)}\npublisher: ${yaml(d.publisher)}\ndoi: ${yaml(d.doi)}\nurl: ${yaml(d.url)}\ncreated_at: ${yaml(Instant.ofEpochMilli(d.createdAt).toString())}\n")
                    append("tags: ${Json.encodeToString(documentTags)}\n")
                    if (d.id in available) append("source_pdf_path: ${yaml(pdfPaths.getValue(d.id))}\n")
                    append("---\n# ${heading(d.title)}\n\n${md(d.description)}\n\n${pdfLink(d)}\n")
                }
                text(sourcePaths.getValue(d.id), content)
            }
            for (e in data.entries) {
                val currentPath = notePaths.getValue(e.id)
                val prefix = "../".repeat(currentPath.count { it == '/' })
                fun encoded(path: String) = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                val rendered = rewriteResearchLinks(e.comment,
                    { id, block -> notePaths[id]?.let { encoded(prefix + it) + (block?.let { b -> "#$b" } ?: "") } },
                    { id, page -> pdfPaths[id]?.takeIf { id in available }?.let { encoded(prefix + it) + "#page=$page" } })
                val d = docs[e.documentId]
                val memberPaths = data.memberships.filter { it.entryId == e.id }.map { collectionPaths.getValue(it.collectionId).removePrefix("Коллекции/") }
                val entryTags = data.entryTags.filter { it.entryId == e.id }.mapNotNull { tags[it.tagId]?.name }
                text(notePaths.getValue(e.id), buildString {
                    append("---\nid: ${yaml(e.id)}\ntype: ${yaml(if (e.type == NoteType.GeneralNote) "General Note" else e.type.name)}\ncolor: ${yaml(e.color.name.lowercase())}\n")
                    append("kind: ${yaml(e.kind.name)}\nsource_ids: ${Json.encodeToString(e.sources)}\n")
                    append("tags: ${Json.encodeToString(entryTags)}\ncollections: ${Json.encodeToString(memberPaths)}\n")
                    val internalPaths = data.readerMemberships.filter { it.entryId == e.id }.map { readerPaths.getValue(it.folderId) }
                    append("reader_folders: ${Json.encodeToString(internalPaths)}\n")
                    append("created_at: ${yaml(Instant.ofEpochMilli(e.createdAt).toString())}\nupdated_at: ${yaml(Instant.ofEpochMilli(e.updatedAt).toString())}\n")
                    if (d != null) {
                        append("source_pdf: ${yaml(d.originalName)}\nsource_title: ${yaml(d.title)}\n")
                        e.anchor?.let { append("source_page: ${it.page + 1}\n") }
                        if (d.id in available) append("source_pdf_path: ${yaml(pdfPaths.getValue(d.id))}\n")
                    }
                    append("---\n# ${heading(e.title)}\n\n")
                    if (e.quote.isNotBlank()) append("## Цитата\n${quote(e.quote)}\n\n## Мой комментарий\n${rendered}\n")
                    else append(rendered + "\n")
                    if (e.kind == EntryKind.Consp && e.sources.isNotEmpty()) {
                        append("\n## Связанные книги\n")
                        e.sources.mapNotNull { docs[it] }.forEach { source -> append("- ${wiki(sourcePaths.getValue(source.id))}\n") }
                    }
                    if (d != null) {
                        append("\n## Источник\n- PDF-файл: `${d.originalName.replace('`', '\'')}`\n")
                        e.anchor?.let { append("- Страница PDF: **${it.page + 1}**\n") }
                        append("- Карточка источника: ${wiki(sourcePaths.getValue(d.id))}\n")
                        if (d.id in available) append("- Открыть PDF: ${pdfLink(d, e.anchor?.page)}\n")
                    }
                })
            }
            for (c in data.collections) {
                val path = collectionPaths.getValue(c.id)
                directory(path)
                val members = data.memberships.filter { it.collectionId == c.id }.map { notePaths.getValue(it.entryId) }
                val children = data.collections.filter { it.parentId == c.id }.map { collectionPaths.getValue(it.id) + "/_Коллекция.md" }
                text("$path/_Коллекция.md", "# ${heading(c.name)}\n\n" + (members + children).joinToString("\n") { "- ${wiki(it)}" } + "\n")
                for (m in data.memberships.filter { it.collectionId == c.id }) {
                    val e = data.entries.first { it.id == m.entryId }
                    text("$path/${stableName(e.title, e.id)} — ссылка.md", "# ${heading(e.title)}\n\n${wiki(notePaths.getValue(e.id))}\n\n!${wiki(notePaths.getValue(e.id))}\n")
                }
            }
            for (b in data.bookmarks) {
                val d = docs.getValue(b.documentId)
                text(bookmarkPaths.getValue(b.id), "---\nid: ${yaml(b.id)}\nsource_pdf: ${yaml(d.originalName)}\nsource_page: ${b.page + 1}\nparent_id: ${yaml(b.parentId.orEmpty())}\nreader_offset: ${b.offset}\n---\n# ${heading(b.title)}\n\n${md(b.comment)}\n\n${pdfLink(d, b.page)}\n\n${wiki(sourcePaths.getValue(d.id))}\n")
            }
            for (f in data.readerFolders) {
                val path = readerPaths.getValue(f.id)
                directory(path)
                val links = if (f.kind == ReaderFolderKind.Quotes)
                    data.readerMemberships.filter { it.folderId == f.id }.map { notePaths.getValue(it.entryId) }
                else data.bookmarks.filter { it.folderId == f.id }.map { bookmarkPaths.getValue(it.id) }
                val children = data.readerFolders.filter { it.parentId == f.id }.map { readerPaths.getValue(it.id) + "/_Папка.md" }
                text("$path/_Папка.md", "# ${heading(f.name)}\n\n" + (links + children).joinToString("\n") { "- ${wiki(it)}" } + "\n")
            }
            text("_Содержание.md", buildString {
                append("# Readerka Vault\n\nОдносторонний снимок библиотеки. Основной текст каждой записи находится в «Заметки» или «Конспекты». В папках коллекций — ссылки и встраивания этих записей. Изменения в Vault не возвращаются в Readerka.\n\n## Источники\n")
                for (d in data.documents) append("- ${wiki(sourcePaths.getValue(d.id))}\n")
                append("\n## Коллекции\n")
                for (c in data.collections) append("- ${wiki(collectionPaths.getValue(c.id) + "/_Коллекция.md")}\n")
                if (missing.isNotEmpty()) append("\n## Недоступные PDF\n" + missing.joinToString("\n") { "- ${it.replace('\n', ' ')}" } + "\n")
            })
        }
        return ExportReport(data.entries.size, data.documents.size, missing)
    }
}
