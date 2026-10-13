package org.readerka.export

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readerka.data.*
import org.readerka.pdf.PdfEngine
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun pdfDigest(file: File): String = file.inputStream().use { input ->
    val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
    md.digest().joinToString("") { "%02x".format(it) }
}

class RestoreService(private val repository: LibraryRepository) {
    /** Existing callers reject conflicts; the transfer UI requires an explicit policy. */
    suspend fun restore(staged: StagedBackup) { apply(staged, ImportPolicy.KeepLocal, strict = true) }
    suspend fun restore(staged: StagedBackup, policy: ImportPolicy, expected: LibrarySnapshot? = null): File = apply(staged, policy, strict = false, expected = expected)

    suspend fun checkPdfs(staged: StagedBackup): StagedBackup = withContext(Dispatchers.IO) {
        val documents = staged.data.documents.map { d ->
            val count = PdfEngine(File(staged.directory, "pdfs/${d.storedName}"), repository.context.cacheDir).use { it.pageCount }
            require(d.pageCount == 0 || d.pageCount == count) { "Число страниц PDF не совпадает с архивом: ${d.originalName}" }
            d.copy(pageCount = count, lastPage = d.lastPage.coerceIn(0, count - 1))
        }
        staged.copy(data = staged.data.copy(documents = documents)).also { BackupArchive.validate(it.data) }
    }

    fun pdfConflicts(staged: StagedBackup) = staged.data.documents.filter { d ->
        val live = repository.pdfFile(d)
        live.isFile && pdfDigest(live) != pdfDigest(File(staged.directory, "pdfs/${d.storedName}"))
    }.map { it.id }

    private suspend fun apply(staged: StagedBackup, policy: ImportPolicy, strict: Boolean, expected: LibrarySnapshot? = null): File {
        repository.ensureRecovered()
        return withContext(NonCancellable + Dispatchers.IO) {
            repository.storageLock.withLock {
                var transaction: PdfFileTransaction? = null
                var committed = false
                var clean = true
                try {
                    val checked = checkPdfs(staged)
                    lateinit var backup: File
                    repository.database.withTransaction {
                        val before = repository.dao.snapshot()
                        require(expected == null || expected == before) { "Библиотека изменилась; повторите сравнение" }
                        if (strict) require(LibraryMerge.differences(before, checked.data).isEmpty() && pdfConflicts(checked).isEmpty()) { "Conflicting backup ID or PDF; choose an import policy" }
                        val next = LibraryMerge.merge(before, checked.data, policy)
                        val oldIds = before.documents.map { it.id }.toSet()
                        val incoming = checked.data.documents.filter { policy != ImportPolicy.KeepLocal || it.id !in oldIds }
                            .map { it.storedName to File(staged.directory, "pdfs/${it.storedName}") }
                        val journalBytes = incoming.sumOf { (name, file) -> file.length() + File(repository.pdfDirectory, name).let { if (it.isFile) it.length() else 0L } }
                        val backupBytes = before.documents.sumOf { repository.pdfFile(it).length() }
                        require(repository.pdfDirectory.usableSpace > journalBytes + backupBytes + 16L * 1024 * 1024) { "Недостаточно места для резервной копии и безопасного импорта" }
                        backup = File(repository.backups, "before-import-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
                        val pending = File(backup.parentFile, backup.name + ".part")
                        try {
                            pending.outputStream().use { BackupArchive.write(it, before, repository::pdfFile) }
                            require(pending.renameTo(backup)) { "Не удалось сохранить резервную копию" }
                        } finally { pending.delete() }
                        transaction = PdfFileTransaction.prepare(repository.transactionDirectory, repository.pdfDirectory, incoming)
                        transaction!!.apply()
                        repository.dao.replaceSnapshot(next)
                        repository.dao.putImportReceipt(ImportReceipt(transaction!!.id))
                    }
                    committed = true
                    transaction!!.clean()
                    repository.dao.deleteImportReceipt(transaction!!.id)
                    backup
                } catch (e: Exception) {
                    transaction?.let { tx ->
                        if (!committed && repository.dao.importCommitted(tx.id) == 0) {
                            try { tx.rollback() } catch (rollback: Exception) { clean = false; e.addSuppressed(rollback) }
                        }
                    }
                    throw e
                } finally {
                    if (clean) transaction?.clean()
                    staged.directory.deleteRecursively()
                }
            }
        }
    }
}
