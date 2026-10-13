package org.readerka

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
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
import org.readerka.pdf.PdfPageView
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class WorkspaceRedesignTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun waitForStartup() = compose.waitForReaderkaReady()
    private val app get() = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val dao get() = app.database.dao()
    private val vm get() = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
    private enum class Input { Touch, Keyboard, Mouse }
    private fun act(node: SemanticsNodeInteraction, input: Input) {
        when (input) {
            Input.Touch -> node.performTouchInput { click() }
            Input.Mouse -> node.performMouseInput { click() }
            Input.Keyboard -> { key(KeyEvent.KEYCODE_TAB, 0); node.performSemanticsAction(SemanticsActions.RequestFocus) { request -> assertTrue("Keyboard action must accept focus", request()) }; node.assertIsFocused(); if (!node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText)) node.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) } }
        }
    }
    private fun key(code: Int, meta: Int = KeyEvent.META_CTRL_ON) = compose.runOnUiThread {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) compose.activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0, meta))
    }
    private fun waitFor(tag: String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun counter(pane: Int) = compose.onNode(hasTestTag("reader-page-counter") and hasAnyAncestor(hasTestTag("workspace-pane-$pane")))
    private fun fixture(scope: CollectionScope = CollectionScope.Notes, block: (Document, List<org.readerka.data.Collection>) -> Unit) {
        val suffix = UUID.randomUUID().toString()
        val doc = Document(suffix, "Чтение · исследование", "research.pdf", "$suffix.pdf", authors = "Readerka", pageCount = 4)
        val folders = listOf(org.readerka.data.Collection("a-$suffix", "Философия", scope = scope), org.readerka.data.Collection("b-$suffix", "Аргументы", scope = scope))
        val oldPreferences = vm.preferences.value
        val oldEntryIds = runBlocking { dao.allEntries().map { it.id }.toSet() }
        val oldDraftIds = vm.workspace.value.drafts.map { it.entry.id }.toSet()
        val oldCollectionIds = runBlocking { dao.allCollections().map { it.id }.toSet() }
        val previous = runBlocking { WorkspaceStore(app).values.first() }
        try {
            val file = File(app.filesDir, "pdfs/${doc.storedName}").apply { parentFile!!.mkdirs() }
            PDDocument().use { pdf ->
                repeat(4) { index ->
                    val page = PDPage(PDRectangle.A4); pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { s ->
                        s.beginText(); s.setFont(PDType1Font.TIMES_BOLD, 23f); s.newLineAtOffset(48f, 740f); s.showText("Reading and thinking"); s.endText()
                        s.beginText(); s.setFont(PDType1Font.TIMES_ROMAN, 16f); s.newLineAtOffset(48f, 690f); s.showText("The limits of my language mean the limits of my world."); s.newLineAtOffset(0f, -30f); s.showText("A place for an argument, a question and a new idea."); s.newLineAtOffset(0f, -50f); s.showText("Chapter ${index + 1} - synthetic test document"); s.endText()
                    }
                }; pdf.save(file)
            }
            runBlocking { dao.putDocument(doc); folders.forEach { dao.putCollection(it) } }
            compose.waitUntil(60_000) { vm.library.value.documents.any { it.id == doc.id } }
            compose.runOnUiThread {
                vm.workspace.value.panes.forEachIndexed { pane, state -> state.tabs.toList().forEach { vm.closeTab(pane, it.id) } }
                vm.layout(split = false, sidebarVisible = false); vm.focusPane(0); vm.settings(oldPreferences.copy(theme = ThemeMode.Light)); vm.open(doc, page = 0, pane = 0)
            }
            waitFor("pdf-pages")
            block(doc, folders)
        } finally {
            compose.runOnUiThread {
                vm.workspace.value.panes.forEachIndexed { pane, state -> state.tabs.toList().forEach { vm.closeTab(pane, it.id) } }
                vm.closeEditor(); vm.navigate("library"); vm.settings(oldPreferences)
            }
            runBlocking {
                vm.workspace.value.drafts.filter { it.entry.id !in oldDraftIds }.forEach { draft -> compose.runOnUiThread { vm.discardDraft(draft.entry.id) }.join() }
                dao.deleteEntries(dao.allEntries().filter { it.id !in oldEntryIds }.map { it.id }); dao.deleteDocument(doc.id)
                dao.allCollections().filter { it.id !in oldCollectionIds }.forEach { dao.deleteCollection(it.id) }
                WorkspaceStore(app).save(previous)
            }
            File(app.filesDir, "pdfs/${doc.storedName}").delete()
        }
    }
    private fun active(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-pane-${vm.workspace.value.activePane}")))
    private fun scenario(input: Input) = fixture(if (input == Input.Keyboard) CollectionScope.Notes else CollectionScope.Consp) { doc, folders ->
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(input == Input.Touch)
        val tab = vm.workspace.value.panes[0].selected!!
        act(compose.onNodeWithTag("tab-menu-${tab.id}"), input)
        act(compose.onNodeWithText("Открыть справа"), input)
        compose.waitUntil(60_000) { vm.readers.value.size == 2 }
        assertNotSame(vm.readers.value[0]!!.engine, vm.readers.value[1]!!.engine)
        act(counter(1), input)
        act(compose.onNode(hasContentDescription("Следующая страница") and hasAnyAncestor(hasTestTag("workspace-pane-1"))), input)
        compose.waitUntil(60_000) { vm.readers.value[1]?.page == 1 }
        assertEquals(0, vm.readers.value[0]!!.page)
        act(counter(0), input)
        compose.waitUntil(60_000) { vm.workspace.value.activePane == 0 }
        act(counter(1), input)
        compose.waitUntil(60_000) { vm.workspace.value.activePane == 1 }
        if (input == Input.Keyboard) key(KeyEvent.KEYCODE_N)
        else {
            act(compose.onNode(hasContentDescription("Создать конспект") and hasAnyAncestor(hasTestTag("workspace-pane-1"))), input)
            compose.onNodeWithTag("new-consp-title").performTextInput("Рабочий конспект")
            act(compose.onNodeWithTag("create-consp-confirm"), input)
        }
        waitFor("entry-editor")
        val title = "Конспект · язык и смысл"
        active("entry-title").performTextReplacement(title)
        active("entry-comment").performScrollTo()
        act(active("entry-comment"), input)
        active("entry-comment").performTextInput("# Язык и смысл\n\n**Главная мысль** и *вопрос*\n\n- [ ] Проверить аргумент\n- [x] Прочитать главу\n\n> Исходный текст хранится отдельно.\n\n[Источник](readerka://document/${doc.id}?page=2)")
        val id = vm.workspace.value.activeDraftId!!
        compose.waitUntil(60_000) {
            val expected = vm.workspace.value.drafts.single { it.entry.id == id }.entry
            runBlocking { WorkspaceStore(app).values.first().drafts.any { it.entry.id == id && it.entry == expected } }
        }
        val draft = vm.workspace.value.drafts.single { it.entry.id == id }
        // Keyboard focus stays in text while PDF retains its own page.
        act(active("entry-comment"), input)
        if (input == Input.Keyboard) key(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        assertEquals(1, vm.readers.value[1]!!.page)
        act(active("note-properties"), input)
        active("entry-collections").performScrollTo()
        act(active("entry-collections"), input)
        folders.forEach { folder -> act(compose.onNodeWithTag("select-folder-${folder.id}"), input) }
        act(compose.onNodeWithText("Применить"), input)
        compose.waitUntil(60_000) { vm.workspace.value.drafts.single { it.entry.id == id }.collections.size == 2 }
        act(active("note-properties"), input)
        compose.activityRule.scenario.recreate()
        waitFor("entry-editor")
        assertEquals(draft.entry.comment, vm.workspace.value.drafts.single { it.entry.id == id }.entry.comment)
        assertEquals(1, vm.readers.value[1]!!.page)
        act(active("markdown-preview"), input)
        waitFor("markdown-rendered")
        compose.onNode(hasText("# Язык и смысл", substring = true) and hasAnyAncestor(hasTestTag("markdown-rendered")) and hasAnyAncestor(hasTestTag("workspace-pane-${vm.workspace.value.activePane}"))).assertExists()
        if (input == Input.Touch) {
            compose.runOnUiThread { vm.layout(sidebarVisible = true); vm.settings(vm.preferences.value.copy(theme = ThemeMode.Light)) }
            screenshot("readerka-0.6-workspace-light.png")
            compose.runOnUiThread { vm.settings(vm.preferences.value.copy(theme = ThemeMode.Dark)) }
            compose.waitUntil(60_000) { vm.preferences.value.theme == ThemeMode.Dark }
            screenshot("readerka-0.6-workspace-dark.png")
        }
        if (input == Input.Keyboard) key(KeyEvent.KEYCODE_S) else act(active("save-entry"), input)
        compose.waitUntil(60_000) { runBlocking { dao.allEntries().any { it.id == id } } && (vm.workspace.value.drafts.none { it.entry.id == id } || vm.draftStatus.value == "Конспект сохранён") }
        assertEquals(1, runBlocking { dao.allEntries().count { it.id == id } })
        assertEquals(folders.map { it.id }.toSet(), runBlocking { dao.allMemberships().filter { it.entryId == id }.map { it.collectionId }.toSet() })
        // Open another independent note beside the first, through the same visible action/command.
        val noteTab = vm.workspace.value.panes[0].selected!!
        act(compose.onNodeWithTag("tab-menu-${noteTab.id}"), input)
        act(compose.onNodeWithText("Открыть справа"), input)
        if (input == Input.Keyboard) key(KeyEvent.KEYCODE_N)
        else {
            act(compose.onNodeWithTag("quick-commands"), input)
            compose.onNodeWithTag("command-query").performTextInput("Создать заметку")
            act(compose.onNodeWithTag("command-new-note"), input)
        }
        waitFor("entry-editor")
        active("entry-title").performTextReplacement("Другая заметка")
        active("entry-comment").performTextInput("Независимый текст второй заметки")
        val secondId = vm.workspace.value.activeDraftId!!
        assertNotEquals(id, secondId)
        assertEquals(WorkspaceKind.Note, vm.workspace.value.panes[0].selected!!.kind)
        assertEquals(WorkspaceKind.Note, vm.workspace.value.panes[1].selected!!.kind)
        if (input == Input.Keyboard) key(KeyEvent.KEYCODE_S) else act(active("save-entry"), input)
        compose.waitUntil(60_000) { runBlocking { dao.allEntries().any { it.id == secondId } } && vm.workspace.value.drafts.none { it.entry.id == secondId } }
        assertEquals(draft.entry.comment, runBlocking { dao.allEntries().single { it.id == id }.comment })
        runBlocking { compose.runOnUiThread { vm.deleteEntries(setOf(secondId)) }.join() }
        if (!vm.workspace.value.sidebarVisible) act(compose.onNodeWithTag("toggle-workspace-sidebar"), input)
        act(compose.onNodeWithText(if (input == Input.Keyboard) "Заметки" else "Конспекты", substring = false), input)
        act(compose.onNodeWithContentDescription("Создать папку"), input)
        compose.onNodeWithText("Название", substring = false).performTextInput("Новая ветка")
        act(compose.onNodeWithText("Сохранить", substring = false), input)
        compose.waitUntil(60_000) { runBlocking { dao.allCollections().any { it.name == "Новая ветка" } } }
        val newFolder = runBlocking { dao.allCollections().single { it.name == "Новая ветка" } }
        waitFor("explorer-menu-f:${newFolder.id}")
        act(compose.onNodeWithTag("explorer-menu-f:${newFolder.id}"), input)
        act(compose.onNodeWithText("Переименовать на месте"), input)
        compose.onNodeWithTag("rename-folder").performTextReplacement("Переименованная ветка")
        act(compose.onNodeWithContentDescription("Сохранить имя"), input)
        compose.waitUntil(60_000) { runBlocking { dao.allCollections().single { it.id == newFolder.id }.name == "Переименованная ветка" } }
        act(compose.onNodeWithTag("explorer-f:${folders.first().id}"), input)
        waitFor("explorer-menu-e:${folders.first().id}:$id")
        if (input == Input.Touch) compose.onNodeWithTag("explorer-e:${folders.first().id}:$id").performTouchInput { longClick() }
        else act(compose.onNodeWithTag("explorer-menu-e:${folders.first().id}:$id"), input)
        act(compose.onNodeWithText("Убрать из этой папки"), input)
        compose.waitUntil(60_000) { runBlocking { dao.allMemberships().count { it.entryId == id } == 1 } }
        assertEquals(1, runBlocking { dao.allEntries().count { it.id == id } })
        assertEquals(folders.last().id, runBlocking { dao.allMemberships().single { it.entryId == id }.collectionId })

    }
    @Test fun touchOnlyWorkspaceEditorMembershipsAndRestore() = scenario(Input.Touch)
    @Test fun keyboardWorkspaceEditorMembershipsAndRestore() = scenario(Input.Keyboard)
    @Test fun mouseWorkspaceEditorMembershipsAndRestore() = scenario(Input.Mouse)
    @Test fun touchResizeAcrossFramesAndNarrowSplitEditorKeepsSaveAboveIme() = fixture { _, _ ->
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val previousIme = shell("settings get secure show_ime_with_hard_keyboard").trim()
        try {
        shell("settings put secure show_ime_with_hard_keyboard 1")
        instrumentation.setInTouchMode(true)
        act(compose.onNodeWithTag("tab-menu-${vm.workspace.value.panes[0].selectedId}"), Input.Touch)
        act(compose.onNodeWithText("Открыть справа"), Input.Touch)
        compose.waitUntil(60_000) { vm.readers.value.size == 2 }
        val before = vm.workspace.value.splitFraction
        // Separate batches let the boundary move and remeasure between finger movements.
        compose.onNodeWithTag("resize-split").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(100f, 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("resize-split").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(100f, 0f)); up() }
        compose.waitUntil(60_000) { vm.workspace.value.splitFraction > before + .06f }
        act(compose.onNode(hasContentDescription("Создать конспект") and hasAnyAncestor(hasTestTag("workspace-pane-1"))), Input.Touch)
        compose.onNodeWithTag("new-consp-title").performTextInput("Проверка клавиатуры")
        act(compose.onNodeWithTag("create-consp-confirm"), Input.Touch)
        waitFor("entry-editor")
        act(active("entry-title"), Input.Touch)
        fun imeBottom() = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view).getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
        }
        compose.waitUntil(60_000) { imeBottom() > 100 }
        active("entry-title").performTextReplacement("Касания и экранная клавиатура")
        active("entry-comment").performTextInput("Текст виден, сохранение доступно")
        active("entry-comment").performScrollTo()
        act(active("entry-comment"), Input.Touch)
        compose.waitUntil(60_000) { imeBottom() > 100 }
        active("save-entry").assertIsDisplayed()
        val activeHeight = compose.onNodeWithTag("workspace-pane-${vm.workspace.value.activePane}").fetchSemanticsNode().boundsInRoot.height / compose.activity.resources.displayMetrics.density
        if (activeHeight < 500f) compose.onNodeWithTag("workspace-pane-1").assertDoesNotExist()
        else compose.onNodeWithTag("workspace-pane-1").assertIsDisplayed()
        val keyboardTop = compose.runOnUiThread { compose.activity.window.decorView.height } - imeBottom()
        assertTrue("The real IME must remain visible while checking Save", imeBottom() > 100)
        assertTrue("Save must be above the real IME", active("save-entry").fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 2)
        compose.waitUntil(60_000) {
            val saveTop = active("save-entry").fetchSemanticsNode().boundsInWindow.top
            compose.runOnUiThread {
                fun find(view: View): org.readerka.ui.ResearchEditText? = when (view) {
                    is org.readerka.ui.ResearchEditText -> view
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                    else -> null
                }
                find(compose.activity.window.decorView)?.let { view ->
                    val visible = android.graphics.Rect()
                    val layout = view.layout
                    if (!view.hasFocus() || !view.getLocalVisibleRect(visible) || layout == null) false
                    else {
                        val line = layout.getLineForOffset(view.selectionEnd.coerceAtLeast(0))
                        val top = view.totalPaddingTop + layout.getLineTop(line) - view.scrollY
                        val bottom = view.totalPaddingTop + layout.getLineBottom(line) - view.scrollY
                        val origin = IntArray(2); view.getLocationInWindow(origin)
                        top >= visible.top && bottom <= visible.bottom && origin[1] + bottom <= saveTop + 2
                    }
                } == true
            }
        }
        assertTrue("IME must still be visible with the caret", imeBottom() > 100)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/readerka-preview-ime-split-test.png").use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val id = vm.workspace.value.activeDraftId!!
        act(active("save-entry"), Input.Touch)
        compose.waitUntil(60_000) { runBlocking { dao.allEntries().any { it.id == id } } && (vm.workspace.value.drafts.none { it.entry.id == id } || vm.draftStatus.value == "Конспект сохранён") }
        } finally { shell("input keyevent 4"); shell("settings put secure show_ime_with_hard_keyboard " + previousIme.ifBlank { "0" }) }
    }
    @Test fun closingSharedDraftClosesOnlyActivePaneAndRestoresWork() = fixture { _, _ ->
        compose.runOnUiThread { vm.newNote() }
        waitFor("entry-editor")
        active("entry-title").performTextInput("Общий черновик")
        active("entry-comment").performTextInput("Текст остаётся после закрытия правой области")
        val id = vm.workspace.value.activeDraftId!!
        compose.runOnUiThread {
            val pane = vm.workspace.value.activePane
            vm.openRight(vm.workspace.value.panes[pane].selected!!, pane)
        }
        compose.waitUntil(60_000) { vm.workspace.value.panes.all { it.selected?.documentId == id } }
        val active = vm.workspace.value.activePane
        val other = 1 - active
        act(compose.onNode(hasContentDescription("Закрыть редактор, сохранив черновик") and hasAnyAncestor(hasTestTag("workspace-pane-$active"))), Input.Touch)
        compose.waitUntil(60_000) { vm.workspace.value.panes[active].tabs.none { it.documentId == id } }
        assertEquals(id, vm.workspace.value.panes[other].selected?.documentId)
        compose.waitUntil(60_000) { vm.draftStatus.value == "Черновик сохранён" }
        assertEquals("Текст остаётся после закрытия правой области", runBlocking { WorkspaceStore(app).values.first() }.drafts.single { it.entry.id == id }.entry.richText().text)
        assertTrue(runBlocking { dao.allEntries().none { it.id == id } })
    }
    @Test fun mouseDragAddsMembershipWithoutDuplicatingOrMovingCanonicalNote() = fixture { _, folders ->
        val entry = Entry(UUID.randomUUID().toString(), "Перенос мышью", comment = "Одна каноническая запись")
        runBlocking { compose.runOnUiThread { vm.saveEntry(entry, "", setOf(folders.first().id)) }.join() }
        compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == entry.id } }
        compose.runOnUiThread { vm.navigate("notes"); vm.layout(sidebarVisible = true) }
        waitFor("explorer-f:${folders.first().id}")
        act(compose.onNodeWithTag("explorer-f:${folders.first().id}"), Input.Mouse)
        val source = compose.onNodeWithTag("explorer-e:${folders.first().id}:${entry.id}")
        val destination = compose.onNodeWithTag("explorer-f:${folders.last().id}")
        source.assertIsDisplayed(); destination.assertIsDisplayed()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val target = destination.fetchSemanticsNode().boundsInRoot.center - sourceBounds.topLeft
        source.performMouseInput { moveTo(center); press(); moveTo((center + target) / 2f); moveTo(target); release() }
        compose.waitUntil(60_000) { runBlocking { dao.allMemberships().count { it.entryId == entry.id } == 2 } }
        assertEquals(1, runBlocking { dao.allEntries().count { it.id == entry.id } })
        assertEquals(entry.comment, runBlocking { dao.allEntries().single { it.id == entry.id }.comment })
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        fun pages(view: View): List<PdfPageView> = when (view) {
            is PdfPageView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { pages(view.getChildAt(it)) }
            else -> emptyList()
        }
        compose.waitUntil(60_000) { compose.runOnUiThread {
            val visible = pages(compose.activity.window.decorView).filter {
                val rect = android.graphics.Rect()
                it.isShown && it.getGlobalVisibleRect(rect) && rect.height() > 100
            }
            visible.isNotEmpty() && visible.all { it.bitmap != null && it.pageText != null }
        } }
        val latch = CountDownLatch(1); lateinit var bitmap: Bitmap; var result = -1
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.postOnAnimation { view.postOnAnimation { PixelCopy.request(compose.activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) } }
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
