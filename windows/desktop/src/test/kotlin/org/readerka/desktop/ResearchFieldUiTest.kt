package org.readerka.desktop

import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.FlatDarkLaf
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

/** Uses dispatched Swing events, no native Robot input or personal library. */
class ResearchFieldUiTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()
    private fun button(c: Component, label: String) = all(c).filterIsInstance<AbstractButton>().single { it.text == label }
    private fun mouse(c: Component, type: Int, x: Int, y: Int, modifiers: Int = 0, key: Int = MouseEvent.BUTTON1) {
        c.dispatchEvent(MouseEvent(c, type, System.currentTimeMillis(), modifiers, x, y, 1, false, key))
    }
    private fun drag(c: Component, dx: Int, dy: Int, modifiers: Int = 0, key: Int = MouseEvent.BUTTON1) {
        mouse(c, MouseEvent.MOUSE_PRESSED, 10, 10, modifiers, key)
        mouse(c, MouseEvent.MOUSE_DRAGGED, 10+dx, 10+dy, modifiers or if (key == MouseEvent.BUTTON2) InputEvent.BUTTON2_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON)
        mouse(c, MouseEvent.MOUSE_RELEASED, 10+dx, 10+dy, modifiers, key)
    }
    private fun modal(action: () -> Unit, handle: (JDialog, JOptionPane) -> Unit) {
        modals(action, listOf(handle))
    }
    private fun modals(action: () -> Unit, handles: List<(JDialog, JOptionPane) -> Unit>) {
        var error: Throwable? = null
        var handled = 0
        val timer = Timer(40, null)
        timer.addActionListener {
            val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing } ?: return@addActionListener
            try {
                handles[handled](dialog, all(dialog).filterIsInstance<JOptionPane>().first()); handled++
                if (handled == handles.size) timer.stop()
            }
            catch (e: Throwable) { error = e; dialog.dispose() }
        }
        timer.start()
        try { action() } finally { timer.stop() }
        error?.let { throw it }
        assertEquals("Expected dialogs", handles.size, handled)
    }
    private fun screenshot(app: DesktopWindow, name: String) {
        app.validate()
        val image = BufferedImage(app.width, app.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); try { app.printAll(g) } finally { g.dispose() }
        val file = File("build/screenshots/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(image, "png", file)
    }
    private fun close(app: DesktopWindow) {
        app.editors.values.forEach { it.disposePanel() }; app.pdfs.values.forEach { it.close() }
        app.researchFields.values.forEach { it.disposePanel() }; app.dispose()
    }

    @Test fun materialCommandsCreateNotesThesesAndReuseRecordsThroughTransfer() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-field-commands").toFile()
        val directory = File(root,"library")
        val repo = DesktopRepository(directory)
        var app: DesktopWindow? = null
        var noteId = ""; var thesisId = ""
        try {
            val session = LibrarySession(repo); val entries = researchFixture(session,root)
            edt {
                FlatLightLaf.setup(); configureChrome()
                app = DesktopWindow(session).apply { setSize(1400,880); isVisible = true; openPdf(entries[0].documentId!!) }
                val panel = app!!.openResearchField("project-a", true)
                modal({ button(panel,"+ Заметка").doClick() }) { dialog,pane ->
                    all(dialog).filterIsInstance<JTextField>().single().text = "Своя заметка"
                    all(dialog).filterIsInstance<JTextArea>().single().text = "Вопрос об основаниях знания"
                    pane.value = JOptionPane.OK_OPTION
                }
                val note = session.data.entries.single { it.title == "Своя заметка" }; noteId = note.id
                assertEquals("Вопрос об основаниях знания",note.richText().text)
                assertEquals(ResearchCardKind.Note,panel.field.placements.last().kind)
                modal({ button(panel,"+ Тезис").doClick() }) { dialog,pane ->
                    all(dialog).filterIsInstance<JTextField>().single().text = "Тезис статьи"
                    all(dialog).filterIsInstance<JTextArea>().single().text = "Различие оснований требует обоснования"
                    pane.value = JOptionPane.OK_OPTION
                }
                thesisId = session.data.entries.single { it.title == "Тезис статьи" }.id
                assertEquals(ResearchCardKind.Thesis,panel.field.placements.last().kind)
                assertEquals(NoteType.Idea,session.data.entries.first { it.id == thesisId }.type)
                val pdf = app!!.pdfs.getValue(entries[0].documentId!!)
                pdf.materials(0)
                val tree = all(pdf).filterIsInstance<JTree>().single { it.accessibleContext.accessibleName == "Цитаты книги" }
                tree.setSelectionRow(1)
                val clipboard = java.awt.datatransfer.Clipboard("research-test")
                tree.transferHandler.exportToClipboard(tree,clipboard,TransferHandler.COPY)
                val transfer = clipboard.getContents(null)
                assertTrue(transfer.isDataFlavorSupported(researchEntryFlavor))
                val surface = all(panel).filterIsInstance<JComponent>().single { it.name == "research-surface" }
                val support = TransferHandler.TransferSupport(surface,transfer)
                val count = session.data.entries.size
                assertTrue(surface.transferHandler.canImport(support)); assertTrue(surface.transferHandler.importData(support))
                assertEquals(count,session.data.entries.size)
                assertEquals(entries[0].id,panel.field.placements.last().entryId)
                val full = entries[0].anchor!!
                val partial = full.copy(end = 6,rects = full.rects.take(6))
                pdf.showAnchor("Reason",partial)
                modal({ pdf.addToResearchField() }) { dialog,pane ->
                    assertEquals("Добавить на исследовательское поле",dialog.title)
                    pane.inputValue = all(dialog).filterIsInstance<JComboBox<*>>().single().getItemAt(0); pane.value = JOptionPane.OK_OPTION
                }
                val quote = session.data.entries.single { it.anchor == partial }
                assertEquals("Reason",quote.quote)
                assertEquals(quote.id,panel.field.placements.last().entryId)
                panel.removeSelected()
                assertTrue(session.data.entries.any { it.id == quote.id })
                modal({ app!!.addToResearchField(listOf(noteId)) }) { dialog,pane ->
                    pane.inputValue = all(dialog).filterIsInstance<JComboBox<*>>().single().getItemAt(1); pane.value = JOptionPane.OK_OPTION
                }
                assertTrue(session.data.researchFields.last().placements.any { it.entryId == noteId })
                assertEquals(1,session.data.entries.count { it.id == noteId })
                modal({ app!!.commands.getValue("research").action() }) { _,pane -> pane.inputValue = "Третий проект"; pane.value = JOptionPane.OK_OPTION }
                assertEquals(3,session.data.researchFields.size)
                assertTrue(app!!.panes[1].selectedComponent is ResearchFieldPanel)
                app!!.flushEditors(); close(app!!); app = null
            }
        } finally { edt { app?.let(::close) }; repo.close() }
        try {
            DesktopRepository(directory).use { reopened ->
                val data = reopened.snapshot()
                assertEquals("Вопрос об основаниях знания",data.entries.first { it.id == noteId }.richText().text)
                assertEquals(NoteType.Idea,data.entries.first { it.id == thesisId }.type)
                assertTrue(data.researchFields.first().placements.any { it.entryId == noteId })
                assertTrue(data.researchFields[1].placements.any { it.entryId == noteId })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun fieldGesturesSourceNavigationAssemblyAndRestartUseCanonicalRecords() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-field-ui").toFile()
        var repo = DesktopRepository(File(root,"library"))
        var app: DesktopWindow? = null
        try {
            val session = LibrarySession(repo); val entries = researchFixture(session, root)
            lateinit var panel: ResearchFieldPanel
            lateinit var saved: ResearchField
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session).apply { setSize(1440,900); isVisible = true; openPdf(entries[0].documentId!!, 0) }
                panel = app!!.openResearchField("project-a", true)
            }
            edt {
                app!!.validate(); panel.fit(false)
                assertEquals(panel, app!!.panes[1].selectedComponent)
                assertTrue(app!!.panes[0].selectedComponent is PdfPanel)
                fun card(id: String) = all(panel).single { it.name == "research-card:$id" }
                fun heading(id: String) = all(card(id)).filterIsInstance<JLabel>().single { it.text.startsWith("Цитата ·") }
                val before = panel.field.placements.first { it.id == "a" }
                val text = all(card("a")).filterIsInstance<JTextArea>().single()
                drag(text, 35, 10); text.select(0, 6)
                assertEquals(before, panel.field.placements.first { it.id == "a" }); assertEquals(6, text.selectedText.length)
                val zoom = panel.field.zoom
                drag(heading("a"), 70, 30)
                assertEquals(before.x+70/zoom, panel.field.placements.first { it.id == "a" }.x, .001)
                assertEquals(before.y+30/zoom, panel.field.placements.first { it.id == "a" }.y, .001)
                button(panel,"Отмена").doClick(); assertEquals(before, panel.field.placements.first { it.id == "a" })
                button(panel,"Повтор").doClick(); assertEquals(before.x+70/zoom, panel.field.placements.first { it.id == "a" }.x, .001)
                val grip = all(card("a")).filterIsInstance<JLabel>().single { it.toolTipText == "Изменить размер" }
                drag(grip, 30, 20)
                assertEquals(before.width+30/zoom, panel.field.placements.first { it.id == "a" }.width, .001)
                val old = panel.field
                mouse(heading("b"), MouseEvent.MOUSE_PRESSED, 10,10,InputEvent.CTRL_DOWN_MASK)
                mouse(heading("b"), MouseEvent.MOUSE_RELEASED,10,10,InputEvent.CTRL_DOWN_MASK)
                assertEquals(setOf("a","b"), panel.selected)
                drag(heading("a"), 25, 15)
                assertEquals(old.placements[0].x+25/zoom, panel.field.placements[0].x,.001)
                assertEquals(old.placements[1].x+25/zoom, panel.field.placements[1].x,.001)
                assertEquals(old.edges, panel.field.edges)
                val surface = all(panel).single { it.name == "research-surface" }
                val geometry = panel.field.placements
                surface.dispatchEvent(MouseWheelEvent(surface, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK, 180,180, 0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,-2))
                assertTrue(panel.field.zoom > zoom); assertEquals(geometry, panel.field.placements)
                val view = panel.field
                drag(surface, 30,20,0,MouseEvent.BUTTON2)
                assertEquals(view.viewX-30/view.zoom,panel.field.viewX,.001)
                assertEquals(geometry, panel.field.placements)
                // Group headings are separate from the text and move their complete membership.
                panel.fit(false)
                val groupZoom = panel.field.zoom
                val groupBefore = panel.field
                val rects = listOf(card("a").bounds, card("b").bounds)
                val gx = rects.minOf { it.x }-4; val gy = rects.minOf { it.y }-30
                mouse(surface,MouseEvent.MOUSE_PRESSED,gx,gy)
                mouse(surface,MouseEvent.MOUSE_DRAGGED,gx+20,gy+15,InputEvent.BUTTON1_DOWN_MASK,MouseEvent.NOBUTTON)
                mouse(surface,MouseEvent.MOUSE_RELEASED,gx+20,gy+15)
                assertEquals(groupBefore.placements[0].x+20/groupZoom,panel.field.placements[0].x,.001)
                assertEquals(groupBefore.placements[1].x+20/groupZoom,panel.field.placements[1].x,.001)
                modal({ button(panel,"Связь").doClick() }) { dialog,pane ->
                    assertEquals("Добавить связь",dialog.title)
                    all(dialog).filterIsInstance<JTextField>().single().text = "Сопоставление"
                    all(dialog).filterIsInstance<JComboBox<*>>().last().selectedIndex = ResearchRelation.Compares.ordinal
                    pane.value = JOptionPane.OK_OPTION
                }
                assertEquals(2,panel.field.edges.size)
                modal({ button(panel,"Группа").doClick() }) { _,pane -> pane.inputValue = "Для введения"; pane.value = JOptionPane.OK_OPTION }
                assertEquals("Для введения",panel.field.groups.last().title)
                assertTrue(panel.field.placements.all { it.groupId == panel.field.groups.last().id })
                // Edit project usage through the actual card menu.
                val menuButton = all(card("a")).filterIsInstance<AbstractButton>().single { it.toolTipText == "Действия карточки" }; menuButton.doClick()
                val popup = MenuSelectionManager.defaultManager().selectedPath.map { it.component }.filterIsInstance<JPopupMenu>().first()
                modal({ popup.components.filterIsInstance<JMenuItem>().single { it.text.startsWith("Применение в этом") }.doClick() }) { dialog,pane ->
                    all(dialog).filterIsInstance<JTextArea>().single().text = "Применение только в первом проекте"; pane.value = JOptionPane.OK_OPTION
                }
                assertEquals("В раздел о разуме", session.data.researchFields.last().placements.single().projectComment)
                assertEquals(entries,session.data.entries.filter { it.kind == EntryKind.Quote })
                panel.selected.clear(); panel.selected.addAll(listOf("a","b"))
                modal({ button(panel,"В конспект…").doClick() }) { dialog,pane ->
                    assertEquals("Сборка конспекта",dialog.title)
                    all(dialog).filterIsInstance<JComboBox<*>>().last().selectedIndex = 1
                    pane.value = JOptionPane.OK_OPTION
                }
                val outline = session.data.entries.first { it.id == "outline" }
                assertTrue(outline.richText().text.indexOf(entries[1].quote) < outline.richText().text.indexOf(entries[0].quote))
                assertEquals(2,outline.sources.size)
                assertEquals(2,outline.richText().marks.count { it.style == TextStyle.Link })
                app!!.openResearchSource(entries[0], panel)
                assertEquals(1,app!!.pdfs.getValue(entries[0].documentId!!).page)
                val selectionField = PdfPanel::class.java.getDeclaredField("selected").apply { isAccessible = true }
                val selection = selectionField.get(app!!.pdfs.getValue(entries[0].documentId!!)) as Pair<*,*>
                assertEquals(entries[0].anchor,selection.second)
                modals({ app!!.pdfs.getValue(entries[0].documentId!!).addToResearchField() }, listOf(
                    { dialog,pane -> assertEquals("Существующая цитата",dialog.title); pane.inputValue = all(dialog).filterIsInstance<JComboBox<*>>().single().getItemAt(0); pane.value = JOptionPane.OK_OPTION },
                    { dialog,pane -> assertEquals("Добавить на исследовательское поле",dialog.title); pane.inputValue = all(dialog).filterIsInstance<JComboBox<*>>().single().getItemAt(1); pane.value = JOptionPane.OK_OPTION },
                ))
                assertEquals(entries,session.data.entries.filter { it.kind == EntryKind.Quote })
                assertEquals(2,session.data.researchFields.last().placements.size)
                assertTrue(session.data.researchFields.last().placements.all { it.entryId == entries[0].id })
                app!!.openResearchField("project-a", true)
                panel.fit(false); panel.flush(); app!!.captureWorkspace()
                saved = panel.field
                MenuSelectionManager.defaultManager().clearSelectedPath()
            }
            // Allow asynchronous PDF rendering to finish before checking the visible highlight.
            val deadline = System.nanoTime()+20_000_000_000L
            var ready = false
            while (!ready && System.nanoTime()<deadline) {
                edt { val image = PdfPanel::class.java.getDeclaredField("image").apply { isAccessible = true }
                    ready = image.get(app!!.pdfs.getValue(entries[0].documentId!!)) != null }
                if (!ready) Thread.sleep(40)
            }
            assertTrue("Source PDF must finish rendering",ready)
            edt {
                val selected = PdfPanel::class.java.getDeclaredField("selected").apply { isAccessible = true }
                assertEquals(entries[0].anchor,(selected.get(app!!.pdfs.getValue(entries[0].documentId!!)) as Pair<*,*>).second)
                screenshot(app!!,"research-field-light")
                FlatDarkLaf.setup(); configureChrome(); SwingUtilities.updateComponentTreeUI(app!!); applyChromeTheme(app!!); panel.refreshTheme()
                screenshot(app!!,"research-field-dark")
                app!!.setSize(900,740); app!!.validate()
            }
            edt {
                panel.fit(false)
                screenshot(app!!,"research-field-narrow")
                // Preserve the saved viewport used by the restart assertion.
                app!!.session.change { it.copy(researchFields = it.researchFields.map { f -> if (f.id == saved.id) saved else f }) }
                close(app!!); app = null
            }
            repo.close(); repo = DesktopRepository(File(root,"library"))
            edt {
                val restored = LibrarySession(repo)
                app = DesktopWindow(restored).apply { setSize(1440,900); isVisible = true }
                val p = app!!.researchFields.getValue("project-a")
                assertEquals(saved,p.field)
                assertTrue(app!!.panes[1].selectedComponent === p)
                assertEquals(2,p.field.edges.size)
                p.selected.clear(); p.selected.add("a"); p.removeSelected()
                assertEquals(entries,restored.data.entries.filter { it.kind == EntryKind.Quote })
                assertEquals(0,p.field.edges.size)
                p.undo(); assertEquals(saved.edges,p.field.edges)
                close(app!!); app = null
            }
        } finally {
            edt { app?.let(::close) }; repo.close(); root.deleteRecursively()
        }
    }
}
