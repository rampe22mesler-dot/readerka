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
            Input.Keyboard -> { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false); compose.waitUntil(30_000) { var focused = false; node.performSemanticsAction(SemanticsActions.RequestFocus) { focused = it() }; focused }; node.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) } }
        }
    }
    private fun key(code: Int, meta: Int = KeyEvent.META_CTRL_ON) = compose.runOnUiThread {
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { compose.activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, it, code, 0, meta)) }
    }
    private fun waitFor(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun editor(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-pane-${vm.workspace.value.activePane}")))
    private fun fixture(block: (Document, Document, Entry, org.readerka.data.Collection) -> Unit) {
        val id = UUID.randomUUID().toString()
        val a = Document("a-$id", "Book A", "a.pdf", "a-$id.pdf", pageCount = 3)
        val b = Document("b-$id", "Book B", "b.pdf", "b-$id.pdf", pageCount = 3)
        val source = Entry("source-$id", "Library note", comment = "Useful original text")
        val folder = org.readerka.data.Collection("folder-$id", "Research topic", scope = CollectionScope.Consp)
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
        act(editor("note-properties"), input)
        // A named word links to the stable identity, preserving its visible label.
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(6, 10, false) }
        act(editor("insert-link"), input)
        act(compose.onNodeWithTag("insert-related-scope"), input)
        act(compose.onNodeWithTag("insert-select-${source.id}"), input)
        act(compose.onNodeWithTag("insert-confirm"), input)
        compose.waitUntil(60_000) { vm.workspace.value.editor?.entry?.content?.marks?.any { it.style == TextStyle.Link && it.value == ResearchLinks.entry(source.id) } == true }
        assertEquals("alpha beta", vm.workspace.value.editor!!.entry.content!!.text)
        assertEquals("beta", vm.workspace.value.editor!!.entry.content!!.let { c -> val m = c.marks.first { it.style == TextStyle.Link }; c.text.substring(m.start, m.end) })
        // A separate clickable card contains a label rather than copied source text.
        val cardStart = vm.workspace.value.editor!!.entry.content!!.text.length
        editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(cardStart, cardStart, false) }
        act(editor("insert-library"), input)
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
        act(editor("insert-library"), input)
        act(compose.onNodeWithTag("insert-related-scope"), input)
        act(compose.onNodeWithTag("insert-select-${source.id}"), input)
        act(compose.onNodeWithTag("insert-confirm"), input)
        compose.waitUntil(60_000) { vm.workspace.value.editor?.entry?.content?.text?.contains(source.comment) == true }
        act(editor("save-entry"), input)
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
                captureWindow("readerka-070-consp-light.png")
                runBlocking { compose.runOnUiThread { vm.settings(oldPreferences.copy(theme = ThemeMode.Dark)) }.join() }
                compose.waitUntil(60_000) { vm.preferences.value.theme == ThemeMode.Dark }; compose.waitForIdle()
                captureWindow("readerka-070-consp-dark.png")
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
    @Test fun narrowSplitKeepsNativeCaretAndSaveAboveRealIme() = fixture { a, _, _, _ ->
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
            waitFor("entry-comment")
            editor("entry-comment").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 12f)) }
            compose.waitUntil(60_000) { compose.runOnUiThread { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0 } > 100 }
            val length = vm.workspace.value.editor!!.entry.richText().text.length
            editor("entry-comment").performSemanticsAction(SemanticsActions.SetSelection) { it(length, length, false) }
            compose.onNodeWithTag("workspace-pane-0").assertDoesNotExist()
            val save = editor("save-entry")
            val bounds = save.fetchSemanticsNode().boundsInWindow
            val available = compose.runOnUiThread { val view = compose.activity.window.decorView; view.height - (androidx.core.view.ViewCompat.getRootWindowInsets(view)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0) }
            assertTrue("Save must be above the keyboard", bounds.bottom <= available + 2)
            compose.waitUntil(60_000) {
                val saveTop = save.fetchSemanticsNode().boundsInWindow.top
                compose.runOnUiThread { nativeEditor()?.let { view -> view.layout?.let { layout -> val position = IntArray(2); view.getLocationInWindow(position); position[1] + view.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset(view.selectionEnd.coerceAtLeast(0))) - view.scrollY <= saveTop } } == true }
            }
            shell("screencap -p /sdcard/readerka-070-ime.png")
            save.performTouchInput { click() }
            val id = vm.workspace.value.editor!!.entry.id
            compose.waitUntil(60_000) { vm.library.value.entries.any { it.id == id && it.content?.text?.contains("Long paragraph 24") == true } }
        } catch (failure: Throwable) {
            shell("screencap -p /sdcard/readerka-070-ime-error.png")
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
        compose.onNodeWithTag("pane-fullscreen-1").performTouchInput { click() }
        compose.onNodeWithTag("close-tab-${right.id}").performTouchInput { click() }
        compose.waitUntil(60_000) { !vm.workspace.value.split && vm.workspace.value.visiblePane == 0 }
        compose.onNodeWithTag("workspace-pane-1").assertDoesNotExist()
        compose.onNodeWithTag("restore-split-0").performTouchInput { click() }
        waitFor("resize-split")
        compose.onNodeWithTag("resize-split").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-600f, 0f), 400); up() }
        compose.waitUntil(60_000) { !vm.workspace.value.split && vm.workspace.value.visiblePane == 1 }
        compose.onNodeWithTag("pane-fullscreen-1").performTouchInput { click() }
    }
}
