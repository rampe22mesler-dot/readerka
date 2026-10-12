@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.readerka.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable
data class EntryDraft(val entry: Entry, val tags: String = "", val collections: Set<String> = emptySet(),
    val readerFolders: Set<String> = emptySet(), @EncodeDefault val modifiedAt: Long = System.currentTimeMillis(),
    val cursorStart: Int = 0, val cursorEnd: Int = 0, val focusedField: String = "")

@Serializable
data class ReadingLocation(val documentId: String, val page: Int, val offset: Int = 0)

@Serializable
data class ReadingHistory(val locations: List<ReadingLocation> = emptyList(), val index: Int = -1) {
    val current get() = locations.getOrNull(index)
    val canBack get() = index > 0
    val canForward get() = index >= 0 && index < locations.lastIndex
    fun update(location: ReadingLocation): ReadingHistory = if (current?.documentId == location.documentId)
        copy(locations = locations.toMutableList().also { it[index] = location }) else this
    fun visit(location: ReadingLocation): ReadingHistory {
        if (current == location) return this
        val next = (locations.take(index + 1) + location).takeLast(100)
        return ReadingHistory(next, next.lastIndex)
    }
    fun back() = if (canBack) copy(index = index - 1) else this
    fun forward() = if (canForward) copy(index = index + 1) else this
}

@Serializable
enum class WorkspaceKind { Pdf, Note, ResearchField, LibraryGraph }

@Serializable
data class WorkspaceTab(val id: String, val documentId: String, val kind: WorkspaceKind,
    val pinned: Boolean = false, val page: Int = 0, val offset: Int = 0)

@Serializable
data class WorkspacePane(val tabs: List<WorkspaceTab> = emptyList(), val selectedId: String? = null) {
    val selected get() = tabs.firstOrNull { it.id == selectedId }
    fun open(tab: WorkspaceTab): WorkspacePane = copy(tabs = if (tabs.any { it.id == tab.id }) tabs else tabs + tab, selectedId = tab.id)
    fun close(id: String): WorkspacePane {
        val index = tabs.indexOfFirst { it.id == id }
        val remaining = tabs.filterNot { it.id == id }
        return copy(tabs = remaining, selectedId = if (selectedId == id) remaining.getOrNull(index.coerceIn(0, (remaining.size - 1).coerceAtLeast(0)))?.id else selectedId)
    }
    fun move(id: String, delta: Int): WorkspacePane {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val target = (index + delta).coerceIn(0, tabs.lastIndex)
        return copy(tabs = tabs.toMutableList().also { it.add(target, it.removeAt(index)) })
    }
}

@Serializable
data class WorkspaceState(val drafts: List<EntryDraft> = emptyList(), val activeDraftId: String? = null,
    val history: ReadingHistory = ReadingHistory(), val panes: List<WorkspacePane> = listOf(WorkspacePane(), WorkspacePane()),
    val activePane: Int = 0, val split: Boolean = false, val splitFraction: Float = .5f,
    val sidebarWidth: Float = 298f, val structureWidth: Float = 240f, val sidebarVisible: Boolean = true,
    val visiblePane: Int = 0, val lastConspByBook: Map<String, String> = emptyMap()) {
    val editor get() = drafts.firstOrNull { it.entry.id == activeDraftId }
}
