package org.readerka

import android.os.SystemClock
import android.view.KeyEvent
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
import org.readerka.pdf.PdfEngine
import org.readerka.ui.LibraryViewModel
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun treeTabsPositionsClosingAndActivityRecreation() {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val dao = app.database.dao()
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
        val originalPreferences = vm.preferences.value
        val id = UUID.randomUUID().toString()
        val folder = DocumentFolder("folder-$id", "Исследования")
        val child = DocumentFolder("child-$id", "Язык и мышление", folder.id)
        val docs = listOf(Document("first-$id", "Мышление и язык", "thinking.pdf", "first-$id.pdf", child.id, pageCount = 4),
            Document("second-$id", "Структура аргумента", "argument.pdf", "second-$id.pdf", child.id, pageCount = 4))
        fun fixture(document: Document, heading: String) {
            val file = File(app.filesDir, "pdfs/${document.storedName}").apply { parentFile!!.mkdirs() }
            PDDocument().use { pdf ->
                repeat(4) { n ->
                    val page = PDPage(PDRectangle.A4); pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.beginText(); stream.setFont(PDType1Font.TIMES_ROMAN, 22f); stream.newLineAtOffset(50f, 745f)
                        stream.showText(heading); stream.setFont(PDType1Font.TIMES_ROMAN, 14f)
                        repeat(20) { line -> stream.newLineAtOffset(0f, -25f); stream.showText("Page ${n + 1}. Reading, research and the structure of an argument. Line ${line + 1}.") }
                        stream.endText()
                    }
                }; pdf.save(file)
            }
        }
        fun waitFor(document: Document, page: Int) {
            compose.waitUntil(30_000) {
                vm.reader.value?.document?.id == document.id && vm.busy.value == null &&
                    compose.onAllNodes(hasTestTag("reader-page-counter") and hasText("Страница ${page + 1} из 4")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reader-page-counter").assertTextEquals("Страница ${page + 1} из 4")
        }
        fun shortcut(code: Int, shift: Boolean = false) = compose.runOnUiThread {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val now = SystemClock.uptimeMillis()
                val event = KeyEvent(now, now, action, code, 0, KeyEvent.META_CTRL_ON or if (shift) KeyEvent.META_SHIFT_ON else 0)
                assertTrue(compose.activity.window.callback.dispatchKeyEvent(event))
            }
        }
        fun savePreview(name: String) {
            fun page(view: android.view.View): org.readerka.pdf.PdfPageView? {
                if (view is org.readerka.pdf.PdfPageView && view.pageText?.page == 3 && view.bitmap != null) return view
                if (view is android.view.ViewGroup) for (n in 0 until view.childCount) page(view.getChildAt(n))?.let { return it }
                return null
            }
            compose.waitUntil(30_000) { compose.runOnUiThread { page(compose.activity.window.decorView) != null } }
            compose.waitForIdle(); SystemClock.sleep(500)
            val rectangles = compose.runOnUiThread {
                val view = page(compose.activity.window.decorView)!!
                val origin = IntArray(2); view.getLocationOnScreen(origin)
                val windowOrigin = IntArray(2); compose.activity.window.decorView.getLocationOnScreen(windowOrigin)
                view.pageText!!.glyphs.take(20).map { view.screenRect(it.rect).apply { offset((origin[0] - windowOrigin[0]).toFloat(), (origin[1] - windowOrigin[1]).toFloat()) } }
            }
            val directory = File(app.filesDir, "ui-previews").apply { mkdirs() }
            val bitmap = compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            }
            val copied = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicInteger(-1)
            compose.runOnUiThread {
                android.view.PixelCopy.request(compose.activity.window, bitmap, { code -> result.set(code); copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            assertTrue("Activity screenshot completed", copied.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS, result.get())
            bitmap.useBitmap { bitmap ->
                val ink = rectangles.count { rect ->
                    (rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(bitmap.height)).any { y ->
                        (rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(bitmap.width)).any { x ->
                            bitmap.getPixel(x, y) and 0x00ffffff != 0x00ffffff
                        }
                    }
                }
                assertTrue("The visible PDF must retain text after switching theme: $ink / ${rectangles.size}", ink >= rectangles.size * .8)
                File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        try {
            fixture(docs[0], "Thinking and language"); fixture(docs[1], "The structure of an argument")
            val anchor = PdfEngine(File(app.filesDir, "pdfs/${docs[0].storedName}"), app.cacheDir).use { it.text(3).matches("Reading, research").first() }
            val quote = Entry("quote-$id", "Чтение и аргументация", "Reading, research", "Тезис для дальнейшего анализа.", docs[0].id, anchor, NoteType.Quote)
            runBlocking { dao.putFolder(folder); dao.putFolder(child); docs.forEach { dao.putDocument(it) }; dao.putEntry(quote) }
            compose.runOnUiThread { vm.open(docs[0]) }; waitFor(docs[0], 0)
            compose.onNodeWithTag("workspace-sidebar").assertIsDisplayed()
            compose.onNodeWithTag("workspace-folder-" + child.id).assertIsDisplayed()
            compose.runOnUiThread {
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                    assertTrue(compose.activity.window.callback.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_MOVE_END)))
            }
            waitFor(docs[0], 3)
            compose.waitUntil(30_000) { runBlocking { dao.allDocuments().first { it.id == docs[0].id }.lastPage == 3 } }
            compose.onNodeWithTag("workspace-document-" + docs[1].id).performClick(); waitFor(docs[1], 0)
            shortcut(KeyEvent.KEYCODE_TAB); waitFor(docs[0], 3)
            shortcut(KeyEvent.KEYCODE_TAB, shift = true); waitFor(docs[1], 0)
            compose.onNodeWithTag("document-tab-" + docs[0].id).performClick(); waitFor(docs[0], 3)
            compose.onNodeWithTag("reader-materials-button").performClick()
            compose.onNodeWithTag("reader-quotes-button").performClick()
            savePreview("readerka-0.3-workspace-light.png")
            compose.runOnUiThread { vm.settings(originalPreferences.copy(theme = ThemeMode.Dark)) }
            compose.waitUntil(30_000) { vm.preferences.value.theme == ThemeMode.Dark }
            savePreview("readerka-0.3-workspace-dark.png")
            compose.runOnUiThread { vm.settings(originalPreferences) }
            shortcut(KeyEvent.KEYCODE_B, shift = true)
            compose.onNodeWithTag("workspace-sidebar").assertDoesNotExist()
            compose.onNodeWithTag("toggle-workspace-sidebar").performClick()
            compose.onNodeWithTag("workspace-sidebar").assertIsDisplayed()
            compose.onNodeWithTag("close-tab-" + docs[1].id).performClick()
            compose.onNodeWithTag("document-tab-" + docs[1].id).assertDoesNotExist(); waitFor(docs[0], 3)
            compose.activityRule.scenario.recreate(); waitFor(docs[0], 3)
            compose.onNodeWithTag("document-tab-" + docs[0].id).assertIsDisplayed()
            compose.onNodeWithTag("document-tab-" + docs[1].id).assertDoesNotExist()
            shortcut(KeyEvent.KEYCODE_W)
            compose.waitUntil(30_000) { vm.reader.value == null && vm.screen.value == "library" }
        } finally {
            compose.runOnUiThread { vm.settings(originalPreferences); docs.forEach { vm.closeDocument(it.id) }; vm.navigate("library") }
            runBlocking { dao.deleteEntries(listOf("quote-$id")); docs.forEach { dao.deleteDocument(it.id) }; dao.deleteFolder(child.id); dao.deleteFolder(folder.id) }
            docs.forEach { File(app.filesDir, "pdfs/${it.storedName}").delete() }
        }
    }
    private inline fun android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
