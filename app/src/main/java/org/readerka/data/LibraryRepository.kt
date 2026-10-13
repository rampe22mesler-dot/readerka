package org.readerka.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.readerka.pdf.PdfEngine
import org.readerka.importer.BookImporter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readerka.export.PdfFileTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LibraryRepository(val context: Context, val database: LibraryDatabase, pdfRoot: File = File(context.filesDir, "pdfs")) {
    val dao = database.dao()
    val pdfDirectory = pdfRoot.apply { mkdirs() }
    val transactionDirectory = File(pdfDirectory.parentFile, pdfDirectory.name + "-transactions")
    val backups = File(pdfDirectory.parentFile, pdfDirectory.name + "-backups").apply { mkdirs() }
    val storageLock = Mutex()
    private var recovered = false
    suspend fun ensureRecovered() = storageLock.withLock {
        if (!recovered) {
            val receipts = dao.allImportReceipts().map { it.id }.toSet()
            withContext(Dispatchers.IO) { PdfFileTransaction.recover(transactionDirectory, pdfDirectory) { it in receipts } }
            receipts.forEach { dao.deleteImportReceipt(it) }
            recovered = true
        }
    }
    val snapshots = database.invalidationTracker.createFlow(
        "documents", "document_folders", "entries", "collections", "memberships", "tags", "entry_tags", "document_tags", "bookmarks", "reader_folders", "reader_memberships", "note_versions", "research_fields"
    ).map { ensureRecovered(); dao.snapshot() }
    suspend fun saveResearchField(field: ResearchField) = database.withTransaction {
        field.validate(dao.allEntries().map { it.id }.toSet())
        dao.putResearchField(ResearchFieldRow.from(field))
    }
    suspend fun deleteEntries(ids: Set<String>) = database.withTransaction {
        dao.allResearchFields().forEach { row ->
            val field = row.field()
            dao.putResearchField(ResearchFieldRow.from(field.removePlacements(field.placements.filter { it.entryId in ids }.map { it.id }.toSet())))
        }
        dao.deleteEntries(ids.toList())
    }
    fun pdfFile(document: Document): File {
        require(document.storedName.matches(Regex("[A-Za-z0-9_-]+\\.pdf"))) { "Unsafe PDF name" }
        return File(pdfDirectory, document.storedName)
    }
    suspend fun importPdf(uri: Uri, folderId: String?): Document = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        var name = if (uri.scheme == "file") File(uri.path.orEmpty()).name.ifBlank { "document.pdf" } else "document.pdf"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        require(BookImporter.extensions.any { name.endsWith(".$it", true) }) { "Неподдерживаемый формат: $name" }
        val extension = if (name.endsWith(".fb2.zip", true)) "fb2.zip" else name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
        val source = File(context.cacheDir, "$id.$extension")
        val pending = File(pdfDirectory, "$id.part")
        val file = File(pdfDirectory, "$id.pdf")
        ensureRecovered()
        storageLock.withLock {
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    val limit = if (extension == "pdf") 1024L * 1024 * 1024 else if (extension in setOf("djvu", "djv")) 512L * 1024 * 1024 else 64L * 1024 * 1024
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= limit) { "Книга превышает поддерживаемый размер" }; require(source.parentFile!!.usableSpace > n + 16L * 1024 * 1024) { "Недостаточно места" }; output.write(buffer, 0, n) }
                } } ?: error("Не удалось открыть выбранную книгу")
                val info = BookImporter.convert(source, pending, context, name)
                val pages = PdfEngine(pending, context.cacheDir).use { it.pageCount }
                require(pending.renameTo(file)) { "Cannot save PDF" }
                val document = Document(id, info.title, name, file.name, folderId, authors = info.authors, pageCount = pages)
                dao.putDocument(document); document
            } catch (e: Exception) { pending.delete(); file.delete(); throw e }
            finally { source.delete() }
        }
    }
    suspend fun saveEntry(entry: Entry, tagNames: String, collectionIds: Set<String>, readerFolderIds: Set<String>? = null) = database.withTransaction {
        val folders = dao.allCollections()
        require(collectionIds.all { id -> folders.any { it.id == id && it.scope == entry.kind.collectionScope() } }) { "Folder belongs to another catalogue" }
        if (readerFolderIds != null) {
            val folders = dao.allReaderFolders().associateBy { it.id }
            require(readerFolderIds.all { folders[it]?.let { f -> f.documentId == entry.documentId && f.kind == ReaderFolderKind.Quotes } == true })
        }
        require(entry.sources.distinct().size == entry.sources.size && entry.sources.all { id -> dao.allDocuments().any { it.id == id } })
        val previous = dao.allEntries().firstOrNull { it.id == entry.id }
        if (previous != null && previous.copy(updatedAt = entry.updatedAt) != entry) {
            val newest = dao.allNoteVersions().firstOrNull { it.entryId == entry.id }
            if (newest == null || System.currentTimeMillis() - newest.createdAt >= 30_000 || kotlin.math.abs(previous.comment.length - entry.comment.length) >= 200) checkpoint(previous, "До изменения")
        }
        dao.putEntry(entry.copy(updatedAt = System.currentTimeMillis()))
        dao.clearEntryTags(entry.id)
        for (tag in resolveTags(tagNames)) dao.putEntryTag(EntryTag(entry.id, tag.id))
        dao.clearMemberships(entry.id)
        for (id in collectionIds) dao.putMembership(Membership(id, entry.id))
        if (readerFolderIds != null) {
            dao.clearReaderMemberships(entry.id)
            for (id in readerFolderIds) dao.putReaderMembership(ReaderMembership(id, entry.id))
        }
    }
    suspend fun checkpoint(entry: Entry, label: String) {
        val data = dao.snapshot()
        val draft = EntryDraft(entry,
            data.entryTags.filter { it.entryId == entry.id }.mapNotNull { link -> data.tags.firstOrNull { it.id == link.tagId }?.name }.joinToString(", "),
            data.memberships.filter { it.entryId == entry.id }.map { it.collectionId }.toSet(),
            data.readerMemberships.filter { it.entryId == entry.id }.map { it.folderId }.toSet())
        dao.putNoteVersion(NoteVersion(UUID.randomUUID().toString(), entry.id, Json.encodeToString(draft), System.currentTimeMillis(), label))
        dao.pruneVersions(entry.id)
    }
    suspend fun saveReaderFolder(folder: ReaderFolder) = database.withTransaction {
        require(folder.name.isNotBlank())
        val all = dao.allReaderFolders()
        val old = all.firstOrNull { it.id == folder.id }
        require(old == null || old.documentId == folder.documentId && old.kind == folder.kind)
        val scoped = all.filter { it.documentId == folder.documentId && it.kind == folder.kind && it.id != folder.id } + folder
        FolderTree.validate(scoped.map { it.id to it.parentId })
        dao.putReaderFolder(folder)
    }
    suspend fun saveBookmark(bookmark: Bookmark) = database.withTransaction {
        require(bookmark.title.isNotBlank() && bookmark.offset in 0..10_000)
        val document = dao.allDocuments().first { it.id == bookmark.documentId }
        require(bookmark.page in 0 until document.pageCount)
        bookmark.folderId?.let { id -> require(dao.allReaderFolders().any { it.id == id && it.documentId == bookmark.documentId && it.kind == ReaderFolderKind.Bookmarks }) }
        val all = dao.allBookmarks()
        require(all.none { it.id == bookmark.id && it.documentId != bookmark.documentId })
        FolderTree.validate((all.filter { it.documentId == bookmark.documentId && it.id != bookmark.id } + bookmark).map { it.id to it.parentId })
        dao.putBookmark(bookmark)
    }
    suspend fun saveDocument(document: Document, tagNames: String) = database.withTransaction {
        dao.putDocument(document); dao.clearDocumentTags(document.id)
        for (tag in resolveTags(tagNames)) dao.putDocumentTag(DocumentTag(document.id, tag.id))
    }
    private suspend fun resolveTags(names: String): List<Tag> {
        val existing = dao.allTags().associateBy { it.name }
        return names.split(',', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct().map {
            existing[it] ?: Tag(UUID.randomUUID().toString(), it).also { tag -> dao.putTag(tag) }
        }
    }
    suspend fun batch(ids: Set<String>, color: Marker? = null, type: NoteType? = null, tags: String? = null, collection: String? = null) = database.withTransaction {
        if (collection != null) {
            val folder = dao.allCollections().first { it.id == collection }
            require(dao.allEntries().filter { it.id in ids }.all { it.kind.collectionScope() == folder.scope })
        }
        for (e in dao.allEntries().filter { it.id in ids }) {
            if (color != null || type != null) dao.putEntry(e.copy(color = color ?: e.color, type = type ?: e.type, updatedAt = System.currentTimeMillis()))
            if (collection != null) dao.putMembership(Membership(collection, e.id))
            if (tags != null) for (tag in resolveTags(tags)) dao.putEntryTag(EntryTag(e.id, tag.id))
        }
    }
    suspend fun saveFolder(folder: DocumentFolder) = database.withTransaction {
        val values = dao.allDocumentFolders().filterNot { it.id == folder.id } + folder
        FolderTree.validate(values.map { it.id to it.parentId }); require(folder.name.isNotBlank()); dao.putFolder(folder)
    }
    suspend fun saveCollection(collection: Collection) = database.withTransaction {
        val all = dao.allCollections()
        require(all.none { it.id == collection.id && it.scope != collection.scope })
        val values = all.filterNot { it.id == collection.id } + collection
        require(collection.parentId == null || values.any { it.id == collection.parentId && it.scope == collection.scope })
        FolderTree.validate(values.map { it.id to it.parentId }); require(collection.name.isNotBlank()); dao.putCollection(collection)
    }
    suspend fun foldersForConversion(ids: Set<String>): Set<String> = database.withTransaction {
        val all = dao.allCollections()
        val notes = all.filter { it.scope == CollectionScope.Notes }
        val needed = ids + FolderTree.ancestors(ids, notes.map { it.id to it.parentId })
        for (folder in FolderTree.ordered(notes.filter { it.id in needed }, { it.id }, { it.parentId })) {
            val id = legacyConspFolderId(folder.id)
            if (all.none { it.id == id }) dao.putCollection(folder.copy(id = id, parentId = folder.parentId?.let(::legacyConspFolderId), scope = CollectionScope.Consp))
        }
        ids.map(::legacyConspFolderId).toSet()
    }
    suspend fun renameTag(id: String, name: String) = database.withTransaction {
        val normalized = name.trim().removePrefix("#")
        require(normalized.isNotBlank())
        val other = dao.allTags().firstOrNull { it.name == normalized && it.id != id }
        if (other == null) dao.renameTag(id, normalized)
        else {
            dao.allEntryTags().filter { it.tagId == id }.forEach { dao.putEntryTag(it.copy(tagId = other.id)) }
            dao.allDocumentTags().filter { it.tagId == id }.forEach { dao.putDocumentTag(it.copy(tagId = other.id)) }
            dao.deleteTag(id)
        }
    }
}
