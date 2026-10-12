package org.readerka.desktop

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.*

internal fun researchFixture(session: LibrarySession, root: File): List<Entry> {
    val entries = listOf("Kant" to "Reason supplies the principles of knowledge.", "Hume" to "Experience supplies the materials of knowledge.").mapIndexed { i, (author, text) ->
        val file = File(root, "source-$i.pdf")
        PDDocument().use { pdf ->
            repeat(2) { page ->
                val p = PDPage(); pdf.addPage(p)
                PDPageContentStream(pdf, p).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 14f); stream.newLineAtOffset(60f, 680f)
                    stream.showText(if (page == 0) "$author - synthetic source" else text); stream.endText()
                }
            }
            pdf.save(file)
        }
        val book = session.importPdf(file, null)
        session.change { it.copy(documents = it.documents.map { d -> if (d.id == book.id) d.copy(authors = author, title = "Позиция $author") else d }) }
        val selection = DesktopPdfEngine(session.repository.pdf(book)).use { engine -> engine.text(1).let { it.selection(0, it.glyphs.lastIndex)!! } }
        val quote = Entry("quote-$i", "Позиция $author", quote = selection.first, documentId = book.id, anchor = selection.second,
            kind = EntryKind.Quote, type = NoteType.Quote, comment = "Общий комментарий $author")
        session.save(EntryDraft(quote))
        session.data.entries.first { it.id == quote.id }
    }
    val a = ResearchPlacement("a", entries[0].id, -120.25, 80.75, projectComment = "Для введения", groupId = "g")
    val b = ResearchPlacement("b", entries[1].id, 420.0, 120.0, groupId = "g")
    session.change { it.copy(researchFields = listOf(
        ResearchField("project-a", "Рационализм и опыт", listOf(a,b), listOf(ResearchEdge("edge", "b", "a", ResearchRelation.Objects, "Основание знания")),
            listOf(ResearchGroup("g", "Позиции авторов")), zoom = .8, viewX = -160.0, viewY = 20.0, sequence = listOf("b", "a")),
        ResearchField("project-b", "План статьи", listOf(a.copy(id = "other-a", groupId = null, projectComment = "В раздел о разуме"))))) }
    session.save(EntryDraft(Entry("outline", "Конспект", kind = EntryKind.Consp).withContent(RichText.plain("План статьи\n"))))
    return entries
}

class ResearchFieldStorageTest {
    @Test fun twoSourcesTwoProjectsSurviveRestartZipAndPlacementRemoval() {
        val root = Files.createTempDirectory("readerka-field-storage").toFile()
        try {
            lateinit var original: List<Entry>
            lateinit var saved: LibrarySnapshot
            DesktopRepository(File(root, "library")).use { repo ->
                val session = LibrarySession(repo); original = researchFixture(session, root)
                val f = session.data.researchFields.first().move(setOf("a", "b"), 91.5, -20.25)
                session.change { it.copy(researchFields = listOf(f) + it.researchFields.drop(1)) }
                session.setWorkspace(session.workspace.copy(split = true, panes = listOf(WorkspacePane(), WorkspacePane(
                    listOf(WorkspaceTab("ResearchField:project-a", "project-a", WorkspaceKind.ResearchField)), "ResearchField:project-a"))))
                saved = session.data; repo.export(File(root, "fields.zip"))
            }
            DesktopRepository(File(root, "library")).use { repo ->
                assertEquals(saved, repo.snapshot())
                assertEquals(WorkspaceKind.ResearchField, repo.workspace().panes[1].selected!!.kind)
                val field = repo.snapshot().researchFields.first()
                assertEquals(-28.75, field.placements.first().x, .00001)
                assertEquals(.8, field.zoom, .00001)
                assertEquals("b", field.edges.single().from); assertEquals("a", field.edges.single().to)
                val session = LibrarySession(repo)
                session.change { it.copy(researchFields = listOf(field.removePlacements(setOf("a"))) + it.researchFields.drop(1)) }
                assertEquals(original, session.data.entries.filter { it.kind == EntryKind.Quote })
                assertEquals(0, session.data.researchFields.first().edges.size)
                assertEquals(listOf("b"), session.data.researchFields.first().sequence)
                assertEquals("В раздел о разуме", session.data.researchFields.last().placements.single().projectComment)
                assertEquals(original[0].id, session.data.researchFields.last().placements.single().entryId)
            }
            DesktopRepository(File(root, "other")).use { repo ->
                repo.applyImport(repo.stage(File(root, "fields.zip")), ImportPolicy.Replace)
                assertEquals(saved, repo.snapshot())
                original.forEach { e ->
                    val doc = repo.snapshot().documents.first { it.id == e.documentId }
                    DesktopPdfEngine(repo.pdf(doc)).use { engine ->
                        val anchor = e.anchor!!; val text = engine.text(anchor.page)
                        assertEquals(e.quote, text.text.substring(anchor.start, anchor.end))
                        assertEquals(anchor, text.selection(0, text.glyphs.lastIndex)!!.second)
                    }
                }
                val session = LibrarySession(repo); session.deleteEntry(original[0].id)
                assertTrue(session.data.researchFields.all { f -> f.placements.none { it.entryId == original[0].id } && f.edges.isEmpty() })
                BackupArchive.validate(repo.snapshot())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyLibraryMigratesOnceAndKeepsAnOldDecoderCompatibleBackup() {
        val root = Files.createTempDirectory("readerka-field-migration").toFile()
        try {
            val old = LibrarySnapshot(version = 4, entries = listOf(Entry("q", "Старая запись", comment = "Текст")))
            DesktopRepository(root).use { it.persist(old) }
            // Write exactly the old format, which does not know researchFields.
            DriverManager.getConnection("jdbc:sqlite:${File(root,"library.sqlite").absolutePath}").use { db ->
                db.prepareStatement("UPDATE state SET json=? WHERE key='library'").use {
                    it.setString(1, Json.encodeToString(old).replace(",\"researchFields\":[]", "")); it.executeUpdate()
                }
            }
            DesktopRepository(root).use { repo ->
                assertEquals(5, repo.snapshot().version); assertEquals(old.entries, repo.snapshot().entries)
                assertTrue(repo.snapshot().researchFields.isEmpty())
                val backup = repo.backups.listFiles()!!.single { it.extension == "zip" }
                ZipFile(backup).use { zip ->
                    val json = zip.getInputStream(zip.getEntry("library.json")).bufferedReader().readText()
                    assertFalse(json.contains("researchFields")); assertTrue(json.contains("\"version\":4"))
                }
                assertEquals(old.copy(version = 5), repo.stage(backup).data)
                assertEquals(1, repo.backups.listFiles()!!.count { it.name.endsWith("workspace.json") })
                val databaseBackup = repo.backups.listFiles()!!.single { it.extension == "sqlite" }
                DriverManager.getConnection("jdbc:sqlite:${databaseBackup.absolutePath}").use { db ->
                    db.createStatement().use { q -> q.executeQuery("SELECT json FROM state WHERE key='library'").use {
                        assertTrue(it.next()); assertEquals(4,Json.decodeFromString<LibrarySnapshot>(it.getString(1)).version)
                    } }
                }
            }
            DesktopRepository(root).use { repo -> assertEquals(1, repo.backups.listFiles()!!.count { it.extension == "zip" }) }
        } finally { root.deleteRecursively() }
    }

    @Test fun archiveMergeTreatsEachFieldAsOneConflictWithoutMixingItsEdges() {
        val a = ResearchPlacement("a", "q", 0.0, 0.0)
        val b = a.copy(id = "b", x = 500.0)
        val field = ResearchField("f", "Проект", listOf(a,b), listOf(ResearchEdge("e", "a", "b")))
        val local = LibrarySnapshot(entries = listOf(Entry("q", "Цитата")), researchFields = listOf(field))
        val incoming = local.copy(researchFields = listOf(field.copy(title = "Изменён", placements = listOf(a.copy(x = 20.0),b),
            edges = listOf(field.edges.single().copy(type = ResearchRelation.Supports, label = "Аргумент")))))
        assertEquals(field, LibraryMerge.merge(local, incoming, ImportPolicy.KeepLocal).researchFields.single())
        assertEquals(incoming.researchFields, LibraryMerge.merge(local, incoming, ImportPolicy.UseIncoming).researchFields)
        assertEquals("Исследовательское поле", LibraryMerge.differences(local, incoming).single().category)
    }

    @Test fun explicitSequencePreservesSourcesAndProjectCommentsWithoutChangingEntries() {
        val a = Entry("a", "Кант", quote = "Reason", documentId = "kant", anchor = Anchor(2,0,6,listOf(PageRect(.1f,.1f,.5f,.2f))), kind = EntryKind.Quote)
        val b = Entry("b", "Юм", quote = "Experience", documentId = "hume", anchor = Anchor(1,0,10,listOf(PageRect(.1f,.1f,.5f,.2f))), kind = EntryKind.Quote)
        val field = ResearchField("f", "Проект", listOf(ResearchPlacement("pa", "a", 0.0, 0.0, projectComment = "Для введения"), ResearchPlacement("pb", "b", 500.0, 0.0)))
        val result = ResearchAssembly.append(RichText.plain(""), field, listOf("pb", "pa"), listOf(a,b), InsertionMode.Content)
        assertTrue(result.text.indexOf("Experience") < result.text.indexOf("Reason"))
        assertTrue(result.text.contains("Для введения"))
        assertTrue(result.marks.any { it.value == "readerka://document/kant?page=3&entry=a" })
        assertTrue(result.marks.any { it.value == "readerka://document/hume?page=2&entry=b" })
        val links = ResearchAssembly.append(RichText.plain(""), field, listOf("pa", "pb"), listOf(a,b), InsertionMode.LinkCard)
        assertEquals(2, links.blocks.count { it.style == BlockStyle.LinkCard })
        assertFalse(links.text.contains("Reason")); assertEquals("", a.comment)
    }

    @Test fun validationRejectsDanglingEdgesInvalidCoordinatesAndMissingEntries() {
        val field = ResearchField("f", "Проект", listOf(ResearchPlacement("p", "q", 0.0, 0.0)))
        fun invalid(f: ResearchField) = assertThrows(IllegalArgumentException::class.java) { f.validate(setOf("q")) }
        invalid(field.copy(edges = listOf(ResearchEdge("e", "p", "missing"))))
        invalid(field.copy(placements = listOf(field.placements.single().copy(x = Double.NaN))))
        invalid(field.copy(placements = listOf(field.placements.single().copy(entryId = "missing"))))
        invalid(field.copy(sequence = listOf("p", "p")))
        invalid(field.copy(zoom = Double.POSITIVE_INFINITY))
    }
}
