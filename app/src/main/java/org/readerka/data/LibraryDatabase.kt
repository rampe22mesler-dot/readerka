package org.readerka.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC, createdAt DESC") abstract fun documents(): Flow<List<Document>>
    @Query("SELECT * FROM entries ORDER BY updatedAt DESC") abstract fun entries(): Flow<List<Entry>>
    @Query("SELECT * FROM document_folders ORDER BY name") abstract fun documentFolders(): Flow<List<DocumentFolder>>
    @Query("SELECT * FROM collections ORDER BY name") abstract fun collections(): Flow<List<Collection>>
    @Query("SELECT * FROM memberships") abstract fun memberships(): Flow<List<Membership>>
    @Query("SELECT * FROM tags ORDER BY name") abstract fun tags(): Flow<List<Tag>>
    @Query("SELECT * FROM entry_tags") abstract fun entryTags(): Flow<List<EntryTag>>
    @Query("SELECT * FROM document_tags") abstract fun documentTags(): Flow<List<DocumentTag>>
    @Query("SELECT * FROM bookmarks ORDER BY page") abstract fun bookmarks(): Flow<List<Bookmark>>
    @Query("SELECT * FROM documents") abstract suspend fun allDocuments(): List<Document>
    @Query("SELECT * FROM entries") abstract suspend fun allEntries(): List<Entry>
    @Query("SELECT * FROM document_folders") abstract suspend fun allDocumentFolders(): List<DocumentFolder>
    @Query("SELECT * FROM collections") abstract suspend fun allCollections(): List<Collection>
    @Query("SELECT * FROM memberships") abstract suspend fun allMemberships(): List<Membership>
    @Query("SELECT * FROM tags") abstract suspend fun allTags(): List<Tag>
    @Query("SELECT * FROM entry_tags") abstract suspend fun allEntryTags(): List<EntryTag>
    @Query("SELECT * FROM document_tags") abstract suspend fun allDocumentTags(): List<DocumentTag>
    @Query("SELECT * FROM bookmarks") abstract suspend fun allBookmarks(): List<Bookmark>
    @Query("SELECT * FROM reader_folders ORDER BY name") abstract suspend fun allReaderFolders(): List<ReaderFolder>
    @Query("SELECT * FROM reader_memberships") abstract suspend fun allReaderMemberships(): List<ReaderMembership>
    @Upsert abstract suspend fun putDocument(value: Document)
    @Upsert abstract suspend fun putEntry(value: Entry)
    @Upsert abstract suspend fun putFolder(value: DocumentFolder)
    @Upsert abstract suspend fun putCollection(value: Collection)
    @Upsert abstract suspend fun putBookmark(value: Bookmark)
    @Upsert abstract suspend fun putReaderFolder(value: ReaderFolder)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putReaderMembership(value: ReaderMembership)
    @Query("DELETE FROM reader_memberships WHERE entryId = :id") abstract suspend fun clearReaderMemberships(id: String)
    @Query("DELETE FROM reader_folders WHERE id = :id") abstract suspend fun deleteReaderFolder(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putMembership(value: Membership)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putTag(value: Tag)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putEntryTag(value: EntryTag)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun putDocumentTag(value: DocumentTag)
    @Query("DELETE FROM entry_tags WHERE entryId = :id") abstract suspend fun clearEntryTags(id: String)
    @Query("DELETE FROM document_tags WHERE documentId = :id") abstract suspend fun clearDocumentTags(id: String)
    @Query("DELETE FROM memberships WHERE entryId = :id") abstract suspend fun clearMemberships(id: String)
    @Query("DELETE FROM memberships WHERE collectionId = :collectionId AND entryId IN (:ids)") abstract suspend fun removeMemberships(collectionId: String, ids: List<String>)
    @Query("DELETE FROM entries WHERE id IN (:ids)") abstract suspend fun deleteEntries(ids: List<String>)
    @Query("DELETE FROM collections WHERE id = :id") abstract suspend fun deleteCollection(id: String)
    @Query("DELETE FROM document_folders WHERE id = :id") abstract suspend fun deleteFolder(id: String)
    @Query("DELETE FROM bookmarks WHERE id = :id") abstract suspend fun deleteBookmark(id: String)
    @Query("DELETE FROM tags WHERE id = :id") abstract suspend fun deleteTag(id: String)
    @Query("DELETE FROM documents WHERE id = :id") abstract suspend fun deleteDocument(id: String)
    @Query("UPDATE tags SET name = :name WHERE id = :id") abstract suspend fun renameTag(id: String, name: String)
    @Query("UPDATE documents SET lastPage=:page,lastOffset=:offset,lastOpenedAt=:time WHERE id=:id") abstract suspend fun savePosition(id: String, page: Int, offset: Int, time: Long)
    @Transaction open suspend fun snapshot() = LibrarySnapshot(
        documents = allDocuments(), documentFolders = allDocumentFolders(), entries = allEntries(),
        collections = allCollections(), memberships = allMemberships(), tags = allTags(),
        entryTags = allEntryTags(), documentTags = allDocumentTags(), bookmarks = allBookmarks(),
        readerFolders = allReaderFolders(), readerMemberships = allReaderMemberships()
    )
}

@Database(entities = [Document::class, DocumentFolder::class, Entry::class, Collection::class, Membership::class, Tag::class, EntryTag::class, DocumentTag::class, Bookmark::class, ReaderFolder::class, ReaderMembership::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reader_folders (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, parentId TEXT, FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(parentId) REFERENCES reader_folders(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reader_folders_documentId ON reader_folders(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reader_folders_parentId ON reader_folders(parentId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reader_memberships (folderId TEXT NOT NULL, entryId TEXT NOT NULL, PRIMARY KEY(folderId,entryId), FOREIGN KEY(folderId) REFERENCES reader_folders(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reader_memberships_entryId ON reader_memberships(entryId)")
                db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_v1")
                db.execSQL("CREATE TABLE bookmarks (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, page INTEGER NOT NULL, title TEXT NOT NULL, comment TEXT NOT NULL, parentId TEXT, folderId TEXT, offset INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(parentId) REFERENCES bookmarks(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(folderId) REFERENCES reader_folders(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("INSERT INTO bookmarks (id,documentId,page,title,comment) SELECT id,documentId,page,title,comment FROM bookmarks_v1")
                db.execSQL("DROP TABLE bookmarks_v1")
                db.execSQL("CREATE INDEX index_bookmarks_documentId ON bookmarks(documentId)")
                db.execSQL("CREATE INDEX index_bookmarks_parentId ON bookmarks(parentId)")
                db.execSQL("CREATE INDEX index_bookmarks_folderId ON bookmarks(folderId)")
            }
        }
        fun open(context: Context, name: String = "readerka.db") = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}
