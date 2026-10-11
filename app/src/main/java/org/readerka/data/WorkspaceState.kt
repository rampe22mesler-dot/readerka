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
