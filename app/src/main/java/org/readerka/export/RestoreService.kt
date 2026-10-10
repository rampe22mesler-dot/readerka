package org.readerka.export

import androidx.room.withTransaction
import org.readerka.data.*
import java.io.File
import java.security.MessageDigest

class RestoreService(private val repository: LibraryRepository) {
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
