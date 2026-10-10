package org.readerka

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ConspModelTest {
    @Test fun legacyNotesAndQuotesKeepTheirIdentityAndAreNotConverted() {
        val note = Json.decodeFromString<Entry>("""{"id":"n","title":"Конспект старой книги","comment":"Text","type":"Summary"}""")
        assertEquals(EntryKind.Note, note.kind)
        assertEquals(EntryKind.Quote, Json.decodeFromString<Entry>("""{"id":"q","title":"Quote","type":"Quote"}""").kind)
        assertNull(note.content)
        assertEquals("Text", note.comment)
    }
    @Test fun multiBookConspIsOneCanonicalEntryInAllFilters() {
        val entry = Entry("n", "Тема", kind = EntryKind.Consp, sources = listOf("a", "b"))
        val snapshot = LibrarySnapshot(documents = listOf(Document("a", "A", "a.pdf", "a.pdf"), Document("b", "B", "b.pdf", "b.pdf")), entries = listOf(entry))
        assertEquals(listOf(entry), NoteQuery(documentId = "a").apply(snapshot))
        assertEquals(listOf(entry), NoteQuery(documentId = "b").apply(snapshot))
        assertEquals(1, snapshot.entries.size)
        val detached = entry.copy(sources = emptyList())
        assertEquals(entry.comment, detached.comment)
        assertEquals(entry.id, detached.id)
    }
    @Test fun visualFormatsAndLinksSurviveSerializationAndMarkdownRoundTrip() {
        val rich = RichText.plain("Сильная мысль\nИсточник").style(0, 7, TextStyle.Bold).style(8, 13, TextStyle.Italic)
            .style(0, 7, TextStyle.Underline).style(14, 22, TextStyle.Link, ResearchLinks.entry("target"))
            .paragraph(0, 0, BlockStyle.Heading, 2)
        val restored = RichText.fromMarkdown(rich.markdown())
        assertEquals(rich.text, restored.text)
        assertEquals(rich.blocks.map { it.id }, restored.blocks.map { it.id })
        assertTrue(restored.marks.any { it.style == TextStyle.Bold })
        assertTrue(restored.marks.any { it.style == TextStyle.Italic })
        assertTrue(restored.marks.any { it.style == TextStyle.Underline })
        assertTrue(restored.marks.any { it.style == TextStyle.Link && it.value == ResearchLinks.entry("target") })
        assertEquals(rich, Json.decodeFromString<RichText>(Json.encodeToString(rich)))
    }
    @Test fun paragraphTargetsStayStableWhenHeadingIsRenamedOrMovedByInsertion() {
        val rich = RichText.plain("Первый\nВторой").paragraph(7, 7, BlockStyle.Heading)
        val id = rich.blocks[1].id
        val changed = rich.changeText("Первый\nДругое название")
        assertEquals(id, changed.blocks[1].id)
        val inserted = changed.replace(0, 0, RichText.plain("Введение\n"))
        assertTrue(inserted.blocks.any { it.id == id && it.start == 16 })
    }
    @Test fun selectionFormattingCanBeRemovedWithoutDestroyingOtherRanges() {
        val rich = RichText.plain("abcdefgh").style(0, 8, TextStyle.Bold).style(2, 6, TextStyle.Bold)
        assertEquals(listOf(TextMark(0, 2, TextStyle.Bold), TextMark(6, 8, TextStyle.Bold)), rich.marks)
        assertEquals("abXYgh", rich.replace(2, 6, RichText.plain("XY")).text)
    }
    @Test fun linkCardHasItsOwnBlockAndCopiedFragmentKeepsFormatWithFreshIdentities() {
        val card = RichText.plain("Запись").paragraph(0, 6, BlockStyle.LinkCard, target = ResearchLinks.entry("n"))
        val inserted = RichText().replace(0, 0, card)
        assertEquals(BlockStyle.LinkCard, inserted.blocks.single().style)
        assertEquals(ResearchLinks.entry("n"), inserted.blocks.single().target)
        val rich = RichText.plain("bold plain").style(0, 4, TextStyle.Bold)
        assertEquals("bold", rich.fragment(0, 4).text)
        assertEquals(TextStyle.Bold, rich.fragment(0, 4).marks.single().style)
        assertNotEquals(card.blocks[0].id, inserted.blocks[0].id)
    }
    @Test fun backlinksUseIdentityEvenAfterRenamingAndIncludeCardsAndFragments() {
        val target = Entry("target", "Old name")
        val referring = Entry("source", "Source", kind = EntryKind.Consp).withContent(RichText.plain("Label").style(0, 5, TextStyle.Link, ResearchLinks.entry(target.id, "block")))
        assertEquals(listOf(referring), ResearchLinks.backlinks(LibrarySnapshot(entries = listOf(target.copy(title = "New name"), referring)), target.id))
        val fromPage = Entry("page", "Page citation", kind = EntryKind.Consp).withContent(RichText.plain("Source").style(0, 6, TextStyle.Link, ResearchLinks.document("d", 2) + "&entry=" + target.id))
        assertEquals(listOf(fromPage), ResearchLinks.backlinks(LibrarySnapshot(entries = listOf(target, fromPage)), target.id))
    }
    @Test fun backupPreservesRichContentSourcesMembershipsAndHistoricalDrafts() {
        val temp = Files.createTempDirectory("consp-backup").toFile()
        try {
            val pdf = File(temp, "a.pdf").apply { writeText("%PDF fixture") }
            val entry = Entry("n", "Конспект", kind = EntryKind.Consp, sources = listOf("a")).withContent(RichText.fromMarkdown("# Вопрос\n\n**Мысль**"))
            val version = NoteVersion("v", "n", Json.encodeToString(EntryDraft(entry, "tag", setOf("c"))), 123, "До вставки")
            val data = LibrarySnapshot(documents = listOf(Document("a", "A", "a.pdf", "a.pdf")), entries = listOf(entry), collections = listOf(Collection("c", "Тема")), memberships = listOf(Membership("c", "n")), noteVersions = listOf(version))
            val bytes = ByteArrayOutputStream(); BackupArchive.write(bytes, data) { pdf }
            assertEquals(data, BackupArchive.stage(bytes.toByteArray().inputStream(), File(temp, "restore")).data)
        } finally { temp.deleteRecursively() }
    }
    @Test fun insertionBeforeFirstParagraphDoesNotMoveItsIdentityToTheNewParagraph() {
        val original = RichText.plain("Existing")
        val next = original.replace(0, 0, RichText.plain("New\n"))
        assertEquals(original.blocks.single().id, next.blocks.last().id)
        assertNotEquals(original.blocks.single().id, next.blocks.first().id)
    }
    @Test fun legacyParagraphLinksAreStableUntilAndAfterVisualEditing() {
        val entry = Entry("legacy", "Old", comment = "# Heading\n\nBody")
        assertEquals(entry.richText().blocks.map { it.id }, entry.copy(title = "Renamed").richText().blocks.map { it.id })
        val content = entry.richText().changeText("New heading\nBody")
        assertEquals(entry.richText().blocks.first().id, content.blocks.first().id)
    }
    @Test fun standaloneMarkdownExportResolvesEntryAndParagraphLinks() {
        val target = Entry("target", "Source").withContent(RichText.plain("Text"))
        val rich = RichText.plain("Label").style(0, 5, TextStyle.Link, ResearchLinks.entry(target.id, target.content!!.blocks.single().id))
        val data = LibrarySnapshot(entries = listOf(target, Entry("n", "Consp", kind = EntryKind.Consp).withContent(rich)))
        val markdown = NotesExporter.markdown(data)
        assertTrue(markdown.contains("[Label](#${target.content!!.blocks.single().id})"))
        assertFalse(markdown.contains("readerka://entry/target"))
    }
    @Test fun quotationInsertionUsesOriginalTextAndCanIncludeFormattedComment() {
        val quote = Entry("q", "Quote", quote = "Original quotation", comment = "**Own thought**", type = NoteType.Quote)
        assertEquals("Original quotation", quote.libraryContent().text)
        assertEquals("Original quotation\n\nOwn thought", quote.libraryContent(true).text)
        assertTrue(quote.libraryContent(true).marks.any { it.style == TextStyle.Bold })
        val multipleLines = RichText.fromMarkdown("> First\n> Second")
        assertTrue(multipleLines.blocks.all { it.style == BlockStyle.Quote })
        val code = RichText.fromMarkdown("~~~\na\nb\n~~~")
        assertTrue(code.blocks.all { it.style == BlockStyle.Code })
        val image = RichText.fromMarkdown("![Map](https://example.org/map.png)")
        assertTrue(image.markdown().contains("https://example.org/map.png"))
        assertEquals(image.text, RichText.fromMarkdown(image.markdown()).text)
        assertTrue(RichText.fromMarkdown("---").blocks.any { it.style == BlockStyle.Divider })
    }
    @Test fun reassigningTheSameLinkKeepsItInsteadOfTogglingItOff() {
        val first = RichText.plain("word").style(0, 4, TextStyle.Link, ResearchLinks.entry("n"))
        assertEquals(first, first.style(0, 4, TextStyle.Link, ResearchLinks.entry("n")))
    }
    @Test fun invalidRichRangesAndMissingSourceBooksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.validate(LibrarySnapshot(entries = listOf(Entry("n", "N", kind = EntryKind.Consp, sources = listOf("missing"))))) }
        val entry = Entry("n", "N").withContent(RichText("x", listOf(TextMark(0, 9, TextStyle.Bold))))
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.validate(LibrarySnapshot(entries = listOf(entry))) }
    }
}
