package org.readerka.importer

import android.graphics.Bitmap
import java.io.File
import kotlin.math.*

internal object NativeTiff {
    init { System.loadLibrary("readerka_djvu") }
    @JvmStatic external fun pageCount(path: String): Int
    @JvmStatic external fun pageSize(path: String, page: Int): IntArray
    @JvmStatic external fun render(path: String, page: Int, bitmap: Bitmap)
}
internal object TiffConverter {
    fun append(source: File, writer: BookPdfWriter) {
        val count = NativeTiff.pageCount(source.absolutePath)
        require(count in 1..20_000) { "Слишком много страниц TIFF" }
        for (page in 0 until count) {
            val size = NativeTiff.pageSize(source.absolutePath, page)
            val scale = minOf(2200.0 / max(size[0], size[1]), sqrt(4_000_000.0 / (size[0].toDouble() * size[1])))
            val bitmap = Bitmap.createBitmap((size[0] * scale).roundToInt().coerceAtLeast(1), (size[1] * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try { NativeTiff.render(source.absolutePath, page, bitmap); writer.append(BookBlock.Break); writer.appendBitmap(bitmap) }
            finally { bitmap.recycle() }
        }
    }
}
