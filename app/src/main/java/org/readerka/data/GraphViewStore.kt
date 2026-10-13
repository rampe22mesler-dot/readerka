package org.readerka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local UI state; never part of the library transfer format. */
@Serializable
data class GraphViewState(val viewX: Double = 0.0, val viewY: Double = 0.0, val zoom: Double = 1.0,
    val positions: Map<String, List<Double>> = emptyMap(), val kinds: Set<String> = GraphKind.entries.map { it.name }.toSet(),
    val query: String = "", val focus: String? = null, val depth: Int = 1)

@Serializable
data class FieldViewState(val selection: Set<String> = emptySet(), val forceCanvas: Boolean = false, val forceList: Boolean = false)

private val Context.graphDataStore by preferencesDataStore("graph_view")
class GraphViewStore(context: Context) {
    private val store = context.applicationContext.graphDataStore
    private val key = stringPreferencesKey("view")
    private val fieldKey = stringPreferencesKey("fields")
    private val json = Json { encodeDefaults = true }
    val values = store.data.map { prefs -> prefs[key]?.let { json.decodeFromString<GraphViewState>(it) } ?: GraphViewState() }.flowOn(Dispatchers.IO)
    suspend fun save(value: GraphViewState) = withContext(Dispatchers.IO) { store.edit { it[key] = json.encodeToString(value) }; Unit }
    val fields = store.data.map { prefs -> prefs[fieldKey]?.let { json.decodeFromString<Map<String, FieldViewState>>(it) } ?: emptyMap() }.flowOn(Dispatchers.IO)
    suspend fun saveFields(value: Map<String, FieldViewState>) = withContext(Dispatchers.IO) { store.edit { it[fieldKey] = json.encodeToString(value) }; Unit }
}
