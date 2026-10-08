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
import kotlin.math.*

data class Glyph(val start: Int, val end: Int, val rect: PageRect)
data class PageText(val page: Int, val text: String, val glyphs: List<Glyph>) {
    fun selection(first: Int, last: Int): Pair<String, Anchor>? {
        if (glyphs.isEmpty()) return null
        val a = min(first, last).coerceIn(glyphs.indices)
        val b = max(first, last).coerceIn(glyphs.indices)
        val start = glyphs[a].start
        val end = glyphs[b].end
        return text.substring(start, end) to Anchor(page, start, end, glyphs.subList(a, b + 1).map { it.rect })
    }
    fun hit(x: Float, y: Float): Int? {
        val exact = glyphs.indexOfFirst { it.rect.contains(x, y) }
        if (exact >= 0) return exact
        return glyphs.indices.minByOrNull {
            val r = glyphs[it].rect
            hypot(x - x.coerceIn(r.left, r.right), y - y.coerceIn(r.top, r.bottom))
        }?.takeIf {
            val r = glyphs[it].rect
            hypot(x - x.coerceIn(r.left, r.right), y - y.coerceIn(r.top, r.bottom)) < .025f
        }
    }
    fun matches(query: String): List<Anchor> {
        if (query.isBlank()) return emptyList()
        val result = mutableListOf<Anchor>()
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            val rects = glyphs.filter { it.end > at && it.start < at + query.length }.map { it.rect }
            if (rects.isNotEmpty()) result += Anchor(page, at, at + query.length, rects)
            from = at + query.length
        }
        return result
    }
}

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
            val w = width.coerceIn(64, 2200)
            val h = (w.toFloat() * p.height / p.width).roundToInt().coerceAtLeast(1)
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
