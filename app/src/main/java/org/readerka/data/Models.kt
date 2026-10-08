package org.readerka.data

import androidx.room.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.max

@Serializable
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun union(other: PageRect) = PageRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
}

@Serializable
data class Anchor(val page: Int, val start: Int, val end: Int, val rects: List<PageRect>)

@Serializable
enum class NoteType { Quote, Summary, Question, Objection, Agreement, Idea, Connection, GeneralNote }
@Serializable
enum class Marker(val argb: Long) {
    Yellow(0xFFFFD86B), Green(0xFF94CF9E), Blue(0xFF90CAF9), Pink(0xFFF2A6C8), Purple(0xFFC4AFF0), Orange(0xFFFFB876)
}

@Serializable
@Entity(tableName = "document_folders", foreignKeys = [ForeignKey(entity = DocumentFolder::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("parentId")])
data class DocumentFolder(@PrimaryKey val id: String, val name: String, val parentId: String? = null)

@Serializable
@Entity(tableName = "documents", foreignKeys = [ForeignKey(entity = DocumentFolder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("folderId")])
data class Document(
    @PrimaryKey val id: String, val title: String, val originalName: String, val storedName: String,
    val folderId: String? = null, val authors: String = "", val year: String = "", val publisher: String = "",
    val doi: String = "", val url: String = "", val description: String = "",
    val createdAt: Long = System.currentTimeMillis(), val lastOpenedAt: Long = 0,
    val lastPage: Int = 0, val lastOffset: Int = 0, val pageCount: Int = 0
)

// A quotation and its comment form one canonical research entry. Memberships never copy entries.
@Serializable
@Entity(tableName = "entries", foreignKeys = [ForeignKey(entity = Document::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("documentId")])
data class Entry(
    @PrimaryKey val id: String, val title: String, val quote: String = "", val comment: String = "",
    val documentId: String? = null, val anchor: Anchor? = null, val type: NoteType = NoteType.GeneralNote,
    val color: Marker = Marker.Yellow, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt
)

@Serializable
@Entity(tableName = "collections", foreignKeys = [ForeignKey(entity = Collection::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("parentId")])
data class Collection(@PrimaryKey val id: String, val name: String, val parentId: String? = null)

@Serializable
@Entity(tableName = "memberships", primaryKeys = ["collectionId", "entryId"], foreignKeys = [
    ForeignKey(entity = Collection::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("entryId")])
data class Membership(val collectionId: String, val entryId: String)

@Serializable
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(@PrimaryKey val id: String, val name: String)

@Serializable
@Entity(tableName = "entry_tags", primaryKeys = ["entryId", "tagId"], foreignKeys = [
    ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("tagId")])
data class EntryTag(val entryId: String, val tagId: String)

@Serializable
@Entity(tableName = "document_tags", primaryKeys = ["documentId", "tagId"], foreignKeys = [
    ForeignKey(entity = Document::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("tagId")])
data class DocumentTag(val documentId: String, val tagId: String)

@Serializable
@Entity(tableName = "bookmarks", foreignKeys = [
    ForeignKey(entity = Document::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Bookmark::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = ReaderFolder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("documentId"), Index("parentId"), Index("folderId")])
data class Bookmark(@PrimaryKey val id: String, val documentId: String, val page: Int, val title: String, val comment: String = "",
    val parentId: String? = null, val folderId: String? = null, @ColumnInfo(defaultValue = "0") val offset: Int = 0)

@Serializable enum class ReaderFolderKind { Quotes, Bookmarks }

@Serializable
@Entity(tableName = "reader_folders", foreignKeys = [
    ForeignKey(entity = Document::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ReaderFolder::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("documentId"), Index("parentId")])
data class ReaderFolder(@PrimaryKey val id: String, val documentId: String, val kind: ReaderFolderKind, val name: String, val parentId: String? = null)

@Serializable
@Entity(tableName = "reader_memberships", primaryKeys = ["folderId", "entryId"], foreignKeys = [
    ForeignKey(entity = ReaderFolder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("entryId")])
data class ReaderMembership(val folderId: String, val entryId: String)

@Serializable
data class LibrarySnapshot(
    val version: Int = 2, val documents: List<Document> = emptyList(), val documentFolders: List<DocumentFolder> = emptyList(),
    val entries: List<Entry> = emptyList(), val collections: List<Collection> = emptyList(),
    val memberships: List<Membership> = emptyList(), val tags: List<Tag> = emptyList(),
    val entryTags: List<EntryTag> = emptyList(), val documentTags: List<DocumentTag> = emptyList(), val bookmarks: List<Bookmark> = emptyList(),
    val readerFolders: List<ReaderFolder> = emptyList(), val readerMemberships: List<ReaderMembership> = emptyList()
)

class Converters {
    @TypeConverter fun anchorToJson(value: Anchor?): String? = value?.let { Json.encodeToString(it) }
    @TypeConverter fun jsonToAnchor(value: String?): Anchor? = value?.let { Json.decodeFromString(it) }
    @TypeConverter fun typeToString(value: NoteType) = value.name
    @TypeConverter fun stringToType(value: String) = NoteType.valueOf(value)
    @TypeConverter fun colorToString(value: Marker) = value.name
    @TypeConverter fun stringToColor(value: String) = Marker.valueOf(value)
    @TypeConverter fun readerKindToString(value: ReaderFolderKind) = value.name
    @TypeConverter fun stringToReaderKind(value: String) = ReaderFolderKind.valueOf(value)
}
