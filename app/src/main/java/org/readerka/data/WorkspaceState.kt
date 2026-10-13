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

private val Context.workspaceDataStore by preferencesDataStore("workspace_state")
@Serializable
data class PdfViewState(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)
class WorkspaceStore(context: Context) {
    private val store = context.applicationContext.workspaceDataStore
    private val key = stringPreferencesKey("state")
    private val selectionKey = stringPreferencesKey("pdf_selections")
    private val viewKey = stringPreferencesKey("pdf_views")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val values = store.data.map { prefs -> prefs[key]?.let { json.decodeFromString<WorkspaceState>(it) } ?: WorkspaceState() }.flowOn(Dispatchers.IO)
    val selections = store.data.map { prefs -> prefs[selectionKey]?.let { json.decodeFromString<Map<String, Pair<String, Anchor>>>(it) } ?: emptyMap() }.flowOn(Dispatchers.IO)
    val views = store.data.map { prefs -> prefs[viewKey]?.let { json.decodeFromString<Map<String, PdfViewState>>(it) } ?: emptyMap() }.flowOn(Dispatchers.IO)
    suspend fun save(state: WorkspaceState, selections: Map<String, Pair<String, Anchor>>? = null, views: Map<String, PdfViewState>? = null) = withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(state)
        store.edit {
            it[key] = encoded
            if (selections != null) it[selectionKey] = json.encodeToString(selections)
            if (views != null) it[viewKey] = json.encodeToString(views)
        }
        Unit
    }
}
