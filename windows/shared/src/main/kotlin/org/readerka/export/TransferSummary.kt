package org.readerka.export

import org.readerka.data.LibrarySnapshot

data class TransferCount(
    val category: String,
    val incoming: Int,
    val added: Int,
    val changed: Int,
    val localOnly: Int,
)

/** Counts identities, rather than titles; repeat imports do not create new identities. */
object TransferSummary {
    fun compare(local: LibrarySnapshot, incoming: LibrarySnapshot): List<TransferCount> = buildList {
        fun <T> count(label: String, before: List<T>, after: List<T>, id: (T) -> String) {
            val a = before.associateBy(id)
            val b = after.associateBy(id)
            add(TransferCount(label, b.size, b.keys.count { it !in a },
                b.count { (key, value) -> key in a && a[key] != value }, a.keys.count { it !in b }))
        }
        count("Книги (PDF)", local.documents, incoming.documents) { it.id }
        count("Цитаты, заметки, конспекты", local.entries, incoming.entries) { it.id }
        count("Исследовательские поля", local.researchFields, incoming.researchFields) { it.id }
        count("Папки книг", local.documentFolders, incoming.documentFolders) { it.id }
        count("Папки записей", local.collections, incoming.collections) { it.id }
        count("Папки внутри книг", local.readerFolders, incoming.readerFolders) { it.id }
        count("Закладки", local.bookmarks, incoming.bookmarks) { it.id }
        count("Теги", local.tags, incoming.tags) { it.id }
        count("Версии записей", local.noteVersions, incoming.noteVersions) { it.id }
    }
}
