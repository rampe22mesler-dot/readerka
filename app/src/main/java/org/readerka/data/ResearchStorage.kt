package org.readerka.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room stores the complete field atomically; the shared model is the ZIP wire format. */
@Entity(tableName = "research_fields")
data class ResearchFieldRow(@PrimaryKey val id: String, val json: String) {
    fun field(): ResearchField = Json.decodeFromString(json)
    companion object {
        fun from(field: ResearchField) = ResearchFieldRow(field.id, Json.encodeToString(field))
    }
}

@Entity(tableName = "import_receipts")
data class ImportReceipt(@PrimaryKey val id: String)
