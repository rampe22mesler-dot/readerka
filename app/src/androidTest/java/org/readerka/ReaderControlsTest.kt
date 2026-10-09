package org.readerka

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.pdf.*
import org.readerka.ui.LibraryViewModel
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val dao get() = app.database.dao()
    private fun findPage(view: View, page: Int): PdfPageView? {
        if (view is PdfPageView && view.pageText?.page == page && view.bitmap != null) return view
        if (view is ViewGroup) for (n in 0 until view.childCount) findPage(view.getChildAt(n), page)?.let { return it }
        return null
    }
    private fun pageView(page: Int): PdfPageView {
        compose.waitUntil(30_000) { compose.runOnUiThread { findPage(compose.activity.window.decorView, page) != null } }
        return compose.runOnUiThread { findPage(compose.activity.window.decorView, page)!! }
    }
    private fun tap(view: PdfPageView, x: Float, y: Float) = compose.runOnUiThread {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
            MotionEvent.obtain(now, now + 30, action, x, y, 0).let { view.onTouchEvent(it); it.recycle() }
    }
    private fun waitPage(page: Int, count: Int = 8) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("reader-page-counter") and hasText("Страница ${page + 1} из $count")).fetchSemanticsNodes().isNotEmpty()
        }
        pageView(page)
    }
    private fun key(code: Int) = compose.runOnUiThread {
        // Dispatch through the real native window callback, including when a PDF canvas has focus.
        // This avoids injecting a key into unrelated emulator system windows.
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            assertTrue(compose.activity.window.callback.dispatchKeyEvent(KeyEvent(action, code)))
    }
    private fun withDocument(block: (Document, LibraryViewModel, Entry) -> Unit) {
        val id = UUID.randomUUID().toString()
        val file = File(app.filesDir, "pdfs/$id.pdf").apply { parentFile!!.mkdirs() }
        val document = Document(id, "Controls $id", "controls.pdf", file.name, pageCount = 8)
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
        try {
            PDDocument().use { pdf ->
                repeat(8) { n ->
                    val page = PDPage(PDRectangle.A4); pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.beginText(); stream.setFont(PDType1Font.TIMES_ROMAN, 16f)
                        stream.newLineAtOffset(40f, 760f); stream.showText("Page ${n + 1}: a quotation & a question?"); stream.endText()
                    }
                }; pdf.save(file)
            }
            val anchor = PdfEngine(file, app.cacheDir).use { it.text(0).matches("quotation").single() }
            val entry = Entry("quote-$id", "Saved quotation", "quotation", documentId = id, anchor = anchor, type = NoteType.Quote)
            runBlocking { dao.putDocument(document); dao.putEntry(entry) }
            compose.runOnUiThread { vm.open(document, page = 0) }; waitPage(0)
            block(document, vm, entry)
        } finally {
            compose.runOnUiThread { vm.navigate("library") }
            runBlocking { dao.deleteEntries(dao.allEntries().filter { it.documentId == id }.map { it.id }); dao.deleteDocument(id) }
            file.delete()
        }
    }

    @Test fun keyboardAndCenterTapSliderNavigateAndScrolledPagesKeepTheirImages() = withDocument { document, _, _ ->
        val focused = pageView(0)
        compose.runOnUiThread { assertTrue(focused.requestFocus()); assertTrue(focused.hasFocus()) }
        for ((key, target) in listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 1, KeyEvent.KEYCODE_DPAD_DOWN to 2,
            KeyEvent.KEYCODE_PAGE_UP to 1, KeyEvent.KEYCODE_DPAD_LEFT to 0, KeyEvent.KEYCODE_DPAD_UP to 0,
            KeyEvent.KEYCODE_MOVE_END to 7, KeyEvent.KEYCODE_MOVE_HOME to 0)) {
            android.util.Log.i("ReaderControls", "Keyboard code $key to page $target")
            key(key); waitPage(target)
        }
        android.util.Log.i("ReaderControls", "Keyboard sequence passed; opening strip after scroll")
        // Opening a toolbar during scrolling must not jump back to the last debounced saved page.
        compose.onNodeWithTag("pdf-pages").performScrollToIndex(5)
        compose.onNodeWithTag("reader-page-counter").performClick()
        waitPage(5)
        compose.onNodeWithTag("page-strip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть").performClick()
        key(KeyEvent.KEYCODE_MOVE_HOME); waitPage(0)
        val first = pageView(0)
        tap(first, first.width * .5f, first.height * .3f)
        compose.onNodeWithTag("page-strip").assertIsDisplayed()
        compose.onNodeWithTag("page-slider").performTouchInput { click(Offset(width * .62f, height / 2f)) }
        compose.waitUntil(30_000) { runBlocking { dao.allDocuments().first { it.id == document.id }.lastPage > 0 } }
        compose.onNodeWithTag("page-strip").assertIsDisplayed()
        compose.onNodeWithContentDescription("Предыдущая страница").assertExists()
        // Exercise the lazy AndroidView reuse by repeatedly scrolling to distant pages, then back.
        compose.onNodeWithContentDescription("Закрыть").performClick()
        repeat(2) {
            for (index in listOf(7, 3, 0)) {
                compose.onNodeWithTag("pdf-pages").performScrollToIndex(index)
                val canvas = pageView(index)
                compose.runOnUiThread {
                    assertFalse(canvas.bitmap!!.isRecycled)
                    assertEquals(index, canvas.pageText!!.page)
                    val image = canvas.bitmap!!
                    var ink = false
                    for (y in 0 until image.height step 3) for (x in 0 until image.width step 3)
                        if (image.getPixel(x, y) and 0x00ffffff != 0x00ffffff) ink = true
                    assertTrue("The returned page must contain rendered ink", ink)
                }
            }
        }
    }

    @Test fun tapSavedHighlightDeleteItAndTranslateRealSelection() = withDocument { document, _, entry ->
        var view = pageView(0)
        compose.waitUntil(30_000) { compose.runOnUiThread { view.highlights.any { it.id == entry.id } } }
        val highlighted = compose.runOnUiThread { view.screenRect(entry.anchor!!.rects.first()) }
        tap(view, highlighted.centerX(), highlighted.centerY())
        compose.onNodeWithTag("delete-quote").performClick()
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitUntil(30_000) { runBlocking { dao.allEntries().none { it.id == entry.id } } }
        assertTrue(runBlocking { dao.allDocuments().any { it.id == document.id } })
        view = pageView(0)
        val text = compose.runOnUiThread { view.pageText!! }
        val start = text.text.indexOf("quotation")
        val glyph = text.glyphs.first { it.start == start + 4 }
        val rect = compose.runOnUiThread { view.screenRect(glyph.rect) }
        fun touch(action: Int) = compose.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, action, rect.centerX(), rect.centerY(), 0).let { view.onTouchEvent(it); it.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(650); touch(MotionEvent.ACTION_UP)
        compose.onNodeWithText("quotation", substring = false).assertExists()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var externalLaunch = false
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    externalLaunch = true
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("Перевести").performClick()
            compose.onNodeWithTag("translation-overlay").assertIsDisplayed()
            compose.onNodeWithTag("translation-source").assertTextEquals("quotation")
            val uri = android.net.Uri.parse(org.readerka.ui.translationUrl("quotation & вопрос?\nслово"))
            assertEquals("https", uri.scheme); assertEquals("translate.google.com", uri.host)
            assertEquals("auto", uri.getQueryParameter("sl")); assertEquals("ru", uri.getQueryParameter("tl"))
            assertEquals("quotation & вопрос?\nслово", uri.getQueryParameter("text"))
            compose.runOnUiThread {
                val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals("quotation", clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            assertFalse("Translation must stay in Readerka", externalLaunch)
            compose.onNodeWithTag("close-translation").performClick()
            waitPage(0)
            compose.onNodeWithTag("translation-overlay").assertDoesNotExist()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun documentFoldersAndNestedBookmarksAreAvailableDuringReading() = withDocument { document, _, entry ->
        compose.onNodeWithTag("reader-quotes-button").performClick()
        compose.onNodeWithTag("create-root-folder").performClick()
        compose.onNode(hasText("Название") and hasAnyAncestor(isDialog())).performTextInput("Chapter")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(30_000) { runBlocking { dao.allReaderFolders().any { it.documentId == document.id && it.name == "Chapter" } } }
        val root = runBlocking { dao.allReaderFolders().first { it.documentId == document.id && it.name == "Chapter" } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("folder-menu-" + root.id).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-menu-" + root.id).performClick()
        compose.onNodeWithText("Создать подпапку").performClick()
        compose.onNode(hasText("Название") and hasAnyAncestor(isDialog())).performTextInput("Argument")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(30_000) { runBlocking { dao.allReaderFolders().any { it.documentId == document.id && it.name == "Argument" } } }
        val child = runBlocking { dao.allReaderFolders().first { it.documentId == document.id && it.name == "Argument" } }
        assertEquals(root.id, child.parentId)
        // The PDF canvas still opens its quotation editor while the document panel is visible.
        val view = pageView(0)
        val rect = compose.runOnUiThread { view.screenRect(entry.anchor!!.rects.first()) }
        tap(view, rect.centerX(), rect.centerY())
        compose.onNodeWithText("Папки цитат в этом PDF", substring = true).performScrollTo().performClick()
        compose.onNode(hasTestTag("expand-folder-" + root.id) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasTestTag("select-folder-" + root.id) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasTestTag("select-folder-" + child.id) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Применить").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(30_000) { runBlocking { dao.allReaderMemberships().count { it.entryId == entry.id } == 2 } }
        compose.onNodeWithTag("select-folder-" + root.id).performClick()
        compose.onAllNodesWithText("Saved quotation").assertCountEquals(1)
        compose.onNodeWithTag("reader-bookmarks-button").performClick()
        compose.onNodeWithContentDescription("Добавить закладку").performClick()
        compose.onNodeWithTag("bookmark-title").performTextClearance()
        compose.onNodeWithTag("bookmark-title").performTextInput("Return to start")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(30_000) { runBlocking { dao.allBookmarks().any { it.documentId == document.id && it.title == "Return to start" } } }
        val parent = runBlocking { dao.allBookmarks().first { it.documentId == document.id && it.title == "Return to start" } }
        compose.onNodeWithContentDescription("Вернуться к тексту").performClick()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitPage(1)
        compose.onNodeWithContentDescription("Добавить закладку").performClick()
        compose.onNodeWithTag("bookmark-title").performTextClearance()
        compose.onNodeWithTag("bookmark-title").performTextInput("Nested return")
        compose.onNodeWithText("Вложить в закладку: Без родительской закладки").performClick()
        compose.onNode(hasTestTag("select-folder-" + parent.id) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(30_000) { runBlocking { dao.allBookmarks().any { it.documentId == document.id && it.title == "Nested return" } } }
        val nested = runBlocking { dao.allBookmarks().first { it.documentId == document.id && it.title == "Nested return" } }
        assertEquals(parent.id, nested.parentId)
        compose.onNodeWithTag("reader-bookmarks-button").performClick()
        compose.onNodeWithTag("reader-bookmark-" + parent.id).performClick(); waitPage(0)
        compose.onNodeWithTag("reader-bookmark-" + nested.id).performClick(); waitPage(1)
        compose.activityRule.scenario.recreate()
        waitPage(1)
        assertEquals(setOf(root.id, child.id), runBlocking { dao.allReaderMemberships().filter { it.entryId == entry.id }.map { it.folderId }.toSet() })
        assertEquals(parent.id, runBlocking { dao.allBookmarks().first { it.id == nested.id }.parentId })
    }
}
