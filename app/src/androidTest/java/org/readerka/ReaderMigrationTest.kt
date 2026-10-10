package org.readerka

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderMigrationTest {
    @Test fun upgradePreviewDatabase2KeepsNotesAndConvertsOnlyExplicitly(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val name = "migration-v2-" + UUID.randomUUID() + ".db"
        val file = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = Json.parseToJsonElement(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("org.readerka.data.LibraryDatabase/2.json").bufferedReader().use { it.readText() }).jsonObject.getValue("database").jsonObject
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { entity ->
                    val e = entity.jsonObject; val table = e.getValue("tableName").jsonPrimitive.content
                    db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                    e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table)) }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO entries(id,title,quote,comment,documentId,anchor,type,color,createdAt,updatedAt) VALUES('note','Конспект старой книги','','**Текст**',NULL,NULL,'Summary','Green',123,456)")
                db.version = 2
            }
            val database = LibraryDatabase.open(app, name)
            try {
                val repository = LibraryRepository(app, database)
                val original = repository.dao.snapshot().entries.single()
                assertEquals(EntryKind.Note, original.kind)
                assertEquals(NoteType.Summary, original.type)
                assertEquals("**Текст**", original.comment)
                assertNull(original.content)
                assertTrue(original.sources.isEmpty())
                val converted = original.copy(kind = EntryKind.Consp).withContent(original.richText())
                repository.saveEntry(converted, "", emptySet())
                assertEquals(original.id, repository.dao.allEntries().single().id)
                assertEquals("Текст", repository.dao.allEntries().single().content!!.text)
                assertTrue(repository.dao.allNoteVersions().any { it.entryId == original.id })
            } finally { database.close() }
        } finally { app.deleteDatabase(name) }
    }
    @Test fun upgradeOriginalDatabaseWithoutLosingQuotesFoldersTagsOrBookmarks(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val name = "migration-" + UUID.randomUUID() + ".db"
        val file = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = Json.parseToJsonElement(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("org.readerka.data.LibraryDatabase/1.json").bufferedReader().use { it.readText() }).jsonObject.getValue("database").jsonObject
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { entity ->
                    val e = entity.jsonObject
                    val table = e.getValue("tableName").jsonPrimitive.content
                    db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                    e["indices"]?.jsonArray?.forEach { index ->
                        db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO documents (id,title,originalName,storedName,folderId,authors,year,publisher,doi,url,description,createdAt,lastOpenedAt,lastPage,lastOffset,pageCount) VALUES ('doc','Research','old.pdf','doc.pdf',NULL,'Author','','','','','',123,456,2,2000,5)")
                db.execSQL("""INSERT INTO entries (id,title,quote,comment,documentId,anchor,type,color,createdAt,updatedAt) VALUES ('quote','Original','Text','My comment','doc','{"page":2,"start":0,"end":4,"rects":[{"left":0.1,"top":0.1,"right":0.2,"bottom":0.2}]}','Quote','Green',123,456)""")
                db.execSQL("INSERT INTO collections (id,name,parentId) VALUES ('collection','Original folder',NULL)")
                db.execSQL("INSERT INTO memberships (collectionId,entryId) VALUES ('collection','quote')")
                db.execSQL("INSERT INTO bookmarks (id,documentId,page,title,comment) VALUES ('bookmark','doc',2,'Return here','Original comment')")
                db.version = 1
            }
            val database = LibraryDatabase.open(app, name)
            try {
                val repository = LibraryRepository(app, database)
                val dao = repository.dao
                val before = dao.snapshot() // Opening Room validates the migrated schema.
                assertEquals("My comment", before.entries.single().comment)
                assertEquals(Marker.Green, before.entries.single().color)
                assertEquals(2, before.entries.single().anchor!!.page)
                assertEquals(1, before.memberships.size)
                assertEquals(2000, before.documents.single().lastOffset)
                assertEquals("Original comment", before.bookmarks.single().comment)
                assertEquals(0, before.bookmarks.single().offset)
                assertNull(before.bookmarks.single().parentId)
                repository.saveReaderFolder(ReaderFolder("root", "doc", ReaderFolderKind.Quotes, "Chapter"))
                repository.saveReaderFolder(ReaderFolder("child", "doc", ReaderFolderKind.Quotes, "Argument", "root"))
                repository.saveEntry(before.entries.single(), "", setOf("collection"), setOf("child"))
                repository.saveBookmark(Bookmark("nested", "doc", 4, "Child bookmark", parentId = "bookmark", offset = 2500))
                assertEquals("child", dao.allReaderMemberships().single().folderId)
                dao.deleteReaderFolder("root")
                assertNull(dao.allReaderFolders().single().parentId)
                assertEquals(1, dao.allEntries().size)
                dao.deleteBookmark("bookmark")
                assertNull(dao.allBookmarks().single().parentId)
                assertEquals(2500, dao.allBookmarks().single().offset)
            } finally { database.close() }
        } finally { app.deleteDatabase(name) }
    }
}
