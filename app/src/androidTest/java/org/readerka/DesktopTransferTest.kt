package org.readerka

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.*
import org.readerka.pdf.PdfEngine

class DesktopTransferTest {
    @Test
    fun desktopZipIntoRoomModifyAndReturnWithBackup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val root = File(app.cacheDir, "desktop-interop-${UUID.randomUUID()}").apply { mkdirs() }
        val db = LibraryDatabase.open(app, "desktop-interop-${UUID.randomUUID()}.db")
        val repository = LibraryRepository(app, db, File(root, "pdfs"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        try {
            val staged =
                assets.open("to-android.zip").use { BackupArchive.stage(it, File(root, "stage")) }
            val expected =
                Json.decodeFromString<LibrarySnapshot>(
                    assets.open("expected.json").bufferedReader().use { it.readText() }
                )
            val firstBackup = RestoreService(repository).restore(staged, ImportPolicy.UseIncoming)
            assertTrue(firstBackup.isFile)
            val actual = db.dao().snapshot()
            assertEquals(expected.documents.toSet(), actual.documents.toSet())
            assertEquals(expected.entries.toSet(), actual.entries.toSet())
            assertEquals(expected.collections.toSet(), actual.collections.toSet())
            assertEquals(expected.memberships.toSet(), actual.memberships.toSet())
            assertEquals(expected.bookmarks.toSet(), actual.bookmarks.toSet())
            assertEquals(expected.readerFolders.toSet(), actual.readerFolders.toSet())
            assertEquals(expected.readerMemberships.toSet(), actual.readerMemberships.toSet())
            assertEquals(expected.noteVersions.toSet(), actual.noteVersions.toSet())
            val texts =
                Json.decodeFromString<List<String>>(
                    assets.open("page-texts.json").bufferedReader().use { it.readText() }
                )
            PdfEngine(repository.pdfFile(actual.documents.single()), app.cacheDir).use { engine ->
                texts.forEachIndexed { index, text -> assertEquals(text, engine.text(index).text) }
            }
            val changed =
                actual.entries
                    .single { it.id == "interop-consp" }
                    .copy(title = "Изменено на Android")
            repository.saveEntry(changed, "философия", setOf("interop-consp-child"))
            val outgoing = File(app.cacheDir, "readerka-android-roundtrip.zip")
            outgoing.outputStream().use {
                BackupArchive.write(it, db.dao().snapshot(), repository::pdfFile)
            }
            // Reimport the original fixture with explicit update; snapshot before import must
            // retain Android edits.
            val original =
                assets.open("to-android.zip").use { BackupArchive.stage(it, File(root, "update")) }
            val backup = RestoreService(repository).restore(original, ImportPolicy.UseIncoming)
            val saved =
                backup.inputStream().use { BackupArchive.stage(it, File(root, "check-backup")) }
            assertEquals(
                "Изменено на Android",
                saved.data.entries.single { it.id == "interop-consp" }.title,
            )
            assertEquals(
                "Конспект по книге",
                db.dao().snapshot().entries.single { it.id == "interop-consp" }.title,
            )
            assertEquals(
                0,
                db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.count },
            )
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
