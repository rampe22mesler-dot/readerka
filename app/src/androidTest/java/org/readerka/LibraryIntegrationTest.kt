package org.readerka

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.export.*
import org.readerka.pdf.PdfEngine
import java.io.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryIntegrationTest {
    @Test fun importCanonicalMembershipBackupRestoreAndConflictProtection() { runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val root = File(app.cacheDir, "integration-${UUID.randomUUID()}").apply { mkdirs() }
        val db = LibraryDatabase.open(app, "integration-source.db")
        val restoredDb = LibraryDatabase.open(app, "integration-target.db")
        try {
            val source = File(root, "Кант.pdf")
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle.A4); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { s -> s.beginText(); s.setFont(PDType1Font.TIMES_ROMAN, 14f); s.newLineAtOffset(40f, 700f); s.showText("Existence is not a real predicate."); s.endText() }
                pdf.save(source)
            }
            val repository = LibraryRepository(app, db, File(root, "source-pdfs"))
            repository.saveFolder(DocumentFolder("root", "Мои книги"))
            repository.saveFolder(DocumentFolder("child", "Вложенная", "root"))
            val document = repository.importPdf(Uri.fromFile(source), "child")
            assertEquals("Кант.pdf", document.originalName); assertEquals(1, document.pageCount)
            val anchor = PdfEngine(repository.pdfFile(document), app.cacheDir).use { it.text(0).matches("Existence").single() }
            repository.saveCollection(org.readerka.data.Collection("c1", "Первая"))
            repository.saveCollection(org.readerka.data.Collection("c2", "Вторая"))
            val entry = Entry("e", "Цитата", "Existence", "Комментарий", document.id, anchor)
            repository.saveEntry(entry, "Кант, Онтология", setOf("c1", "c2"))
            repository.batch(setOf("e"), Marker.Green, NoteType.Objection)
            assertEquals(1, db.dao().allEntries().size); assertEquals(2, db.dao().allMemberships().size)
            db.dao().putBookmark(Bookmark("b", document.id, 0, "Аргумент"))
            val snapshot = db.dao().snapshot()
            val bytes = ByteArrayOutputStream(); BackupArchive.write(bytes, snapshot, repository::pdfFile)
            val restored = LibraryRepository(app, restoredDb, File(root, "restored-pdfs"))
            RestoreService(restored).restore(BackupArchive.stage(bytes.toByteArray().inputStream(), File(root, "staged")))
            assertEquals(snapshot, restoredDb.dao().snapshot())
            assertArrayEquals(source.readBytes(), restored.pdfFile(document).readBytes())
            PdfEngine(restored.pdfFile(document), app.cacheDir).use { assertTrue(it.text(0).text.contains("Existence")) }
            // Restoring the identical snapshot is idempotent.
            RestoreService(restored).restore(BackupArchive.stage(bytes.toByteArray().inputStream(), File(root, "again")))
            assertEquals(snapshot, restoredDb.dao().snapshot())
            val modified = restoredDb.dao().allEntries().single().copy(comment = "Новая мысль")
            restoredDb.dao().putEntry(modified)
            var rejected = false
            try { RestoreService(restored).restore(BackupArchive.stage(bytes.toByteArray().inputStream(), File(root, "conflict"))) } catch (e: IllegalArgumentException) { rejected = true }
            assertTrue(rejected); assertEquals(modified, restoredDb.dao().allEntries().single())
            assertArrayEquals(source.readBytes(), restored.pdfFile(document).readBytes())
            restoredDb.dao().removeMemberships("c2", listOf("e"))
            assertEquals(1, restoredDb.dao().allMemberships().size); assertEquals(1, restoredDb.dao().allEntries().size)
        } finally { db.close(); restoredDb.close(); app.deleteDatabase("integration-source.db"); app.deleteDatabase("integration-target.db"); root.deleteRecursively() }
    } }
}
