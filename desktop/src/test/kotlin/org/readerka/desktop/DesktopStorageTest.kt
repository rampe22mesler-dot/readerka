package org.readerka.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection
import org.readerka.export.*

class DesktopStorageTest {
    @Test
    fun draftsSurviveCloseAndSavedFormattingReturnsThroughZip() {
        val root = Files.createTempDirectory("readerka-storage").toFile()
        try {
            val folder = Collection("folder", "Тема", scope = CollectionScope.Consp)
            val entry =
                Entry("same-id", "Конспект", kind = EntryKind.Consp)
                    .withContent(RichText.plain("Жирный текст").style(0, 6, TextStyle.Bold))
            DesktopRepository(File(root, "library")).use { repo ->
                val session = LibrarySession(repo)
                session.change { it.copy(collections = listOf(folder)) }
                session.save(EntryDraft(entry, collections = setOf(folder.id)))
                session.updateDraft(
                    session
                        .draft(entry.id)
                        .copy(
                            entry =
                                entry.withContent(
                                    entry.richText().changeText("Жирный текст и черновик")
                                ),
                            cursorStart = 3,
                            cursorEnd = 5,
                        )
                )
            }
            DesktopRepository(File(root, "library")).use { repo ->
                val session = LibrarySession(repo)
                assertEquals(3, session.workspace.drafts.single().cursorStart)
                assertTrue(
                    session.workspace.drafts.single().entry.richText().marks.any {
                        it.style == TextStyle.Bold
                    }
                )
                session.saveAll()
                repo.export(File(root, "transfer.zip"))
                assertEquals(1, session.data.entries.size)
                assertEquals(entry.id, session.data.entries.single().id)
                assertTrue(session.data.noteVersions.isNotEmpty())
            }
            DesktopRepository(File(root, "other")).use { repo ->
                val staged = repo.stage(File(root, "transfer.zip"))
                val backup = repo.applyImport(staged, ImportPolicy.UseIncoming)
                assertTrue(backup.isFile)
                assertTrue(repo.snapshot().entries.single().richText().text.endsWith("черновик"))
                assertEquals(listOf(Membership(folder.id, entry.id)), repo.snapshot().memberships)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importBackupPreservesPreviousStateAndIncomingReplacesRelations() {
        val root = Files.createTempDirectory("readerka-conflicts").toFile()
        try {
            DesktopRepository(File(root, "library")).use { repo ->
                val original = Entry("note", "Старая заметка")
                val old =
                    LibrarySnapshot(
                        entries = listOf(original),
                        collections = listOf(Collection("folder", "Папка")),
                        memberships = listOf(Membership("folder", original.id)),
                    )
                repo.persist(old)
                val next =
                    old.copy(
                        entries = listOf(original.copy(title = "Изменено на ПК")),
                        memberships = emptyList(),
                    )
                val zip = File(root, "incoming.zip")
                zip.outputStream().use { BackupArchive.write(it, next) { error("No PDFs") } }
                val staged = repo.stage(zip)
                assertEquals(2, LibraryMerge.differences(old, next).size)
                val backup = repo.applyImport(staged, ImportPolicy.UseIncoming)
                assertEquals("Изменено на ПК", repo.snapshot().entries.single().title)
                assertTrue(repo.snapshot().memberships.isEmpty())
                val before = repo.stage(backup)
                assertEquals(old, before.data)
                before.directory.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidMixedScopesNeverModifyTheLibrary() {
        val root = Files.createTempDirectory("readerka-invalid").toFile()
        try {
            DesktopRepository(root).use { repo ->
                val old = LibrarySnapshot(collections = listOf(Collection("folder", "Заметки")))
                repo.persist(old)
                val incoming =
                    LibrarySnapshot(
                        collections =
                            listOf(
                                Collection("folder", "Конспекты", scope = CollectionScope.Consp)
                            ),
                        entries = listOf(Entry("outline", "Конспект", kind = EntryKind.Consp)),
                        memberships = listOf(Membership("folder", "outline")),
                    )
                val zip = File(root, "incoming.zip")
                zip.outputStream().use { BackupArchive.write(it, incoming) { error("No PDFs") } }
                val staged = repo.stage(zip)
                try {
                    repo.applyImport(staged, ImportPolicy.KeepLocal)
                    fail("Must reject mixed scopes")
                } catch (e: IllegalArgumentException) {
                    assertEquals(old, repo.snapshot())
                } finally {
                    staged.directory.deleteRecursively()
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun independentSameNamedTagsConvergeWithoutDuplicateEntries() {
        val old =
            LibrarySnapshot(
                entries = listOf(Entry("old", "Старая")),
                tags = listOf(Tag("a", "Тема")),
                entryTags = listOf(EntryTag("old", "a")),
            )
        val incoming =
            LibrarySnapshot(
                entries = listOf(Entry("new", "Новая")),
                tags = listOf(Tag("b", "Тема")),
                entryTags = listOf(EntryTag("new", "b")),
            )
        val merged = LibraryMerge.merge(old, incoming, ImportPolicy.UseIncoming)
        assertEquals(2, merged.entries.size)
        assertEquals(1, merged.tags.size)
        assertTrue(merged.entryTags.all { it.tagId == merged.tags.single().id })
    }

    @Test
    fun selectedWordLinkAndContentHaveDifferentSemantics() {
        val source =
            Entry("source", "Источник")
                .withContent(RichText.plain("Содержимое").style(0, 10, TextStyle.Italic))
        val original = RichText.plain("слово далее")
        val link = ResearchInsertion.insert(original, 0, 5, source, InsertionMode.InlineLink)
        assertEquals(original.text, link.text)
        assertEquals(ResearchLinks.entry(source.id), link.marks.single().value)
        val content =
            ResearchInsertion.insert(
                original,
                original.text.length,
                original.text.length,
                source,
                InsertionMode.Content,
            )
        assertTrue(content.text.contains("Содержимое"))
        assertTrue(content.marks.any { it.style == TextStyle.Italic })
        assertTrue(
            content.marks.any {
                it.style == TextStyle.Link && it.value == ResearchLinks.entry(source.id)
            }
        )
    }

    @Test
    fun replacementIsExplicitAndKeepLocalDoesNotLoseEdits() {
        val original = Entry("same", "Локальная", createdAt = 1000)
        val local = LibrarySnapshot(entries = listOf(original, Entry("extra", "Дополнительная")))
        val incoming = LibrarySnapshot(entries = listOf(original.copy(title = "Импорт")))
        assertEquals(
            "Локальная",
            LibraryMerge.merge(local, incoming, ImportPolicy.KeepLocal)
                .entries
                .first { it.id == "same" }
                .title,
        )
        assertEquals(2, LibraryMerge.merge(local, incoming, ImportPolicy.UseIncoming).entries.size)
        assertEquals(incoming, LibraryMerge.merge(local, incoming, ImportPolicy.Replace))
    }

    @Test
    fun interruptedPdfImportRecoversPreviousBytesOnNextLaunch() {
        val root = Files.createTempDirectory("readerka-crash").toFile()
        try {
            val repo = DesktopRepository(root)
            val document = Document("pdf", "Тест", "book.pdf", "book.pdf", pageCount = 1)
            val old = LibrarySnapshot(documents = listOf(document))
            repo.pdf(document).writeText("original PDF bytes")
            repo.persist(old)
            val incoming = File(root, "incoming.pdf").apply { writeText("new PDF bytes") }
            val tx =
                PdfFileTransaction.prepare(
                    File(root, "transactions"),
                    repo.pdfDirectory,
                    listOf(document.storedName to incoming),
                )
            tx.apply()
            assertEquals("new PDF bytes", repo.pdf(document).readText())
            repo.close()
            DesktopRepository(root).use { reopened ->
                assertEquals(old, reopened.snapshot())
                assertEquals("original PDF bytes", reopened.pdf(document).readText())
                assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedPdfTransactionDoesNotRollBackAfterRestart() {
        val root = Files.createTempDirectory("readerka-commit").toFile()
        try {
            val repo = DesktopRepository(root)
            val document = Document("pdf", "Тест", "book.pdf", "book.pdf", pageCount = 1)
            repo.pdf(document).writeText("old")
            repo.persist(LibrarySnapshot(documents = listOf(document)))
            val incoming = File(root, "incoming.pdf").apply { writeText("new") }
            val tx =
                PdfFileTransaction.prepare(
                    File(root, "transactions"),
                    repo.pdfDirectory,
                    listOf(document.storedName to incoming),
                )
            tx.apply()
            val next = LibrarySnapshot(documents = listOf(document.copy(title = "Изменено")))
            repo.persist(next, importReceipt = tx.id)
            repo.close()
            DesktopRepository(root).use { reopened ->
                assertEquals(next, reopened.snapshot())
                assertEquals("new", reopened.pdf(document).readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun renamedTagConflictRespectsChosenIdentity() {
        val local = LibrarySnapshot(tags = listOf(Tag("same", "Старое название")))
        val incoming = LibrarySnapshot(tags = listOf(Tag("same", "Новое название")))
        assertEquals(
            "Старое название",
            LibraryMerge.merge(local, incoming, ImportPolicy.KeepLocal).tags.single().name,
        )
        assertEquals(
            "Новое название",
            LibraryMerge.merge(local, incoming, ImportPolicy.UseIncoming).tags.single().name,
        )
    }

    @Test
    fun multipleCommittedJournalsNeverRestoreOlderPdfBytes() {
        val root = Files.createTempDirectory("readerka-receipts").toFile()
        try {
            val repo = DesktopRepository(root)
            val document = Document("pdf", "Тест", "book.pdf", "book.pdf", pageCount = 1)
            val data = LibrarySnapshot(documents = listOf(document))
            repo.persist(data)
            repo.pdf(document).writeText("original")
            val incoming = File(root, "incoming.pdf")
            incoming.writeText("first")
            val first =
                PdfFileTransaction.prepare(
                    File(root, "transactions"),
                    repo.pdfDirectory,
                    listOf(document.storedName to incoming),
                )
            first.apply()
            repo.persist(data, importReceipt = first.id)
            incoming.writeText("second")
            val second =
                PdfFileTransaction.prepare(
                    File(root, "transactions"),
                    repo.pdfDirectory,
                    listOf(document.storedName to incoming),
                )
            second.apply()
            repo.persist(data, importReceipt = second.id)
            repo.close()
            DesktopRepository(root).use {
                assertEquals("second", it.pdf(document).readText())
                assertTrue(File(root, "transactions").listFiles().orEmpty().isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
