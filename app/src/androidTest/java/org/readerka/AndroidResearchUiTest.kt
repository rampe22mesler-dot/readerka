package org.readerka

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.ui.LibraryViewModel
import org.readerka.ui.ResearchEditText
import org.readerka.ui.ReaderkaRoot
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidResearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun waitForStartup() = compose.waitForReaderkaReady()
    private val app get() = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val dao get() = app.database.dao()
    private val vm get() = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
    private fun waitTag(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun key(code: Int, meta: Int = KeyEvent.META_CTRL_ON) = compose.runOnUiThread {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0, meta))
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val done = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var result = -1
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(compose.activity.window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(20, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(app.filesDir, "ui-previews/$name").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun fieldEditsSurviveRecreationAndRemovingPlacementKeepsCanonicalText() {
        val token = UUID.randomUUID().toString()
        val entries = listOf(Entry("note-$token", "Аргумент", comment = "Канонический комментарий", content = RichText.fromMarkdown("# Аргумент\n\n**Основание** для исследования")),
            Entry("quote-$token", "Возражение", quote = "Исходная цитата", kind = EntryKind.Quote))
        val cards = listOf(ResearchPlacement("a-$token", entries[0].id, -40.0, 30.0), ResearchPlacement("b-$token", entries[1].id, 340.0, 30.0))
        val field = ResearchField("field-$token", "Исследование аргумента", placements = cards, viewX = -64.0)
        val oldIds = runBlocking { dao.allEntries().map { it.id }.toSet() }
        try {
            runBlocking { entries.forEach { dao.putEntry(it) }; dao.putResearchField(ResearchFieldRow.from(field)) }
            compose.waitUntil(60_000) { vm.library.value.researchFields.any { it.id == field.id } }
            compose.runOnUiThread { vm.layout(split = false, sidebarVisible = false); vm.openResearchField(field.id, 0) }
            waitTag("research-field")
            screenshot("android-tablet-field.png")
            compose.onNodeWithText("Список", substring = false).performClick()
            waitTag("field-list")
            compose.onNodeWithTag("field-comment-${cards[0].id}").performScrollTo().performClick()
            compose.onNodeWithTag("field-comment-input").performTextInput("Применение только в этом проекте")
            compose.onNode(hasText("Сохранить") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithTag("field-geometry-${cards[0].id}").performScrollTo().performClick()
            compose.onNodeWithTag("field-geometry-0").performTextReplacement("-120")
            compose.onNode(hasText("Сохранить") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithTag("field-select-${cards[0].id}").performScrollTo().performClick()
            compose.onNodeWithTag("field-select-${cards[1].id}").performScrollTo().performClick()
            compose.onNodeWithText("Связать", substring = false).performClick()
            compose.onNode(hasText("Связать") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("В порядок", substring = false).performClick()
            compose.waitUntil(60_000) { runBlocking { dao.snapshot().researchFields.first { it.id == field.id }.sequence.size == 2 } }
            compose.activityRule.scenario.recreate()
            waitTag("field-list")
            val restored = runBlocking { dao.snapshot().researchFields.first { it.id == field.id } }
            assertEquals(-120.0, restored.placements.first().x, 0.0)
            assertEquals("Применение только в этом проекте", restored.placements.first().projectComment)
            assertEquals(1, restored.edges.size)
            assertEquals(entries, runBlocking { dao.allEntries().filter { it.id in entries.map { e -> e.id } } }.sortedBy { it.id }.let { found -> entries.map { expected -> found.single { it.id == expected.id } } })
            compose.onNodeWithText("Собрать конспект", substring = false).performClick()
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.kind == EntryKind.Consp && it.richText().text.contains("Применение только") } }
            screenshot("android-tablet-consp.png")
            compose.runOnUiThread { vm.openResearchField(field.id, 0) }
            waitTag("field-list")
            compose.onNodeWithText("Убрать карточки", substring = false).performClick()
            compose.waitUntil(60_000) { runBlocking { dao.snapshot().researchFields.first { it.id == field.id }.placements.isEmpty() } }
            assertEquals(2, runBlocking { dao.allEntries().count { it.id in entries.map { e -> e.id } } })
            key(KeyEvent.KEYCODE_Z)
            compose.waitUntil(60_000) { runBlocking { dao.snapshot().researchFields.first { it.id == field.id }.placements.size == 2 } }
            compose.runOnUiThread { vm.openLibraryGraph(0) }
            waitTag("library-graph-canvas")
            screenshot("android-tablet-graph.png")
            compose.onNodeWithText("Поиск узлов").performTextInput("Аргумент")
            compose.onNodeWithText("Показать список").performClick()
            val graphArgument = hasText("Аргумент") and hasAnyAncestor(hasTestTag("library-graph-list"))
            compose.waitUntil(60_000) { compose.onAllNodes(graphArgument).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(graphArgument).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(60_000) { vm.graphViewState.value.query == "Аргумент" }
        } finally {
            runBlocking {
                compose.runOnUiThread { vm.deleteResearchField(field.id) }.join()
                vm.workspace.value.drafts.filter { it.entry.id !in oldIds }.forEach { draft -> compose.runOnUiThread { vm.discardDraft(draft.entry.id) }.join() }
                dao.deleteEntries(entries.map { it.id })
            }
            compose.runOnUiThread { vm.navigate("library") }
        }
    }
    @Test fun quoteAndCommentHaveIndependentUnicodeUndoAndImeComposition() {
        val entry = Entry(UUID.randomUUID().toString(), "Редактирование цитаты", quote = "Original quote", kind = EntryKind.Quote,
            content = RichText.fromMarkdown("**Канонический комментарий**"))
        try {
            runBlocking { dao.putEntry(entry) }
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == entry.id } }
            compose.runOnUiThread { vm.layout(split = false, sidebarVisible = false); vm.editEntry(entry, 0) }
            waitTag("entry-quotation")
            compose.onNodeWithTag("entry-quotation").performClick()
            compose.onNodeWithTag("entry-quotation").performTextReplacement("Цитата 😀 e\u0301 中文")
            key(KeyEvent.KEYCODE_Z)
            compose.onNodeWithTag("entry-quotation").assertTextContains("Original quote")
            key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("entry-quotation").assertTextContains("Цитата 😀 e\u0301 中文")
            fun native(view: View): ResearchEditText? = if (view is ResearchEditText) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { native(view.getChildAt(it)) } else null
            compose.onNodeWithTag("entry-comment").performScrollTo().performClick()
            compose.runOnUiThread {
                val editor = native(compose.activity.window.decorView)!!
                editor.setSelection(editor.length())
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                connection.beginBatchEdit()
                connection.setComposingText(" 中文", 1)
                connection.finishComposingText()
                connection.commitText(" 😀", 1)
                connection.endBatchEdit()
            }
            compose.waitUntil(60_000) { vm.workspace.value.drafts.first { it.entry.id == entry.id }.entry.richText().text.endsWith(" 中文 😀") }
            key(KeyEvent.KEYCODE_S)
            compose.waitUntil(60_000) { runBlocking { dao.allEntries().single { it.id == entry.id }.quote.startsWith("Цитата") } }
            val saved = runBlocking { dao.allEntries().single { it.id == entry.id } }
            assertEquals("Цитата 😀 e\u0301 中文", saved.quote)
            assertTrue(saved.richText().marks.any { it.style == org.readerka.data.TextStyle.Bold })
            assertEquals(entry.richText().blocks.first().id, saved.richText().blocks.first().id)
            compose.activityRule.scenario.recreate()
            assertEquals(saved, runBlocking { dao.allEntries().single { it.id == entry.id } })
        } finally {
            runBlocking { compose.runOnUiThread { vm.discardDraft(entry.id) }.join(); dao.deleteEntries(listOf(entry.id)) }
            compose.runOnUiThread { vm.navigate("library") }
        }
    }
    @Test fun phone320And360KeepHiddenPaneDraftAndVisibleSaveWithLargerText() {
        val token = UUID.randomUUID().toString()
        val first = Entry("phone-a-$token", "Первая запись", content = RichText.plain("Первый текст"))
        val second = Entry("phone-b-$token", "Вторая запись", content = RichText.plain("Второй текст"))
        try {
            runBlocking { dao.putEntry(first); dao.putEntry(second) }
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == second.id } }
            compose.runOnUiThread {
                vm.layout(split = true, sidebarVisible = false)
            }
            runBlocking {
                compose.runOnUiThread { vm.editEntry(first, 0) }.join()
                compose.runOnUiThread { vm.editEntry(second, 1) }.join()
            }
            compose.runOnUiThread { vm.focusPane(1) }
            compose.waitUntil(60_000) { vm.workspace.value.drafts.any { it.entry.id == second.id } }
            for (width in listOf(320, 360)) {
                compose.runOnUiThread {
                    compose.activity.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                            Box(Modifier.size(width.dp, 700.dp)) { ReaderkaRoot(compose.activity, vm) }
                        }
                    }
                }
                waitTag("entry-editor")
                compose.onNodeWithTag("workspace-pane-0").assertDoesNotExist()
                compose.onNodeWithTag("workspace-pane-1").assertIsDisplayed()
                compose.onNodeWithTag("entry-comment").performTextInput(" 😀$width")
                compose.onNodeWithTag("save-entry").assertIsDisplayed()
                val save = compose.onNodeWithTag("save-entry").fetchSemanticsNode().boundsInRoot
                assertTrue(save.right <= width + 1 && save.bottom <= 700)
                compose.onNodeWithText("Область 1", substring = false).performClick()
                compose.onNodeWithTag("entry-comment").assertTextContains("Первый текст")
                compose.onNodeWithText("Область 2", substring = false).performClick()
                compose.onNodeWithTag("entry-comment").assertTextContains("😀$width", substring = true)
                assertEquals(2, vm.workspace.value.drafts.count { it.entry.id in setOf(first.id, second.id) })
            }
        } finally {
            runBlocking {
                for (id in listOf(first.id, second.id)) compose.runOnUiThread { vm.discardDraft(id) }.join()
                dao.deleteEntries(listOf(first.id, second.id))
            }
            compose.runOnUiThread { vm.navigate("library") }
        }
    }
    @Test fun pendingFieldEditCannotResurrectPlacementAfterCanonicalEntryDeletion() {
        val token = UUID.randomUUID().toString()
        val entry = Entry("deleted-$token", "Удаляемая каноническая запись")
        val retained = Entry("retained-$token", "Сохраняемая запись")
        val cards = listOf(ResearchPlacement("a-$token", entry.id, 0.0, 0.0), ResearchPlacement("b-$token", retained.id, 350.0, 0.0))
        val field = ResearchField("delete-field-$token", "Проверка очереди", placements = cards,
            edges = listOf(ResearchEdge("edge-$token", cards[0].id, cards[1].id)), sequence = cards.map { it.id })
        try {
            runBlocking { dao.putEntry(entry); dao.putEntry(retained); dao.putResearchField(ResearchFieldRow.from(field)) }
            compose.waitUntil(60_000) { vm.library.value.researchFields.any { it.id == field.id } }
            compose.runOnUiThread { vm.saveResearchField(field.copy(title = "Правка перед удалением")) }
            runBlocking { compose.runOnUiThread { vm.deleteEntries(setOf(entry.id)) }.join() }
            compose.waitUntil(60_000) {
                runBlocking { dao.snapshot().researchFields.first { it.id == field.id }.let { it.title == "Правка перед удалением" && it.placements.none { p -> p.entryId == entry.id } } }
            }
            val saved = runBlocking { dao.snapshot().researchFields.first { it.id == field.id } }
            assertEquals(listOf(cards[1]), saved.placements)
            assertTrue(saved.edges.isEmpty()); assertEquals(listOf(cards[1].id), saved.sequence)
            assertTrue(vm.fieldEdits.value[field.id]?.placements.orEmpty().none { it.entryId == entry.id })
            assertEquals(retained, runBlocking { dao.allEntries().single { it.id == retained.id } })
        } finally {
            runBlocking { compose.runOnUiThread { vm.deleteResearchField(field.id) }.join(); dao.deleteEntries(listOf(entry.id, retained.id)) }
        }
    }
}
