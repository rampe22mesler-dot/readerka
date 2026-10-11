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

    fun persistPreference(key: String, value: String) = put("preference:$key", value)

    fun snapshot(): LibrarySnapshot =
        read("library")?.let { json.decodeFromString(it) } ?: LibrarySnapshot()

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
    fun persistWorkspace(value: WorkspaceState) = put("workspace", json.encodeToString(value))

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

    fun stage(file: File) =
        file.inputStream().use { BackupArchive.stage(it, File(directory, "import-${newId()}")) }

    fun pdfConflicts(staged: StagedBackup): List<String> =
        staged.data.documents
            .filter { d ->
                val live = pdf(d)
                live.exists() && sha(live) != sha(File(staged.directory, "pdfs/${d.storedName}"))
            }
            .map { it.id }

    @Synchronized
    fun applyImport(staged: StagedBackup, policy: ImportPolicy): File {
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
            staged.directory.deleteRecursively()
        }
        return backup
    }

    override fun close() {
        db.close()
        lock.release()
        lockChannel.close()
    }

    companion object {
        fun defaultDirectory(): File =
            System.getenv("LOCALAPPDATA")?.let { File(it, "Readerka") }
                ?: File(
                    System.getenv("XDG_DATA_HOME")
                        ?: "${System.getProperty("user.home")}/.local/share",
                    "readerka",
                )

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
