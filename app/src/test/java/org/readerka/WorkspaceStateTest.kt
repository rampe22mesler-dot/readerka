package org.readerka

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class WorkspaceStateTest {
    @Test fun branchingAfterBackDropsForwardAndPreservesExactReadingOffset() {
        val start = ReadingLocation("a", 2, 3210)
        val next = ReadingLocation("b", 7, 800)
        val history = ReadingHistory().visit(start).visit(next).update(next.copy(offset = 2345))
        assertEquals(start, history.back().current)
        assertEquals(next.copy(offset = 2345), history.back().forward().current)
        val branch = history.back().visit(ReadingLocation("c", 1))
        assertFalse(branch.canForward)
        assertEquals(listOf("a", "c"), branch.locations.map { it.documentId })
    }
    @Test fun historyIsBoundedAndScrollingUpdatesCurrentWithoutAddingTransitions() {
        var history = ReadingHistory()
        repeat(150) { history = history.visit(ReadingLocation("a", it)) }
        assertEquals(100, history.locations.size)
        assertEquals(50, history.locations.first().page)
        history = history.update(ReadingLocation("a", 160, 9000))
        assertEquals(100, history.locations.size)
        assertEquals(148, history.back().current!!.page)
        assertEquals(ReadingLocation("a", 160, 9000), history.back().forward().current)
    }
    @Test fun draftRoundTripKeepsQuoteAnchorTagsAndMultipleMemberships() {
        val draft = EntryDraft(Entry("draft", "Кант", quote = "Being", comment = "Мысль\n**важно**", documentId = "pdf",
            anchor = Anchor(3, 10, 15, listOf(PageRect(.1f, .2f, .3f, .4f))), type = NoteType.Question, color = Marker.Green),
            tags = "онтология, 存在", collections = setOf("root", "child"), readerFolders = setOf("p1", "p2"))
        val state = WorkspaceState(listOf(draft), "draft", ReadingHistory().visit(ReadingLocation("pdf", 3, 5400)))
        val restored = Json.decodeFromString<WorkspaceState>(Json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals(draft, restored.editor)
        assertEquals(setOf("root", "child"), restored.editor!!.collections)
    }
}
