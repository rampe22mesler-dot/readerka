package org.readerka.desktop

import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.Document
import org.readerka.data.Entry
import org.readerka.data.EntryDraft
import org.readerka.data.ReadingLocation

class PdfViewerTest {
    @Test fun invisibleOcrRemainsSearchableWithoutPaintingGlyphs() {
        val root = Files.createTempDirectory("readerka-ocr").toFile()
        try {
            val source = File(root, "ocr.pdf")
            PDDocument().use { pdf ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use {
                    it.beginText(); it.setFont(PDType1Font.HELVETICA, 24f)
                    it.setRenderingMode(RenderingMode.NEITHER)
                    it.newLineAtOffset(50f, 700f); it.showText("Invisible OCR"); it.endText()
                }
                pdf.save(source)
            }
            DesktopPdfEngine(source, root).use { engine ->
                assertTrue(engine.text(0).text.contains("Invisible OCR"))
                val image = engine.render(0, 400)
                assertTrue((0 until image.height).all { y ->
                    (0 until image.width).all { x -> image.getRGB(x, y) and 0xffffff == 0xffffff }
                })
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun jbig2DecoderIsPackaged() {
        assertTrue("JBIG2 scans must have an ImageIO decoder", ImageIO.getImageReadersByFormatName("JBIG2").hasNext())
    }

    @Test fun fittingRespectsBothDimensionsAndRotation() {
        val root = Files.createTempDirectory("readerka-pdf-fit").toFile()
        try {
            val source = File(root, "pages.pdf")
            PDDocument().use { pdf ->
                pdf.addPage(PDPage(PDRectangle(600f, 1200f)))
                pdf.addPage(PDPage(PDRectangle(600f, 1200f)).apply { rotation = 90 })
                pdf.addPage(PDPage(PDRectangle(200f, 14000f)))
                pdf.save(source)
            }
            DesktopPdfEngine(source, root).use { engine ->
                val portrait = engine.render(0, 800, 600)
                assertTrue(portrait.width <= 800 && portrait.height <= 600)
                assertEquals(2.0, portrait.height.toDouble() / portrait.width, .02)
                val landscape = engine.render(1, 800, 600)
                assertTrue(landscape.width <= 800 && landscape.height <= 600)
                assertEquals(2.0, landscape.width.toDouble() / landscape.height, .02)
                val tall = engine.render(2, 3200)
                assertTrue("Large pages have a bounded raster", tall.width.toLong() * tall.height <= 12_000_000)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun readingPositionPersistsWithoutRefreshingEditorsOrLosingDrafts() {
        val root = Files.createTempDirectory("readerka-pdf-position").toFile()
        try {
            DesktopRepository(root).use { repo ->
                val session = LibrarySession(repo)
                val entry = Entry("note", "Note")
                session.change { it.copy(
                    documents = listOf(Document("book", "Book", "book.pdf", "book.pdf", pageCount = 20)),
                    entries = listOf(entry)) }
                val draft = EntryDraft(entry.copy(comment = "Unsaved edits"))
                session.updateDraft(draft)
                session.setWorkspace(session.workspace.copy(history = session.workspace.history.visit(ReadingLocation("book", 0))))
                var refreshes = 0
                session.onChange = { refreshes++ }
                session.saveReadingPosition("book", 7, 3200)
                assertEquals(0, refreshes)
                assertEquals(7, repo.snapshot().documents.single().lastPage)
                assertEquals(3200, repo.snapshot().documents.single().lastOffset)
                assertEquals(session.workspace.history, repo.workspace().history)
                assertEquals(ReadingLocation("book", 7, 3200), session.workspace.history.current)
                assertEquals(draft, repo.workspace().drafts.single())
                session.saveReadingPosition("missing", 1, 0)
                assertEquals(1, session.data.documents.size)
            }
        } finally { root.deleteRecursively() }
    }
}
