package org.readerka.importer

import java.io.File
import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory

internal sealed interface BookBlock {
    data class Text(val value: String, val heading: Boolean = false) : BookBlock
    data class Image(val bytes: ByteArray) : BookBlock
    data object Break : BookBlock
}

internal fun bookFont(pdf: PDDocument, context: Context): PDType0Font =
    context.assets.open("fonts/DejaVuSans.ttf").use { PDType0Font.load(pdf, it) }

/** Load the large CJK fallback only when the primary font lacks a glyph. */
internal class BookFonts(private val pdf: PDDocument, private val context: Context) {
    private val primary by lazy { bookFont(pdf, context) }
    private val cjk by lazy { context.assets.open("fonts/Readerka-CJK-Regular.ttf").use { PDType0Font.load(pdf, it) } }
    private fun PDType0Font.accepts(text: String): Boolean = try { encode(text); true } catch (_: IllegalArgumentException) { false }
    fun forText(text: String): PDType0Font = if (primary.accepts(text)) primary else if (cjk.accepts(text)) cjk else primary
    fun runs(text: String): List<Pair<PDType0Font, String>> = buildList {
        var active: PDType0Font? = null; var run = StringBuilder()
        for (point in text.codePoints().toArray()) {
            val glyph = String(Character.toChars(point)); val font = forText(glyph)
            if (font !== active) {
                if (run.isNotEmpty()) add(active!! to run.toString())
                active = font; run = StringBuilder()
            }
            run.append(glyph)
        }
        if (run.isNotEmpty()) add(active!! to run.toString())
    }
    fun printable(text: String): String = buildString {
        text.codePoints().forEach { point ->
            val glyph = String(Character.toChars(point))
            if (!Character.isISOControl(point)) append(glyph.also { require(forText(it).accepts(it)) { "Символ U+${point.toString(16)} не поддерживается шрифтами конвертера" } })
        }
    }
}

/** Stable pagination keeps existing PDF anchors and portable ZIP backups usable. */
internal class BookPdfWriter(private val target: File, private val context: Context) : AutoCloseable {
    private val pdf = PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(target.parentFile))
    private val fonts = BookFonts(pdf, context)
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
        val clean = fonts.printable(value.replace(Regex("\\s+"), " ").trim())
        if (clean.isBlank()) return
        var line = StringBuilder()
        var lineWidth = 0f
        fun emit() {
            if (line.isEmpty()) return
            if (stream == null || y - leading < margin) page()
            y -= leading
            stream!!.apply {
                beginText(); newLineAtOffset(margin, y)
                fonts.runs(line.toString().trimEnd()).forEach { (font, text) -> setFont(font, size); showText(text) }
                endText()
            }
            line = StringBuilder(); lineWidth = 0f
        }
        // Wrap by glyph as well as by word: URLs and unbroken strings cannot overflow.
        for (word in clean.split(' ')) {
            val wordWidth = word.codePoints().toArray().sumOf { point -> val glyph = String(Character.toChars(point)); (fonts.forText(glyph).getStringWidth(glyph) * size / 1000).toDouble() }.toFloat()
            if (line.isNotEmpty() && lineWidth + wordWidth > width) emit()
            for (point in (word + " ").codePoints().toArray()) {
                val s = String(Character.toChars(point))
                val glyphWidth = fonts.forText(s).getStringWidth(s) * size / 1000
                if (lineWidth + glyphWidth > width) emit()
                if (line.isNotEmpty() || s != " ") { line.append(s); lineWidth += glyphWidth }
            }
        }
        emit()
        y -= if (heading) 10f else 6f
    }

    private fun image(bytes: ByteArray) {
        if (bytes.size >= 4 && ((bytes[0] == 73.toByte() && bytes[1] == 73.toByte() && bytes[2] == 42.toByte()) || (bytes[0] == 77.toByte() && bytes[1] == 77.toByte() && bytes[3] == 42.toByte()))) {
            val source = File.createTempFile("readerka-image-", ".tiff", context.cacheDir)
            try { source.writeBytes(bytes); TiffConverter.append(source, this) } finally { source.delete() }
            return
        }
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) {
            val header = com.bumptech.glide.gifdecoder.GifHeaderParser().setData(bytes).parseHeader()
            require(header.status == com.bumptech.glide.gifdecoder.GifDecoder.STATUS_OK && header.numFrames in 1..20_000)
            require(header.width.toLong() * header.height <= 40_000_000) { "Изображение превышает 40 млн пикселей" }
            var sample = 1
            while (header.width.toLong() * header.height / (sample.toLong() * sample) > 4_000_000) sample *= 2
            val provider = object : com.bumptech.glide.gifdecoder.GifDecoder.BitmapProvider {
                override fun obtain(width: Int, height: Int, config: android.graphics.Bitmap.Config) = android.graphics.Bitmap.createBitmap(width, height, config)
                override fun release(bitmap: android.graphics.Bitmap) { bitmap.recycle() }
                override fun obtainByteArray(size: Int) = ByteArray(size)
                override fun release(bytes: ByteArray) {}
                override fun obtainIntArray(size: Int) = IntArray(size)
                override fun release(array: IntArray) {}
            }
            val decoder = com.bumptech.glide.gifdecoder.StandardGifDecoder(provider)
            decoder.setData(header, java.nio.ByteBuffer.wrap(bytes), sample)
            try {
                repeat(header.numFrames) {
                    decoder.advance()
                    val frame = decoder.nextFrame ?: error("Повреждённый GIF")
                    try { append(BookBlock.Break); appendBitmap(frame) } finally { frame.recycle() }
                }
            } finally { decoder.clear() }
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Формат изображения не поддерживается" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "Изображение превышает 40 млн пикселей" }
        val options = BitmapFactory.Options().apply {
            while (bounds.outWidth.toLong() * bounds.outHeight / (inSampleSize.coerceAtLeast(1).toLong() * inSampleSize.coerceAtLeast(1)) > 4_000_000) inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Не удалось прочитать изображение")
        try { appendBitmap(bitmap) } finally { bitmap.recycle() }
    }
    fun appendBitmap(bitmap: android.graphics.Bitmap) {
        val w = bitmap.width; val h = bitmap.height
        val scale = minOf(width / w, (PDRectangle.A4.height - margin * 2) / h)
        val dw = w * scale; val dh = h * scale
        if (stream == null || y - dh < margin) page()
        stream!!.drawImage(LosslessFactory.createFromImage(pdf, bitmap), margin + (width - dw) / 2, y - dh, dw, dh)
        y -= dh + 12f
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
