package org.readerka.desktop

import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.*
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.tree.*
import org.apache.pdfbox.pdmodel.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection

class MaterialActionsUiTest {
    @Test fun createCatalogueAndPdfFoldersInPropertiesWithParentsAndCancel() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("readerka-properties-folders").toFile()
        val library = File(directory, "library")
        val repo = DesktopRepository(library)
        var repoClosed = false
        val session = LibrarySession(repo)
        val source = File(directory, "book.pdf")
        PDDocument().use { pdf -> pdf.addPage(PDPage()); pdf.save(source) }
        val book = session.importPdf(source, null)
        session.change { it.copy(entries = listOf(Entry("q", "Цитата", quote = "Текст", documentId = book.id, kind = EntryKind.Quote))) }
        var app: DesktopWindow? = null
        fun answer(title: String, action: (JDialog) -> Unit, open: () -> Unit) {
            var failure: Throwable? = null
            var handled = false
            val timer = Timer(80, null)
            timer.addActionListener {
                val dialog = Window.getWindows().filterIsInstance<JDialog>().lastOrNull { it.isShowing && it.title == title }
                if (dialog != null) {
                    timer.stop()
                    handled = true
                    try { action(dialog) } catch (e: Throwable) { failure = e; dialog.dispose() }
                }
            }
            timer.start()
            try { open() } finally { timer.stop() }
            failure?.let { throw it }
            assertTrue("Dialog was handled: $title", handled)
        }
        fun close(dialog: JDialog, ok: Boolean) = all(dialog).filterIsInstance<JButton>().first {
            it.text in if (ok) listOf("OK", "ОК") else listOf("Cancel", "Отмена")
        }.doClick()
        fun create(dialog: JDialog, pdf: Boolean, name: String, parent: String? = null, cancel: Boolean = false) {
            val tabs = all(dialog).filterIsInstance<JTabbedPane>().single()
            tabs.selectedIndex = tabs.indexOfTab(if (pdf) "Папки PDF" else "Папки каталога")
            val button = all(tabs.selectedComponent).filterIsInstance<JButton>().single { it.text == "Создать папку" }
            assertTrue(button.isEnabled)
            answer("Создать папку", { form ->
                all(form).filterIsInstance<JTextField>().single().text = "  $name  "
                val parents = all(form).filterIsInstance<JComboBox<*>>().single()
                if (parent != null) parents.selectedIndex = (0 until parents.itemCount).first { parents.getItemAt(it).toString() == parent }
                close(form, !cancel)
            }) { button.doClick() }
            if (!cancel) assertTrue(all(tabs.selectedComponent).filterIsInstance<JCheckBox>().single {
                it.text.endsWith(" / $name")
            }.isSelected)
        }
        try {
            edt {
                FlatLightLaf.setup()
                val window = DesktopWindow(session).also { app = it; it.setSize(1100, 800); it.isVisible = true }
                answer("Свойства записи", { dialog ->
                    create(dialog, false, "Каталог")
                    create(dialog, false, "Вложенная", "Каталог")
                    create(dialog, true, "PDF")
                    create(dialog, true, "Внутренняя", "PDF")
                    create(dialog, true, "Отменённая", cancel = true)
                    assertTrue("Folder creation is staged", session.data.collections.isEmpty() && session.data.readerFolders.isEmpty())
                    dialog.validate()
                    val rendered = java.awt.image.BufferedImage(dialog.width, dialog.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    rendered.createGraphics().let { graphics ->
                        try { dialog.printAll(graphics) } finally { graphics.dispose() }
                    }
                    ImageIO.write(rendered, "png", File("build/screenshots").apply { mkdirs() }.resolve("quote-create-folder-properties.png"))
                    close(dialog, true)
                }) { window.entryProperties("q") }
                val root = session.data.collections.single { it.name == "Каталог" }
                val child = session.data.collections.single { it.name == "Вложенная" }
                val pdfRoot = session.data.readerFolders.single { it.name == "PDF" }
                val pdfChild = session.data.readerFolders.single { it.name == "Внутренняя" }
                assertEquals(root.id, child.parentId)
                assertEquals(pdfRoot.id, pdfChild.parentId)
                assertTrue(session.data.readerFolders.all { it.documentId == book.id && it.kind == ReaderFolderKind.Quotes })
                assertEquals(setOf(root.id, child.id), session.draft("q").collections)
                assertEquals(setOf(pdfRoot.id, pdfChild.id), session.draft("q").readerFolders)
                val saved = session.data
                answer("Свойства записи", { dialog ->
                    create(dialog, false, "Несохранённая")
                    create(dialog, true, "Несохранённая PDF")
                    val tabs = all(dialog).filterIsInstance<JTabbedPane>().single()
                    all(tabs.selectedComponent).filterIsInstance<JButton>().single { it.text == "Снять все назначения" }.doClick()
                    assertTrue(all(tabs.selectedComponent).filterIsInstance<JCheckBox>().none { it.isSelected })
                    close(dialog, false)
                }) { window.entryProperties("q") }
                assertEquals(saved, session.data)
                window.dispose()
                app = null
            }
            repo.close()
            repoClosed = true
            DesktopRepository(library).use { reopened ->
                val restored = LibrarySession(reopened)
                assertEquals(session.data, restored.data)
                assertEquals(session.draft("q").collections, restored.draft("q").collections)
                assertEquals(session.draft("q").readerFolders, restored.draft("q").readerFolders)
            }
        } finally {
            edt { app?.let { window ->
                Window.getWindows().filterIsInstance<JDialog>().filter { it.owner === window }.forEach { it.dispose() }
                window.dispose()
            } }
            if (!repoClosed) repo.close()
            directory.deleteRecursively()
        }
    }

    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()
    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        var ready = false
        while (!ready && System.nanoTime() < deadline) {
            edt { ready = condition() }
            if (!ready) Thread.sleep(50)
        }
        assertTrue(message, ready)
    }
    private fun capture(robot: Robot, c: Component, file: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        (c as? RootPaneContainer)?.rootPane?.let { it.paintImmediately(0, 0, it.width, it.height) }
        Toolkit.getDefaultToolkit().sync()
        ImageIO.write(robot.createScreenCapture(Rectangle(c.locationOnScreen, c.size)), "png", File(dir, file))
    }

    @Test fun directBookmarkDragDropMultipleFoldersAndVisibleEditingTools() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("readerka-material-actions").toFile()
        val repo = DesktopRepository(File(directory, "library"))
        val session = LibrarySession(repo)
        val source = File(directory, "book.pdf")
        PDDocument().use { pdf -> pdf.addPage(PDPage()); pdf.addPage(PDPage()); pdf.save(source) }
        val book = session.importPdf(source, null)
        session.change { it.copy(
            entries = listOf(
                Entry("q", "Цитата", quote = "Полный исходный текст цитаты", documentId = book.id,
                    anchor = Anchor(0, 0, 6, listOf(PageRect(.1f, .1f, .2f, .2f))), kind = EntryKind.Quote),
                Entry("consp", "Конспект", kind = EntryKind.Consp).withContent(RichText.plain("Текст конспекта"))),
            collections = listOf(Collection("g1", "Каталог A"), Collection("g2", "Каталог B")),
            readerFolders = listOf(
                ReaderFolder("a", book.id, ReaderFolderKind.Quotes, "PDF A"),
                ReaderFolder("b", book.id, ReaderFolderKind.Quotes, "PDF B"),
                ReaderFolder("child", book.id, ReaderFolderKind.Quotes, "Дочерняя", "a"),
                ReaderFolder("marks", book.id, ReaderFolderKind.Bookmarks, "Закладки"))) }
        lateinit var app: DesktopWindow
        var created = false
        lateinit var panel: PdfPanel
        lateinit var drawer: BookMaterialsPanel
        val robot = Robot().apply { autoDelay = 45 }
        fun materialTree(name: String) = all(drawer).filterIsInstance<JTree>().single { it.accessibleContext.accessibleName == name }
        fun index(tree: JTree, id: String, source: String? = null) = (0 until tree.rowCount).first { i ->
            val node = (tree.getPathForRow(i).lastPathComponent as DefaultMutableTreeNode).userObject as BookMaterialNode
            val matches = when (val v = node.value) {
                is Entry -> v.id == id
                is ReaderFolder -> v.id == id
                is Bookmark -> v.id == id
                else -> false
            }
            matches && (source == null || node.source == source)
        }
        fun openFolder(tree: JTree, id: String) { tree.expandRow(index(tree, id)) }
        fun drag(tree: JTree, from: Int, to: Int, copy: Boolean = false) {
            var start = Point(); var end = Point()
            edt {
                val origin = tree.locationOnScreen
                val a = tree.getRowBounds(from); val b = tree.getRowBounds(to)
                start = Point(origin.x + a.x + 45, origin.y + a.y + a.height / 2)
                end = Point(origin.x + b.x + 45, origin.y + b.y + b.height / 2)
            }
            robot.mouseMove(start.x, start.y)
            if (copy) robot.keyPress(KeyEvent.VK_CONTROL)
            try {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                for (n in 1..15) robot.mouseMove(start.x + (end.x - start.x) * n / 15, start.y + (end.y - start.y) * n / 15)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                if (copy) robot.keyRelease(KeyEvent.VK_CONTROL)
            }
            robot.waitForIdle()
        }
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session); created = true
                app.setSize(1200, 840); app.setLocation(0, 0); app.isVisible = true
                app.openPdf(book.id); panel = app.pdfs.getValue(book.id)
                all(panel).filterIsInstance<JButton>().single { it.accessibleContext.accessibleName == "Добавить закладку · Ctrl+D" }.doClick()
                drawer = all(panel).filterIsInstance<BookMaterialsPanel>().single()
                assertEquals(1, session.data.bookmarks.size)
                assertFalse(Window.getWindows().filterIsInstance<JDialog>().any { it.isShowing })
                panel.bookmark()
                assertEquals("Repeated quick action selects the existing bookmark", 1, session.data.bookmarks.size)
            }
            awaitUi("Bookmark drawer visible") { drawer.isShowing && materialTree("Закладки книги").width > 100 }
            val marks = materialTree("Закладки книги")
            var bookmark = ""
            edt { bookmark = session.data.bookmarks.single().id }
            var from = 0; var to = 0
            edt { from = index(marks, bookmark); to = index(marks, "marks") }
            drag(marks, from, to)
            awaitUi("Dragging bookmark files it without changing page") { session.data.bookmarks.single().folderId == "marks" }
            edt { panel.materials(0) }
            val quotes = materialTree("Цитаты книги")
            edt { from = index(quotes, "q"); to = index(quotes, "a") }
            drag(quotes, from, to)
            awaitUi("Quote dropped into PDF folder") { session.draft("q").readerFolders == setOf("a") }
            edt { openFolder(quotes, "a"); from = index(quotes, "q"); to = index(quotes, "child") }
            edt {
                assertTrue("Sibling folder remains visible when A is expanded", index(quotes, "b") >= 0)
                assertTrue(quotes.isExpanded(quotes.getPathForRow(index(quotes, "a"))))
            }
            drag(quotes, from, to, copy = true)
            awaitUi("Ctrl drag adds a second folder") { session.draft("q").readerFolders == setOf("a", "child") }
            edt { from = index(quotes, "q", "a") }
            drag(quotes, from, 0)
            awaitUi("Drop on parent removes only the source membership") { session.draft("q").readerFolders == setOf("child") }
            edt {
                var failure: Throwable? = null
                val timer = Timer(100, null)
                timer.addActionListener {
                    val dialog = Window.getWindows().filterIsInstance<JDialog>().lastOrNull { it.isShowing && it.title == "Свойства записи" }
                    if (dialog != null) {
                        timer.stop()
                        try {
                            val tabs = all(dialog).filterIsInstance<JTabbedPane>().single()
                            assertTrue((0 until tabs.tabCount).map { tabs.getTitleAt(it) }.containsAll(listOf("Папки каталога", "Папки PDF", "Текст цитаты")))
                            val boxes = all(dialog).filterIsInstance<JCheckBox>()
                            boxes.filter { it.text.contains("Каталог A") || it.text.contains("Каталог B") }.forEach { if (!it.isSelected) it.doClick() }
                            boxes.filter { it.text.contains(" / PDF ") }.forEach { box ->
                                val selected = !box.text.contains("Дочерняя")
                                if (box.isSelected != selected) box.doClick()
                            }
                            tabs.selectedIndex = (0 until tabs.tabCount).first { tabs.getTitleAt(it) == "Папки PDF" }
                            capture(robot, dialog, "quote-folder-properties.png")
                            all(dialog).filterIsInstance<JButton>().first { it.text in listOf("OK", "ОК") }.doClick()
                        } catch (e: Throwable) { failure = e; dialog.dispose() }
                    }
                }
                timer.start()
                try { app.entryProperties("q") } finally { timer.stop() }
                failure?.let { throw it }
                assertEquals(setOf("g1", "g2"), session.draft("q").collections)
                assertEquals(setOf("a", "b"), session.draft("q").readerFolders)
                assertEquals(1, session.data.entries.count { it.id == "q" })
                app.openEntry("consp")
                app.setSize(880, 740)
            }
            val entry = app.editors.getValue("consp")
            awaitUi("Editing tools wrap within the narrow pane") {
                val toolbar = all(entry).filterIsInstance<JPanel>().single { it.accessibleContext.accessibleName == "Редактирование текста" }
                toolbar.isShowing && toolbar.components.all { it.x >= 0 && it.x + it.width <= toolbar.width && it.y + it.height <= toolbar.height }
            }
            edt {
                val buttons = all(entry).filterIsInstance<AbstractButton>()
                entry.editor.textPane.select(0, 5)
                buttons.single { it.accessibleContext.accessibleName == "Жирный · Ctrl+B" }.doClick()
                assertTrue(entry.editor.rich.marks.any { it.style == TextStyle.Bold && it.start == 0 && it.end == 5 })
                assertTrue((buttons.single { it.accessibleContext.accessibleName == "Жирный · Ctrl+B" } as JToggleButton).isSelected)
                buttons.single { it.accessibleContext.accessibleName == "Заголовок 1" }.doClick()
                assertTrue(entry.editor.rich.blocks.any { it.style == BlockStyle.Heading && it.level == 1 })
                assertTrue(buttons.single { it.accessibleContext.accessibleName == "Каталог вставки" }.isShowing)
                capture(robot, app, "direct-editing-toolbar.png")
            }
        } finally {
            edt { if (created) {
                Window.getWindows().filterIsInstance<JDialog>().filter { it.owner === app }.forEach { it.dispose() }
                app.editors.values.forEach { it.disposePanel() }; app.pdfs.values.forEach { it.close() }; app.dispose()
            } }
            repo.close()
            directory.deleteRecursively()
        }
    }
}
