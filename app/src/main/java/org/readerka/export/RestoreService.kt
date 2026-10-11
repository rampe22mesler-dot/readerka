package org.readerka.export

import androidx.room.withTransaction
import org.readerka.data.*
import java.io.File
import java.security.MessageDigest

class RestoreService(private val repository: LibraryRepository) {
    /** Explicit policy import: validate, back up, journal PDFs, and commit all Room rows together. */
    suspend fun restore(staged: StagedBackup, policy: ImportPolicy): File {
        val rollback = File(repository.pdfDirectory.parentFile, "restore-rollback-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val touched = mutableListOf<File>()
        val backups = File(repository.pdfDirectory.parentFile, "automatic-backups").apply { mkdirs() }
        val backup = File(backups, "before-import-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.zip")
        try {
            repository.database.withTransaction {
                val current = repository.dao.snapshot()
                val merged = LibraryMerge.merge(current, staged.data, policy)
                val temporary = File(backups, backup.name + ".pending")
                try { temporary.outputStream().use { BackupArchive.write(it, current, repository::pdfFile) }; require(temporary.renameTo(backup)) }
                finally { temporary.delete() }
                val existing = current.documents.map { it.id }.toSet()
                staged.data.documents.filter { policy != ImportPolicy.KeepLocal || it.id !in existing }.forEach { d ->
                    val target = repository.pdfFile(d)
                    if (target.exists()) target.copyTo(File(rollback, target.name), overwrite = true)
                    touched += target
                    File(staged.directory, "pdfs/${d.storedName}").copyTo(target, overwrite = true)
                }
                val sql = repository.database.openHelper.writableDatabase
                listOf("note_versions", "reader_memberships", "entry_tags", "document_tags", "memberships", "bookmarks", "entries", "reader_folders", "documents", "collections", "document_folders", "tags").forEach { table -> sql.execSQL("DELETE FROM $table") }
                val dao = repository.dao
                FolderTree.ordered(merged.documentFolders, { it.id }, { it.parentId }).forEach { dao.putFolder(it) }
                FolderTree.ordered(merged.collections, { it.id }, { it.parentId }).forEach { dao.putCollection(it) }
                merged.documents.forEach { dao.putDocument(it) }
                FolderTree.ordered(merged.readerFolders, { it.id }, { it.parentId }).forEach { dao.putReaderFolder(it) }
                merged.entries.forEach { dao.putEntry(it) }; merged.tags.forEach { dao.putTag(it) }
                merged.memberships.forEach { dao.putMembership(it) }; merged.entryTags.forEach { dao.putEntryTag(it) }
                merged.documentTags.forEach { dao.putDocumentTag(it) }; merged.readerMemberships.forEach { dao.putReaderMembership(it) }
                FolderTree.ordered(merged.bookmarks, { it.id }, { it.parentId }).forEach { dao.putBookmark(it) }
                merged.noteVersions.forEach { dao.putNoteVersion(it) }
            }
            return backup
        } catch (e: Exception) {
            touched.forEach { target -> val previous = File(rollback, target.name); if (previous.exists()) previous.copyTo(target, overwrite = true) else target.delete() }
            throw e
        } finally { rollback.deleteRecursively(); staged.directory.deleteRecursively() }
    }
    /** Merge a verified snapshot. Conflicting IDs fail instead of overwriting current research. */
    suspend fun restore(staged: StagedBackup) {
        val createdFiles = mutableListOf<File>()
        val s = staged.data
        try {
            repository.database.withTransaction {
                val current = repository.dao.snapshot()
                fun <T> checkConflicts(old: List<T>, new: List<T>, id: (T) -> String) {
                    val existing = old.associateBy(id)
                    new.forEach { require(existing[id(it)] == null || existing[id(it)] == it) { "Conflicting backup ID" } }
                }
                checkConflicts(current.documents, s.documents) { it.id }; checkConflicts(current.entries, s.entries) { it.id }
                checkConflicts(current.documentFolders, s.documentFolders) { it.id }; checkConflicts(current.collections, s.collections) { it.id }
                checkConflicts(current.tags, s.tags) { it.id }; checkConflicts(current.bookmarks, s.bookmarks) { it.id }
                checkConflicts(current.readerFolders, s.readerFolders) { it.id }; checkConflicts(current.noteVersions, s.noteVersions) { it.id }
                require(s.tags.none { t -> current.tags.any { it.name == t.name && it.id != t.id } }) { "Conflicting tag identity" }
                fun hash(file: File): ByteArray = file.inputStream().use { input ->
                    val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }; md.digest()
                }
                for (d in s.documents) {
                    val source = File(staged.directory, "pdfs/${d.storedName}")
                    val target = repository.pdfFile(d)
                    if (target.exists()) require(hash(target).contentEquals(hash(source))) { "Conflicting PDF" }
                    else {
                        val temporary = File(repository.pdfDirectory, d.storedName + ".restore")
                        try { source.copyTo(temporary, overwrite = false); require(temporary.renameTo(target)); createdFiles += target }
                        finally { temporary.delete() }
                    }
                }
                val dao = repository.dao
                FolderTree.ordered(s.documentFolders, { it.id }, { it.parentId }).forEach { dao.putFolder(it) }
                FolderTree.ordered(s.collections, { it.id }, { it.parentId }).forEach { dao.putCollection(it) }
                s.documents.forEach { dao.putDocument(it) }
                FolderTree.ordered(s.readerFolders, { it.id }, { it.parentId }).forEach { dao.putReaderFolder(it) }
                s.entries.forEach { dao.putEntry(it) }
                s.noteVersions.forEach { dao.putNoteVersion(it) }
                s.tags.forEach { dao.putTag(it) }; s.memberships.forEach { dao.putMembership(it) }
                s.entryTags.forEach { dao.putEntryTag(it) }; s.documentTags.forEach { dao.putDocumentTag(it) }
                s.readerMemberships.forEach { dao.putReaderMembership(it) }
                FolderTree.ordered(s.bookmarks, { it.id }, { it.parentId }).forEach { dao.putBookmark(it) }
            }
        } catch (e: Exception) { createdFiles.forEach { it.delete() }; throw e }
        finally { staged.directory.deleteRecursively() }
    }
}
