package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class QuoteEditingUiTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()

    @Test fun quoteTitleTextAndCommentAreIndependentAndSurviveRestart() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("readerka-quote-edit").toFile()
        val repo = DesktopRepository(directory)
        val session = LibrarySession(repo)
        val anchor = Anchor(2, 10, 180, listOf(PageRect(0.1f, 0.2f, 0.9f, 0.3f)))
        val original = "Это длинный фрагмент цитаты из книги, который сохраняется целиком в основном поле редактора и получает сокращённый заголовок."
        session.change { LibrarySnapshot(
            documents = listOf(Document("book", "Источник цитаты", "book.pdf", "book.pdf", pageCount = 3)),
            entries = listOf(Entry("q", quoteTitle(original), quote = original, documentId = "book", anchor = anchor))) }
        var app: DesktopWindow? = null
        try {
            edt {
                FlatDarkLaf.setup()
                val window = DesktopWindow(session).also { app = it }
                window.setSize(1100, 800)
                window.isVisible = true
                window.openEntry("q")
            }
            edt {
                val window = app!!
                val panel = window.editors.getValue("q")
                assertEquals(original, panel.quotation.text)
                assertEquals("", panel.editor.rich.text)
                assertTrue(panel.quotation.isShowing)
                assertTrue(panel.editor.isShowing)
                assertTrue(panel.title.text.endsWith("…"))
                val toolbar = all(panel).filterIsInstance<JPanel>().single { it.accessibleContext.accessibleName == "Редактирование текста" }
                assertTrue(SwingUtilities.convertPoint(toolbar, 0, 0, panel).y > SwingUtilities.convertPoint(panel.quotation, 0, 0, panel).y)
                panel.title.text = "Название вручную"
                panel.quotation.append(" Исправление.")
                panel.quotation.actionMap.get("control Z").actionPerformed(ActionEvent(panel.quotation, 0, ""))
                assertEquals(original, panel.quotation.text)
                panel.quotation.actionMap.get("control shift Z").actionPerformed(ActionEvent(panel.quotation, 0, ""))
                assertEquals(original + " Исправление.", panel.quotation.text)
                panel.editor.replace(RichText.plain("Отдельный комментарий").style(0, 9, TextStyle.Bold))
                panel.flush()
                assertEquals(original + " Исправление.", session.draft("q").entry.quote)
                assertEquals("Название вручную", session.draft("q").entry.title)
                panel.save()
                assertEquals(anchor, session.data.entries.single().anchor)
                val previous = Json.decodeFromString<EntryDraft>(session.data.noteVersions.single().snapshot)
                assertEquals(original, previous.entry.quote)
                assertEquals("", previous.entry.richText().text)
                panel.quotation.append(" Черновик.")
                panel.flush()
                panel.refreshFromSaved()
                assertTrue(panel.quotation.text.endsWith(" Черновик."))
                panel.quotation.actionMap.get("control Z").actionPerformed(ActionEvent(panel.quotation, 0, ""))
                assertTrue("Refresh must reset undo rather than resurrect older text", panel.quotation.text.endsWith(" Черновик."))
                panel.save()
                window.validate()
                val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try { window.paint(graphics) } finally { graphics.dispose() }
                val output = File("build/screenshots/quote-editing.png").apply { parentFile.mkdirs() }
                ImageIO.write(image, "png", output)
                all(panel).filterIsInstance<AbstractButton>().single { it.toolTipText == "Режим чтения" }.doClick()
                assertFalse(panel.quotation.isEditable)
                assertTrue(panel.editor.preview)
                panel.quotation.actionMap.get("control Z").actionPerformed(ActionEvent(panel.quotation, 0, ""))
                assertEquals(original + " Исправление. Черновик.", panel.quotation.text)
                panel.togglePreview()
                assertTrue(panel.quotation.isEditable)
                window.setSize(880, 740)
            }
            // Let Swing recompute wrapping row heights after the window width changes.
            edt { app!!.validate() }
            edt {
                val window = app!!
                val panel = window.editors.getValue("q")
                window.validate()
                assertTrue(panel.quotation.height >= 80)
                assertTrue(panel.editor.height >= 80)
                val toolbar = all(panel).filterIsInstance<JPanel>().single { it.accessibleContext.accessibleName == "Редактирование текста" }
                all(toolbar).filterIsInstance<AbstractButton>().forEach { button ->
                    val at = SwingUtilities.convertPoint(button, 0, 0, toolbar)
                    assertTrue("${button.accessibleContext.accessibleName} must fit the toolbar", at.x >= 0 && at.y >= 0 && at.x + button.width <= toolbar.width && at.y + button.height <= toolbar.height)
                }
                val narrow = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
                val narrowGraphics = narrow.createGraphics()
                try { window.paint(narrowGraphics) } finally { narrowGraphics.dispose() }
                ImageIO.write(narrow, "png", File("build/screenshots/quote-editing-narrow.png"))
                panel.refreshFromSaved()
                assertEquals("Название вручную", panel.title.text)
            }
        } finally {
            edt { app?.let { window ->
                window.editors.values.forEach { it.disposePanel() }
                window.pdfs.values.forEach { it.close() }
                window.dispose()
            } }
            repo.close()
        }
        try {
            DesktopRepository(directory).use { reopened ->
                val entry = reopened.snapshot().entries.single()
                assertEquals("Название вручную", entry.title)
                assertEquals(original + " Исправление. Черновик.", entry.quote)
                assertEquals("Отдельный комментарий", entry.richText().text)
                assertTrue(entry.richText().hasStyle(0, 9, TextStyle.Bold))
                assertEquals(anchor, entry.anchor)
                assertEquals("book", entry.documentId)
            }
        } finally { directory.deleteRecursively() }
    }
}
