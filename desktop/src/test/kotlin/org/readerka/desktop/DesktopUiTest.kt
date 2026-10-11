package org.readerka.desktop

import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection

/** Real Swing/Robot input, on the same desktop controls shipped for Windows. Requires DISPLAY. */
class DesktopUiTest {
    private fun edt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
    }

    @Test
    fun keyboardFormattingFoldersRightPaneFullscreenAndDraftRecovery() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-ui").toFile()
        val repo = DesktopRepository(root)
        val session = LibrarySession(repo)
        val folder = Collection("consp-folder", "Тема конспектов", scope = CollectionScope.Consp)
        val entry =
            Entry("editor-test", "Конспект", kind = EntryKind.Consp)
                .withContent(RichText.plain("Форматированный текст"))
        session.change {
            it.copy(
                collections = listOf(folder),
                entries = listOf(entry),
                memberships = listOf(Membership(folder.id, entry.id)),
            )
        }
        lateinit var app: DesktopWindow
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session)
                app.setSize(1200, 840)
                app.isVisible = true
                app.navigate(Place(2, null, "Конспекты"))
                assertEquals(1, app.table.rowCount)
                assertTrue(app.table.getValueAt(0, 0).toString().contains(folder.name))
                app.navigate(Place(2, folder.id, folder.name))
                assertEquals(1, app.table.rowCount)
                app.openEntry(entry.id)
                val editor = app.editors.getValue(entry.id).editor
                editor.textPane.requestFocusInWindow()
                editor.textPane.select(0, 15)
            }
            val robot = Robot().apply { autoDelay = 30 }
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_B)
            robot.keyRelease(KeyEvent.VK_B)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            robot.waitForIdle()
            edt {
                val panel = app.editors.getValue(entry.id)
                assertTrue(
                    "Ctrl+B applies portable bold",
                    panel.editor.rich.marks.any { it.style == TextStyle.Bold },
                )
                panel.save()
                app.openEntry(entry.id, right = true)
                assertTrue(session.workspace.split)
                assertEquals(1, app.panes[1].tabCount)
                app.toggleFullscreen()
                assertTrue(app.isUndecorated)
                app.closeTab(panel)
                assertFalse("Empty right pane collapses in fullscreen", session.workspace.split)
                assertTrue(app.isUndecorated)
                app.toggleFullscreen()
                assertFalse(app.isUndecorated)
                app.openEntry(entry.id)
                val reopened = app.editors.getValue(entry.id)
                assertTrue(reopened.editor.rich.marks.any { it.style == TextStyle.Bold })
                reopened.editor.replace(
                    reopened.editor.rich.changeText("Форматированный текст\nЧерновик")
                )
                reopened.flush()
                app.captureWorkspace()
                assertEquals(1, session.workspace.drafts.size)
                assertEquals(
                    app.commands.values.mapNotNull { it.key }.distinct().size,
                    app.commands.values.count { it.key != null },
                )
            }
            robot.waitForIdle()
            File("build/screenshots").mkdirs()
            ImageIO.write(
                robot.createScreenCapture(app.bounds),
                "png",
                File("build/screenshots/desktop-editor.png"),
            )
        } finally {
            edt {
                app.editors.values.forEach { it.disposePanel() }
                app.pdfs.values.forEach { it.close() }
                app.dispose()
            }
            repo.close()
        }
        DesktopRepository(root).use { reopened ->
            assertTrue(
                reopened.workspace().drafts.single().entry.richText().text.contains("Черновик")
            )
            assertTrue(
                reopened.snapshot().entries.single().richText().marks.any {
                    it.style == TextStyle.Bold
                }
            )
        }
        root.deleteRecursively()
    }

    @Test
    fun nativeTypingUndoAndCopyDoNotReplaceStableBlocks() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val block = RichText.plain("Введение\nТекст").paragraph(0, 0, BlockStyle.Heading, 1)
        lateinit var editor: RichEditor
        lateinit var frame: JFrame
        try {
            edt {
                editor = RichEditor(block, { _, _, _ -> }, {})
                frame = JFrame("Editor test")
                frame.add(editor)
                frame.setSize(650, 500)
                frame.isVisible = true
                editor.textPane.caretPosition = editor.rich.text.length
                editor.textPane.requestFocusInWindow()
            }
            val robot = Robot()
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_A)
            robot.waitForIdle()
            edt {
                assertTrue(editor.rich.text.endsWith("a"))
                assertEquals(block.blocks[0].id, editor.rich.blocks[0].id)
                editor.undo()
                assertEquals(block, editor.rich)
                editor.redo()
                assertTrue(editor.rich.text.endsWith("a"))
                editor.textPane.select(0, 8)
                editor.textPane.copy()
                assertTrue(
                    Toolkit.getDefaultToolkit()
                        .systemClipboard
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor)
                        .toString()
                        .contains("Введение")
                )
            }
        } finally {
            edt { frame.dispose() }
        }
    }

    @Test
    fun boldAtCaretAppliesToNewTypingAndReadingOutlineCanFold() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        lateinit var frame: JFrame
        lateinit var editor: RichEditor
        try {
            edt {
                editor =
                    RichEditor(
                        RichText.plain("Раздел\nТекст").paragraph(0, 0, BlockStyle.Heading),
                        { _, _, _ -> },
                        {},
                    )
                frame = JFrame("Typing and reading")
                frame.add(editor)
                frame.setSize(650, 500)
                frame.isVisible = true
                editor.textPane.caretPosition = editor.rich.text.length
                editor.style(TextStyle.Bold)
                editor.textPane.requestFocusInWindow()
            }
            val robot = Robot()
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_B)
            robot.keyRelease(KeyEvent.VK_B)
            robot.waitForIdle()
            edt {
                val last = editor.rich.text.lastIndex
                assertTrue(
                    editor.rich.marks.any {
                        it.style == TextStyle.Bold && last in it.start until it.end
                    }
                )
                editor.togglePreview()
                assertFalse(editor.textPane.isEditable)
                fun buttons(c: java.awt.Container): List<JButton> {
                    return c.components.flatMap {
                        if (it is JButton) listOf(it)
                        else if (it is java.awt.Container) buttons(it) else emptyList()
                    }
                }
                val fold = buttons(editor).first { it.text.startsWith("▾") }
                fold.doClick()
                assertTrue(fold.text.startsWith("▸"))
                fold.doClick()
                assertTrue(fold.text.startsWith("▾"))
                editor.togglePreview()
                assertTrue(editor.textPane.isEditable)
            }
        } finally {
            edt { frame.dispose() }
        }
    }

    @Test
    fun internalClipboardKeepsFormattingAndLinks() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        edt {
            val source =
                RichEditor(
                    RichText.plain("Ссылка")
                        .style(0, 6, TextStyle.Bold)
                        .style(0, 6, TextStyle.Link, "readerka://entry/source"),
                    { _, _, _ -> },
                    {},
                )
            val destination = RichEditor(RichText.plain("Начало "), { _, _, _ -> }, {})
            source.textPane.select(0, 6)
            source.textPane.copy()
            destination.textPane.caretPosition = destination.rich.text.length
            destination.textPane.paste()
            assertEquals("Начало Ссылка", destination.rich.text)
            assertTrue(destination.rich.marks.any { it.style == TextStyle.Bold && it.start == 7 })
            assertTrue(
                destination.rich.marks.any {
                    it.style == TextStyle.Link && it.value == "readerka://entry/source"
                }
            )
        }
    }

    @Test
    fun mouseSelectsPdfQuoteAndOpensEditor() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-pdf-ui").toFile()
        val source = File(root, "source.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { d ->
            val p = org.apache.pdfbox.pdmodel.PDPage()
            d.addPage(p)
            org.apache.pdfbox.pdmodel.PDPageContentStream(d, p).use { stream ->
                stream.beginText()
                stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.TIMES_ROMAN, 20f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText("Existence is not a real predicate.")
                stream.endText()
            }
            d.save(source)
        }
        val repo = DesktopRepository(File(root, "library"))
        val session = LibrarySession(repo)
        val document = session.importPdf(source, null)
        val page = DesktopPdfEngine(repo.pdf(document)).use { it.text(0) }
        lateinit var app: DesktopWindow
        lateinit var canvas: JPanel
        fun all(c: java.awt.Container): List<Component> =
            c.components.flatMap {
                listOf(it) + (if (it is java.awt.Container) all(it) else emptyList())
            }
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session)
                app.setSize(1200, 840)
                app.isVisible = true
                app.openPdf(document.id)
                canvas =
                    all(app).filterIsInstance<JPanel>().first {
                        it.accessibleContext.accessibleName == "Страница PDF"
                    }
            }
            val robot = Robot().apply { autoDelay = 40 }
            val deadline = System.nanoTime() + 20_000_000_000L
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                robot.waitForIdle()
                edt { ready = canvas.preferredSize.width >= 500 }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("PDF image rendered", ready)
            robot.waitForIdle()
            var origin = Point()
            var size = Dimension()
            edt {
                origin = canvas.locationOnScreen
                size = canvas.preferredSize
            }
            fun point(index: Int): Point {
                val r = page.glyphs[index].rect
                return Point(
                    origin.x + ((r.left + r.right) * .5 * size.width).toInt(),
                    origin.y + ((r.top + r.bottom) * .5 * size.height).toInt(),
                )
            }
            val first = point(0)
            val last = point(8)
            robot.mouseMove(first.x, first.y)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseMove(last.x, last.y)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            lateinit var action: JButton
            edt {
                action =
                    all(app).filterIsInstance<JButton>().first { it.text == "Цитата / комментарий" }
            }
            val at = action.locationOnScreen
            robot.mouseMove(at.x + action.width / 2, at.y + action.height / 2)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            edt {
                val quote = session.data.entries.single { it.kind == EntryKind.Quote }
                assertEquals(document.id, quote.documentId)
                assertTrue("Selected text: ${quote.quote}", quote.quote.startsWith("Existence"))
                assertNotNull(quote.anchor)
                assertTrue(app.editors.containsKey(quote.id))
                app.openEntry(quote.id, right = true)
            }
            robot.waitForIdle()
            val fitDeadline = System.nanoTime() + 10_000_000_000L
            var fitted = false
            while (!fitted && System.nanoTime() < fitDeadline) {
                edt { fitted = canvas.preferredSize.width <= canvas.parent.width }
                if (!fitted) Thread.sleep(50)
            }
            assertTrue("Fit to width adapts to split pane", fitted)
            File("build/screenshots").mkdirs()
            ImageIO.write(
                robot.createScreenCapture(app.bounds),
                "png",
                File("build/screenshots/desktop-pdf-and-editor.png"),
            )
        } finally {
            edt {
                app.editors.values.forEach { it.disposePanel() }
                app.pdfs.values.forEach { it.close() }
                app.dispose()
            }
            repo.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun keyboardSelectsPdfTextAndCreatesAnchoredQuote() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-keyboard-pdf").toFile()
        val source = File(root, "source.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { d ->
            val p = org.apache.pdfbox.pdmodel.PDPage()
            d.addPage(p)
            org.apache.pdfbox.pdmodel.PDPageContentStream(d, p).use { stream ->
                stream.beginText()
                stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.TIMES_ROMAN, 16f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText("Keyboard selection preserves the PDF anchor.")
                stream.endText()
            }
            d.save(source)
        }
        val repo = DesktopRepository(File(root, "library"))
        val session = LibrarySession(repo)
        val document = session.importPdf(source, null)
        lateinit var app: DesktopWindow
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session)
                app.setSize(1200, 840)
                app.isVisible = true
                app.openPdf(document.id)
                app.panes[0].requestFocusInWindow()
            }
            val robot = Robot().apply { autoDelay = 30 }
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_ALT)
            robot.keyPress(KeyEvent.VK_R)
            robot.keyRelease(KeyEvent.VK_R)
            robot.keyRelease(KeyEvent.VK_ALT)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            robot.waitForIdle()
            val deadline = System.nanoTime() + 20_000_000_000L
            var dialog: JDialog? = null
            while (dialog == null && System.nanoTime() < deadline) {
                edt {
                    dialog =
                        java.awt.Window.getWindows().filterIsInstance<JDialog>().firstOrNull {
                            it.isVisible && it.title.startsWith("Текст PDF")
                        }
                }
                if (dialog == null) Thread.sleep(50)
            }
            assertNotNull("Keyboard command opens page text", dialog)
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_SHIFT)
            robot.keyPress(KeyEvent.VK_Q)
            robot.keyRelease(KeyEvent.VK_Q)
            robot.keyRelease(KeyEvent.VK_SHIFT)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            robot.waitForIdle()
            edt {
                val quote = session.data.entries.single()
                assertTrue(quote.quote.startsWith("Keyboard selection"))
                assertEquals(document.id, quote.documentId)
                assertNotNull(quote.anchor)
                assertEquals(0, quote.anchor!!.page)
                assertTrue(quote.anchor!!.rects.isNotEmpty())
                assertTrue(app.editors.containsKey(quote.id))
            }
        } finally {
            edt {
                java.awt.Window.getWindows()
                    .filterIsInstance<JDialog>()
                    .filter { it.owner === app }
                    .forEach { it.dispose() }
                app.editors.values.forEach { it.disposePanel() }
                app.pdfs.values.forEach { it.close() }
                app.dispose()
            }
            repo.close()
            root.deleteRecursively()
        }
    }
}
