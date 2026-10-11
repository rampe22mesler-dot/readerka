package org.readerka

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.ui.LibraryViewModel
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CatalogueFoldersTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val vm get() = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
    private enum class Input { Touch, Keyboard, Mouse }
    private fun act(node: SemanticsNodeInteraction, input: Input) {
        when (input) {
            Input.Touch -> node.performTouchInput { click() }
            Input.Mouse -> node.performMouseInput { click() }
            Input.Keyboard -> {
                InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
                compose.waitUntil(30_000) { var focused = false; node.performSemanticsAction(SemanticsActions.RequestFocus) { focused = it() }; focused }
                node.performKeyInput { pressKey(Key.Enter) }
            }
        }
        compose.waitForIdle()
    }
    private fun card(title: String) = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("catalogue-list")))
    private fun sidebar(matcher: SemanticsMatcher) = compose.onNode(matcher and hasAnyAncestor(hasTestTag("workspace-sidebar")))
    private fun waitFor(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture() {
        val bitmap = compose.runOnUiThread { val view = compose.activity.window.decorView; android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888) }
        val done = java.util.concurrent.CountDownLatch(1)
        val status = java.util.concurrent.atomic.AtomicInteger(-1)
        compose.runOnUiThread { android.view.PixelCopy.request(compose.activity.window, bitmap, { status.set(it); done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, status.get())
        File(app.filesDir, "readerka-0.9-folders.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    private fun scenario(input: Input) {
        val token = UUID.randomUUID().toString()
        val folders = listOf(Collection("c-$token", "0 Темы конспектов", scope = CollectionScope.Consp), Collection("cc-$token", "Аргументы", "c-$token", CollectionScope.Consp), Collection("n-$token", "0 Материалы заметок"))
        val entries = listOf(Entry("e-$token", "Конспект по теме", kind = EntryKind.Consp), Entry("ec-$token", "Конспект аргумента", kind = EntryKind.Consp), Entry("en-$token", "Самостоятельная заметка"), Entry("eq-$token", "Цитата в папке", quote = "Исходный текст", kind = EntryKind.Quote))
        val pdfFolder = DocumentFolder("pdf-folder-$token", "0 Книги")
        val pdf = Document("pdf-$token", "Книга в папке", "book.pdf", "unused-$token.pdf", folderId = pdfFolder.id, pageCount = 1)
        val previous = runBlocking { WorkspaceStore(app).values.first() }
        val originalIds = runBlocking { app.database.dao().allEntries().map { it.id }.toSet() }
        try {
            runBlocking {
                val dao = app.database.dao(); folders.forEach { dao.putCollection(it) }; entries.forEach { dao.putEntry(it) }
                dao.putMembership(Membership(folders[0].id, entries[0].id)); dao.putMembership(Membership(folders[1].id, entries[1].id)); dao.putMembership(Membership(folders[2].id, entries[3].id))
                dao.putFolder(pdfFolder); dao.putDocument(pdf)
            }
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == entries[0].id } }
            compose.runOnUiThread { vm.layout(sidebarVisible = false); vm.navigate("consp") }
            waitFor("catalogue-folder-${folders[0].id}")
            compose.onNodeWithTag("catalogue-folder-${folders[2].id}").assertDoesNotExist()
            card(entries[0].title).assertDoesNotExist()
            act(compose.onNodeWithTag("catalogue-folder-${folders[0].id}"), input)
            card(entries[0].title).assertIsDisplayed()
            card(entries[1].title).assertDoesNotExist()
            act(compose.onNodeWithTag("catalogue-folder-${folders[1].id}"), input)
            card(entries[1].title).assertIsDisplayed()
            act(compose.onNodeWithTag("catalogue-up"), input)
            card(entries[0].title).assertIsDisplayed()
            act(compose.onNodeWithTag("catalogue-flat"), input)
            card(entries[0].title).assertIsDisplayed(); card(entries[1].title).assertIsDisplayed()
            act(compose.onNodeWithTag("catalogue-flat"), input)
            card(entries[1].title).assertDoesNotExist()
            act(compose.onNodeWithText("Создать конспект"), input)
            compose.onNodeWithTag("new-consp-title").performTextInput("Создан в папке $token")
            act(compose.onNodeWithTag("create-consp-confirm"), input)
            waitFor("entry-editor")
            compose.waitUntil(60_000) { runBlocking { app.database.dao().allEntries().any { it.title == "Создан в папке $token" } } }
            val created = runBlocking { app.database.dao().allEntries().single { it.title == "Создан в папке $token" } }
            compose.waitUntil(60_000) { runBlocking { app.database.dao().allMemberships().any { it.entryId == created.id && it.collectionId == folders[0].id } } }
            act(compose.onNodeWithTag("workspace-nav-notes"), input)
            waitFor("catalogue-folder-${folders[2].id}")
            compose.onNodeWithTag("catalogue-folder-${folders[0].id}").assertDoesNotExist()
            card(entries[2].title).assertIsDisplayed(); card(entries[3].title).assertDoesNotExist()
            act(compose.onNodeWithTag("catalogue-folder-${folders[2].id}"), input)
            card(entries[3].title).assertIsDisplayed()
            act(compose.onNodeWithTag("workspace-nav-consp"), input)
            card(entries[0].title).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            // Activity opens its retained draft; returning to catalogue retains the folder independently.
            act(compose.onNodeWithTag("workspace-nav-consp"), input)
            card(entries[0].title).assertIsDisplayed()
            act(compose.onNodeWithTag("toggle-workspace-sidebar"), input)
            waitFor("workspace-sidebar")
            if (input == Input.Touch) capture()
            act(sidebar(hasText("Заметки")), input)
            act(sidebar(hasTestTag("explorer-f:${folders[2].id}")), input)
            card(entries[3].title).assertIsDisplayed()
            act(sidebar(hasText("PDF")), input)
            act(sidebar(hasTestTag("explorer-f:${pdfFolder.id}")), input)
            compose.onNode(hasText(pdf.title) and hasAnyAncestor(hasTestTag("catalogue-list"))).assertIsDisplayed()
            act(compose.onNodeWithTag("catalogue-root"), input)
            compose.onNode(hasText(pdf.title) and hasAnyAncestor(hasTestTag("catalogue-list"))).assertDoesNotExist()
            compose.onNodeWithTag("catalogue-folder-${pdfFolder.id}").assertIsDisplayed()
            assertEquals(1, runBlocking { app.database.dao().allEntries().count { it.id == created.id } })
        } finally {
            compose.runOnUiThread { vm.workspace.value.panes.forEachIndexed { pane, state -> state.tabs.toList().forEach { vm.closeTab(pane, it.id) } }; vm.navigate("library") }
            runBlocking {
                vm.workspace.value.drafts.filter { it.entry.id !in originalIds }.forEach { compose.runOnUiThread { vm.discardDraft(it.entry.id) }.join() }
                val dao = app.database.dao(); dao.deleteEntries(dao.allEntries().filter { it.id !in originalIds }.map { it.id }); folders.asReversed().forEach { dao.deleteCollection(it.id) }; dao.deleteDocument(pdf.id); dao.deleteFolder(pdfFolder.id); WorkspaceStore(app).save(previous)
            }
        }
    }
    @Test fun touchFoldersOpenDirectlyAndCataloguesStaySeparate() = scenario(Input.Touch)
    @Test fun keyboardFoldersOpenDirectlyAndCataloguesStaySeparate() = scenario(Input.Keyboard)
    @Test fun mouseFoldersOpenDirectlyAndCataloguesStaySeparate() = scenario(Input.Mouse)
}
