package org.readerka

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.export.*
import org.readerka.pdf.PdfEngine
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPortIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private fun stage(root: File) = assets.open("interop/to-android.zip").use { BackupArchive.stage(it, File(root, "staged-${UUID.randomUUID()}")) }

    @Test fun insufficientSpaceRejectsImportBeforeDatabaseOrLivePdfMutation(): Unit = runBlocking {
        val root = File(app.cacheDir, "space-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "space-${UUID.randomUUID()}.db"; val db = LibraryDatabase.open(app, name)
        val pdfs = object : File(root, "pdfs") { override fun getUsableSpace(): Long = 0 }
        val repo = LibraryRepository(app, db, pdfs)
        try {
            repo.ensureRecovered()
            val sentinel = File(pdfs, "local.pdf").apply { writeText("Unchanged local bytes") }
            val before = repo.dao.snapshot()
            val incoming = stage(root)
            var rejected = false
            try { RestoreService(repo).restore(incoming, ImportPolicy.Replace) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(before, repo.dao.snapshot())
            assertEquals("Unchanged local bytes", sentinel.readText())
            assertTrue(repo.dao.allImportReceipts().isEmpty())
            assertFalse(incoming.directory.exists())
        } finally { db.close(); app.deleteDatabase(name); root.deleteRecursively() }
    }

    @Test fun windowsZip5RichTextFieldsAndAnchorsRoundTripWithoutIdentityOrPdfLoss(): Unit = runBlocking {
        val root = File(app.cacheDir, "port-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "port-${UUID.randomUUID()}.db"
        val db = LibraryDatabase.open(app, name)
        val repo = LibraryRepository(app, db, File(root, "pdfs"))
        try {
            val expected = Json.decodeFromString<LibrarySnapshot>(assets.open("interop/expected.json").bufferedReader().use { it.readText() })
            assertEquals(5, expected.version)
            val initial = stage(root)
            val sourceHash = pdfDigest(File(initial.directory, "pdfs/${expected.documents.single().storedName}"))
            RestoreService(repo).restore(initial, ImportPolicy.Replace)
            val imported = repo.dao.snapshot()
            fun keyed(s: LibrarySnapshot) = s.copy(documents = s.documents.sortedBy { it.id }, documentFolders = s.documentFolders.sortedBy { it.id },
                entries = s.entries.sortedBy { it.id }, collections = s.collections.sortedBy { it.id }, memberships = s.memberships.sortedBy { it.toString() },
                tags = s.tags.sortedBy { it.id }, entryTags = s.entryTags.sortedBy { it.toString() }, documentTags = s.documentTags.sortedBy { it.toString() },
                bookmarks = s.bookmarks.sortedBy { it.id }, readerFolders = s.readerFolders.sortedBy { it.id }, readerMemberships = s.readerMemberships.sortedBy { it.toString() },
                noteVersions = s.noteVersions.sortedBy { it.id })
            assertEquals(keyed(expected), keyed(imported))
            assertEquals(expected.researchFields, imported.researchFields)
            assertEquals(2, imported.researchFields.single().placements.count { it.entryId == "interop-quote" })
            RestoreService(repo).restore(stage(root), ImportPolicy.KeepLocal)
            assertEquals(imported, repo.dao.snapshot())
            val original = imported.entries.first { it.id == "interop-consp" }
            val old = original.richText()
            val changed = old.replace(old.text.length, old.text.length, RichText.plain("\nAndroid: русский текст 🙂"))
                .style(0, 3, TextStyle.Bold)
            val edit = original.copy(title = "Изменено на Android").withContent(changed)
            repo.dao.putEntry(edit)
            val book = imported.documents.single()
            val newQuote = PdfEngine(repo.pdfFile(book), app.cacheDir).use { engine ->
                val text = engine.text(1)
                val selection = text.selection(0, text.glyphs.lastIndex)!!
                assertTrue(selection.first.contains("Existence"))
                Entry("android-quote", "Создано на Android", quote = selection.first, documentId = book.id, anchor = selection.second,
                    kind = EntryKind.Quote, type = NoteType.Quote, createdAt = 1000, updatedAt = 1000)
            }
            repo.dao.putEntry(newQuote)
            assertEquals(imported.entries.first { it.id == "interop-quote" }.anchor, repo.dao.allEntries().first { it.id == "interop-quote" }.anchor)
            val exported = File(app.filesDir, "android-port-roundtrip.zip")
            exported.outputStream().use { BackupArchive.write(it, repo.dao.snapshot(), repo::pdfFile) }
            val back = exported.inputStream().use { BackupArchive.stage(it, File(root, "return")) }
            assertEquals(imported.researchFields, back.data.researchFields)
            assertEquals(changed, back.data.entries.first { it.id == original.id }.richText())
            assertEquals(old.blocks.map { it.id }, changed.blocks.take(old.blocks.size).map { it.id })
            assertEquals(sourceHash, pdfDigest(File(back.directory, "pdfs/${book.storedName}")))
            assertEquals(newQuote, back.data.entries.first { it.id == newQuote.id })
            File(app.filesDir, "android-port-expected.json").writeText(Json.encodeToString(expected))
        } finally { db.close(); app.deleteDatabase(name); root.deleteRecursively() }
    }

    @Test fun explicitPoliciesResolveWholeFieldsAndReplacementKeepsRecoverableBackup(): Unit = runBlocking {
        val root = File(app.cacheDir, "policy-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "policy-${UUID.randomUUID()}.db"; val db = LibraryDatabase.open(app, name)
        val repo = LibraryRepository(app, db, File(root, "pdfs"))
        try {
            val original = stage(root).also { RestoreService(repo).restore(it, ImportPolicy.Replace) }.data
            val field = original.researchFields.single().copy(title = "Местное поле")
            repo.saveResearchField(field)
            repo.dao.putEntry(Entry("local-only", "Местная запись"))
            val preview = stage(root)
            val before = repo.dao.snapshot()
            RestoreService(repo).checkPdfs(preview)
            assertEquals(before, repo.dao.snapshot()) // Preview and cancellation cannot mutate live data.
            preview.directory.deleteRecursively()
            RestoreService(repo).restore(stage(root), ImportPolicy.KeepLocal)
            assertEquals(field, repo.dao.snapshot().researchFields.single())
            RestoreService(repo).restore(stage(root), ImportPolicy.UseIncoming)
            assertEquals(original.researchFields, repo.dao.snapshot().researchFields)
            assertTrue(repo.dao.allEntries().any { it.id == "local-only" })
            val backup = RestoreService(repo).restore(stage(root), ImportPolicy.Replace)
            assertFalse(repo.dao.allEntries().any { it.id == "local-only" })
            val preserved = backup.inputStream().use { BackupArchive.stage(it, File(root, "backup")) }
            assertTrue(preserved.data.entries.any { it.id == "local-only" })
            repo.deleteEntries(setOf("interop-quote"))
            assertTrue(repo.dao.snapshot().researchFields.single().placements.none { it.entryId == "interop-quote" })
        } finally { db.close(); app.deleteDatabase(name); root.deleteRecursively() }
    }

    @Test fun interruptedFileSwapRollsBackWithoutReceiptAndKeepsCommittedFilesWithReceipt(): Unit = runBlocking {
        val root = File(app.cacheDir, "journal-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "journal-${UUID.randomUUID()}.db"; val db = LibraryDatabase.open(app, name)
        val pdfs = File(root, "pdfs").apply { mkdirs() }
        try {
            val old = File(pdfs, "book.pdf").apply { writeText("old valid state") }
            val source = File(root, "incoming.pdf").apply { writeText("new committed state") }
            val repo = LibraryRepository(app, db, pdfs)
            repo.ensureRecovered()
            val uncommitted = PdfFileTransaction.prepare(repo.transactionDirectory, pdfs, listOf("book.pdf" to source))
            uncommitted.apply()
            assertEquals("new committed state", old.readText())
            LibraryRepository(app, db, pdfs).ensureRecovered()
            assertEquals("old valid state", old.readText())
            val committed = PdfFileTransaction.prepare(repo.transactionDirectory, pdfs, listOf("book.pdf" to source))
            committed.apply()
            db.withTransaction { repo.dao.putImportReceipt(ImportReceipt(committed.id)) }
            LibraryRepository(app, db, pdfs).ensureRecovered()
            assertEquals("new committed state", old.readText())
            assertTrue(repo.dao.allImportReceipts().isEmpty())
            assertTrue(repo.transactionDirectory.listFiles().orEmpty().isEmpty())
        } finally { db.close(); app.deleteDatabase(name); root.deleteRecursively() }
    }
}
