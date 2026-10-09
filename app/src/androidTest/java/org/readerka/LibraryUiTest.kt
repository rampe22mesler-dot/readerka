package org.readerka

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.pdf.PdfEngine
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import org.readerka.pdf.PdfPageView
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun createEditAndOrganizeNoteThroughRealUi() {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val dao = app.database.dao()
        val suffix = UUID.randomUUID().toString().take(8)
        val title = "Проверка каталога $suffix"
        val names = listOf("Первая тестовая папка $suffix", "Вторая тестовая папка $suffix")
        val tagNames = listOf("ТестКант_$suffix", "ТестОнтология_$suffix")
        try {
            compose.onNodeWithContentDescription("Заметки и цитаты").performClick()
            for (name in names) {
                compose.onNodeWithText("Создать папку").performClick()
                compose.onNodeWithText("Название").performTextInput(name)
                compose.onNodeWithText("Сохранить").performClick()
                compose.waitUntil(30_000) { runBlocking { dao.allCollections().any { it.name == name } } }
            }
            compose.onNodeWithText("Создать заметку").performClick()
            compose.onNodeWithTag("entry-title").performTextInput(title)
            compose.onNodeWithTag("entry-comment").performTextInput("Независимая мысль о существовании")
            compose.onNodeWithTag("entry-tags").performScrollTo().performTextInput(tagNames.joinToString(", "))
            for (name in names) compose.onNode(hasText(name) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allEntries().any { it.title == title } } }
            val entry = runBlocking { dao.allEntries().single { it.title == title } }
            assertEquals("Независимая мысль о существовании", entry.comment)
            assertNull(entry.documentId); assertNull(entry.anchor)
            compose.waitUntil(30_000) { runBlocking { dao.allMemberships().count { it.entryId == entry.id } == 2 } }
            assertEquals(2, runBlocking { dao.allEntryTags().count { it.entryId == entry.id } })
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Выбрать: $title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Выбрать: $title").performScrollTo().performClick()
            compose.onNodeWithText("Цвет: Все").performScrollTo().performClick()
            compose.onNodeWithText("Зелёный").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allEntries().first { it.id == entry.id }.color == Marker.Green } }
            val memberships = runBlocking { dao.allMemberships().filter { it.entryId == entry.id } }
            runBlocking { dao.removeMemberships(memberships.first().collectionId, listOf(entry.id)) }
            assertEquals(1, runBlocking { dao.allMemberships().count { it.entryId == entry.id } })
            assertEquals(1, runBlocking { dao.allEntries().count { it.id == entry.id } })
        } finally {
            runBlocking {
                dao.deleteEntries(dao.allEntries().filter { it.title == title }.map { it.id })
                dao.allCollections().filter { it.name in names }.forEach { dao.deleteCollection(it.id) }
                dao.allTags().filter { it.name in tagNames }.forEach { dao.deleteTag(it.id) }
            }
        }
    }
    @Test fun openQuotationFromCatalogAtActualPageAndKeepItAfterRecreation() {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val dao = app.database.dao()
        val id = UUID.randomUUID().toString()
        val file = File(app.filesDir, "pdfs/$id.pdf").apply { parentFile!!.mkdirs() }
        val title = "Источник $id"
        var entry: Entry? = null
        try {
            PDDocument().use { pdf ->
                repeat(3) { n -> val page = PDPage(PDRectangle.A4); pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { s -> s.beginText(); s.setFont(PDType1Font.TIMES_ROMAN, 14f); s.newLineAtOffset(40f, 150f); s.showText("Page ${n + 1}: Existence is not a predicate."); s.endText() }
                }; pdf.save(file)
            }
            val anchor = PdfEngine(file, app.cacheDir).use { it.text(2).matches("Existence").single() }
            val d = Document(id, title, "source.pdf", file.name, pageCount = 3)
            entry = Entry(id, "Цитата $id", "Existence", documentId = id, anchor = anchor, type = NoteType.Quote)
            runBlocking { dao.putDocument(d); dao.putEntry(entry!!) }
            compose.onNodeWithContentDescription("Заметки и цитаты").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Цитата $id").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Открыть в PDF").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Страница 3 из 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Страница 3 из 3").assertIsDisplayed()
            compose.waitUntil(30_000) { runBlocking { dao.allDocuments().first { it.id == id }.lastPage == 2 } }
            fun findPage(view: View): PdfPageView? {
                if (view is PdfPageView && view.pageText?.page == 2) return view
                if (view is ViewGroup) for (n in 0 until view.childCount) findPage(view.getChildAt(n))?.let { return it }
                return null
            }
            compose.waitUntil(30_000) { compose.runOnUiThread { findPage(compose.activity.window.decorView) != null } }
            fun touch(action: Int, last: Boolean) = compose.runOnUiThread {
                val view = findPage(compose.activity.window.decorView)!!
                val glyphs = view.pageText!!.glyphs
                val rect = view.screenRect(if (last) glyphs.last().rect else glyphs.first().rect)
                val now = SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, action, rect.centerX(), rect.centerY(), 0).let { event -> view.onTouchEvent(event); event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, false); SystemClock.sleep(650)
            touch(MotionEvent.ACTION_MOVE, true); touch(MotionEvent.ACTION_UP, true)
            compose.onNodeWithContentDescription("Жёлтый").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allEntries().count { it.documentId == id } == 2 } }
            val saved = runBlocking { dao.allEntries().first { it.documentId == id && it.id != id } }
            assertTrue(saved.quote.contains("Existence is not a predicate."))
            assertEquals(2, saved.anchor!!.page); assertEquals(Marker.Yellow, saved.color)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Страница 3 из 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Страница 3 из 3").assertIsDisplayed()
        } finally { runBlocking { dao.deleteEntries(dao.allEntries().filter { it.documentId == id }.map { it.id }); dao.deleteDocument(id) }; file.delete() }
    }
}
