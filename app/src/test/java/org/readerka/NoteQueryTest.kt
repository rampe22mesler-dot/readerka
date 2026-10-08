package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class NoteQueryTest {
    private val data = LibrarySnapshot(documents = listOf(Document("d", "Критика", "Kant.pdf", "d.pdf", authors = "Кант")),
        entries = listOf(Entry("e1", "Цитата", "Existence", "ВОЗРАЖЕНИЕ", "d", type = NoteType.Objection, color = Marker.Green, createdAt = 100), Entry("e2", "Мысль", comment = "Идея", createdAt = 200)),
        collections = listOf(org.readerka.data.Collection("c1", "Первая"), org.readerka.data.Collection("c2", "Вторая")),
        memberships = listOf(Membership("c1", "e1"), Membership("c2", "e1")), tags = listOf(Tag("t", "Онтология")), entryTags = listOf(EntryTag("e1", "t")))
    @Test fun unicodeSearchAndCombinedFiltersFindCanonicalEntryOnlyOnce() {
        for (text in listOf("кант", "возражение", "онтология", "EXISTENCE")) assertEquals(listOf("e1"), NoteQuery(text = text).apply(data).map { it.id })
        assertEquals(listOf("e1"), NoteQuery(collectionId = "c1", type = NoteType.Objection, color = Marker.Green, tagId = "t", documentId = "d").apply(data).map { it.id })
        assertTrue(NoteQuery(collectionId = "c1", color = Marker.Blue).apply(data).isEmpty())
        assertEquals(listOf("e2"), NoteQuery(createdAfter = 150).apply(data).map { it.id })
        assertEquals(listOf("e1", "e2"), NoteQuery(sort = NoteSort.Oldest).apply(data).map { it.id })
    }
    @Test fun changingMembershipDoesNotCopyOrDestroyEntry() {
        val removed = data.copy(memberships = data.memberships.filterNot { it.collectionId == "c2" })
        assertTrue(NoteQuery(collectionId = "c2").apply(removed).isEmpty())
        assertEquals(1, NoteQuery(collectionId = "c1").apply(removed).size)
        assertEquals(2, NoteQuery().apply(removed).size)
    }
    @Test fun cyclicParentChangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { FolderTree.validate(listOf("a" to "b", "b" to "a")) }
        assertThrows(IllegalArgumentException::class.java) { FolderTree.validate(listOf("a" to "unknown")) }
        assertEquals(listOf("a", "b"), FolderTree.ordered(listOf("b" to "a", "a" to null), { it.first }, { it.second }).map { it.first })
    }
}
