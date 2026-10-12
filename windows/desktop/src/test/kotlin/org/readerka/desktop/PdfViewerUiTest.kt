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
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class PdfViewerUiTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun all(c: Container): List<Component> = c.components.flatMap {
        listOf(it) + if (it is Container) all(it) else emptyList()
    }
    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        var ready = false
        while (!ready && System.nanoTime() < deadline) {
            edt { ready = condition() }
            if (!ready) Thread.sleep(40)
        }
        assertTrue(message, ready)
    }

    @Test fun materialsStayInResizableSidebarAndRefreshWithTheBook() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-pdf-materials").toFile()
        val source = File(root, "book.pdf")
        PDDocument().use { pdf ->
            repeat(3) { pdf.addPage(PDPage()) }
            pdf.save(source)
        }
        val repo = DesktopRepository(File(root, "library"))
        val session = LibrarySession(repo)
        val book = session.importPdf(source, null)
        val other = session.importPdf(source, null)
        session.change { it.copy(
            entries = listOf(
                Entry("quote", "Цитата текущей книги", documentId = book.id, kind = EntryKind.Quote),
                Entry("other-quote", "Чужая цитата", documentId = other.id, kind = EntryKind.Quote),
                Entry("consp", "Конспект текущей книги", kind = EntryKind.Consp, sources = listOf(book.id)),
                Entry("other-consp", "Чужой конспект", kind = EntryKind.Consp, sources = listOf(other.id))),
            bookmarks = listOf(Bookmark("bookmark", book.id, 1, "Раздел книги"),
                Bookmark("other-bookmark", other.id, 0, "Чужая закладка"))) }
        lateinit var app: DesktopWindow
        var created = false
        lateinit var panel: PdfPanel
        lateinit var drawer: BookMaterialsPanel
        lateinit var split: JSplitPane
        lateinit var scroll: JScrollPane
        var fullWidth = 0
        fun materialsList(name: String) = all(drawer).filterIsInstance<JTree>().single { it.accessibleContext.accessibleName == name }
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session); created = true
                app.setSize(1200, 840); app.setLocation(0, 0); app.isVisible = true
                app.openPdf(book.id)
                panel = app.pdfs.getValue(book.id)
                split = all(panel).filterIsInstance<JSplitPane>().single { it.name == "pdf-viewer-split" }
                scroll = split.leftComponent as JScrollPane
            }
            awaitUi("PDF viewport has a usable size") { scroll.viewport.width > 400 }
            edt { fullWidth = scroll.viewport.width; panel.materials(1) }
            awaitUi("Materials occupy the right side of the viewer") {
                (split.rightComponent as? BookMaterialsPanel)?.isShowing == true && scroll.viewport.width < fullWidth - 200
            }
            edt {
                drawer = split.rightComponent as BookMaterialsPanel
                assertEquals(1, drawer.selectedTab)
                assertTrue(SwingUtilities.isDescendingFrom(drawer, panel))
                assertFalse(Window.getWindows().filterIsInstance<JDialog>().any { it.isShowing && it.title == "Материалы книги" })
                assertEquals(1, (materialsList("Цитаты книги").model.root as DefaultMutableTreeNode).childCount)
                assertEquals(1, (materialsList("Закладки книги").model.root as DefaultMutableTreeNode).childCount)
                assertEquals(1, (materialsList("Конспекты книги").model.root as DefaultMutableTreeNode).childCount)
                val bookmarks = materialsList("Закладки книги")
                bookmarks.setSelectionRow(1)
                session.change { it.copy(bookmarks = it.bookmarks.map { b ->
                    if (b.id == "bookmark") b.copy(title = "Обновлённый раздел") else b
                } + Bookmark("second", book.id, 2, "Следующий раздел")) }
                assertEquals(2, (bookmarks.model.root as DefaultMutableTreeNode).childCount)
                assertEquals("bookmark", (((bookmarks.selectionPath.lastPathComponent as DefaultMutableTreeNode).userObject as BookMaterialNode).value as Bookmark).id)
                assertEquals("Обновлённый раздел", (((bookmarks.selectionPath.lastPathComponent as DefaultMutableTreeNode).userObject as BookMaterialNode).value as Bookmark).title)
                bookmarks.requestFocusInWindow()
            }
            val robot = Robot().apply { autoDelay = 50 }
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER)
            awaitUi("Bookmark in the sidebar navigates the PDF") { panel.page == 1 }
            edt { split.dividerLocation = split.width - 385; panel.materials(2) }
            awaitUi("Drawer width can be changed") { drawer.width >= 370 }
            var drawerWidth = 0
            edt { drawerWidth = drawer.width }
            val screenshots = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(robot.createScreenCapture(app.bounds), "png", File(screenshots, "pdf-materials-sidebar.png"))
            edt { all(drawer).filterIsInstance<JButton>().single { it.accessibleContext.accessibleName == "Скрыть материалы книги" }.doClick() }
            awaitUi("Hiding the drawer returns the width to the PDF") { split.rightComponent == null && scroll.viewport.width >= fullWidth - 5 }
            edt { all(panel).filterIsInstance<JButton>().single { it.accessibleContext.accessibleName == "Показать / скрыть материалы книги" }.doClick() }
            awaitUi("Reopening preserves width and selected tab") { split.rightComponent === drawer && kotlin.math.abs(drawer.width - drawerWidth) <= 5 && drawer.selectedTab == 2 }
            edt { panel.close() }
            assertEquals("true", repo.preference("pdf-side-visible"))
            assertEquals("2", repo.preference("pdf-side-tab"))
            edt {
                app.closeTab(panel)
                app.openPdf(book.id)
                panel = app.pdfs.getValue(book.id)
                split = all(panel).filterIsInstance<JSplitPane>().single { it.name == "pdf-viewer-split" }
            }
            awaitUi("New PDF viewer restores the drawer state from storage") {
                val restored = split.rightComponent as? BookMaterialsPanel
                restored?.isShowing == true && restored.selectedTab == 2 && kotlin.math.abs(restored.width - drawerWidth) <= 5
            }
        } finally {
            edt { if (created) { app.pdfs.values.forEach { it.close() }; app.dispose() } }
            repo.close()
        }
    }

    @Test fun wheelZoomArrowsFitAndRapidNavigation() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-pdf-input").toFile()
        val source = File(root, "pages.pdf")
        PDDocument().use { pdf ->
            repeat(12) { n ->
                val p = PDPage()
                pdf.addPage(p)
                PDPageContentStream(pdf, p).use {
                    it.beginText(); it.setFont(PDType1Font.HELVETICA, 24f)
                    it.newLineAtOffset(50f, 700f); it.showText("Page ${n + 1}"); it.endText()
                }
            }
            pdf.save(source)
        }
        val repo = DesktopRepository(File(root, "library"))
        val session = LibrarySession(repo)
        val book = session.importPdf(source, null)
        lateinit var app: DesktopWindow
        var created = false
        lateinit var panel: PdfPanel
        lateinit var canvas: JPanel
        lateinit var scroll: JScrollPane
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session)
                created = true
                app.setSize(1200, 840); app.setLocation(0, 0); app.isVisible = true
                app.openPdf(book.id)
                panel = app.pdfs.getValue(book.id)
                canvas = all(panel).filterIsInstance<JPanel>().first { it.accessibleContext.accessibleName == "Страница книги" }
                scroll = all(panel).filterIsInstance<JScrollPane>().first()
            }
            awaitUi("Whole page fits both viewport dimensions") {
                canvas.preferredSize.width > 100 && canvas.preferredSize.height > 100 &&
                    canvas.preferredSize.width <= scroll.viewport.width && canvas.preferredSize.height <= scroll.viewport.height
            }
            val robot = Robot().apply { autoDelay = 50 }
            var at = Point()
            var original = 0
            edt {
                original = canvas.preferredSize.width
                at = canvas.locationOnScreen.let { Point(it.x + 100, it.y + 100) }
                canvas.requestFocusInWindow()
            }
            robot.mouseMove(at.x, at.y)
            robot.keyPress(KeyEvent.VK_CONTROL); robot.mouseWheel(-6); robot.keyRelease(KeyEvent.VK_CONTROL)
            awaitUi("Ctrl+wheel zooms without changing page") { canvas.preferredSize.width > original * 1.5 }
            edt { assertEquals(0, panel.page) }
            awaitUi("Image layout has scrollable height") { scroll.verticalScrollBar.maximum > scroll.verticalScrollBar.visibleAmount }
            robot.mouseWheel(1)
            awaitUi("Wheel scrolls enlarged page") { scroll.verticalScrollBar.value > 0 }
            robot.keyPress(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_RIGHT)
            awaitUi("Right arrow advances a page") { panel.page == 1 }
            robot.keyPress(KeyEvent.VK_LEFT); robot.keyRelease(KeyEvent.VK_LEFT)
            awaitUi("Left arrow returns a page") { panel.page == 0 }
            edt { repeat(60) { panel.go(it % 12) }; panel.go(8) }
            awaitUi("Latest navigation wins and its text is ready") {
                panel.page == 8 && canvas.accessibleContext.accessibleDescription == "Страница 9 из 12" &&
                    all(panel).filterIsInstance<JLabel>().any { it.text.startsWith("Выделите текст;") }
            }
            edt { app.setSize(900, 700) }
            robot.waitForIdle()
            val screenshots = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(robot.createScreenCapture(app.bounds), "png", File(screenshots, "pdf-viewer-zoom.png"))
            edt { panel.close() }
            assertEquals(8, repo.snapshot().documents.single().lastPage)
        } finally {
            edt {
                if (created) {
                    app.pdfs.values.forEach { it.close() }
                    app.dispose()
                }
            }
            repo.close()
        }
    }
}
