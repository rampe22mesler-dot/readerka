package org.readerka.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.readerka.data.Anchor
import org.readerka.data.PageRect
import java.io.Closeable
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

/** All operations are serialized: neither Android's renderer nor PDDocument is thread-safe. */
class PdfEngine(file: File, cache: File) : Closeable {
    private val pdf = PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(cache))
    private val descriptor: ParcelFileDescriptor
    private val renderer: PdfRenderer
    @Volatile private var closed = false
    private val rendererLock = Any()
    private val textLock = Any()
    private val sizes = mutableMapOf<Int, Pair<Int, Int>>()
    private val images = LinkedHashMap<Pair<Int, Int>, Bitmap>(8, .75f, true)
    private var imageBytes = 0L
    private val texts = LinkedHashMap<Int, PageText>(8, .75f, true)
    val pageCount: Int = pdf.numberOfPages
    init {
        try {
            require(pageCount > 0) { "PDF не содержит страниц" }
            require(!pdf.isEncrypted) { "Encrypted PDFs are not supported" }
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try { renderer = PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
        } catch (e: Exception) { pdf.close(); throw e }
    }
    fun dimensions(page: Int): Pair<Int, Int> = synchronized(rendererLock) {
        check(!closed)
        sizes.getOrPut(page) { renderer.openPage(page).use { it.width to it.height } }
    }
    fun render(page: Int, width: Int): Bitmap = synchronized(rendererLock) {
        check(!closed)
        val key = page to width.coerceIn(64, 2200)
        images[key]?.let { return@synchronized it }
        val rendered = renderer.openPage(page).use { p ->
            require(p.width > 0 && p.height > 0) { "Некорректный размер страницы PDF" }
            val ratio = p.height.toDouble() / p.width
            val w = minOf(width.coerceIn(64, 2200), sqrt(4_000_000.0 / ratio).toInt(), (12000 / ratio).toInt()).coerceAtLeast(1)
            val h = (w * ratio).roundToInt().coerceIn(1, 12000)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
        images[key] = rendered
        imageBytes += rendered.allocationByteCount
        val iterator = images.entries.iterator()
        while (imageBytes > 32L * 1024 * 1024 && iterator.hasNext()) {
            imageBytes -= iterator.next().value.allocationByteCount
            // Visible views can still hold this bitmap: let GC reclaim it, never recycle here.
            iterator.remove()
        }
        rendered
    }
    fun text(page: Int): PageText = synchronized(textLock) {
        check(!closed)
        texts[page]?.let { return@synchronized it }
        val pdPage = pdf.getPage(page)
        val rotated = pdPage.rotation % 180 != 0
        val w = if (rotated) pdPage.cropBox.height else pdPage.cropBox.width
        val h = if (rotated) pdPage.cropBox.width else pdPage.cropBox.height
        val buffer = StringBuilder()
        val glyphs = mutableListOf<Glyph>()
        val stripper = object : PDFTextStripper() {
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
                    glyphs += Glyph(start, buffer.length, PageRect(
                        (xs.min() / w).coerceIn(0f, 1f), (ys.min() / h).coerceIn(0f, 1f),
                        (xs.max() / w).coerceIn(0f, 1f), (ys.max() / h).coerceIn(0f, 1f)))
                }
            }
            override fun writeWordSeparator() { buffer.append(' ') }
            override fun writeLineSeparator() { buffer.append('\n') }
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
    override fun close() = synchronized(rendererLock) {
        synchronized(textLock) {
            if (!closed) {
                closed = true; images.clear(); imageBytes = 0; texts.clear(); sizes.clear()
                renderer.close(); descriptor.close(); pdf.close()
            }
        }
    }
}
