package org.readerka

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.ui.MarkdownDocument
import org.commonmark.node.*

class WorkspaceLayoutTest {
    @Test fun oldWorkspaceJsonRetainsDraftAndHistoryWithNewLayoutDefaults() {
        val old = """{"drafts":[{"entry":{"id":"n","title":"Старая заметка","comment":"**без потерь**"},"collections":["a","b"]}],"activeDraftId":"n","history":{"locations":[{"documentId":"pdf","page":7,"offset":900}],"index":0}}"""
        val state = Json.decodeFromString<WorkspaceState>(old)
        assertEquals("**без потерь**", state.editor!!.entry.comment)
        assertEquals(setOf("a", "b"), state.editor!!.collections)
        assertEquals(7, state.history.current!!.page)
        assertEquals(2, state.panes.size)
        assertEquals(.5f, state.splitFraction)
    }
    @Test fun twoPlacesOfOnePdfAndNoteTabsRoundTripIndependently() {
        val left = WorkspaceTab("left", "pdf", WorkspaceKind.Pdf, pinned = true, page = 2, offset = 1450)
        val right = WorkspaceTab("right", "pdf", WorkspaceKind.Pdf, page = 9, offset = 6200)
        val note = WorkspaceTab("note-tab", "n", WorkspaceKind.Note)
        val state = WorkspaceState(panes = listOf(WorkspacePane().open(left), WorkspacePane().open(right).open(note)), activePane = 1, split = true, splitFraction = .67f, sidebarWidth = 310f, structureWidth = 260f)
        val restored = Json.decodeFromString<WorkspaceState>(Json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals(2, restored.panes[0].selected!!.page)
        assertEquals(9, restored.panes[1].tabs.first().page)
        assertEquals(WorkspaceKind.Note, restored.panes[1].selected!!.kind)
    }
    @Test fun tabCloseChoosesNeighborWithoutDeletingDocumentOrOtherPane() {
        val a = WorkspaceTab("a", "same-note", WorkspaceKind.Note)
        val b = WorkspaceTab("b", "pdf", WorkspaceKind.Pdf, pinned = true)
        val state = WorkspacePane().open(a).open(b)
        val closed = state.close("b")
        assertEquals("a", closed.selectedId)
        assertEquals(listOf(a), closed.tabs)
        assertEquals(2, state.tabs.size)
        assertNull(closed.close("a").selectedId)
    }
    @Test fun reorderingPreservesPinnedStateAndExactLocations() {
        val a = WorkspaceTab("a", "pdf", WorkspaceKind.Pdf, pinned = true, page = 12, offset = 1234)
        val b = WorkspaceTab("b", "n", WorkspaceKind.Note)
        val moved = WorkspacePane().open(a).open(b).move("a", 1)
        assertEquals(listOf(b, a), moved.tabs)
        assertEquals("b", moved.selectedId)
        assertEquals(moved, moved.move("missing", -1))
    }
    @Test fun markdownParsingPreservesOriginalSourceAndRecognizesBlocksAndLinks() {
        val source = "# Заголовок\n\n**важно** и *курсив*\n\n- [ ] задача\n- [x] готово\n\n> цитата\n\n[PDF](readerka://document/id?page=7)"
        val root = MarkdownDocument.parse(source)
        val counts = mutableMapOf<String, Int>()
        root.accept(object: AbstractVisitor() {
            override fun visit(heading: Heading) { counts["heading"] = 1; visitChildren(heading) }
            override fun visit(list: BulletList) { counts["list"] = 1; visitChildren(list) }
            override fun visit(quote: BlockQuote) { counts["quote"] = 1; visitChildren(quote) }
            override fun visit(link: Link) { assertEquals("readerka://document/id?page=7", link.destination); counts["link"] = 1 }
        })
        assertEquals(setOf("heading", "list", "quote", "link"), counts.keys)
        assertTrue(source.contains("- [ ] задача"))
        val draft = EntryDraft(Entry("n", "Заголовок", comment = source))
        assertEquals(source, Json.decodeFromString<EntryDraft>(Json.encodeToString(draft)).entry.comment)
    }
}
