package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.FolderTree

class FolderBrowserTest {
    private data class F(val id: String, val name: String, val parent: String? = null)
    private val folders = listOf(F("a", "Philosophy"), F("b", "Epistemology", "a"), F("c", "Kant", "b"), F("d", "Logic", "a"), F("e", "Other"))
    private fun rows(expanded: Set<String> = emptySet(), search: String = "") = FolderTree.visible(folders, { it.id }, { it.parent }, { it.name }, expanded, search)
    @Test fun collapseHidesWholeBranchAndSearchRevealsOnlyMatchingPaths() {
        assertEquals(listOf("e", "a"), rows().map { it.value.id })
        assertEquals(listOf("e", "a", "b", "d"), rows(setOf("a")).map { it.value.id })
        assertEquals(listOf("a", "b", "c"), rows(search = " kAnT ").map { it.value.id })
        assertEquals(listOf(0, 1, 2), rows(search = "Kant").map { it.depth })
        assertTrue(rows(search = "absent").isEmpty())
        assertEquals(setOf("a", "b"), FolderTree.ancestors(setOf("c", "d"), folders.map { it.id to it.parent }))
    }
    @Test fun largeTreeKeepsClosedBranchesOutOfVisibleList() {
        val large = listOf(F("root", "Root")) + (1..1000).map { F("child$it", "Folder $it", "root") }
        val closed = FolderTree.visible(large, { it.id }, { it.parent }, { it.name }, emptySet())
        assertEquals(1, closed.size)
        assertTrue(closed.single().hasChildren)
        assertEquals(1001, FolderTree.visible(large, { it.id }, { it.parent }, { it.name }, setOf("root")).size)
    }
}
