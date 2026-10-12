package org.readerka.desktop

import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.*
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class EditorInteractionUiTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()

    @Test fun nativeListInputActiveFormattingAndCatalogueLinkBoundaries() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("readerka-editor-input").toFile()
        val repo = DesktopRepository(directory)
        val session = LibrarySession(repo)
        session.change { it.copy(entries = listOf(
            Entry("consp", "Конспект", kind = EntryKind.Consp).withContent(RichText.plain("plain")),
            Entry("source", "Материал для ссылки").withContent(RichText.plain("Содержание источника")))) }
        lateinit var app: DesktopWindow
        var created = false
        lateinit var entry: EntryPanel
        val robot = Robot().apply { autoDelay = 45 }
        fun key(code: Int) { robot.keyPress(code); robot.keyRelease(code); robot.waitForIdle() }
        fun toggle(name: String) = all(entry).filterIsInstance<JToggleButton>().single { it.accessibleContext.accessibleName == name }
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session); created = true
                app.setSize(1100, 800); app.setLocation(0, 0); app.isVisible = true
                app.openEntry("consp"); entry = app.editors.getValue("consp")
                entry.editor.textPane.caretPosition = 5
                entry.editor.textPane.requestFocusInWindow()
                toggle("Жирный · Ctrl+B").doClick()
                assertTrue(toggle("Жирный · Ctrl+B").isSelected)
            }
            robot.waitForIdle()
            key(KeyEvent.VK_A)
            edt {
                assertTrue(entry.editor.rich.hasStyle(5, 6, TextStyle.Bold))
                toggle("Жирный · Ctrl+B").doClick()
                assertFalse(toggle("Жирный · Ctrl+B").isSelected)
            }
            key(KeyEvent.VK_B)
            edt { assertFalse(entry.editor.rich.hasStyle(6, 7, TextStyle.Bold)) }
            key(KeyEvent.VK_HOME)
            edt { assertFalse(toggle("Жирный · Ctrl+B").isSelected) }
            repeat(6) { key(KeyEvent.VK_RIGHT) }
            edt { assertTrue("Caret on bold text updates the toggle", toggle("Жирный · Ctrl+B").isSelected) }
            key(KeyEvent.VK_RIGHT)
            edt { assertFalse(toggle("Жирный · Ctrl+B").isSelected) }

            edt {
                entry.editor.replace(RichText.plain("first"))
                entry.editor.textPane.caretPosition = 5
                toggle("Маркированный список").doClick()
                entry.editor.textPane.requestFocusInWindow()
            }
            key(KeyEvent.VK_ENTER)
            edt {
                assertEquals("first\n", entry.editor.rich.text)
                assertEquals(listOf(BlockStyle.Bullet, BlockStyle.Bullet), entry.editor.rich.blocks.map { it.style })
                assertTrue(toggle("Маркированный список").isSelected)
            }
            key(KeyEvent.VK_B); key(KeyEvent.VK_ENTER)
            edt { assertEquals(3, entry.editor.rich.blocks.count { it.style == BlockStyle.Bullet }) }
            key(KeyEvent.VK_BACK_SPACE)
            edt {
                assertTrue(entry.editor.rich.text.endsWith('\n'))
                assertEquals(BlockStyle.Paragraph, entry.editor.rich.blocks.last().style)
                assertFalse(toggle("Маркированный список").isSelected)
            }
            key(KeyEvent.VK_A)
            edt {
                entry.editor.undo(); entry.editor.undo()
                assertEquals(BlockStyle.Bullet, entry.editor.rich.blocks.last().style)
                assertTrue(toggle("Маркированный список").isSelected)
                entry.editor.redo()
                assertEquals(BlockStyle.Paragraph, entry.editor.rich.blocks.last().style)
                entry.editor.replace(RichText.plain("one"))
                entry.editor.textPane.caretPosition = 3
                toggle("Нумерованный список").doClick()
                entry.editor.textPane.requestFocusInWindow()
            }
            key(KeyEvent.VK_ENTER)
            edt { assertEquals(listOf(BlockStyle.Ordered, BlockStyle.Ordered), entry.editor.rich.blocks.map { it.style }) }
            key(KeyEvent.VK_ENTER)
            edt {
                assertEquals("one\n", entry.editor.rich.text)
                assertEquals(BlockStyle.Paragraph, entry.editor.rich.blocks.last().style)
                entry.editor.replace(RichText.plain("word tail"))
                entry.editor.textPane.select(0, 4)
                var failure: Throwable? = null
                val answer = Timer(100, null)
                answer.addActionListener {
                    val dialog = Window.getWindows().filterIsInstance<JDialog>().lastOrNull { it.isShowing && it.title == "Вставить из библиотеки" }
                    if (dialog != null) {
                        answer.stop()
                        try {
                            val list = all(dialog).filterIsInstance<JList<*>>().single()
                            list.selectedIndex = (0 until list.model.size).first { (list.model.getElementAt(it) as Entry).id == "source" }
                            all(dialog).filterIsInstance<JButton>().first { it.text in listOf("OK", "ОК") }.doClick()
                        } catch (e: Throwable) { failure = e; dialog.dispose() }
                    }
                }
                answer.start()
                try { entry.insertCatalogue() } finally { answer.stop() }
                failure?.let { throw it }
                assertEquals(4, entry.editor.textPane.selectionStart)
                assertEquals(4, entry.editor.textPane.selectionEnd)
                assertTrue(entry.editor.rich.hasStyle(0, 4, TextStyle.Link))
            }
            key(KeyEvent.VK_X); key(KeyEvent.VK_SPACE); key(KeyEvent.VK_Y); key(KeyEvent.VK_ENTER); key(KeyEvent.VK_Z)
            edt {
                assertTrue(entry.editor.rich.text.length > 9)
                val links = entry.editor.rich.marks.filter { it.style == TextStyle.Link }
                assertEquals(listOf(TextMark(0, 4, TextStyle.Link, ResearchLinks.entry("source"))), links)
                assertEquals(BlockStyle.Paragraph, entry.editor.rich.blocks.last().style)
                entry.editor.textPane.select(0, 4)
                listOf("Жирный · Ctrl+B", "Курсив · Ctrl+I", "Подчёркивание · Ctrl+U").forEach { name ->
                    toggle(name).doClick(); assertTrue(toggle(name).isSelected)
                }
                app.rootPane.paintImmediately(0, 0, app.rootPane.width, app.rootPane.height)
                val screenshots = File("build/screenshots").apply { mkdirs() }
                ImageIO.write(robot.createScreenCapture(Rectangle(app.locationOnScreen, app.size)), "png", File(screenshots, "editor-active-formatting.png"))
                entry.save()
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
