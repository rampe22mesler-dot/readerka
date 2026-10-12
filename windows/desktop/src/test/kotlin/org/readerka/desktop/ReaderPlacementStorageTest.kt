package org.readerka.desktop

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection

class ReaderPlacementStorageTest {
    @Test fun newFoldersAndQuoteAssignmentsCommitTogether() {
        val directory = Files.createTempDirectory("readerka-properties-save").toFile()
        try {
            DesktopRepository(directory).use { repo ->
                val session = LibrarySession(repo)
                session.change { LibrarySnapshot(
                    documents = listOf(Document("book", "Book", "book.pdf", "book.pdf", pageCount = 1)),
                    entries = listOf(Entry("q", "Quote", quote = "Original", documentId = "book", kind = EntryKind.Quote)),
                ) }
                val folders = listOf(Collection("root", "Root"), Collection("child", "Child", "root"))
                val readerFolders = listOf(ReaderFolder("pdf", "book", ReaderFolderKind.Quotes, "PDF"))
                val draft = session.draft("q").copy(collections = setOf("child"), readerFolders = setOf("pdf"))
                val before = session.data
                var changes = 0
                session.onChange = { changes++ }
                assertThrows(IllegalArgumentException::class.java) {
                    session.save(draft, newCollections = folders, newReaderFolders = listOf(readerFolders.single().copy(parentId = "missing")))
                }
                assertEquals(before, session.data)
                assertEquals(before, repo.snapshot())
                assertEquals(0, changes)
                session.save(draft, "Свойства", folders, readerFolders)
                assertEquals(1, changes)
                assertEquals(1, session.data.noteVersions.size)
            }
            DesktopRepository(directory).use { repo ->
                val session = LibrarySession(repo)
                assertEquals("root", session.data.collections.single { it.id == "child" }.parentId)
                assertEquals(setOf("child"), session.draft("q").collections)
                assertEquals(setOf("pdf"), session.draft("q").readerFolders)
                assertEquals("Original", session.draft("q").entry.quote)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun movingQuoteSynchronizesAnOpenDraftAndSurvivesSavingAndRestart() {
        val directory = Files.createTempDirectory("readerka-placement").toFile()
        try {
            DesktopRepository(directory).use { repo ->
                val session = LibrarySession(repo)
                session.change { LibrarySnapshot(
                    documents = listOf(Document("book", "Book", "book.pdf", "book.pdf", pageCount = 1)),
                    entries = listOf(Entry("q", "Quote", quote = "Original", documentId = "book", kind = EntryKind.Quote)),
                    collections = listOf(Collection("global", "Global")),
                    memberships = listOf(Membership("global", "q")),
                    readerFolders = listOf("a", "b", "c").map { ReaderFolder(it, "book", ReaderFolderKind.Quotes, it) },
                    readerMemberships = listOf(ReaderMembership("a", "q"), ReaderMembership("b", "q")),
                ) }
                val content = RichText.plain("Unsaved comment").style(0, 7, TextStyle.Bold)
                session.updateDraft(session.draft("q").let { it.copy(entry = it.entry.withContent(content)) })
                session.change { ReaderOperations.move(it, "book", ReaderFolderKind.Quotes,
                    listOf(ReaderItem(ReaderItemKind.Quote, "q")), "a", "c") }
                assertEquals(setOf("b", "c"), session.draft("q").readerFolders)
                assertEquals(content, session.draft("q").entry.richText())
                session.save(session.draft("q"))
                // Changing only PDF assignments must also create a recoverable version.
                val versions = session.data.noteVersions.size
                session.save(session.draft("q").copy(readerFolders = setOf("a", "b")), "Свойства")
                assertEquals(versions + 1, session.data.noteVersions.size)
            }
            DesktopRepository(directory).use { repo ->
                val session = LibrarySession(repo)
                assertEquals(setOf("a", "b"), session.draft("q").readerFolders)
                assertEquals(setOf("global"), session.draft("q").collections)
                assertEquals("Original", session.draft("q").entry.quote)
                assertEquals("Unsaved comment", session.draft("q").entry.richText().text)
                assertTrue(session.draft("q").entry.richText().marks.any { it.style == TextStyle.Bold })
            }
        } finally { directory.deleteRecursively() }
    }
}
