package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.BackupArchive
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class BackupArchiveTest {
    @Test fun backupRoundTripPreservesAllRelationshipsAndPdfBytes() {
        val temp = Files.createTempDirectory("readerka-test").toFile()
        try {
            val pdf = File(temp, "doc.pdf").apply { writeBytes("%PDF-fixture\ncontent".toByteArray()) }
            val s = LibrarySnapshot(documents = listOf(Document("d", "Книга", "original.pdf", "doc.pdf", "f", pageCount = 2)),
                documentFolders = listOf(DocumentFolder("f", "Книги")),
                entries = listOf(Entry("e", "Цитата", "Текст", "Комментарий", "d", Anchor(1, 0, 5, listOf(PageRect(.1f, .2f, .3f, .4f))))),
                collections = listOf(Collection("c", "Мои материалы")), memberships = listOf(Membership("c", "e")),
                tags = listOf(Tag("t", "Тег")), entryTags = listOf(EntryTag("e", "t")), documentTags = listOf(DocumentTag("d", "t")),
                bookmarks = listOf(Bookmark("b", "d", 1, "Закладка")))
            val out = ByteArrayOutputStream(); BackupArchive.write(out, s) { pdf }
            val staged = BackupArchive.stage(out.toByteArray().inputStream(), File(temp, "restore"))
            assertEquals(s, staged.data)
            assertArrayEquals(pdf.readBytes(), File(staged.directory, "pdfs/doc.pdf").readBytes())
        } finally { temp.deleteRecursively() }
    }
    @Test fun invalidArchivesAreRejectedWithoutLeavingStagedData() {
        val temp = Files.createTempDirectory("readerka-test").toFile()
        try {
            fun archive(entries: Map<String, String>): ByteArray {
                val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z -> for ((p, s) in entries) { z.putNextEntry(ZipEntry(p)); z.write(s.toByteArray()); z.closeEntry() } }; return out.toByteArray()
            }
            for ((i, bad) in listOf(
                archive(mapOf("../escape" to "bad")),
                archive(mapOf("manifest.json" to "{\"version\":99,\"sha256\":{}}")),
                archive(mapOf("manifest.json" to "{\"version\":1,\"sha256\":{\"library.json\":\"incorrect\"}}", "library.json" to "{}"))
            ).withIndex()) {
                val dir = File(temp, "restore$i")
                assertThrows(Exception::class.java) { BackupArchive.stage(bad.inputStream(), dir) }
                assertFalse(dir.exists()); assertFalse(File(temp, "escape").exists())
            }
        } finally { temp.deleteRecursively() }
    }
    @Test fun orphanReferencesAndFolderCyclesCannotEnterBackup() {
        assertThrows(Exception::class.java) { BackupArchive.validate(LibrarySnapshot(collections = listOf(Collection("a", "A", "b"), Collection("b", "B", "a")))) }
        assertThrows(Exception::class.java) { BackupArchive.validate(LibrarySnapshot(entries = listOf(Entry("e", "Orphan", documentId = "missing")))) }
    }
}
