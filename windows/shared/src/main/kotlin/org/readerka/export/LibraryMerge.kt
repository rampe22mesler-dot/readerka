package org.readerka.export

import org.readerka.data.*

/** Identity preserving merges. Deletions propagate only through explicit Replace. */
enum class ImportPolicy {
    KeepLocal,
    UseIncoming,
    Replace,
}

data class ImportDifference(
    val category: String,
    val id: String,
    val before: String,
    val after: String,
    val title: String = id,
)

object LibraryMerge {
    fun differences(local: LibrarySnapshot, incoming: LibrarySnapshot): List<ImportDifference> =
        buildList {
            fun <T> compare(category: String, a: List<T>, b: List<T>, id: (T) -> String) {
                val indexed = a.associateBy(id)
                b.forEach { next ->
                    indexed[id(next)]
                        ?.takeIf { it != next }
                        ?.let {
                            add(
                                ImportDifference(
                                    category,
                                    id(next),
                                    importDescription(it as Any, local),
                                    importDescription(next as Any, incoming),
                                    importTitle(it as Any),
                                )
                            )
                        }
                }
            }
            compare("PDF", local.documents, incoming.documents) { it.id }
            compare("Запись", local.entries, incoming.entries) { it.id }
            compare("Исследовательское поле", local.researchFields, incoming.researchFields) { it.id }
            compare("Папка PDF", local.documentFolders, incoming.documentFolders) { it.id }
            compare("Папка записей", local.collections, incoming.collections) { it.id }
            compare("Закладка", local.bookmarks, incoming.bookmarks) { it.id }
            compare("Папка книги", local.readerFolders, incoming.readerFolders) { it.id }
            compare("Тег", local.tags, incoming.tags) { it.id }
            compare("Версия", local.noteVersions, incoming.noteVersions) { it.id }
            incoming.entries.forEach { e ->
                if (local.entries.any { it.id == e.id }) {
                    val old =
                        local.memberships.filter { it.entryId == e.id }.toSet() to
                            local.entryTags.filter { it.entryId == e.id }.toSet()
                    val next =
                        incoming.memberships.filter { it.entryId == e.id }.toSet() to
                            incoming.entryTags.filter { it.entryId == e.id }.toSet()
                    if (old != next)
                        add(
                            ImportDifference(
                                "Папки и теги",
                                e.id,
                                "Папки: " +
                                    local.memberships
                                        .filter { it.entryId == e.id }
                                        .joinToString { m ->
                                            local.collections
                                                .firstOrNull { it.id == m.collectionId }
                                                ?.name ?: m.collectionId
                                        } +
                                    "\nТеги: " +
                                    local.entryTags
                                        .filter { it.entryId == e.id }
                                        .joinToString { t ->
                                            local.tags.firstOrNull { it.id == t.tagId }?.name
                                                ?: t.tagId
                                        },
                                "Папки: " +
                                    incoming.memberships
                                        .filter { it.entryId == e.id }
                                        .joinToString { m ->
                                            incoming.collections
                                                .firstOrNull { it.id == m.collectionId }
                                                ?.name ?: m.collectionId
                                        } +
                                    "\nТеги: " +
                                    incoming.entryTags
                                        .filter { it.entryId == e.id }
                                        .joinToString { t ->
                                            incoming.tags.firstOrNull { it.id == t.tagId }?.name
                                                ?: t.tagId
                                        },
                                local.entries.first { it.id == e.id }.title,
                            )
                        )
                }
            }
            incoming.entries.filter { e -> local.entries.any { it.id == e.id } }.forEach { e ->
                val old = local.readerMemberships.filter { it.entryId == e.id }.map { it.folderId }.toSet()
                val next = incoming.readerMemberships.filter { it.entryId == e.id }.map { it.folderId }.toSet()
                if (old != next) add(ImportDifference("Папки цитат", e.id,
                    old.joinToString { id -> local.readerFolders.first { it.id == id }.name }.ifBlank { "Без папок" },
                    next.joinToString { id -> incoming.readerFolders.first { it.id == id }.name }.ifBlank { "Без папок" }, e.title))
            }
            incoming.documents.filter { d -> local.documents.any { it.id == d.id } }.forEach { d ->
                val old = local.documentTags.filter { it.documentId == d.id }.map { it.tagId }.toSet()
                val next = incoming.documentTags.filter { it.documentId == d.id }.map { it.tagId }.toSet()
                if (old != next) add(ImportDifference("Теги PDF", d.id,
                    old.joinToString { id -> local.tags.first { it.id == id }.name }.ifBlank { "Без тегов" },
                    next.joinToString { id -> incoming.tags.first { it.id == id }.name }.ifBlank { "Без тегов" }, d.title))
            }
        }

    fun merge(
        local: LibrarySnapshot,
        incoming: LibrarySnapshot,
        policy: ImportPolicy,
    ): LibrarySnapshot {
        BackupArchive.validate(local)
        BackupArchive.validate(incoming)
        if (policy == ImportPolicy.Replace) return incoming
        fun <T> combine(a: List<T>, b: List<T>, id: (T) -> String): List<T> =
            (if (policy == ImportPolicy.UseIncoming) a + b else b + a)
                .associateBy(id)
                .values
                .toList()
        fun <T> relations(
            a: List<T>,
            b: List<T>,
            owner: (T) -> String,
            localIds: Set<String>,
            incomingIds: Set<String>,
        ): List<T> =
            if (policy == ImportPolicy.UseIncoming)
                (a.filter { owner(it) !in incomingIds } + b).distinct()
            else (a + b.filter { owner(it) !in localIds }).distinct()
        val localEntries = local.entries.map { it.id }.toSet()
        val incomingEntries = incoming.entries.map { it.id }.toSet()
        // Different devices can independently create the same tag name. Reuse its canonical ID.
        val chosenTags = combine(local.tags, incoming.tags) { it.id }.associateBy { it.id }
        val canonicalTags =
            (if (policy == ImportPolicy.UseIncoming) incoming.tags + local.tags
                else local.tags + incoming.tags)
                .map { chosenTags.getValue(it.id) }
                .distinctBy { it.name }
        val byName = canonicalTags.associateBy { it.name }
        val tagMap =
            (local.tags + incoming.tags).associate {
                it.id to byName.getValue(chosenTags.getValue(it.id).name).id
            }
        val result =
            LibrarySnapshot(
                documents = combine(local.documents, incoming.documents) { it.id },
                documentFolders =
                    combine(local.documentFolders, incoming.documentFolders) { it.id },
                entries = combine(local.entries, incoming.entries) { it.id },
                collections = combine(local.collections, incoming.collections) { it.id },
                memberships =
                    relations(
                        local.memberships,
                        incoming.memberships,
                        { it.entryId },
                        localEntries,
                        incomingEntries,
                    ),
                tags = canonicalTags,
                entryTags =
                    relations(
                            local.entryTags,
                            incoming.entryTags,
                            { it.entryId },
                            localEntries,
                            incomingEntries,
                        )
                        .map { it.copy(tagId = tagMap.getValue(it.tagId)) }
                        .distinct(),
                documentTags =
                    relations(
                            local.documentTags,
                            incoming.documentTags,
                            { it.documentId },
                            local.documents.map { it.id }.toSet(),
                            incoming.documents.map { it.id }.toSet(),
                        )
                        .map { it.copy(tagId = tagMap.getValue(it.tagId)) }
                        .distinct(),
                bookmarks = combine(local.bookmarks, incoming.bookmarks) { it.id },
                readerFolders = combine(local.readerFolders, incoming.readerFolders) { it.id },
                readerMemberships =
                    relations(
                        local.readerMemberships,
                        incoming.readerMemberships,
                        { it.entryId },
                        localEntries,
                        incomingEntries,
                    ),
                noteVersions = combine(local.noteVersions, incoming.noteVersions) { it.id },
                researchFields = combine(local.researchFields, incoming.researchFields) { it.id },
            )
        BackupArchive.validate(
            result
        ) // Reject incompatible mixed folder trees before any mutation.
        return result
    }
}
