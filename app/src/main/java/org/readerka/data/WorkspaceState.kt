@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.readerka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
enum class WorkspaceKind { Pdf, Note }

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
    val sidebarWidth: Float = 260f, val structureWidth: Float = 280f, val sidebarVisible: Boolean = true,
    val visiblePane: Int = 0, val lastConspByBook: Map<String, String> = emptyMap()) {
    val editor get() = drafts.firstOrNull { it.entry.id == activeDraftId }
}

private val Context.workspaceDataStore by preferencesDataStore("workspace_state")
class WorkspaceStore(context: Context) {
    private val store = context.applicationContext.workspaceDataStore
    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val values = store.data.map { prefs -> prefs[key]?.let { json.decodeFromString<WorkspaceState>(it) } ?: WorkspaceState() }.flowOn(Dispatchers.IO)
    suspend fun save(state: WorkspaceState) = withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(state)
        store.edit { it[key] = encoded }
        Unit
    }
}
