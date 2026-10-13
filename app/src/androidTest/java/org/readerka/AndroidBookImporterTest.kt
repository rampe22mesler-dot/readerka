package org.readerka

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.importer.BookImporter
import org.readerka.pdf.PdfEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidBookImporterTest {
    private val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private fun temporary(block: (File) -> Unit) {
        val root = File(app.cacheDir, "formats-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun zip(file: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() } }
        return file
    }
    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } finally { bitmap.recycle() }
    }
    private fun readText(file: File) = PdfEngine(file, app.cacheDir).use { engine -> (0 until engine.pageCount).joinToString("\n") { engine.text(it).text } }

    @Test fun textEncodingsRtfAndHtmlImportOfflineIntoSearchablePages() = temporary { root ->
        listOf(Charsets.UTF_8, Charsets.UTF_16, Charset.forName("windows-1251")).forEachIndexed { i, charset ->
            val source = File(root, "text$i.txt").apply { writeBytes("Привет, читатель\n".repeat(80).toByteArray(charset)) }
            val pdf = File(root, "text$i.pdf"); BookImporter.convert(source, pdf, app)
            assertTrue(readText(pdf).contains("Привет, читатель"))
        }
        val rtf = File(root, "input.rtf").apply { writeText("{\\rtf1\\ansi Hello \\b reader\\b0\\par Second paragraph}", Charsets.US_ASCII) }
        val rtfPdf = File(root, "rtf.pdf"); BookImporter.convert(rtf, rtfPdf, app); assertTrue(readText(rtfPdf).contains("Hello reader"))
        val html = File(root, "input.html").apply { writeText("<html><head><style>HIDDEN_STYLE</style></head><body><h1>Заголовок</h1><p>Книга &amp; текст</p><script>HIDDEN_SCRIPT</script><img src='https://invalid.example/image.png'></body></html>") }
        val htmlPdf = File(root, "html.pdf"); BookImporter.convert(html, htmlPdf, app)
        assertTrue(readText(htmlPdf).contains("Книга & текст")); assertFalse(readText(htmlPdf).contains("HIDDEN"))
    }
    @Test fun mixedCyrillicCjkAndSupplementaryTextRemainSearchable() = temporary { root ->
        val original = "Русский текст 中文 日本語 한국어 😀"
        val source = File(root, "unicode.txt").apply { writeText(original) }
        val pdf = File(root, "unicode.pdf"); BookImporter.convert(source, pdf, app)
        val extracted = readText(pdf)
        File(app.filesDir, "unicode-diagnostic.pdf").apply { pdf.copyTo(this, overwrite = true) }
        File(app.filesDir, "unicode-diagnostic.txt").writeText(extracted)
        assertTrue("Extracted: $extracted", extracted.contains(original))
        PdfEngine(pdf, app.cacheDir).use { engine ->
            val text = engine.text(0)
            assertTrue(text.matches("中文").isNotEmpty())
            assertTrue(text.matches("日本語").isNotEmpty())
            assertTrue(text.matches("한국어").isNotEmpty())
            assertTrue(text.matches("😀").isNotEmpty())
        }
    }
    @Test fun epubFb2CompressedFb2AndDocxPreserveOrderAndMetadata() = temporary { root ->
        val epub = zip(File(root, "book.epub"),
            "META-INF/container.xml" to "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>".toByteArray(),
            "OPS/book.opf" to "<package xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Название EPUB</dc:title><dc:creator>Автор</dc:creator></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>".toByteArray(),
            "OPS/a.xhtml" to "<html><body><p>Вторая глава</p></body></html>".toByteArray(),
            "OPS/b.xhtml" to "<html><body><p>Первая глава</p></body></html>".toByteArray())
        val epubPdf = File(root, "epub.pdf"); val info = BookImporter.convert(epub, epubPdf, app)
        assertEquals("Название EPUB", info.title); assertEquals("Автор", info.authors)
        val text = readText(epubPdf); assertTrue(text.indexOf("Первая") < text.indexOf("Вторая"))
        val fb2 = "<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'><description><title-info><book-title>Название FB2</book-title></title-info></description><body><section><p>Текст книги</p></section></body></FictionBook>".toByteArray()
        listOf(File(root, "book.fb2").apply { writeBytes(fb2) }, zip(File(root, "book.fb2.zip"), "book.fb2" to fb2)).forEachIndexed { i, file ->
            val pdf = File(root, "fb2$i.pdf"); assertEquals("Название FB2", BookImporter.convert(file, pdf, app).title); assertTrue(readText(pdf).contains("Текст книги"))
        }
        val docx = zip(File(root, "book.docx"), "word/document.xml" to "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Текст DOCX</w:t></w:r></w:p></w:body></w:document>".toByteArray())
        val docxPdf = File(root, "docx.pdf"); BookImporter.convert(docx, docxPdf, app); assertTrue(readText(docxPdf).contains("Текст DOCX"))
    }
    @Test fun cbzRasterAndMultiPageTiffKeepEveryPageInOrder() = temporary { root ->
        val cbz = zip(File(root, "comic.cbz"), "10.png" to image(Color.BLUE), "2.png" to image(Color.RED))
        val pdf = File(root, "cbz.pdf"); BookImporter.convert(cbz, pdf, app)
        PdfEngine(pdf, app.cacheDir).use { engine ->
            assertEquals(2, engine.pageCount)
            val bitmap = engine.render(0, 300); assertTrue(Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200)
        }
        val png = File(root, "scan.png").apply { writeBytes(image(Color.GREEN)) }
        BookImporter.convert(png, File(root, "image.pdf"), app)
        val tiff = File(root, "book.tiff")
        InstrumentationRegistry.getInstrumentation().context.assets.open("formats/two-pages.tiff").use { input -> tiff.outputStream().use { input.copyTo(it) } }
        val tiffPdf = File(root, "tiff.pdf"); BookImporter.convert(tiff, tiffPdf, app)
        PdfEngine(tiffPdf, app.cacheDir).use { engine ->
            assertEquals(2, engine.pageCount)
            val a = engine.render(0, 300); val b = engine.render(1, 300)
            assertTrue(Color.red(a.getPixel(a.width / 2, a.height / 2)) > 200)
            assertTrue(Color.blue(b.getPixel(b.width / 2, b.height / 2)) > 200)
        }
    }
    @Test fun jpegBmpAndAnimatedGifPreserveRasterAndFrames() = temporary { root ->
        val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val jpeg = File(root, "scan.jpg")
        jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("scan.bmp", "two-frames.gif")) assets.open("formats/$name").use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
        for (name in listOf("scan.jpg", "scan.bmp", "two-frames.gif")) {
            val pdf = File(root, "$name.pdf"); BookImporter.convert(File(root, name), pdf, app)
            PdfEngine(pdf, app.cacheDir).use { engine ->
                assertEquals(if (name.endsWith("gif")) 2 else 1, engine.pageCount)
                val first = engine.render(0, 300)
                assertTrue("First raster must be red: $name", Color.red(first.getPixel(first.width / 2, first.height / 2)) > 200)
                if (engine.pageCount == 2) {
                    val second = engine.render(1, 300)
                    assertTrue(Color.blue(second.getPixel(second.width / 2, second.height / 2)) > 200)
                }
            }
        }
    }
    @Test fun djvuNativeDecoderPreservesAllPagesOcrAndRotation() = temporary { root ->
        for (name in listOf("mixed.djvu", "rotated.djvu")) {
            val source = File(root, name)
            InstrumentationRegistry.getInstrumentation().context.assets.open("formats/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
            val pdf = File(root, "$name.pdf"); BookImporter.convert(source, pdf, app)
            PdfEngine(pdf, app.cacheDir).use { engine ->
                assertEquals(3, engine.pageCount)
                assertTrue(engine.text(0).text.isBlank()); assertTrue(engine.text(2).text.isBlank())
                val text = engine.text(1); assertTrue(text.text.contains("Привет")); assertTrue(text.text.contains("DJVU"))
                val image = engine.render(1, 500)
                assertEquals(name == "rotated.djvu", image.width > image.height)
                val r = text.glyphs.first().rect
                val color = image.getPixel(((r.left + r.right) * .5 * image.width).toInt().coerceIn(0, image.width - 1), ((r.top + r.bottom) * .5 * image.height).toInt().coerceIn(0, image.height - 1))
                assertTrue("OCR must cover the red scan stripe: $name", Color.red(color) > Color.green(color) + 80)
            }
        }
    }
    @Test fun unsafeXmlMissingEpubResourcesAndUnsupportedFormatsFailBeforeLiveMutation() = temporary { root ->
        val xml = File(root, "bad.fb2").apply { writeText("<!DOCTYPE FictionBook [<!ENTITY x SYSTEM 'file:///proc/self/environ'>]><FictionBook><body><p>&x;</p></body></FictionBook>") }
        assertThrows(Exception::class.java) { BookImporter.convert(xml, File(root, "bad.pdf"), app) }
        val unknown = File(root, "bad.exe").apply { writeText("invalid") }
        assertFalse(BookImporter.accepts(unknown))
        assertThrows(Exception::class.java) { BookImporter.convert(unknown, File(root, "unknown.pdf"), app) }
    }
}
