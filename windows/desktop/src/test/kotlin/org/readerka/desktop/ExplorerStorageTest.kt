package org.readerka.desktop

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection

class ExplorerStorageTest {
    private val folders = listOf(Collection("a", "A"), Collection("b", "B"), Collection("child", "Child", "a"), Collection("deep", "Deep", "child"))
    private fun fixture() = LibrarySnapshot(collections = folders, entries = listOf(Entry("note", "Title").withContent(RichText.plain("Body"))),
        memberships = listOf(Membership("child", "note"), Membership("b", "note")))

    @Test fun moveOnlyReplacesTheOccurrenceAndAddNeverDuplicatesContent() {
        val item = ExplorerItem(1, "entry", "note", "child")
        val original = fixture()
        val moved = ExplorerOperations.move(original, listOf(item), 1, "a")
        assertEquals(setOf(Membership("a", "note"), Membership("b", "note")), moved.memberships.toSet())
        val added = ExplorerOperations.move(moved, listOf(item.copy(parent = "a")), 1, "child", true)
        assertEquals(3, added.memberships.size)
        assertEquals(original.entries, added.entries)
        assertEquals(added, ExplorerOperations.move(added, listOf(item), 1, "child", true))
        val root = ExplorerOperations.move(moved, listOf(item.copy(parent = "a")), 1, null)
        assertEquals(listOf(Membership("b", "note")), root.memberships)
    }

    @Test fun movingParentAndChildKeepsTheSubtreeIntact() {
        val moved = ExplorerOperations.move(fixture(), listOf(ExplorerItem(1, "folder", "a"), ExplorerItem(1, "folder", "child", "a"), ExplorerItem(1, "entry", "note", "child")), 1, "b")
        assertEquals("b", moved.collections.first { it.id == "a" }.parentId)
        assertEquals("a", moved.collections.first { it.id == "child" }.parentId)
        assertEquals(fixture().memberships, moved.memberships)
    }

    @Test fun deletingNestedFolderLiftsChildrenAndMembershipsToItsParent() {
        val original = fixture()
        val deleted = ExplorerOperations.deleteFolder(original, 1, "child")
        assertEquals("a", deleted.collections.first { it.id == "deep" }.parentId)
        assertEquals(setOf(Membership("a", "note"), Membership("b", "note")), deleted.memberships.toSet())
        assertEquals(original.entries, deleted.entries)
        val pdf = LibrarySnapshot(documentFolders = listOf(DocumentFolder("p", "Parent"), DocumentFolder("c", "Child", "p"), DocumentFolder("d", "Deep", "c")),
            documents = listOf(Document("pdf", "PDF", "original.pdf", "pdf.pdf", folderId = "c")))
        val lifted = ExplorerOperations.deleteFolder(pdf, 0, "c")
        assertEquals("p", lifted.documents.single().folderId)
        assertEquals("p", lifted.documentFolders.last().parentId)
        assertEquals("pdf.pdf", lifted.documents.single().storedName)
    }

    @Test fun cyclesAndMixedScopesAreRejectedBeforeAnyChange() {
        val original = fixture()
        for (target in listOf("a", "child", "deep")) {
            assertTrue(runCatching { ExplorerOperations.move(original, listOf(ExplorerItem(1, "folder", "a")), 1, target) }.isFailure)
        }
        assertTrue(runCatching { ExplorerOperations.move(original, listOf(ExplorerItem(1, "entry", "note", "child")), 2, null) }.isFailure)
        assertTrue(runCatching { ExplorerOperations.move(original, listOf(ExplorerItem(1, "folder", "a")), 1, "missing") }.isFailure)
        assertEquals(folders, original.collections)
    }

    @Test fun renameMoveDeleteAndSaveDraftSurviveRestartWithStableFormattingAndLinks() {
        val root = Files.createTempDirectory("readerka-explorer").toFile()
        try {
            DesktopRepository(root).use { repo ->
                val session = LibrarySession(repo)
                session.change { fixture() }
                val text = RichText.plain("Draft readerka://entry/note").style(0, 5, TextStyle.Bold)
                session.updateDraft(session.draft("note").copy(entry = session.draft("note").entry.withContent(text), cursorStart = 3))
                session.change { s -> s.copy(entries = s.entries.map { it.copy(title = "Renamed") }) }
                session.change { ExplorerOperations.move(it, listOf(ExplorerItem(1, "entry", "note", "child")), 1, "a") }
                session.change { ExplorerOperations.deleteFolder(it, 1, "child") }
                assertEquals("Renamed", session.draft("note").entry.title)
                assertEquals(text, session.draft("note").entry.richText())
                assertEquals(setOf("a", "b"), session.draft("note").collections)
                repo.persistPreference("explorer-expanded", "1:folder:a:")
                repo.persistPreference("explorer-selection", "1:entry:note:a")
                session.setWorkspace(session.workspace.copy(sidebarWidth = 294f, split = true,
                    panes = listOf(WorkspacePane(listOf(WorkspaceTab("Note:note", "note", WorkspaceKind.Note)), "Note:note"), WorkspacePane())))
            }
            DesktopRepository(root).use { repo ->
                val session = LibrarySession(repo)
                assertEquals(3, session.draft("note").cursorStart)
                assertEquals(294f, session.workspace.sidebarWidth)
                assertTrue(session.workspace.split)
                assertEquals("note", session.workspace.panes.first().selected?.documentId)
                assertEquals("1:folder:a:", repo.preference("explorer-expanded"))
                assertEquals("1:entry:note:a", repo.preference("explorer-selection"))
                session.save(session.draft("note"))
                assertEquals("Renamed", session.data.entries.single().title)
                assertEquals("note", session.data.entries.single().id)
                assertTrue(session.data.entries.single().richText().marks.any { it.style == TextStyle.Bold })
                assertTrue(session.data.entries.single().richText().text.contains("readerka://entry/note"))
                assertEquals(setOf("a", "b"), session.draft("note").collections)
                assertTrue(session.data.noteVersions.isNotEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
