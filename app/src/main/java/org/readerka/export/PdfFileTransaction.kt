package org.readerka.export

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class PdfJournal(val files: List<String>, val existing: Set<String>)

/** Crash recovery ties PDF changes to a receipt committed in the same SQLite transaction. */
class PdfFileTransaction
private constructor(
    val directory: File,
    private val pdfRoot: File,
    private val journal: PdfJournal,
) {
    val id: String
        get() = directory.name

    fun apply() {
        journal.files.forEach { name ->
            atomicCopy(File(directory, "incoming/$name"), File(pdfRoot, name))
        }
    }

    fun rollback() {
        journal.files.forEach { name ->
            val target = File(pdfRoot, name)
            if (name in journal.existing) atomicCopy(File(directory, "previous/$name"), target)
            else target.delete()
        }
    }

    fun clean() {
        directory.deleteRecursively()
    }

    companion object {
        private fun atomicCopy(source: File, target: File) {
            val temporary = File(target.parentFile, "${target.name}.transaction")
            try {
                source.inputStream().use { input ->
                    FileOutputStream(temporary).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }

        fun prepare(
            root: File,
            pdfs: File,
            incoming: List<Pair<String, File>>,
        ): PdfFileTransaction {
            require(incoming.all { it.first.matches(Regex("[A-Za-z0-9_-]+\\.pdf")) })
            val folder = File(root, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
            val existing = incoming.map { it.first }.filter { File(pdfs, it).exists() }.toSet()
            try {
                File(folder, "incoming").mkdirs()
                File(folder, "previous").mkdirs()
                incoming.forEach { (name, source) ->
                    atomicCopy(source, File(folder, "incoming/$name"))
                    if (name in existing)
                        atomicCopy(File(pdfs, name), File(folder, "previous/$name"))
                }
                val journal = PdfJournal(incoming.map { it.first }, existing)
                FileOutputStream(File(folder, "journal.json")).use {
                    it.write(Json.encodeToString(journal).toByteArray())
                    it.fd.sync()
                }
                return PdfFileTransaction(folder, pdfs, journal)
            } catch (e: Exception) {
                folder.deleteRecursively()
                throw e
            }
        }

        fun recover(root: File, pdfs: File, committed: (String) -> Boolean) {
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .forEach { folder ->
                    val file = File(folder, "journal.json")
                    if (!file.exists()) {
                        folder.deleteRecursively()
                        return@forEach
                    }
                    val journal = Json.decodeFromString<PdfJournal>(file.readText())
                    require(journal.files.all { it.matches(Regex("[A-Za-z0-9_-]+\\.pdf")) })
                    val tx = PdfFileTransaction(folder, pdfs, journal)
                    if (!committed(tx.id)) tx.rollback()
                    tx.clean()
                }
        }
    }
}
