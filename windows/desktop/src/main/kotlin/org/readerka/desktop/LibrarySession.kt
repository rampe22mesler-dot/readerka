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
    private val deletedEntries = mutableSetOf<String>()

    fun change(transform: (LibrarySnapshot) -> LibrarySnapshot) {
        val before = data
        val transformed = transform(before)
        val available = transformed.entries.map { it.id }.toSet()
        val next = transformed.copy(researchFields = transformed.researchFields.map { field ->
            field.removePlacements(field.placements.filter { it.entryId !in available }.map { it.id }.toSet())
        })
        val removed = before.entries.map { it.id }.toSet() - next.entries.map { it.id }.toSet()
        val ws = workspace.copy(drafts = workspace.drafts.filterNot { it.entry.id in removed }.map { draft ->
            val old = savedDraft(draft.entry, before)
            val current = savedDraft(draft.entry, next)
            draft.copy(
                entry = draft.entry.copy(title = if (before.entries.firstOrNull { it.id == draft.entry.id }?.title != next.entries.firstOrNull { it.id == draft.entry.id }?.title)
                    next.entries.first { it.id == draft.entry.id }.title else draft.entry.title),
                collections = if (old.collections != current.collections) current.collections else draft.collections,
                readerFolders = if (old.readerFolders != current.readerFolders) current.readerFolders else draft.readerFolders,
                tags = if (old.tags != current.tags) current.tags else draft.tags,
            )
        }, activeDraftId = workspace.activeDraftId?.takeUnless { it in removed })
        repository.persist(next, ws)
        data = next
        workspace = ws
        deletedEntries += removed
        onChange()
    }

    fun setWorkspace(next: WorkspaceState, preferences: Map<String, String> = emptyMap()) {
        if (next == workspace) {
            repository.persistPreferences(preferences)
            return
        }
        repository.persistWorkspace(next, preferences)
        workspace = next
    }

    /** Reading position does not change the library tree or editor relationships. */
    fun saveReadingPosition(id: String, page: Int, offset: Int, updateHistory: Boolean = true) {
        val document = data.documents.firstOrNull { it.id == id } ?: return
        val next = data.copy(documents = data.documents.map {
            if (it.id == id) document.copy(lastPage = page, lastOffset = offset) else it
        })
        val ws = if (updateHistory) workspace.copy(
            history = workspace.history.update(ReadingLocation(id, page, offset))
        ) else workspace
        if (next == data && ws == workspace) return
        repository.persist(next, ws)
        data = next
        workspace = ws
    }

    fun draft(id: String): EntryDraft {
        workspace.drafts
            .firstOrNull { it.entry.id == id }
            ?.let {
                return it
            }
        return savedDraft(data.entries.first { it.id == id }, data)
    }

    private fun savedDraft(entry: Entry, snapshot: LibrarySnapshot): EntryDraft {
        val id = entry.id
        return EntryDraft(
            entry,
            snapshot.entryTags
                .filter { it.entryId == id }
                .mapNotNull { a -> snapshot.tags.firstOrNull { it.id == a.tagId }?.name }
                .joinToString(", "),
            snapshot.memberships.filter { it.entryId == id }.map { it.collectionId }.toSet(),
            snapshot.readerMemberships.filter { it.entryId == id }.map { it.folderId }.toSet(),
        )
    }

    fun updateDraft(draft: EntryDraft, preferences: Map<String, String> = emptyMap()) {
        require(draft.entry.id !in deletedEntries) { "Запись удалена" }
        setWorkspace(
            workspace.copy(
                drafts = workspace.drafts.filterNot { it.entry.id == draft.entry.id } + draft,
                activeDraftId = draft.entry.id,
            ),
            preferences,
        )
    }

    fun save(
        draft: EntryDraft,
        label: String = "Сохранение",
        newCollections: List<org.readerka.data.Collection> = emptyList(),
        newReaderFolders: List<ReaderFolder> = emptyList(),
    ) {
        require(draft.entry.id !in deletedEntries) { "Запись удалена" }
        require(draft.entry.title.isNotBlank()) { "Введите название" }
        val entry = draft.entry.copy(updatedAt = System.currentTimeMillis())
        val scope = entry.kind.collectionScope()
        val collections = data.collections + newCollections
        require(
            draft.collections.all { id ->
                collections.any { it.id == id && it.scope == scope }
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
                        it.readerFolders != draft.readerFolders ||
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
                collections = collections,
                readerFolders = data.readerFolders + newReaderFolders,
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
    }

    fun importPdf(file: File, folder: String?): Document = importBook(file, folder)

    fun importBook(file: File, folder: String?): Document {
        val id = newId()
        val target = File(repository.pdfDirectory, "$id.pdf")
        try {
            val info = BookImporter.convert(file, target)
            val pages = DesktopPdfEngine(target).use { it.pageCount }
            val d =
                Document(
                    id,
                    info.title,
                    file.name,
                    target.name,
                    folderId = folder,
                    pageCount = pages,
                    authors = info.authors,
                    description = if (file.extension.equals("pdf", true)) "" else
                        "Импорт из ${file.name}: сохранена копия PDF для чтения и переноса библиотеки. Исходный файл не включён в библиотеку.",
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
        deletedEntries.clear()
        onChange()
    }

    fun restorableDraft(draft: EntryDraft): EntryDraft {
        val documents = data.documents.map { it.id }.toSet()
        val source = draft.entry.documentId?.takeIf { it in documents }
        val entry = draft.entry.copy(
            documentId = source,
            anchor = draft.entry.anchor?.takeIf { a -> source != null && a.page < data.documents.first { it.id == source }.pageCount },
            sources = draft.entry.sources.filter { it in documents },
        )
        return draft.copy(
            entry = entry,
            collections = draft.collections.filter { id -> data.collections.any { it.id == id && it.scope == entry.kind.collectionScope() } }.toSet(),
            readerFolders = draft.readerFolders.filter { id -> data.readerFolders.any { it.id == id && it.documentId == source && it.kind == ReaderFolderKind.Quotes } }.toSet(),
        )
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
