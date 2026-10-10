package org.readerka

import android.view.KeyEvent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.ui.LibraryViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ReadingWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val dao get() = app.database.dao()
    private val vm get() = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
    private fun shortcut(code: Int, meta: Int = KeyEvent.META_CTRL_ON) = compose.runOnUiThread {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(0, 0, action, code, 0, meta)
            assertTrue(compose.activity.window.callback.dispatchKeyEvent(event))
        }
    }
    private fun page(number: Int) {
        try {
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("reader-page-counter") and hasText("Страница $number из 4")).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Expected page $number; reader=${vm.reader.value?.let { "${it.document.id} page=${it.page} offset=${it.offset}" }}; panes=${vm.workspace.value.panes}; counters=${compose.onAllNodesWithTag("reader-page-counter").fetchSemanticsNodes().map { it.config }}", error)
        }
    }
    private fun fixture(block: (List<Document>) -> Unit) {
        val suffix = UUID.randomUUID().toString()
        val previous = runBlocking { WorkspaceStore(app).values.first() }
        val documents = (1..2).map { n -> Document("$suffix-$n", "Workspace $n $suffix", "workspace.pdf", "$suffix-$n.pdf", authors = "Author $n", pageCount = 4) }
        try {
            documents.forEach { document ->
                val file = File(app.filesDir, "pdfs/" + document.storedName).apply { parentFile!!.mkdirs() }
                PDDocument().use { pdf ->
                    repeat(4) { n ->
                        val sheet = PDPage(PDRectangle.A4); pdf.addPage(sheet)
                        PDPageContentStream(pdf, sheet).use { stream ->
                            stream.beginText(); stream.setFont(PDType1Font.TIMES_ROMAN, 18f); stream.newLineAtOffset(40f, 760f)
                            stream.showText("Reading workspace: page ${n + 1}"); stream.endText()
                        }
                    }; pdf.save(file)
                }
                runBlocking { dao.putDocument(document) }
            }
            compose.waitUntil(30_000) { vm.library.value.documents.any { it.id == documents.last().id } }
            compose.runOnUiThread {
                vm.workspace.value.panes.forEachIndexed { pane, state -> state.tabs.toList().forEach { vm.closeTab(pane, it.id) } }
                vm.layout(split = false, sidebarVisible = false); vm.focusPane(0)
            }
            block(documents)
        } finally {
            compose.runOnUiThread { vm.closeEditor(); documents.forEach { vm.closeDocument(it.id) }; vm.navigate("library") }
            runBlocking {
                vm.workspace.value.drafts.filter { it.entry.documentId in documents.map { d -> d.id } }.forEach { draft ->
                    compose.runOnUiThread { vm.discardDraft(draft.entry.id) }.join()
                }
                documents.forEach { document ->
                    dao.deleteEntries(dao.allEntries().filter { it.documentId == document.id }.map { it.id })
                    dao.deleteDocument(document.id); File(app.filesDir, "pdfs/" + document.storedName).delete()
                }
                WorkspaceStore(app).save(previous)
            }
        }
    }
    @Test fun inlineDraftSurvivesRecreationAndKeyboardSaveDoesNotDuplicateNote() = fixture { documents ->
        compose.runOnUiThread { vm.open(documents.first(), page = 1) }; page(2)
        shortcut(KeyEvent.KEYCODE_N)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("entry-editor").fetchSemanticsNodes().isNotEmpty() }
        // Both panes occupy the same Activity; the PDF stays visible while typing.
        compose.onNodeWithTag("entry-editor").assertIsDisplayed()
        compose.onNodeWithTag("pdf-pages").assertIsDisplayed()
        compose.onNodeWithTag("entry-title").performTextInput("Новая мысль")
        compose.onNodeWithTag("entry-comment").performTextInput("Мысль рядом с PDF\n**доказательство**")
        val id = vm.workspace.value.activeDraftId!!
        compose.waitUntil(30_000) { vm.draftStatus.value == "Черновик сохранён" }
        assertFalse(runBlocking { dao.allEntries().any { it.id == id } })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("entry-comment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entry-comment").assertTextContains("Мысль рядом с PDF\n**доказательство**")
        compose.onNodeWithTag("entry-title").assertTextContains("Новая мысль")
        page(2)
        // Plain cursor arrows inside the editor must not turn the PDF page.
        compose.onNodeWithTag("entry-comment").performClick()
        compose.runOnUiThread {
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        page(2)
        compose.onNodeWithTag("pdf-page-1").performTouchInput { click(center) }
        shortcut(KeyEvent.KEYCODE_DPAD_RIGHT, 0); page(3)
        shortcut(KeyEvent.KEYCODE_DPAD_RIGHT, 0); page(4)
        shortcut(KeyEvent.KEYCODE_DPAD_LEFT, 0); page(3)
        shortcut(KeyEvent.KEYCODE_DPAD_LEFT, 0); page(2)
        screenshot()
        shortcut(KeyEvent.KEYCODE_S)
        compose.waitUntil(30_000) { runBlocking { dao.allEntries().count { it.id == id } == 1 } && vm.workspace.value.editor == null }
        val entry = runBlocking { dao.allEntries().single { it.id == id } }
        assertEquals(documents.first().id, entry.documentId)
        assertEquals("Мысль рядом с PDF\n**доказательство**", entry.comment)
        assertFalse(vm.workspace.value.drafts.any { it.entry.id == id })
        shortcut(KeyEvent.KEYCODE_S)
        assertEquals(1, runBlocking { dao.allEntries().count { it.id == id } })
    }
    @Test fun commandSearchOpensSourceAndAltHistoryReturnsToPageAndOffset() = fixture { documents ->
        compose.runOnUiThread { vm.open(documents.first(), page = 1) }; page(2)
        compose.onNodeWithTag("pdf-pages").performTouchInput { swipeUp(startY = height * .75f, endY = height * .60f, durationMillis = 600) }
        compose.waitUntil(30_000) { (vm.workspace.value.history.current?.offset ?: 0) > 0 }
        val first = vm.workspace.value.history.current!!
        shortcut(KeyEvent.KEYCODE_O)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("command-query").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("command-query").performTextInput(documents.last().title)
        compose.onNodeWithTag("command-source-" + documents.last().id).assertIsDisplayed().performClick()
        page(1)
        compose.runOnUiThread { vm.open(documents.last(), page = 3, recordHistory = false) }; page(4)
        compose.waitUntil(30_000) { vm.workspace.value.history.current?.page == 3 }
        val second = vm.workspace.value.history.current!!
        shortcut(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON)
        page(first.page + 1)
        compose.waitUntil(30_000) { vm.workspace.value.history.current?.documentId == documents.first().id }
        assertEquals(first.page, vm.reader.value!!.page)
        assertTrue("Restored normalized offset must remain near the original", kotlin.math.abs(first.offset - vm.reader.value!!.offset) < 150)
        shortcut(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_ALT_ON); page(second.page + 1)
        assertEquals(documents.last().id, vm.reader.value!!.document.id)
        shortcut(KeyEvent.KEYCODE_P)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("command-query").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("command-query").performTextInput("Создать заметку")
        compose.onNodeWithTag("command-query").assertIsFocused()
        shortcut(KeyEvent.KEYCODE_ENTER, 0)
        compose.waitUntil(30_000) { vm.workspace.value.editor?.entry?.documentId == documents.last().id }
        compose.onNodeWithTag("entry-editor").assertIsDisplayed()
    }
    @Test fun closedStandaloneDraftResumesFromToolbarAndCtrlEnterSavesInsideDialog() {
        val title = "Standalone " + UUID.randomUUID().toString()
        var id: String? = null
        try {
            compose.runOnUiThread { vm.navigate("notes") }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Создать заметку").fetchSemanticsNodes().isNotEmpty() }
            shortcut(KeyEvent.KEYCODE_N)
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("entry-title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("entry-title").performTextInput(title)
            compose.onNodeWithTag("entry-comment").performTextInput("Незавершённая самостоятельная заметка")
            id = vm.workspace.value.activeDraftId!!
            compose.waitUntil(30_000) { vm.draftStatus.value == "Черновик сохранён" }
            compose.onNodeWithContentDescription("Закрыть редактор, сохранив черновик").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("entry-editor").fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { dao.allEntries().none { it.id == id } })
            compose.onNodeWithTag("open-drafts").performClick()
            compose.onNodeWithTag("draft-" + id).performClick()
            compose.onNodeWithTag("entry-comment").assertTextContains("Незавершённая самостоятельная заметка")
            compose.onNodeWithTag("entry-comment").performClick()
            compose.onNodeWithTag("entry-comment").performKeyInput {
                keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
                keyDown(androidx.compose.ui.input.key.Key.Enter)
                keyUp(androidx.compose.ui.input.key.Key.Enter)
                keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
            }
            compose.waitUntil(30_000) { runBlocking { dao.allEntries().any { it.id == id } } && vm.workspace.value.editor == null }
            assertEquals(1, runBlocking { dao.allEntries().count { it.id == id } })
        } finally {
            id?.let { value -> runBlocking { compose.runOnUiThread { vm.discardDraft(value) }.join(); dao.deleteEntries(listOf(value)) } }
            compose.runOnUiThread { vm.navigate("library") }
        }
    }
    private fun screenshot() {
        val result = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var status = -1
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(compose.activity.window, bitmap, { status = it; result.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(result.await(15, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
        File(app.getExternalFilesDir(null), "readerka-0.5-editor.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
