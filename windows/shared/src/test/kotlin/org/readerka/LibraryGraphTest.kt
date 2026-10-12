package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class LibraryGraphTest {
    private fun fixture() = LibrarySnapshot(
        documents = listOf(Document("a", "Критика", "a.pdf", "a.pdf", authors = "Кант; Другой автор"),
            Document("b", "Пролегомены", "b.pdf", "b.pdf", folderId = "books", authors = "  КАНТ  ")),
        documentFolders = listOf(DocumentFolder("books", "Философия")),
        entries = listOf(Entry("q", "Цитата", documentId = "a", kind = EntryKind.Quote),
            Entry("n", "Вопрос", comment = "[цитата](readerka://entry/q?block=abc) [книга](readerka://document/b?page=2&entry=q) [нет](readerka://entry/missing)"),
            Entry("c", "Сравнение", kind = EntryKind.Consp, sources = listOf("a", "b", "a"))),
        tags = listOf(Tag("t", "Познание"), Tag("t2", " ПОЗНАНИЕ ")),
        entryTags = listOf(EntryTag("q", "t"), EntryTag("n", "t2")), documentTags = listOf(DocumentTag("b", "t")),
        collections = listOf(org.readerka.data.Collection("root", "Исследование"), org.readerka.data.Collection("child", "Аргументы", "root")),
        memberships = listOf(Membership("root", "q"), Membership("child", "q")),
        readerFolders = listOf(ReaderFolder("rf", "a", ReaderFolderKind.Quotes, "Цитаты")),
        readerMemberships = listOf(ReaderMembership("rf", "q")),
        bookmarks = listOf(Bookmark("bm", "b", 2, "Страница")),
        researchFields = listOf(ResearchField("f", "Проект", listOf(ResearchPlacement("p1", "q", 0.0, 0.0, groupId = "g"),
            ResearchPlacement("p2", "n", 300.0, 0.0)), listOf(ResearchEdge("e", "p2", "p1", ResearchRelation.Objects, "Проверить")),
            listOf(ResearchGroup("g", "Тезисы")), sequence = listOf("p1", "p2"))))

    @Test fun sharedAuthorsTopicsAndMultipleSourcesPreserveCanonicalRecords() {
        val g = LibraryGraph.build(fixture())
        assertEquals(2, g.nodes.count { it.kind == GraphKind.Author })
        assertEquals(1, g.nodes.count { it.kind == GraphKind.Topic })
        assertEquals(3, g.nodes.count { it.id.startsWith("entry:") })
        assertEquals(setOf("book:a", "book:b"), g.edges.filter { it.from == "entry:c" && it.relation == "источник" }.map { it.to }.toSet())
        assertEquals(2, g.edges.count { it.from == "author:кант" })
        assertEquals(3, g.incident.getValue("topic:познание").size)
        assertEquals(2, g.edges.count { it.from == "entry:q" && it.relation == "в папке" })
        assertTrue(g.edges.any { it.from == "entry:q" && it.to == "reader:rf" })
    }

    @Test fun typedFieldEdgesLinkEntriesAndRetainTheirContext() {
        val g = LibraryGraph.build(fixture())
        assertTrue(g.edges.contains(GraphEdge("entry:n", "entry:q", "возражает", "Проект · Проверить")))
        assertTrue(g.edges.contains(GraphEdge("entry:q", "entry:n", "следует перед", "Проект")))
        assertTrue(g.edges.contains(GraphEdge("entry:q", "group:f:g", "в группе")))
        assertTrue(g.edges.contains(GraphEdge("group:f:g", "field:f", "группа поля")))
        assertFalse(g.nodes.any { it.id == "p1" || it.id == "p2" })
    }

    @Test fun internalLinksResolveQueriesAndMissingTargetsDoNotInventNodes() {
        val g = LibraryGraph.build(fixture())
        assertTrue(g.edges.contains(GraphEdge("entry:n", "entry:q", "ссылка")))
        assertTrue(g.edges.contains(GraphEdge("entry:n", "book:b", "ссылка")))
        assertTrue(g.edges.contains(GraphEdge("entry:n", "entry:q", "ссылка на цитату")))
        assertFalse(g.byId.containsKey("entry:missing"))
        assertTrue(g.edges.all { it.from in g.byId && it.to in g.byId && it.from != it.to })
        assertEquals(g.edges.distinct(), g.edges)
    }

    @Test fun richLinksAndInvertedAuthorNamesAreUnambiguous() {
        val data = fixture().copy(documents = listOf(Document("a", "Книга", "a.pdf", "a.pdf", authors = "Kant, Immanuel")),
            entries = listOf(Entry("n", "Текст", content = RichText.plain("Книга").style(0, 5, TextStyle.Link, ResearchLinks.document("a", 2)))))
        val g = LibraryGraph.build(data)
        assertEquals("Kant, Immanuel", g.nodes.single { it.kind == GraphKind.Author }.title)
        assertTrue(g.edges.contains(GraphEdge("entry:n", "book:a", "ссылка")))
    }

    @Test fun filteringHasExactHopLimitsAndKeepsOnlyEdgesWithVisibleEndpoints() {
        val g = LibraryGraph(listOf(GraphNode("a", "Автор", GraphKind.Author), GraphNode("b", "Книга", GraphKind.Book),
            GraphNode("q", "Цитата", GraphKind.Quote), GraphNode("t", "Тема", GraphKind.Topic), GraphNode("x", "Без связей", GraphKind.Note)),
            listOf(GraphEdge("a", "b", "автор"), GraphEdge("q", "b", "источник"), GraphEdge("q", "t", "тема")))
        val all = GraphKind.entries.toSet()
        assertEquals(setOf("a", "b"), g.visible(all, focus = "a").byId.keys)
        assertEquals(setOf("a", "b", "q"), g.visible(all, focus = "a", depth = 2).byId.keys)
        assertEquals(setOf("a", "b", "q", "t"), g.visible(all, focus = "a", depth = 3).byId.keys)
        assertEquals(setOf("q", "t"), g.visible(all, query = "Тема").byId.keys)
        assertTrue(g.visible(all, query = "отсутствует").nodes.isEmpty())
        assertTrue(g.visible(emptySet()).edges.isEmpty())
        val filtered = g.visible(all - GraphKind.Book)
        assertEquals(listOf(GraphEdge("q", "t", "тема")), filtered.edges)
        assertTrue("x" in filtered.byId)
    }

    @Test fun rebuildingAfterDeletionRemovesRelationshipsAndUnrelatedTitlesCreateNoEdges() {
        val data = fixture()
        val g = LibraryGraph.build(data.copy(entries = data.entries.filterNot { it.id == "q" }))
        assertFalse("entry:q" in g.byId)
        assertFalse(g.edges.any { it.from == "entry:q" || it.to == "entry:q" })
        val empty = LibraryGraph.build(LibrarySnapshot(entries = listOf(Entry("a", "Общая тема"), Entry("b", "Общая тема"))))
        assertEquals(2, empty.nodes.size); assertTrue(empty.edges.isEmpty())
        assertTrue(LibraryGraph.build(LibrarySnapshot()).nodes.isEmpty())
    }
}
