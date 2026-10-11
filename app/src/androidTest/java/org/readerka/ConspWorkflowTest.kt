package org.readerka

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.ui.LibraryViewModel
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ConspWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private val vm get() = compose.runOnUiThread { ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
    private enum class Input { Touch, Keyboard, Mouse }
    private fun act(node: SemanticsNodeInteraction, input: Input) {
        if (generateSequence(node.fetchSemanticsNode().parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }) node.performScrollTo()
        when (input) {
            Input.Touch -> node.performTouchInput { click() }
            Input.Mouse -> node.performMouseInput { click() }
            Input.Keyboard -> {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
                compose.waitUntil(30_000) {
                    var focused = false
                    node.performSemanticsAction(SemanticsActions.RequestFocus) { focused = it() }
                    focused
                }
                node.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            }
        }
    }
    private fun closeProperties(input: Input) {
        val tag = if (compose.onAllNodesWithTag("close-properties-dialog").fetchSemanticsNodes().isNotEmpty()) "close-properties-dialog" else "close-note-properties"
        act(compose.onNodeWithTag(tag), input)
    }
    private fun insert(tag: String, input: Input) { act(editor("editor-insert"), input); act(compose.onNodeWithTag(tag), input) }
    private fun save(input: Input) { act(editor("entry-save-status"), input); act(compose.onNodeWithTag("save-entry"), input) }
    private fun key(code: Int, meta: Int = KeyEvent.META_CTRL_ON) {
        compose.waitUntil(30_000) { compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() } }
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val time = android.os.SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { instrumentation.sendKeySync(KeyEvent(time, android.os.SystemClock.uptimeMillis(), it, code, 0, meta)) }
        compose.waitForIdle()
    }
    private fun waitFor(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun editor(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-pane-${vm.workspace.value.activePane}")))
    private fun fixture(block: (Document, Document, Entry, org.readerka.data.Collection) -> Unit) {
        val id = UUID.randomUUID().toString()
        val a = Document("a-$id", "Book A", "a.pdf", "a-$id.pdf", pageCount = 3)
        val b = Document("b-$id", "Book B", "b.pdf", "b-$id.pdf", pageCount = 3)
        val source = Entry("source-$id", "Library note", comment = "Useful original text")
        val folder = org.readerka.data.Collection("folder-$id", "Research topic")
        val previous = runBlocking { WorkspaceStore(app).values.first() }
        val old = runBlocking { app.database.dao().snapshot() }
        val oldDrafts = vm.workspace.value.drafts.map { it.entry.id }.toSet()
        try {
            listOf(a, b).forEach { d -> PDDocument().use { pdf ->
                repeat(3) { i -> val page = PDPage(PDRectangle.A4); pdf.addPage(page); PDPageContentStream(pdf, page).use { out -> out.beginText(); out.setFont(PDType1Font.TIMES_ROMAN, 18f); out.newLineAtOffset(40f, 700f); out.showText("Readerka research source ${i + 1}"); out.endText() } }
                pdf.save(File(app.filesDir, "pdfs/${d.storedName}").apply { parentFile!!.mkdirs() })
            } }
            runBlocking { val dao = app.database.dao(); dao.putDocument(a); dao.putDocument(b); dao.putEntry(source); dao.putCollection(folder) }
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == source.id } }
            compose.runOnUiThread {
                vm.workspace.value.panes.forEachIndexed { pane, state -> state.tabs.toList().forEach { vm.closeTab(pane, it.id) } }
                vm.maximizePane(0); vm.layout(sidebarVisible = false); vm.navigate("consp")
            }
            waitFor("workspace-nav-consp")
            block(a, b, source, folder)
        } finally {
            compose.runOnUiThread { vm.workspace.value.panes.forEachIndexed { pane, state -> state.tabs.toList().forEach { vm.closeTab(pane, it.id) } }; vm.navigate("library") }
            runBlocking {
                vm.workspace.value.drafts.filter { it.entry.id !in oldDrafts }.forEach { compose.runOnUiThread { vm.discardDraft(it.entry.id) }.join() }
                val dao = app.database.dao(); dao.deleteEntries(dao.allEntries().filter { e -> old.entries.none { it.id == e.id } }.map { it.id }); dao.deleteDocument(a.id); dao.deleteDocument(b.id); dao.deleteCollection(folder.id); WorkspaceStore(app).save(previous)
            }
            listOf(a, b).forEach { File(app.filesDir, "pdfs/${it.storedName}").delete() }
        }
    }
    private fun captureWindow(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        val done = java.util.concurrent.CountDownLatch(1)
        var status = -1
        compose.runOnUiThread { android.view.PixelCopy.request(compose.activity.window, bitmap, { status = it; done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, status)
        File(app.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun scenario(input: Input) = fixture { a, b, source, folder ->
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(input == Input.Touch)
        act(compose.onNodeWithText("Создать конспект"), input)
        compose.onNodeWithTag("new-consp-title").performTextInput("Topic outline")
        act(compose.onNodeWithTag("create-consp-confirm"), input)
        waitFor("entry-editor")
        editor("entry-comment").performTextReplacement("alpha beta")
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(0, 5, false)) }
        if (input == Input.Keyboard) key(KeyEvent.KEYCODE_B) else act(editor("format-bold"), input)
        val id = vm.workspace.value.editor!!.entry.id
        compose.waitUntil(60_000) { vm.workspace.value.editor?.entry?.content?.marks?.any { it.style == TextStyle.Bold && it.start == 0 && it.end == 5 } == true }
        act(editor("note-properties"), input)
        act(editor("consp-source-${a.id}"), input); act(editor("consp-source-${b.id}"), input)
        editor("entry-tags").performTextInput("research, argument")
        act(editor("entry-collections"), input)
        act(compose.onNodeWithTag("select-folder-${folder.id}"), input)
        act(compose.onNodeWithText(app.getString(org.readerka.R.string.apply_selection)), input)
        compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == id && it.sources.toSet() == setOf(a.id, b.id) } }
        closeProperties(input)
        // A named word links to the stable identity, preserving its visible label.
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(6, 10, false) }
        insert("insert-link", input)
        act(compose.onNodeWithTag("insert-related-scope"), input)
        act(compose.onNodeWithTag("insert-select-${source.id}"), input)
        act(compose.onNodeWithTag("insert-confirm"), input)
        compose.waitUntil(60_000) { vm.workspace.value.editor?.entry?.content?.marks?.any { it.style == TextStyle.Link && it.value == ResearchLinks.entry(source.id) } == true }
        assertEquals("alpha beta", vm.workspace.value.editor!!.entry.content!!.text)
        assertEquals("beta", vm.workspace.value.editor!!.entry.content!!.let { c -> val m = c.marks.first { it.style == TextStyle.Link }; c.text.substring(m.start, m.end) })
        // A separate clickable card contains a label rather than copied source text.
        val cardStart = vm.workspace.value.editor!!.entry.content!!.text.length
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(cardStart, cardStart, false) }
        insert("insert-library", input)
        act(compose.onNodeWithText("Способ: Вставить содержимое"), input)
        act(compose.onNodeWithText("Ссылка блоком"), input)
        act(compose.onNodeWithTag("insert-related-scope"), input)
        act(compose.onNodeWithTag("insert-select-${source.id}"), input)
        act(compose.onNodeWithTag("insert-confirm"), input)
        compose.waitUntil(60_000) { vm.workspace.value.editor?.entry?.content?.blocks?.any { it.style == BlockStyle.LinkCard && it.target == ResearchLinks.entry(source.id) } == true }
        assertFalse(vm.workspace.value.editor!!.entry.content!!.text.contains(source.comment))
        // Content insertion makes a snapshot with a provenance link.
        val length = vm.workspace.value.editor!!.entry.content!!.text.length
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(length, length, false) }
        insert("insert-library", input)
        act(compose.onNodeWithTag("insert-related-scope"), input)
        act(compose.onNodeWithTag("insert-select-${source.id}"), input)
        act(compose.onNodeWithTag("insert-confirm"), input)
        compose.waitUntil(60_000) { vm.workspace.value.editor?.entry?.content?.text?.contains(source.comment) == true }
        save(input)
        compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == id && it.content?.text?.contains(source.comment) == true } }
        val snapshot = runBlocking { app.database.dao().snapshot() }
        assertEquals(1, snapshot.entries.count { it.id == id })
        assertTrue(snapshot.memberships.any { it.entryId == id && it.collectionId == folder.id })
        assertEquals(2, snapshot.entryTags.count { it.entryId == id })
        assertTrue(snapshot.noteVersions.any { it.entryId == id })
        assertTrue(ResearchLinks.backlinks(snapshot, source.id).any { it.id == id })
        if (input == Input.Touch) {
            val oldPreferences = vm.preferences.value
            try {
                runBlocking { compose.runOnUiThread { vm.settings(oldPreferences.copy(theme = ThemeMode.Light)) }.join() }
                compose.waitUntil(60_000) { vm.preferences.value.theme == ThemeMode.Light }; compose.waitForIdle()
                captureWindow("readerka-080-consp-light.png")
                runBlocking { compose.runOnUiThread { vm.settings(oldPreferences.copy(theme = ThemeMode.Dark)) }.join() }
                compose.waitUntil(60_000) { vm.preferences.value.theme == ThemeMode.Dark }; compose.waitForIdle()
                captureWindow("readerka-080-consp-dark.png")
            } finally { runBlocking { compose.runOnUiThread { vm.settings(oldPreferences) }.join() } }
        }
        // Opening a source and returning retains the editor and its cursor.
        val pane = vm.workspace.value.activePane
        compose.runOnUiThread { vm.openLink(ResearchLinks.entry(source.id), pane) }
        compose.waitUntil(60_000) { vm.workspace.value.panes[pane].selected?.documentId == source.id }
        act(compose.onNodeWithTag("return-to-note-$pane"), input)
        compose.waitUntil(60_000) { vm.workspace.value.panes[pane].selected?.documentId == id }
        assertTrue(vm.workspace.value.drafts.any { it.entry.id == id })
        compose.activityRule.scenario.recreate()
        compose.waitUntil(60_000) { vm.workspace.value.drafts.any { it.entry.id == id } }
        assertTrue(vm.workspace.value.drafts.first { it.entry.id == id }.entry.content!!.marks.any { it.style == TextStyle.Bold })
    }
    @Test fun everyShortcutIsUnambiguousAndListedInHelp() = fixture { _, _, _, _ ->
        val registry = org.readerka.ui.AppShortcuts
        val chords = registry.all.groupBy { listOf(it.code, it.ctrl, it.alt, it.shift) }
        assertTrue(chords.values.all { it.size == 1 })
        registry.all.forEach { binding ->
            val meta = (if (binding.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (binding.alt) KeyEvent.META_ALT_ON else 0) or (if (binding.shift) KeyEvent.META_SHIFT_ON else 0)
            assertEquals(binding, registry.match(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, binding.code, 0, meta)))
            assertNull(registry.match(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, binding.code, 0, meta or KeyEvent.META_META_ON)))
        }
        key(KeyEvent.KEYCODE_F1, 0)
        registry.all.forEach { compose.onNodeWithText("${it.keys} — ${it.label}").assertExists() }
        compose.onNodeWithText("Закрыть").performClick()
        key(KeyEvent.KEYCODE_SLASH)
        compose.onNodeWithText("Общие команды").assertExists()
        compose.onNodeWithText("Закрыть").performClick()
    }
    @Test fun editorShortcutsFormatNavigateAndPreserveNativeCopyPaste() = fixture { _, _, _, _ ->
        compose.runOnUiThread { vm.newConsp(title = "Keyboard outline", content = RichText.plain("alpha beta")) }
        waitFor("entry-comment")
        val id = vm.workspace.value.editor!!.entry.id
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        key(KeyEvent.KEYCODE_B); key(KeyEvent.KEYCODE_I); key(KeyEvent.KEYCODE_U)
        compose.waitUntil(30_000) { vm.workspace.value.editor!!.entry.richText().marks.count { it.style in setOf(TextStyle.Bold, TextStyle.Italic, TextStyle.Underline) } == 3 }
        val sidebar = vm.workspace.value.sidebarVisible
        key(KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        compose.waitUntil(30_000) { vm.workspace.value.sidebarVisible != sidebar }
        assertTrue(vm.workspace.value.editor!!.entry.richText().marks.any { it.style == TextStyle.Bold })
        key(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { vm.workspace.value.editor!!.entry.richText().blocks.first().style == BlockStyle.Heading }
        key(KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.onNodeWithText("Оглавление").assertExists(); compose.onNodeWithText("Закрыть").performClick()
        key(KeyEvent.KEYCODE_F)
        compose.onNodeWithTag("editor-search-field").assertExists()
        key(KeyEvent.KEYCODE_ESCAPE, 0)
        compose.onNodeWithTag("editor-search-field").assertDoesNotExist()
        key(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.onNodeWithTag("note-properties-panel").assertExists(); closeProperties(Input.Keyboard)
        key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
        compose.waitUntil(30_000) { vm.library.value.noteVersions.any { it.entryId == id && it.label == "Версия пользователя" } }
        assertFalse(vm.workspace.value.split)
        key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.onNodeWithText("История изменений").assertExists(); compose.onNodeWithText("Закрыть").performClick()
        editor("entry-comment").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
        compose.runOnUiThread { println("Clipboard editor focus=${compose.activity.currentFocus?.javaClass?.simpleName} window=${compose.activity.window.decorView.hasWindowFocus()}") }
        key(KeyEvent.KEYCODE_C)
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("alpha", clipboard.primaryClip!!.getItemAt(0).text.toString())
        val length = vm.workspace.value.editor!!.entry.richText().text.length
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(length, length, false) }
        key(KeyEvent.KEYCODE_V)
        compose.waitUntil(30_000) { vm.workspace.value.editor!!.entry.richText().text == "alpha betaalpha" }
        key(KeyEvent.KEYCODE_A); key(KeyEvent.KEYCODE_X)
        compose.waitUntil(30_000) { vm.workspace.value.editor!!.entry.richText().text.isEmpty() }
        key(KeyEvent.KEYCODE_Z)
        compose.waitUntil(30_000) { vm.workspace.value.editor!!.entry.richText().text == "alpha betaalpha" }
        key(KeyEvent.KEYCODE_S)
        compose.waitUntil(30_000) { vm.library.value.entries.any { it.id == id && it.content?.marks?.count { m -> m.style in setOf(TextStyle.Bold, TextStyle.Italic, TextStyle.Underline) } == 3 } }
        key(KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { vm.workspace.value.split && vm.workspace.value.panes.all { it.selected?.documentId == id } }
        val active = vm.workspace.value.activePane
        key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { vm.workspace.value.activePane == 1 - active }
        key(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { !vm.workspace.value.split }
        key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { vm.workspace.value.split }
        key(KeyEvent.KEYCODE_1, KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { vm.screen.value == "library" }
        val libraryPane = vm.workspace.value.activePane
        key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.waitUntil(30_000) { vm.screen.value == "reader" && vm.workspace.value.activePane == 1 - libraryPane }
        compose.onNodeWithTag("workspace-pane-${1 - libraryPane}").assertExists()
    }
    @Test fun pdfShortcutsAndVisualLayoutsSurviveRecreation() = fixture { a, _, _, _ ->
        compose.runOnUiThread { vm.open(a, pane = 0); vm.maximizePane(0) }
        waitFor("reader-materials-button")
        key(KeyEvent.KEYCODE_B, KeyEvent.META_ALT_ON)
        compose.onNodeWithTag("reader-bookmarks-button").assertIsSelected()
        key(KeyEvent.KEYCODE_Q, KeyEvent.META_ALT_ON)
        compose.onNodeWithTag("reader-quotes-button").assertIsSelected()
        key(KeyEvent.KEYCODE_C, KeyEvent.META_ALT_ON)
        compose.onNodeWithTag("reader-consp-button").assertIsSelected()
        key(KeyEvent.KEYCODE_M)
        compose.onNodeWithTag("book-materials-panel").assertDoesNotExist()
        key(KeyEvent.KEYCODE_D)
        compose.onNodeWithTag("bookmark-title").performTextReplacement("Keyboard bookmark")
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(30_000) { vm.library.value.bookmarks.any { it.documentId == a.id && it.title == "Keyboard bookmark" } }
        key(KeyEvent.KEYCODE_G)
        compose.onNodeWithTag("pdf-page-input").performTextReplacement("2")
        compose.onNodeWithText(app.getString(R.string.go)).performClick()
        compose.waitUntil(30_000) { vm.readers.value[0]?.page == 1 }
        key(KeyEvent.KEYCODE_F11, 0)
        compose.onAllNodesWithTag("fullscreen-button").assertCountEquals(1)
        key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.onNodeWithContentDescription("Полный экран · F11").assertExists()
        key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        key(KeyEvent.KEYCODE_ESCAPE, 0)
        compose.onNodeWithContentDescription("Полный экран · F11").assertExists()
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.onNodeWithTag("layout-vertical").performTouchInput { click() }
        compose.onNodeWithText("Готово").performClick()
        compose.waitUntil(30_000) { vm.workspace.value.split && vm.workspace.value.splitOrientation == SplitOrientation.Vertical }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { vm.workspace.value.splitOrientation == SplitOrientation.Vertical && vm.workspace.value.split }
        compose.onNodeWithTag("workspace-layout").performTouchInput { click() }
        compose.onNodeWithTag("layout-horizontal").performTouchInput { click() }
        compose.onNodeWithText("Готово").performClick()
        compose.waitUntil(30_000) { vm.workspace.value.splitOrientation == SplitOrientation.Horizontal }
    }
    @Test fun touchOnlyEditorSourcesAndLibraryInsertion() = scenario(Input.Touch)
    @Test fun keyboardEditorSourcesAndLibraryInsertion() = scenario(Input.Keyboard)
    @Test fun mouseEditorSourcesAndLibraryInsertion() = scenario(Input.Mouse)
    @Test fun repeatedQuotesReuseOneConspAndSourcesCanBeDetached() = fixture { a, b, _, _ ->
        compose.runOnUiThread { vm.focusPane(0); vm.insertQuote("First quote", a, 0) }
        compose.waitUntil(60_000) { vm.library.value.entries.any { it.kind == EntryKind.Consp && a.id in it.sources } }
        val entry = vm.library.value.entries.first { it.kind == EntryKind.Consp && a.id in it.sources }
        compose.runOnUiThread { vm.focusPane(0); vm.insertQuote("Second quote", a, 1) }
        compose.waitUntil(60_000) { vm.workspace.value.drafts.firstOrNull { it.entry.id == entry.id }?.entry?.content?.text?.contains("Second quote") == true }
        compose.runOnUiThread { val draft = vm.workspace.value.drafts.first { it.entry.id == entry.id }; vm.updateDraft(draft.copy(entry = draft.entry.copy(sources = listOf(b.id)))) }
        compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == entry.id && it.sources == listOf(b.id) && it.content?.text?.contains("Second quote") == true } }
        val saved = vm.library.value.entries.first { it.id == entry.id }
        assertTrue(saved.content!!.text.contains("First quote")); assertTrue(saved.comment.contains("readerka://document/${a.id}?page=1"))
        assertEquals(1, vm.library.value.entries.count { it.kind == EntryKind.Consp && it.id == entry.id })
    }
    @Test fun narrowSplitKeepsNativeCaretAndSaveAboveRealIme() = fixture { a, _, source, _ ->
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val previousIme = shell("settings get secure show_ime_with_hard_keyboard").trim()
        fun nativeEditor(): org.readerka.ui.ResearchEditText? {
            fun find(view: android.view.View): org.readerka.ui.ResearchEditText? {
                if (view is org.readerka.ui.ResearchEditText) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            return compose.runOnUiThread { find(compose.activity.window.decorView) }
        }
        try {
            shell("wm size 590x1000"); shell("settings put secure show_ime_with_hard_keyboard 1")
            instrumentation.setInTouchMode(true)
            compose.runOnUiThread { vm.focusPane(0); vm.open(a, pane = 0); vm.newConsp(a.id, title = "IME outline", content = RichText.plain((1..24).joinToString("\n") { "Long paragraph $it remains editable." })) }
            compose.waitUntil(60_000) { vm.workspace.value.split && vm.workspace.value.editor?.entry?.title == "IME outline" }
            val consp = vm.workspace.value.editor!!.entry
            compose.runOnUiThread { vm.openNote(source, 1); vm.openNote(consp, 1) }
            waitFor("entry-comment")
            editor("entry-comment").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 12f)) }
            compose.waitUntil(60_000) { compose.runOnUiThread { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0 } > 100 }
            val length = vm.workspace.value.editor!!.entry.richText().text.length
            editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(length, length, false) }
            compose.onNodeWithTag("workspace-pane-0").assertDoesNotExist()
            val save = editor("entry-save-status")
            val bounds = save.fetchSemanticsNode().boundsInWindow
            val available = compose.runOnUiThread { val view = compose.activity.window.decorView; view.height - (androidx.core.view.ViewCompat.getRootWindowInsets(view)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0) }
            assertTrue("Save must be above the keyboard", bounds.bottom <= available + 2)
            compose.waitUntil(60_000) {
                val saveTop = save.fetchSemanticsNode().boundsInWindow.top
                compose.runOnUiThread { nativeEditor()?.let { view -> view.layout?.let { layout -> val position = IntArray(2); view.getLocationInWindow(position); position[1] + view.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset(view.selectionEnd.coerceAtLeast(0))) - view.scrollY <= saveTop } } == true }
            }
            shell("screencap -p /sdcard/readerka-080-ime.png")
            save.performTouchInput { click() }
            compose.onNodeWithTag("save-entry").performTouchInput { click() }
            val id = vm.workspace.value.editor!!.entry.id
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == id && it.content?.text?.contains("Long paragraph 24") == true } }
            key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
            compose.waitUntil(30_000) { vm.workspace.value.panes[1].selected?.documentId == source.id }
            key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)
            compose.waitUntil(30_000) { vm.workspace.value.panes[1].selected?.documentId == id }
            editor("entry-comment").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 12f)) }
            compose.waitUntil(60_000) { compose.runOnUiThread { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0 } > 100 }
            val active = vm.workspace.value.activePane
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
            compose.waitUntil(30_000) { vm.workspace.value.activePane == 1 - active }
            compose.onNodeWithTag("workspace-pane-${1 - active}").assertExists()
            key(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
            compose.waitUntil(30_000) { vm.workspace.value.activePane == active }
            compose.onNodeWithTag("workspace-pane-$active").assertExists()
        } catch (failure: Throwable) {
            shell("screencap -p /sdcard/readerka-080-ime-error.png")
            compose.runOnUiThread { nativeEditor()?.let { editor -> val point = IntArray(2); editor.getLocationInWindow(point); println("IME editor focus=${editor.hasFocus()} y=${point[1]} height=${editor.height} scroll=${editor.scrollY} selection=${editor.selectionEnd} lineBottom=${editor.layout?.getLineBottom(editor.layout.getLineForOffset(editor.selectionEnd.coerceAtLeast(0)))}") } }
            throw failure
        } finally {
            shell("input keyevent 4"); shell("wm size 1280x900"); shell("settings put secure show_ime_with_hard_keyboard " + previousIme.ifBlank { "0" })
        }
    }
    @Test fun fullscreenLastTabCloseCollapsesAndTouchBoundaryCanHidePane() = fixture { _, _, source, _ ->
        compose.runOnUiThread { vm.openNote(source, 0); vm.openRight(WorkspaceTab("source", source.id, WorkspaceKind.Note), 0) }
        compose.waitUntil(60_000) { vm.workspace.value.split }
        val right = vm.workspace.value.panes[1].selected!!
        compose.onNodeWithTag("fullscreen-button").performTouchInput { click() }
        compose.onNodeWithTag("close-tab-${right.id}").performTouchInput { click() }
        compose.waitUntil(60_000) { !vm.workspace.value.split && vm.workspace.value.visiblePane == 0 }
        compose.onNodeWithTag("workspace-pane-1").assertDoesNotExist()
        compose.onNodeWithTag("workspace-layout").performTouchInput { click() }
        compose.onNodeWithTag("restore-split-0").performTouchInput { click() }
        waitFor("resize-split")
        compose.onNodeWithTag("resize-split").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-600f, 0f), 400); up() }
        compose.waitUntil(60_000) { !vm.workspace.value.split && vm.workspace.value.visiblePane == 1 }
        compose.onNodeWithTag("fullscreen-button").performTouchInput { click() }
    }
}
