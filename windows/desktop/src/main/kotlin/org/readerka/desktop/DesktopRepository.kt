package org.readerka.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readerka.data.*
import org.readerka.export.*

fun newId(): String = UUID.randomUUID().toString()

class DesktopRepository(val directory: File) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private val db: java.sql.Connection
    val pdfDirectory = File(directory, "pdfs")
    val backups = File(directory, "backups")

    init {
        require(directory.exists() || directory.mkdirs()) { "Не удалось создать ${directory.path}" }
        lockChannel =
            FileChannel.open(
                File(directory, "readerka.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        lock =
            try {
                lockChannel.tryLock() ?: error("Эта библиотека уже открыта в другом окне Readerka")
            } catch (e: Exception) {
                lockChannel.close()
                throw e
            }
        try {
            Class.forName("org.sqlite.JDBC")
            db =
                DriverManager.getConnection(
                    "jdbc:sqlite:${File(directory, "library.sqlite").absolutePath}"
                )
            db.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA synchronous=FULL")
                it.execute("PRAGMA busy_timeout=5000")
                it.execute(
                    "CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, json TEXT NOT NULL)"
                )
            }
            pdfDirectory.mkdirs()
            backups.mkdirs()
            PdfFileTransaction.recover(
                File(directory, "transactions"),
                pdfDirectory,
                { id -> read("completed-import:$id") == "1" },
            )
            val saved = read("library")?.let { json.decodeFromString<LibrarySnapshot>(it) }
            if (saved != null && saved.version < 5) {
                BackupArchive.validate(saved)
                // A v4 ZIP and exact workspace JSON allow returning to the preceding application.
                val name = "before-research-v5-${System.currentTimeMillis()}"
                export(File(backups, "$name.zip"), saved)
                File(backups, "$name-workspace.json").writeText(read("workspace") ?: json.encodeToString(WorkspaceState()))
                db.prepareStatement("VACUUM INTO ?").use { q ->
                    q.setString(1, File(backups, "$name.sqlite").absolutePath)
                    q.execute()
                }
                val migrated = saved.migrateResearchFields()
                val migratedWorkspace = if (saved.version < 4) workspace().let { ws -> ws.copy(drafts = ws.drafts.map { draft ->
                    draft.copy(collections = scopedCollectionIds(draft.collections, draft.entry.kind, migrated.collections))
                }) } else null
                persist(migrated, migratedWorkspace)
            }
            BackupArchive.validate(snapshot()) // Never reset a library silently on corruption.
        } catch (e: Exception) {
            lock.release()
            lockChannel.close()
            throw e
        }
    }

    @Synchronized
    private fun read(key: String): String? =
        db.prepareStatement("SELECT json FROM state WHERE key=?").use { q ->
            q.setString(1, key)
            q.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    @Synchronized
    private fun put(key: String, value: String) =
        db.prepareStatement(
                "INSERT INTO state(key,json) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET json=excluded.json"
            )
            .use { q ->
                q.setString(1, key)
                q.setString(2, value)
                q.executeUpdate()
                Unit
            }

    fun preference(key: String): String? = read("preference:$key")

    fun persistPreference(key: String, value: String) = persistPreferences(mapOf(key to value))

    @Synchronized
    fun persistPreferences(values: Map<String, String>) {
        val changed = values.filter { (key, value) -> preference(key) != value }
        if (changed.isEmpty()) return
        workspaceTransaction { changed.forEach { (key, value) -> put("preference:$key", value) } }
    }

    fun snapshot(): LibrarySnapshot {
        val saved = read("library")?.let { json.decodeFromString<LibrarySnapshot>(it) } ?: LibrarySnapshot()
        return saved.copy(documents = saved.documents.map { document ->
            document.copy(
                lastPage = if (document.pageCount > 0) document.lastPage.coerceIn(0, document.pageCount - 1) else document.lastPage.coerceAtLeast(0),
                lastOffset = document.lastOffset.coerceIn(0, 10_000),
            )
        })
    }

    fun workspace(): WorkspaceState =
        read("workspace")?.let { json.decodeFromString(it) } ?: WorkspaceState()

    @Synchronized
    fun persist(
        data: LibrarySnapshot,
        workspace: WorkspaceState? = null,
        importReceipt: String? = null,
    ) {
        BackupArchive.validate(data)
        db.autoCommit = false
        try {
            put("library", json.encodeToString(data))
            workspace?.let { put("workspace", json.encodeToString(it)) }
            importReceipt?.let { put("completed-import:$it", "1") }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    @Synchronized
    fun persistWorkspace(value: WorkspaceState, preferences: Map<String, String> = emptyMap()) {
        val encoded = json.encodeToString(value)
        workspaceTransaction {
            put("workspace", encoded)
            preferences.forEach { (key, preference) -> put("preference:$key", preference) }
        }
    }

    private fun workspaceTransaction(write: () -> Unit) {
        db.autoCommit = false
        try {
            write()
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    fun pdf(document: Document) = File(pdfDirectory, document.storedName)

    fun export(file: File, data: LibrarySnapshot = snapshot()) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${newId()}.tmp")
        try {
            temp.outputStream().use { BackupArchive.write(it, data, ::pdf) }
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }

    fun stage(file: File): StagedBackup {
        val staged = file.inputStream().use { BackupArchive.stage(it, File(directory, "import-${newId()}")) }
        try {
            val documents = staged.data.documents.map { document ->
                val pages = DesktopPdfEngine(File(staged.directory, "pdfs/${document.storedName}"), staged.directory).use { it.pageCount }
                require(document.pageCount == 0 || document.pageCount == pages) { "Число страниц PDF не совпадает с архивом: ${document.originalName}" }
                document.copy(pageCount = pages, lastPage = document.lastPage.coerceIn(0, pages - 1))
            }
            val data = staged.data.copy(documents = documents)
            BackupArchive.validate(data)
            return staged.copy(data = data)
        } catch (e: Exception) {
            staged.directory.deleteRecursively()
            throw e
        }
    }

    fun pdfConflicts(staged: StagedBackup): List<String> =
        staged.data.documents
            .filter { d ->
                val live = pdf(d)
                live.exists() && sha(live) != sha(File(staged.directory, "pdfs/${d.storedName}"))
            }
            .map { it.id }

    @Synchronized
    fun applyImport(staged: StagedBackup, policy: ImportPolicy): File = try {
        applyStagedImport(staged, policy)
    } finally {
        staged.directory.deleteRecursively()
    }

    private fun applyStagedImport(staged: StagedBackup, policy: ImportPolicy): File {
        val before = snapshot()
        val next = LibraryMerge.merge(before, staged.data, policy)
        val backup = File(backups, "before-import-${System.currentTimeMillis()}-${newId()}.zip")
        export(backup, before)
        val oldIds = before.documents.map { it.id }.toSet()
        val transaction =
            PdfFileTransaction.prepare(
                File(directory, "transactions"),
                pdfDirectory,
                staged.data.documents
                    .filter { policy != ImportPolicy.KeepLocal || it.id !in oldIds }
                    .map { it.storedName to File(staged.directory, "pdfs/${it.storedName}") },
            )
        var cleanup = true
        try {
            transaction.apply()
            persist(next, WorkspaceState(), transaction.id)
        } catch (e: Exception) {
            if (read("completed-import:${transaction.id}") != "1")
                try {
                    transaction.rollback()
                } catch (rollback: Exception) {
                    cleanup = false
                    e.addSuppressed(rollback)
                }
            throw e
        } finally {
            if (cleanup) transaction.clean()
        }
        return backup
    }

    override fun close() {
        db.close()
        lock.release()
        lockChannel.close()
    }

    companion object {
        fun defaultDirectory(): File = DesktopPlatform.dataDirectory()

        fun sha(file: File): String =
            file.inputStream().use { stream ->
                val md = MessageDigest.getInstance("SHA-256")
                val bytes = ByteArray(65536)
                while (true) {
                    val n = stream.read(bytes)
                    if (n < 0) break
                    md.update(bytes, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
    }
}
