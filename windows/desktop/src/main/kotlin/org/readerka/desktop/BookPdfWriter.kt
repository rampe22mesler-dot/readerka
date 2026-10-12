package org.readerka.desktop

import java.io.File
import java.awt.Color
import javax.imageio.ImageIO
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory

internal sealed interface BookBlock {
    data class Text(val value: String, val heading: Boolean = false) : BookBlock
    data class Image(val bytes: ByteArray) : BookBlock
    data object Break : BookBlock
}

internal fun bookFont(pdf: PDDocument): PDType0Font {
    val windows = File(System.getenv("WINDIR") ?: "C:/Windows", "Fonts/arial.ttf")
    val font = listOf(windows, File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
        .firstOrNull { it.isFile } ?: error("Не найден шрифт Arial для импорта книги")
    return PDType0Font.load(pdf, font)
}

internal fun PDType0Font.printable(value: String): String = buildString {
    value.codePoints().forEach { point ->
        val s = String(Character.toChars(point))
        if (!Character.isISOControl(point)) append(try { encode(s); s } catch (_: IllegalArgumentException) { "?" })
    }
}

/** Stable pagination keeps existing PDF anchors and portable ZIP backups usable. */
internal class BookPdfWriter(private val target: File) : AutoCloseable {
    private val pdf = PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(target.parentFile))
    private val font by lazy { bookFont(pdf) }
    private var stream: PDPageContentStream? = null
    private var y = 0f
    private val margin = 48f
    private val width = PDRectangle.A4.width - margin * 2
    private fun page() {
        require(pdf.numberOfPages < 20_000) { "Книга содержит слишком много страниц" }
        stream?.close()
        val page = PDPage(PDRectangle.A4)
        pdf.addPage(page)
        stream = PDPageContentStream(pdf, page)
        y = PDRectangle.A4.height - margin
    }

    fun append(block: BookBlock) {
        when (block) {
            BookBlock.Break -> if (stream != null) { stream?.close(); stream = null }
            is BookBlock.Image -> image(block.bytes)
            is BookBlock.Text -> paragraph(block.value, block.heading)
        }
    }

    private fun paragraph(value: String, heading: Boolean) {
        val size = if (heading) 17f else 12f
        val leading = size * 1.45f
        val clean = font.printable(value.replace(Regex("\\s+"), " ").trim())
        if (clean.isBlank()) return
        var line = StringBuilder()
        var lineWidth = 0f
        fun emit() {
            if (line.isEmpty()) return
            if (stream == null || y - leading < margin) page()
            y -= leading
            stream!!.apply {
                beginText(); setFont(font, size); newLineAtOffset(margin, y)
                showText(line.toString().trimEnd()); endText()
            }
            line = StringBuilder(); lineWidth = 0f
        }
        // Wrap by glyph as well as by word: URLs and unbroken strings cannot overflow.
        for (word in clean.split(' ')) {
            val wordWidth = font.getStringWidth(word) * size / 1000
            if (line.isNotEmpty() && lineWidth + wordWidth > width) emit()
            for (point in (word + " ").codePoints().toArray()) {
                val s = String(Character.toChars(point))
                val glyphWidth = font.getStringWidth(s) * size / 1000
                if (lineWidth + glyphWidth > width) emit()
                if (line.isNotEmpty() || s != " ") { line.append(s); lineWidth += glyphWidth }
            }
        }
        emit()
        y -= if (heading) 10f else 6f
    }

    private fun image(bytes: ByteArray) {
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            require(input != null) { "Не удалось прочитать изображение" }
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: error("Формат изображения не поддерживается")
            try {
                reader.input = input
                val count = reader.getNumImages(true)
                for (index in 0 until count) {
                    val w = reader.getWidth(index); val h = reader.getHeight(index)
                    require(w.toLong() * h <= 40_000_000) { "Изображение превышает 40 млн пикселей" }
                    val raw = reader.read(index)
                    val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    image.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, w, h); drawImage(raw, 0, 0, null); dispose() }
                    val scale = minOf(width / w, (PDRectangle.A4.height - margin * 2) / h)
                    val dw = w * scale; val dh = h * scale
                    if (stream == null || y - dh < margin) page()
                    stream!!.drawImage(LosslessFactory.createFromImage(pdf, image), margin + (width - dw) / 2, y - dh, dw, dh)
                    y -= dh + 12f
                }
            } finally { reader.dispose() }
        }
    }

    fun save(title: String, authors: String) {
        stream?.close(); stream = null
        require(pdf.numberOfPages > 0) { "Документ не содержит текста или изображений" }
        pdf.documentInformation.title = title
        pdf.documentInformation.author = authors
        pdf.documentInformation.creator = "Readerka"
        pdf.save(target)
    }

    override fun close() { try { stream?.close() } finally { pdf.close() } }
}
