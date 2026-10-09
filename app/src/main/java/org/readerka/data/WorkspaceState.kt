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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EntryDraft(val entry: Entry, val tags: String = "", val collections: Set<String> = emptySet(),
    val readerFolders: Set<String> = emptySet(), val modifiedAt: Long = System.currentTimeMillis())

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
data class WorkspaceState(val drafts: List<EntryDraft> = emptyList(), val activeDraftId: String? = null,
    val history: ReadingHistory = ReadingHistory()) {
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
