package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection

class ReaderOperationsTest {
    private val quote = ReaderItem(ReaderItemKind.Quote, "q")
    private fun library() = LibrarySnapshot(
        documents = listOf(Document("book", "Book", "book.pdf", "book.pdf"), Document("other", "Other", "other.pdf", "other.pdf")),
        entries = listOf(Entry("q", "Quote", documentId = "book", kind = EntryKind.Quote)),
        collections = listOf(Collection("catalog", "Catalog")), memberships = listOf(Membership("catalog", "q")),
        readerFolders = listOf(
            ReaderFolder("a", "book", ReaderFolderKind.Quotes, "A"),
            ReaderFolder("b", "book", ReaderFolderKind.Quotes, "B"),
            ReaderFolder("c", "book", ReaderFolderKind.Quotes, "C", "a"),
            ReaderFolder("foreign", "other", ReaderFolderKind.Quotes, "Foreign"),
            ReaderFolder("marks", "book", ReaderFolderKind.Bookmarks, "Marks")),
        readerMemberships = listOf(ReaderMembership("a", "q"), ReaderMembership("b", "q")),
        bookmarks = listOf(Bookmark("mark", "book", 0, "Mark", offset = 1200)),
    )
    private fun move(data: LibrarySnapshot, source: String?, target: String?, additional: Boolean = false) =
        ReaderOperations.move(data, "book", ReaderFolderKind.Quotes, listOf(quote), source, target, additional)

    @Test fun moveOnlyReplacesTheSourceMembershipAndKeepsCatalogAndContent() {
        val before = library()
        val after = move(before, "a", "c")
        assertEquals(setOf(ReaderMembership("b", "q"), ReaderMembership("c", "q")), after.readerMemberships.toSet())
        assertEquals(before.memberships, after.memberships)
        assertEquals(before.entries, after.entries)
        assertEquals(before.bookmarks, after.bookmarks)
    }

    @Test fun copyAddsOneMembershipAndMovingToAnExistingFolderDoesNotDuplicateIt() {
        val before = library()
        val copied = move(before, "a", "c", true)
        assertEquals(3, copied.readerMemberships.size)
        assertEquals(copied, move(copied, "a", "c", true))
        assertEquals(listOf(ReaderMembership("b", "q")), move(before, "a", "b").readerMemberships)
    }

    @Test fun rootRemovesOnlyTheCurrentAssignmentAndCanAssignAnUnfiledQuote() {
        val after = move(library(), "a", null)
        assertEquals(listOf(ReaderMembership("b", "q")), after.readerMemberships)
        val root = move(after, "b", null)
        assertTrue(root.readerMemberships.isEmpty())
        assertEquals(listOf(ReaderMembership("c", "q")), move(root, null, "c").readerMemberships)
    }

    @Test fun rejectOtherBookWrongSectionAndStaleSource() {
        val before = library()
        for (target in listOf("foreign", "marks")) assertThrows(IllegalArgumentException::class.java) { move(before, "a", target) }
        assertThrows(IllegalArgumentException::class.java) { move(before, "c", "a") }
        assertThrows(IllegalArgumentException::class.java) { move(before, null, "a") }
        assertThrows(IllegalArgumentException::class.java) { move(before, "a", null, true) }
    }

    @Test fun movingFoldersKeepsChildrenButRejectsCycles() {
        val before = library()
        val item = listOf(ReaderItem(ReaderItemKind.Folder, "a"))
        val moved = ReaderOperations.move(before, "book", ReaderFolderKind.Quotes, item, null, "b")
        assertEquals("b", moved.readerFolders.first { it.id == "a" }.parentId)
        assertEquals("a", moved.readerFolders.first { it.id == "c" }.parentId)
        for (target in listOf("a", "c")) assertThrows(IllegalArgumentException::class.java) {
            ReaderOperations.move(before, "book", ReaderFolderKind.Quotes, item, null, target)
        }
    }

    @Test fun bookmarkMoveKeepsItsPositionAndRejectsCopyAndQuoteFolders() {
        val before = library()
        val item = listOf(ReaderItem(ReaderItemKind.Bookmark, "mark"))
        val moved = ReaderOperations.move(before, "book", ReaderFolderKind.Bookmarks, item, null, "marks")
        assertEquals(before.bookmarks.single().copy(folderId = "marks"), moved.bookmarks.single())
        assertThrows(IllegalArgumentException::class.java) {
            ReaderOperations.move(before, "book", ReaderFolderKind.Bookmarks, item, null, "marks", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderOperations.move(before, "book", ReaderFolderKind.Bookmarks, item, null, "a")
        }
    }

    @Test fun invalidBatchLeavesAllItemsInPlace() {
        val before = library()
        assertThrows(IllegalArgumentException::class.java) {
            ReaderOperations.move(before, "book", ReaderFolderKind.Quotes,
                listOf(quote, ReaderItem(ReaderItemKind.Folder, "a")), "a", "c")
        }
        assertEquals(2, before.readerMemberships.size)
        assertNull(before.readerFolders.first { it.id == "a" }.parentId)
    }

    @Test fun movingOccurrencesFromTwoExpandedBranchesKeepsOneCanonicalQuote() {
        val before = library()
        val moved = ReaderOperations.moveOccurrences(before, "book", ReaderFolderKind.Quotes,
            listOf(ReaderOccurrence(quote, "a"), ReaderOccurrence(quote, "b")), "c")
        assertEquals(listOf(ReaderMembership("c", "q")), moved.readerMemberships)
        assertEquals(before.entries, moved.entries)
        assertEquals(before.memberships, moved.memberships)
    }

    @Test fun selectingFolderWithItsVisibleDescendantsKeepsTheSubtreeIntact() {
        val before = library()
        val moved = ReaderOperations.moveOccurrences(before, "book", ReaderFolderKind.Quotes, listOf(
            ReaderOccurrence(ReaderItem(ReaderItemKind.Folder, "a"), null),
            ReaderOccurrence(ReaderItem(ReaderItemKind.Folder, "c"), "a"), ReaderOccurrence(quote, "a")), "b")
        assertEquals("b", moved.readerFolders.first { it.id == "a" }.parentId)
        assertEquals("a", moved.readerFolders.first { it.id == "c" }.parentId)
        assertEquals(before.readerMemberships, moved.readerMemberships)
    }
}
