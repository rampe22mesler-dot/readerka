package org.readerka.desktop

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class LibraryGraphLayoutTest {
    @Test fun aLargeGraphIncludesIsolatedNodesAndProducesFiniteDistinctPositions() {
        val graph = LibraryGraph((0 until 1200).map { GraphNode("n$it", "Узел $it", GraphKind.Note) },
            (1 until 1000).map { GraphEdge("n0", "n$it", "ссылка") })
        val points = LibraryGraphLayout.arrange(graph)
        assertEquals(graph.byId.keys,points.keys)
        assertTrue(points.values.all { it.x.isFinite() && it.y.isFinite() })
        assertEquals(1200,points.values.toSet().size)
    }
    @Test fun emptyAndCancelledLayoutsReturnImmediately() {
        assertTrue(LibraryGraphLayout.arrange(LibraryGraph(emptyList(),emptyList())).isEmpty())
        val graph = LibraryGraph(listOf(GraphNode("a","A",GraphKind.Book)),emptyList())
        assertTrue(LibraryGraphLayout.arrange(graph) { true }.isEmpty())
        assertEquals(LibraryGraphLayout.arrange(graph), LibraryGraphLayout.arrange(graph))
    }
}
