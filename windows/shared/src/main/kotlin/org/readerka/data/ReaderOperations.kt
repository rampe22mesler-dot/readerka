package org.readerka.data

enum class ReaderItemKind { Quote, Bookmark, Folder }
data class ReaderItem(val kind: ReaderItemKind, val id: String)
data class ReaderOccurrence(val item: ReaderItem, val source: String?)

/** Moving a quote replaces only its membership in the source branch. */
object ReaderOperations {
    fun moveOccurrences(data: LibrarySnapshot, book: String, kind: ReaderFolderKind,
        occurrences: List<ReaderOccurrence>, target: String?, additional: Boolean = false): LibrarySnapshot {
        require(occurrences.isNotEmpty()) { "Выберите материал" }
        val folders = occurrences.filter { it.item.kind == ReaderItemKind.Folder }.map { it.item.id }.toSet()
        val graph = data.readerFolders.map { it.id to it.parentId }
        val nested = folders.flatMap { FolderTree.descendants(it, graph) }.toSet()
        val roots = occurrences.distinct().filter { occurrence ->
            if (occurrence.item.kind == ReaderItemKind.Folder)
                FolderTree.ancestors(setOf(occurrence.item.id), graph).none { it in folders }
            else occurrence.source !in nested
        }
        return roots.groupBy { it.source }.entries.fold(data) { current, (source, values) ->
            move(current, book, kind, values.map { it.item }, source, target, additional)
        }
    }

    fun move(data: LibrarySnapshot, book: String, kind: ReaderFolderKind,
        items: List<ReaderItem>, source: String?, target: String?, additional: Boolean = false): LibrarySnapshot {
        require(items.isNotEmpty()) { "Выберите материал" }
        fun folder(id: String) = data.readerFolders.firstOrNull { it.id == id }
            ?.also { require(it.documentId == book && it.kind == kind) { "Папка другой книги или раздела" } }
            ?: error("Папка удалена")
        source?.let(::folder)
        target?.let(::folder)
        require(!additional || target != null) { "Для дополнительного назначения выберите папку" }
        val selected = items.distinct()
        for (item in selected) when (item.kind) {
            ReaderItemKind.Quote -> {
                val entry = data.entries.first { it.id == item.id }
                require(kind == ReaderFolderKind.Quotes && entry.kind == EntryKind.Quote && entry.documentId == book)
                require(if (source == null) data.readerMemberships.none { it.entryId == item.id }
                    else ReaderMembership(source, item.id) in data.readerMemberships) { "Материал перемещён из исходной папки" }
            }
            ReaderItemKind.Bookmark -> {
                val bookmark = data.bookmarks.first { it.id == item.id }
                require(!additional && kind == ReaderFolderKind.Bookmarks && bookmark.documentId == book && bookmark.folderId == source)
            }
            ReaderItemKind.Folder -> require(!additional && folder(item.id).parentId == source)
        }
        val quotes = selected.filter { it.kind == ReaderItemKind.Quote }.map { it.id }.toSet()
        val bookmarks = selected.filter { it.kind == ReaderItemKind.Bookmark }.map { it.id }.toSet()
        val folders = selected.filter { it.kind == ReaderItemKind.Folder }.map { it.id }.toSet()
        val next = data.copy(
            readerMemberships = (data.readerMemberships.filterNot {
                !additional && it.entryId in quotes && it.folderId == source
            } + quotes.mapNotNull { id -> target?.let { ReaderMembership(it, id) } }).distinct(),
            bookmarks = data.bookmarks.map { if (it.id in bookmarks) it.copy(folderId = target) else it },
            readerFolders = data.readerFolders.map { if (it.id in folders) it.copy(parentId = target) else it },
        )
        ReaderStructure.validate(next.readerFolders, next.readerMemberships, next.bookmarks, next.entries, next.documents.map { it.id }.toSet())
        return next
    }
}
