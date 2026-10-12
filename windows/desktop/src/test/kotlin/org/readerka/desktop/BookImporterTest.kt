package org.readerka.desktop

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.DocumentFolder

class BookImporterTest {
    private fun temporary(test: (File) -> Unit) {
        val root = Files.createTempDirectory("readerka-formats-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun zip(file: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
        }
        return file
    }
    private fun String.bytes() = toByteArray(Charsets.UTF_8)
    private fun text(pdf: File): String = DesktopPdfEngine(pdf).use { engine ->
        (0 until engine.pageCount).joinToString("\n") { engine.text(it).text }
    }
    private fun image(color: Color, format: String = "png"): ByteArray {
        val image = BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 120, 180); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test fun textEncodingsPaginationAndLongLinesStaySearchable() = temporary { root ->
        val content = "Привет, читатель.\n" + "длинноеслово".repeat(200) + "\n" + "Следующий абзац\n".repeat(140)
        listOf(Charsets.UTF_8, Charsets.UTF_16, Charset.forName("windows-1251")).forEachIndexed { i, charset ->
            val source = File(root, "текст$i.TXT").apply { writeBytes(content.toByteArray(charset)) }
            val output = File(root, "text$i.pdf")
            BookImporter.convert(source, output)
            assertTrue(text(output).contains("Привет, читатель."))
            assertTrue(text(output).contains("Следующий абзац"))
            DesktopPdfEngine(output).use { engine ->
                assertTrue(engine.pageCount > 1)
                assertTrue(engine.text(0).glyphs.isNotEmpty())
            }
        }
    }

    @Test fun fb2AndCompressedFb2IncludeMetadataBodyAndIllustrations() = temporary { root ->
        val cover = java.util.Base64.getEncoder().encodeToString(image(Color.RED))
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Книга</book-title><author><first-name>Имя</first-name><last-name>Фамилия</last-name></author></title-info></description><body><section><title><p>Первая глава</p></title><p>Текст книги</p><image l:href="#cover"/></section></body><binary id="cover" content-type="image/png">$cover</binary></FictionBook>""".bytes()
        val sources = listOf(File(root, "book.fb2").apply { writeBytes(fb2) }, zip(File(root, "book.fb2.zip"), "nested/book.fb2" to fb2))
        sources.forEachIndexed { i, source ->
            val output = File(root, "fb2$i.pdf")
            val info = BookImporter.convert(source, output)
            assertEquals("Книга", info.title); assertEquals("Имя Фамилия", info.authors)
            assertTrue(text(output).contains("Текст книги"))
            DesktopPdfEngine(output).use { engine -> assertTrue(engine.pageCount >= 2) }
        }
    }

    private fun epub(root: File, encrypted: Boolean = false, missing: Boolean = false): File {
        val entries = mutableListOf(
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""".bytes(),
            "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EPUB название</dc:title><dc:creator>Автор</dc:creator></metadata><manifest><item id="a" href="text/first%20chapter.xhtml" media-type="application/xhtml+xml"/><item id="b" href="text/second.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>""".bytes(),
            "OPS/text/first chapter.xhtml" to """<html><head><style>HIDDEN_STYLE</style></head><body><h1>Глава A</h1><p>Альфа &amp; Омега</p><img src="../images/pic.png"/></body></html>""".bytes(),
            "OPS/images/pic.png" to image(Color.BLUE),
        )
        if (!missing) entries += "OPS/text/second.xhtml" to """<html><body><p>Глава B</p></body></html>""".bytes()
        if (encrypted) entries += "META-INF/encryption.xml" to "<encryption/>".bytes()
        return zip(File(root, "book.epub"), *entries.toTypedArray())
    }

    @Test fun epubUsesSpineOrderResolvesImagesAndPreservesMetadata() = temporary { root ->
        val output = File(root, "epub.pdf")
        val info = BookImporter.convert(epub(root), output)
        assertEquals("EPUB название", info.title); assertEquals("Автор", info.authors)
        val text = text(output)
        assertTrue(text.indexOf("Глава B") < text.indexOf("Глава A"))
        assertTrue(text.contains("Альфа & Омега")); assertFalse(text.contains("HIDDEN_STYLE"))
        DesktopPdfEngine(output).use { assertEquals(3, it.pageCount) }
    }

    @Test fun docxImportsParagraphsAndTableCellsWithoutExecutingExternalRelationships() = temporary { root ->
        val source = zip(File(root, "book.docx"),
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Заголовок</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Ячейка таблицы</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""".bytes(),
            "docProps/core.xml" to """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Документ</dc:title><dc:creator>Автор DOCX</dc:creator></cp:coreProperties>""".bytes())
        val output = File(root, "docx.pdf")
        assertEquals("Автор DOCX", BookImporter.convert(source, output).authors)
        assertTrue(text(output).contains("Заголовок")); assertTrue(text(output).contains("Ячейка таблицы"))
    }

    @Test fun htmlAndRtfRemainReadable() = temporary { root ->
        File(root, "picture.png").writeBytes(image(Color.BLUE))
        val html = File(root, "book.html").apply { writeText("<html><head><title>HIDDEN_TITLE</title></head><body><h1>Заголовок</h1><p>Книга &amp; текст</p><script>HIDDEN_SCRIPT</script><img src=\"https://invalid.example/image.png\"><img src=\"picture.png\"></body></html>") }
        val rtf = File(root, "book.rtf").apply { writeText("{\\rtf1\\ansi Hello \\b reader\\b0\\par Second paragraph}", Charsets.US_ASCII) }
        listOf(html, rtf).forEach { source -> BookImporter.convert(source, File(root, "${source.extension}.pdf")) }
        val value = text(File(root, "html.pdf"))
        assertTrue(value.contains("Книга & текст")); assertFalse(value.contains("HIDDEN_"))
        DesktopPdfEngine(File(root, "html.pdf")).use { assertEquals(2, it.pageCount) }
        assertTrue(text(File(root, "rtf.pdf")).contains("Hello reader"))
    }

    @Test fun comicPagesUseNaturalOrderAndRasterFormatsOpen() = temporary { root ->
        val cbz = zip(File(root, "comic.cbz"), "10.png" to image(Color.BLUE), "2.png" to image(Color.RED), "info.txt" to "ignored".bytes())
        val output = File(root, "comic.pdf")
        BookImporter.convert(cbz, output)
        DesktopPdfEngine(output).use { engine ->
            assertEquals(2, engine.pageCount)
            val first = engine.render(0, 300)
            val color = Color(first.getRGB(first.width / 2, first.height / 2))
            assertTrue(color.red > 200 && color.blue < 30)
        }
        listOf("png", "jpg", "bmp", "gif", "tif").forEach { format ->
            val source = File(root, "scan.$format").apply { writeBytes(image(Color.GREEN, format)) }
            val pdf = File(root, "$format.pdf"); BookImporter.convert(source, pdf)
            DesktopPdfEngine(pdf).use { assertEquals(1, it.pageCount) }
        }
    }

    @Test fun djvuHasAllPagesAndOcrStaysOnItsOwnPageWithCorrectCoordinates() = temporary { root ->
        val source = File(root, "Книга со сканами.djvu")
        javaClass.getResourceAsStream("/formats/mixed.djvu")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
        val folder = File(root, "Библиотека").apply { mkdirs() }
        val output = File(folder, "book.pdf")
        BookImporter.convert(source, output)
        DesktopPdfEngine(output).use { engine ->
            assertEquals(3, engine.pageCount)
            assertTrue(engine.text(0).text.isBlank()); assertTrue(engine.text(2).text.isBlank())
            val page = engine.text(1)
            assertTrue(page.text.contains("Привет")); assertTrue(page.text.contains("DJVU"))
            assertTrue(page.glyphs.first().rect.left in .09f.. .12f)
            assertTrue(page.glyphs.first().rect.top in .18f.. .3f)
            val image = engine.render(1, 300)
            assertTrue(image.height > image.width)
            val color = Color(image.getRGB(image.width / 2, (image.height * .23).toInt()))
            assertTrue(color.red > color.green + 80)
        }
        assertTrue(folder.listFiles()!!.none { it.name.startsWith(".djvu-") })
    }

    @Test fun rotatedDjvuOcrFollowsTheVisibleScan() = temporary { root ->
        val source = File(root, "rotated.djvu")
        javaClass.getResourceAsStream("/formats/rotated.djvu")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
        val output = File(root, "book.pdf")
        BookImporter.convert(source, output)
        DesktopPdfEngine(output).use { engine ->
            val page = engine.text(1)
            assertTrue(page.text.contains("Привет"))
            val image = engine.render(1, 500)
            assertTrue(image.width > image.height)
            val r = page.glyphs.first().rect
            val color = Color(image.getRGB(((r.left + r.right) * .5f * image.width).toInt(), ((r.top + r.bottom) * .5f * image.height).toInt()))
            assertTrue("OCR glyph must cover the red stripe after rotation", color.red > color.green + 80)
        }
    }

    @Test fun multiPageTiffKeepsAllFrames() = temporary { root ->
        val source = File(root, "scan.tiff")
        val writer = ImageIO.getImageWritersByFormatName("TIFF").next()
        try {
            ImageIO.createImageOutputStream(source).use { out ->
                writer.output = out; writer.prepareWriteSequence(null)
                listOf(Color.RED, Color.BLUE).forEach { color ->
                    val bitmap = ImageIO.read(image(color).inputStream())
                    writer.writeToSequence(javax.imageio.IIOImage(bitmap, null, null), null)
                }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
        val output = File(root, "scan.pdf"); BookImporter.convert(source, output)
        DesktopPdfEngine(output).use { assertEquals(2, it.pageCount) }
    }

    @Test fun convertedBookAndReadingPositionSurviveReopenAndLibraryZip() = temporary { root ->
        val source = File(root, "book.txt").apply { writeText("Книга для переноса\n".repeat(100)) }
        val library = File(root, "library")
        val backup = File(root, "transfer.zip")
        DesktopRepository(library).use { repo ->
            val session = LibrarySession(repo)
            session.change { it.copy(documentFolders = listOf(DocumentFolder("folder", "Папка"))) }
            val d = session.importBook(source, "folder")
            assertEquals("book.txt", d.originalName); assertTrue(d.storedName.endsWith(".pdf"))
            assertEquals("folder", d.folderId)
            session.saveReadingPosition(d.id, 1, 1200)
            repo.export(backup)
        }
        DesktopRepository(library).use { repo ->
            assertEquals(1, repo.snapshot().documents.single().lastPage)
            val staged = repo.stage(backup)
            try {
                val d = staged.data.documents.single()
                assertEquals(1200, d.lastOffset)
                assertTrue(text(File(staged.directory, "pdfs/${d.storedName}")).contains("Книга для переноса"))
            } finally { staged.directory.deleteRecursively() }
        }
    }

    @Test fun failedImportsLeaveNoDocumentOrPartialPdf() = temporary { root ->
        DesktopRepository(File(root, "library")).use { repo ->
            val session = LibrarySession(repo)
            val cases = listOf(
                File(root, "empty.txt").apply { writeText("") },
                File(root, "bad.djvu").apply { writeText("invalid djvu") },
                File(root, "bad.fb2").apply { writeText("<FictionBook>") },
                File(root, "unsupported.mobi").apply { writeText("unsupported") },
                epub(root, missing = true).copyTo(File(root, "missing.epub")),
                epub(root, encrypted = true).copyTo(File(root, "encrypted.epub")),
            )
            cases.forEach { source ->
                assertThrows(Exception::class.java) { session.importBook(source, null) }
                assertTrue(session.data.documents.isEmpty())
                assertTrue(repo.snapshot().documents.isEmpty())
                assertTrue(repo.pdfDirectory.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun fileMatchingDistinguishesCompressedFb2FromLibraryBackups() {
        assertTrue(BookImporter.accepts(File("КНИГА.FB2.ZIP")))
        assertTrue(BookImporter.accepts(File("book.DJV")))
        assertFalse(BookImporter.accepts(File("library.zip")))
        assertFalse(BookImporter.accepts(File("book.exe")))
    }

    @Test fun xmlDoesNotReadExternalEntities() = temporary { root ->
        val secret = File(root, "secret.txt").apply { writeText("EXTERNAL_SECRET") }
        val source = File(root, "external.fb2").apply { writeText("""<!DOCTYPE FictionBook [<!ENTITY test SYSTEM "${secret.toURI()}">]><FictionBook><body><p>Visible &test;</p></body></FictionBook>""") }
        val output = File(root, "safe.pdf")
        BookImporter.convert(source, output)
        assertTrue(text(output).contains("Visible")); assertFalse(text(output).contains("EXTERNAL_SECRET"))
    }
}
