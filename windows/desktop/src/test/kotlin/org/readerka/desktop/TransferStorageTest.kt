package org.readerka.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.*

class TransferStorageTest {
    @Test fun repeatedExchangeBetweenTwoDevicesPreservesIdentitiesAndLocalOnlyMaterials() {
        val root = Files.createTempDirectory("readerka-transfer").toFile()
        try {
            DesktopRepository(File(root, "device-a")).use { a ->
                DesktopRepository(File(root, "device-b")).use { b ->
                    val session = LibrarySession(a)
                    researchFixture(session, root)
                    val doc = session.data.documents.first()
                    session.change { it.copy(
                        documents = it.documents.map { d -> if (d.id == doc.id) d.copy(lastPage = 1, lastOffset = 2400) else d },
                        bookmarks = listOf(Bookmark("bookmark", doc.id, 1, "Место чтения")),
                        tags = listOf(Tag("tag", "Тема")),
                        entryTags = listOf(EntryTag(it.entries.first().id, "tag")),
                    ) }
                    val note = session.data.entries.first { it.kind == EntryKind.Consp }
                    session.updateDraft(session.draft(note.id).copy(entry = note.withContent(RichText.plain("Сохранённый черновик"))))
                    session.saveAll()
                    val original = a.snapshot()
                    val outbound = File(root, TransferPanel.suggestedFilename())
                    a.export(outbound)
                    b.applyImport(b.stage(outbound), ImportPolicy.KeepLocal)
                    assertEquals(original, b.snapshot())
                    val repeated = TransferSummary.compare(b.snapshot(), b.stage(outbound).let {
                        it.directory.deleteRecursively(); it.data
                    })
                    assertTrue(repeated.all { it.added == 0 && it.changed == 0 && it.localOnly == 0 })
                    b.applyImport(b.stage(outbound), ImportPolicy.KeepLocal)
                    assertEquals(original, b.snapshot())
                    original.documents.forEach { assertEquals(DesktopRepository.sha(a.pdf(it)), DesktopRepository.sha(b.pdf(it))) }

                    val remote = original.copy(entries = original.entries.map { if (it.id == note.id) it.copy(title = "Правка на B") else it })
                    b.persist(remote)
                    a.persist(original.copy(entries = original.entries + Entry("only-a", "Только на A")))
                    val beforeA = a.snapshot()
                    val inbound = File(root, "return.zip")
                    b.export(inbound)
                    val counts = TransferSummary.compare(a.snapshot(), remote).first { it.category == "Цитаты, заметки, конспекты" }
                    assertEquals(1, counts.changed)
                    assertEquals(1, counts.localOnly)
                    val backup = a.applyImport(a.stage(inbound), ImportPolicy.UseIncoming)
                    assertEquals("Правка на B", a.snapshot().entries.first { it.id == note.id }.title)
                    assertTrue(a.snapshot().entries.any { it.id == "only-a" })
                    assertEquals(original.researchFields, a.snapshot().researchFields)
                    val before = a.stage(backup)
                    try { assertEquals(beforeA, before.data) }
                    finally { before.directory.deleteRecursively() }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun pdfOnlyConflictIsDetectedAndKeepLocalPreservesItsBytes() {
        val root = Files.createTempDirectory("readerka-transfer-pdf").toFile()
        try {
            DesktopRepository(File(root, "a")).use { a ->
                DesktopRepository(File(root, "b")).use { b ->
                    researchFixture(LibrarySession(a), root)
                    val zip = File(root, "transfer.zip")
                    a.export(zip)
                    b.applyImport(b.stage(zip), ImportPolicy.UseIncoming)
                    val doc = a.snapshot().documents.first()
                    val originalHash = DesktopRepository.sha(b.pdf(doc))
                    org.apache.pdfbox.pdmodel.PDDocument.load(a.pdf(doc)).use { pdf ->
                        pdf.documentInformation.author = "Changed on A"
                        pdf.save(File(root, "changed.pdf"))
                    }
                    File(root, "changed.pdf").copyTo(a.pdf(doc), overwrite = true)
                    a.export(zip)
                    val staged = b.stage(zip)
                    assertTrue(LibraryMerge.differences(b.snapshot(), staged.data).isEmpty())
                    assertEquals(listOf(doc.id), b.pdfConflicts(staged))
                    b.applyImport(staged, ImportPolicy.KeepLocal)
                    assertEquals(originalHash, DesktopRepository.sha(b.pdf(doc)))
                    val backup = b.applyImport(b.stage(zip), ImportPolicy.UseIncoming)
                    assertEquals(DesktopRepository.sha(a.pdf(doc)), DesktopRepository.sha(b.pdf(doc)))
                    val before = b.stage(backup)
                    try { assertEquals(originalHash, DesktopRepository.sha(File(before.directory, "pdfs/${doc.storedName}"))) }
                    finally { before.directory.deleteRecursively() }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
