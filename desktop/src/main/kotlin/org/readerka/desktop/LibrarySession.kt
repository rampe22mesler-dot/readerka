package org.readerka.desktop

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readerka.data.*
import org.readerka.export.*

/** UI independent, all identities and snapshots use the Android model. */
class LibrarySession(val repository: DesktopRepository) {
    var data = repository.snapshot()
        private set

    var workspace = repository.workspace()
        private set

    var onChange: () -> Unit = {}

    fun change(transform: (LibrarySnapshot) -> LibrarySnapshot) {
        val next = transform(data)
        repository.persist(next)
        data = next
        onChange()
    }

    fun setWorkspace(next: WorkspaceState) {
        repository.persistWorkspace(next)
        workspace = next
    }

    fun draft(id: String): EntryDraft {
        workspace.drafts
            .firstOrNull { it.entry.id == id }
            ?.let {
                return it
            }
        val entry = data.entries.first { it.id == id }
        return EntryDraft(
            entry,
            data.entryTags
                .filter { it.entryId == id }
                .mapNotNull { a -> data.tags.firstOrNull { it.id == a.tagId }?.name }
                .joinToString(", "),
            data.memberships.filter { it.entryId == id }.map { it.collectionId }.toSet(),
            data.readerMemberships.filter { it.entryId == id }.map { it.folderId }.toSet(),
        )
    }

    fun updateDraft(draft: EntryDraft) {
        setWorkspace(
            workspace.copy(
                drafts = workspace.drafts.filterNot { it.entry.id == draft.entry.id } + draft,
                activeDraftId = draft.entry.id,
            )
        )
    }

    fun save(draft: EntryDraft, label: String = "Сохранение") {
        require(draft.entry.title.isNotBlank()) { "Введите название" }
        val entry = draft.entry.copy(updatedAt = System.currentTimeMillis())
        val scope = entry.kind.collectionScope()
        require(
            draft.collections.all { id ->
                data.collections.any { it.id == id && it.scope == scope }
            }
        ) {
            "Папки другого раздела"
        }
        val tags = data.tags.toMutableList()
        val assigned =
            draft.tags
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { name ->
                    tags.firstOrNull { it.name == name } ?: Tag(newId(), name).also { tags += it }
                }
        val old = data.entries.firstOrNull { it.id == entry.id }
        val oldDraft =
            old?.let {
                EntryDraft(
                    it,
                    tags =
                        data.entryTags
                            .filter { t -> t.entryId == it.id }
                            .mapNotNull { t -> data.tags.firstOrNull { it.id == t.tagId }?.name }
                            .joinToString(", "),
                    collections =
                        data.memberships
                            .filter { m -> m.entryId == it.id }
                            .map { it.collectionId }
                            .toSet(),
                    readerFolders =
                        data.readerMemberships
                            .filter { m -> m.entryId == it.id }
                            .map { it.folderId }
                            .toSet(),
                )
            }
        val version =
            oldDraft
                ?.takeIf {
                    it.entry.copy(updatedAt = entry.updatedAt) != entry ||
                        it.collections != draft.collections ||
                        it.tags != draft.tags
                }
                ?.let {
                    NoteVersion(
                        newId(),
                        entry.id,
                        Json.encodeToString(it),
                        System.currentTimeMillis(),
                        label,
                    )
                }
        val versions =
            (data.noteVersions + listOfNotNull(version))
                .groupBy { it.entryId }
                .values
                .flatMap { it.sortedByDescending { it.createdAt }.take(30) }
        val next =
            data.copy(
                entries = data.entries.filterNot { it.id == entry.id } + entry,
                tags = tags,
                entryTags =
                    data.entryTags.filterNot { it.entryId == entry.id } +
                        assigned.map { EntryTag(entry.id, it.id) },
                memberships =
                    data.memberships.filterNot { it.entryId == entry.id } +
                        draft.collections.map { Membership(it, entry.id) },
                readerMemberships =
                    data.readerMemberships.filterNot { it.entryId == entry.id } +
                        draft.readerFolders.map { ReaderMembership(it, entry.id) },
                noteVersions = versions,
            )
        val ws = workspace.copy(drafts = workspace.drafts.filterNot { it.entry.id == entry.id })
        repository.persist(next, ws)
        data = next
        workspace = ws
        onChange()
    }

    fun saveAll() {
        workspace.drafts.toList().forEach { save(it, "Перед переносом") }
    }

    fun create(
        kind: EntryKind,
        collection: String? = null,
        book: String? = null,
        template: ConspTemplate = ConspTemplate.Blank,
    ): Entry {
        val e =
            Entry(
                    newId(),
                    if (kind == EntryKind.Consp) "Новый конспект" else "Новая заметка",
                    kind = kind,
                    documentId = book.takeIf { kind != EntryKind.Consp },
                    sources = if (kind == EntryKind.Consp) listOfNotNull(book) else emptyList(),
                )
                .withContent(RichText.fromMarkdown(template.source))
        save(EntryDraft(e, collections = setOfNotNull(collection)))
        return e
    }

    fun deleteEntry(id: String) {
        change { s ->
            s.copy(
                entries = s.entries.filterNot { it.id == id },
                memberships = s.memberships.filterNot { it.entryId == id },
                entryTags = s.entryTags.filterNot { it.entryId == id },
                readerMemberships = s.readerMemberships.filterNot { it.entryId == id },
                noteVersions = s.noteVersions.filterNot { it.entryId == id },
            )
        }
        setWorkspace(workspace.copy(drafts = workspace.drafts.filterNot { it.entry.id == id }))
    }

    fun importPdf(file: File, folder: String?): Document {
        val id = newId()
        val target = File(repository.pdfDirectory, "$id.pdf")
        file.copyTo(target)
        try {
            val pages = DesktopPdfEngine(target).use { it.pageCount }
            val d =
                Document(
                    id,
                    file.nameWithoutExtension,
                    file.name,
                    target.name,
                    folderId = folder,
                    pageCount = pages,
                )
            change { it.copy(documents = it.documents + d) }
            return d
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun reload() {
        data = repository.snapshot()
        workspace = repository.workspace()
        onChange()
    }

    fun checkpoint(id: String, label: String) {
        val d = draft(id)
        if (workspace.drafts.any { it.entry.id == id }) save(d)
        change {
            it.copy(
                noteVersions =
                    it.noteVersions +
                        NoteVersion(
                            newId(),
                            id,
                            Json.encodeToString(d),
                            System.currentTimeMillis(),
                            label,
                        )
            )
        }
    }
}
