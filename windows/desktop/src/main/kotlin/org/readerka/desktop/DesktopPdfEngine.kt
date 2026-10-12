package org.readerka.desktop

import java.io.File
import kotlin.math.*
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.readerka.data.PageRect
import org.readerka.pdf.*

class DesktopPdfEngine(file: File, cache: File = file.parentFile) : AutoCloseable {
    private val pdf =
        PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(cache))
    private var closed = false
    private val texts = LinkedHashMap<Int, PageText>()
    val pageCount: Int
        get() = pdf.numberOfPages

    init {
        if (pdf.numberOfPages == 0) {
            pdf.close()
            error("PDF не содержит страниц")
        }
        if (pdf.isEncrypted) {
            pdf.close()
            error("Защищённые PDF не поддерживаются")
        }
    }

    @Synchronized
    fun render(page: Int, width: Int, height: Int? = null): java.awt.image.BufferedImage {
        check(!closed)
        val p = pdf.getPage(page)
        val w = if (p.rotation % 180 != 0) p.cropBox.height else p.cropBox.width
        val h = if (p.rotation % 180 != 0) p.cropBox.width else p.cropBox.height
        require(w > 0 && h > 0) { "Некорректный размер страницы PDF" }
        val scale = minOf(width.coerceIn(64, 3200) / w,
            height?.coerceAtLeast(64)?.div(h) ?: Float.MAX_VALUE,
            sqrt(12_000_000f / (w * h)))
        val renderer = object : PDFRenderer(pdf) {
            override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer =
                object : PageDrawer(parameters) {
                    override fun showFontGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
                        // Scanned books often have OCR fonts with no drawable glyphs.
                        // Rendering mode 3 neither paints nor clips; text extraction is separate.
                        if (graphicsState.textState.renderingMode != RenderingMode.NEITHER)
                            super.showFontGlyph(matrix, font, code, displacement)
                    }
                }
        }
        // Keep the full decoded scan: JBIG2 subsampling can truncate odd-sized images.
        return renderer.renderImage(page, scale)
    }

    fun text(page: Int): PageText =
        synchronized(this) {
            check(!closed)
            texts[page]?.let {
                return@synchronized it
            }
            val pdPage = pdf.getPage(page)
            val rotated = pdPage.rotation % 180 != 0
            val w = if (rotated) pdPage.cropBox.height else pdPage.cropBox.width
            val h = if (rotated) pdPage.cropBox.width else pdPage.cropBox.height
            val buffer = StringBuilder()
            val glyphs = mutableListOf<Glyph>()
            val stripper =
                object : PDFTextStripper() {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        for (p in positions) {
                            val start = buffer.length
                            buffer.append(p.unicode)
                            val angle = Math.toRadians((pdPage.rotation - p.dir).toDouble())
                            val dx = cos(angle).toFloat() * p.widthDirAdj
                            val dy = sin(angle).toFloat() * p.widthDirAdj
                            val nx = sin(angle).toFloat() * p.heightDir
                            val ny = -cos(angle).toFloat() * p.heightDir
                            val xs = listOf(p.x, p.x + dx, p.x + nx, p.x + dx + nx)
                            val ys = listOf(p.y, p.y + dy, p.y + ny, p.y + dy + ny)
                            glyphs +=
                                Glyph(
                                    start,
                                    buffer.length,
                                    PageRect(
                                        (xs.min() / w).coerceIn(0f, 1f),
                                        (ys.min() / h).coerceIn(0f, 1f),
                                        (xs.max() / w).coerceIn(0f, 1f),
                                        (ys.max() / h).coerceIn(0f, 1f),
                                    ),
                                )
                        }
                    }

                    override fun writeWordSeparator() {
                        buffer.append(' ')
                    }

                    override fun writeLineSeparator() {
                        buffer.append('\n')
                    }
                }
            stripper.sortByPosition = true
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            stripper.getText(pdf)
            val result = PageText(page, buffer.toString(), glyphs)
            texts[page] = result
            if (texts.size > 6) texts.remove(texts.keys.first())
            result
        }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            pdf.close()
            texts.clear()
        }
    }
}
