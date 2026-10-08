package org.readerka

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

class ReaderStructureTest {
    private val document = Document("doc", "Research", "original.pdf", "doc.pdf", pageCount = 5)
    private val quote = Entry("quote", "Quotation", "A thought", documentId = "doc",
        anchor = Anchor(2, 0, 9, listOf(PageRect(.1f, .1f, .4f, .2f))), type = NoteType.Quote)
    private fun snapshot(): LibrarySnapshot = LibrarySnapshot(documents = listOf(document), entries = listOf(quote),
        readerFolders = listOf(ReaderFolder("q1", "doc", ReaderFolderKind.Quotes, "Chapter"), ReaderFolder("q2", "doc", ReaderFolderKind.Quotes, "Argument", "q1"),
            ReaderFolder("b1", "doc", ReaderFolderKind.Bookmarks, "Bookmarks")),
        readerMemberships = listOf(ReaderMembership("q2", "quote")),
        bookmarks = listOf(Bookmark("parent", "doc", 0, "Beginning", folderId = "b1"),
            Bookmark("child", "doc", 2, "Argument", parentId = "parent", folderId = "b1", offset = 2350)))

    @Test fun backupAndVaultKeepDocumentFoldersAndNestedBookmarks() {
        val data = snapshot()
        val temporary = File.createTempFile("reader", ".pdf").apply { writeText("test PDF payload") }
        val stage = File(temporary.parentFile, "reader-stage-" + System.nanoTime())
        try {
            val archive = ByteArrayOutputStream()
            BackupArchive.write(archive, data) { temporary }
            val restored = BackupArchive.stage(ByteArrayInputStream(archive.toByteArray()), stage).data
            assertEquals(data, restored)
            val vault = ByteArrayOutputStream()
            VaultExporter().write(vault, data, true, { ByteArrayInputStream(temporary.readBytes()) })
            val files = mutableMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(vault.toByteArray())).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) files[entry.name] = String(zip.readBytes()) }
            }
            val folder = files.entries.single { it.key.startsWith("Readerka Vault/Структура PDF/") && it.value.contains("# Argument") }
            assertTrue(folder.value.contains("Заметки/Цитаты/"))
            val child = files.entries.single { it.value.contains("parent_id: \"parent\"") }
            assertTrue(child.key.contains("Beginning"))
            assertTrue(child.value.contains("source_page: 3"))
            assertTrue(child.value.contains("reader_offset: 2350"))
        } finally { stage.deleteRecursively(); temporary.delete() }
    }

    @Test fun rejectCrossDocumentAndWrongKindMembershipsAndBookmarkCycles() {
        val data = snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.validate(data.copy(readerFolders = data.readerFolders.map { if (it.id == "q2") it.copy(documentId = "other") else it },
                documents = data.documents + document.copy(id = "other", storedName = "other.pdf")))
        }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.validate(data.copy(readerMemberships = listOf(ReaderMembership("b1", "quote")))) }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.validate(data.copy(bookmarks = data.bookmarks.map { if (it.id == "parent") it.copy(parentId = "child") else it })) }
    }

    @Test fun restoreOriginalVersionOneArchiveWithoutNewFields() {
        val library = """{"version":1,"documents":[{"id":"doc","title":"Old","originalName":"old.pdf","storedName":"doc.pdf","pageCount":5}],"bookmarks":[{"id":"old","documentId":"doc","page":2,"title":"Saved"}]}""".toByteArray()
        val pdf = "original PDF".toByteArray()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = """{"version":1,"sha256":{"library.json":"${hash(library)}","pdfs/doc.pdf":"${hash(pdf)}"}}""".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf("library.json" to library, "pdfs/doc.pdf" to pdf, "manifest.json" to manifest)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val stage = File(System.getProperty("java.io.tmpdir"), "reader-old-" + System.nanoTime())
        try {
            val data = BackupArchive.stage(ByteArrayInputStream(output.toByteArray()), stage).data
            assertTrue(data.readerFolders.isEmpty()); assertTrue(data.readerMemberships.isEmpty())
            assertNull(data.bookmarks.single().parentId); assertEquals(0, data.bookmarks.single().offset)
            assertEquals(2, data.bookmarks.single().page)
        } finally { stage.deleteRecursively() }
    }
}
