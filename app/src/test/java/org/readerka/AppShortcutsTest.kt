package org.readerka

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.ui.AppShortcuts
import org.readerka.data.*

class AppShortcutsTest {
    @Test fun everyChordHasOneMeaningAcrossAllContexts() {
        val groups = AppShortcuts.all.groupBy { listOf(it.code, it.ctrl, it.alt, it.shift) }
        assertTrue("Conflicting shortcuts: " + groups.filterValues { it.size > 1 }, groups.values.all { it.size == 1 })
        assertTrue(AppShortcuts.all.all { it.keys.isNotBlank() && it.label.isNotBlank() })
        assertEquals(AppShortcuts.all.size, AppShortcuts.all.map { it.keys }.distinct().size)
        assertEquals("Ctrl+B", AppShortcuts.keys("bold"))
        assertEquals("Ctrl+Shift+B", AppShortcuts.keys("sidebar"))
    }
    @Test fun standardTextEditingChordsRemainWithNativeTextFields() {
        val nativeOnly = setOf("Ctrl+X", "Ctrl+V", "Ctrl+A", "Ctrl+Shift+V")
        assertTrue(AppShortcuts.all.none { it.keys in nativeOnly })
        assertEquals(listOf("copy-selection"), AppShortcuts.all.filter { it.keys == "Ctrl+C" }.map { it.action })
    }
    @Test fun layoutOrientationIsAdditiveAndSurvivesRestart() {
        val old = Json.decodeFromString<WorkspaceState>("{\"split\":true,\"splitFraction\":0.67}")
        assertEquals(SplitOrientation.Auto, old.splitOrientation)
        for (orientation in SplitOrientation.entries) {
            val state = old.copy(splitOrientation = orientation, visiblePane = 1)
            assertEquals(state, Json.decodeFromString<WorkspaceState>(Json.encodeToString(state)))
        }
    }
}
