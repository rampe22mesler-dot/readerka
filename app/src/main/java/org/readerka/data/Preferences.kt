package org.readerka.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.readerPreferences by preferencesDataStore("reader_preferences")
enum class ThemeMode { System, Light, Dark }
data class ReaderPreferences(val theme: ThemeMode = ThemeMode.System, val defaultColor: Marker = Marker.Yellow, val scale: Float = 1f)

class Preferences(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val colorKey = stringPreferencesKey("default_color")
    private val scaleKey = floatPreferencesKey("ui_scale")
    val values = context.readerPreferences.data.map { p -> ReaderPreferences(
        runCatching { ThemeMode.valueOf(p[themeKey] ?: "System") }.getOrDefault(ThemeMode.System),
        runCatching { Marker.valueOf(p[colorKey] ?: "Yellow") }.getOrDefault(Marker.Yellow),
        (p[scaleKey] ?: 1f).coerceIn(.85f, 1.4f)
    ) }
    suspend fun save(value: ReaderPreferences) = context.readerPreferences.edit {
        it[themeKey] = value.theme.name; it[colorKey] = value.defaultColor.name; it[scaleKey] = value.scale
    }
}
