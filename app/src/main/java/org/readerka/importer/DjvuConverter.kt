package org.readerka.importer

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.*

internal object NativeDjvu {
    init { System.loadLibrary("readerka_djvu") }
    @JvmStatic external fun open(path: String): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun pageCount(handle: Long): Int
    @JvmStatic external fun pageSize(handle: Long, page: Int): IntArray
    @JvmStatic external fun render(handle: Long, page: Int, bitmap: Bitmap)
    @JvmStatic external fun text(handle: Long, page: Int): ByteArray
}

@Serializable private data class OcrWord(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val text: String)
@Serializable private data class OcrPage(val width: Int, val height: Int, val rotation: Int, val words: List<OcrWord>)

internal object DjvuConverter {
    fun convert(source: File, target: File, context: Context) {
        require(source.length() <= 512L * 1024 * 1024) { "DjVu превышает 512 МБ" }
        val handle = NativeDjvu.open(source.absolutePath)
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                val count = NativeDjvu.pageCount(handle)
                require(count in 1..20_000) { "Слишком много страниц DjVu" }
                val fonts = BookFonts(pdf, context)
                for (index in 0 until count) {
                    val size = NativeDjvu.pageSize(handle, index)
                    val scale = minOf(2200.0 / max(size[0], size[1]), sqrt(4_000_000.0 / (size[0].toDouble() * size[1])))
                    val w = (size[0] * scale).roundToInt().coerceAtLeast(1); val h = (size[1] * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        NativeDjvu.render(handle, index, bitmap)
                        val page = PDPage(PDRectangle(w.toFloat(), h.toFloat())); pdf.addPage(page)
                        PDPageContentStream(pdf, page).use { stream ->
                            stream.drawImage(LosslessFactory.createFromImage(pdf, bitmap), 0f, 0f, w.toFloat(), h.toFloat())
                            val ocr = Json.decodeFromString<OcrPage>(String(NativeDjvu.text(handle, index), Charsets.UTF_8))
                            require(ocr.width > 0 && ocr.height > 0 && ocr.rotation in 0..3)
                            val words = ocr.words.filter { it.text.isNotBlank() }
                            if (words.isNotEmpty()) {

                                val pw = w.toFloat(); val ph = h.toFloat()
                                val transform = when (ocr.rotation) {
                                    1 -> Matrix(0f, ph / ocr.width, -pw / ocr.height, 0f, pw, 0f)
                                    2 -> Matrix(-pw / ocr.width, 0f, 0f, -ph / ocr.height, pw, ph)
                                    3 -> Matrix(0f, -ph / ocr.width, pw / ocr.height, 0f, 0f, ph)
                                    else -> Matrix(pw / ocr.width, 0f, 0f, ph / ocr.height, 0f, 0f)
                                }
                                stream.transform(transform)
                                words.forEach { word ->
                                    val text = fonts.printable(word.text).trim()
                                    if (text.isNotEmpty() && word.x1 > word.x0 && word.y1 > word.y0) {
                                        val fontSize = ((word.y1 - word.y0) * .85f).coerceIn(1f, 1000f)
                                        val runs = fonts.runs(text)
                                        val length = runs.sumOf { (font, value) -> (font.getStringWidth(value) * fontSize / 1000).toDouble() }.toFloat()
                                        if (length > 0) {
                                            stream.beginText(); stream.setRenderingMode(RenderingMode.NEITHER)
                                            stream.setHorizontalScaling(((word.x1 - word.x0) / length * 100).coerceIn(1f, 1000f))
                                            stream.newLineAtOffset(word.x0.toFloat(), word.y0.toFloat())
                                            runs.forEach { (font, value) -> stream.setFont(font, fontSize); stream.showText(value) }; stream.endText()
                                        }
                                    }
                                }
                            }
                        }
                    } finally { bitmap.recycle() }
                }
                pdf.documentInformation.creator = "Readerka Android / DjVuLibre 3.5.29"
                pdf.save(target)
            }
        } finally { NativeDjvu.close(handle) }
    }
}
