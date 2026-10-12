package org.readerka.desktop

import java.io.File
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipFile
import javax.swing.text.html.*
import javax.swing.text.html.parser.ParserDelegator
import javax.swing.text.rtf.RTFEditorKit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class ImportedBook(val title: String, val authors: String = "")

/** Imports offline into the existing, portable paginated document model. */
object BookImporter {
    val extensions = arrayOf("pdf", "djvu", "djv", "epub", "fb2", "fb2.zip", "txt", "rtf", "html", "htm", "docx", "cbz", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif")
    const val formats = "PDF, DJVU, EPUB, FB2, TXT, RTF, HTML, DOCX, CBZ, изображения"
    private const val MAX_ENTRY = 64 * 1024 * 1024
    private const val MAX_ARCHIVE = 256L * 1024 * 1024
    private val images = setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif")

    fun accepts(file: File): Boolean = extensions.any { file.name.endsWith(".$it", true) }

    internal fun convert(source: File, target: File): ImportedBook {
        require(source.isFile) { "Файл не найден: ${source.name}" }
        val ext = source.extension.lowercase(Locale.ROOT)
        val title = source.name.removeSuffix(".${source.extension}").let { if (ext == "zip") it.removeSuffix(".fb2") else it }
        if (ext == "pdf") { source.copyTo(target); return ImportedBook(title) }
        if (ext in setOf("djvu", "djv")) { DjvuConverter.convert(source, target); return ImportedBook(title) }
        BookPdfWriter(target).use { writer ->
            val emit: (BookBlock) -> Unit = writer::append
            val info = when {
                ext == "epub" -> Archive(source).use { epub(it, emit) }
                ext == "fb2" -> fb2(read(source), emit)
                ext == "zip" && source.name.endsWith(".fb2.zip", true) -> Archive(source).use { archive ->
                    val entry = archive.names.singleOrNull { it.endsWith(".fb2", true) }
                        ?: error("Архив должен содержать одну книгу FB2")
                    fb2(archive.read(entry), emit)
                }
                ext == "docx" -> Archive(source).use { docx(it, emit) }
                ext == "cbz" -> Archive(source).use { archive ->
                    val names = archive.names.filter { it.substringAfterLast('.').lowercase(Locale.ROOT) in images }
                        .sortedWith { a, b -> naturalCompare(a, b) }
                    require(names.isNotEmpty()) { "CBZ не содержит изображений" }
                    names.forEach { emit(BookBlock.Break); emit(BookBlock.Image(archive.read(it))) }
                    ImportedBook(title)
                }
                ext in images -> { emit(BookBlock.Image(read(source))); ImportedBook(title) }
                ext == "txt" -> { decode(read(source)).lineSequence().forEach { emit(BookBlock.Text(it)) }; ImportedBook(title) }
                ext == "rtf" -> {
                    val kit = RTFEditorKit(); val doc = kit.createDefaultDocument()
                    read(source).inputStream().use { kit.read(it, doc, 0) }
                    doc.getText(0, doc.length).lineSequence().forEach { emit(BookBlock.Text(it)) }
                    ImportedBook(title)
                }
                ext in setOf("html", "htm") -> {
                    html(decode(read(source)), emit) { ref ->
                        val uri = URI(ref.replace(" ", "%20"))
                        if (uri.isAbsolute || uri.rawAuthority != null || uri.path.startsWith('/')) null
                        else {
                            val base = source.absoluteFile.parentFile.canonicalFile
                            val image = File(base, uri.path).canonicalFile
                            if (image.toPath().startsWith(base.toPath()) && image.isFile) read(image) else null
                        }
                    }
                    ImportedBook(title)
                }
                else -> error("Формат .$ext не поддерживается. Доступны: $formats")
            }
            val result = info.copy(title = info.title.ifBlank { title })
            writer.save(result.title, result.authors)
            return result
        }
    }

    private fun read(file: File): ByteArray {
        require(file.length() <= MAX_ENTRY) { "Файл превышает 64 МБ" }
        return file.inputStream().use { bounded(it) }
    }

    private fun bounded(input: java.io.InputStream): ByteArray {
        val bytes = input.readNBytes(MAX_ENTRY + 1)
        require(bytes.size <= MAX_ENTRY) { "Распакованный файл превышает 64 МБ" }
        return bytes
    }

    internal fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) { String(bytes, Charset.forName("windows-1251")) }
    }

    private class Archive(file: File) : AutoCloseable {
        private val zip = ZipFile(file)
        val names: List<String>
        private var total = 0L
        init {
            try {
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 20_000) { "Архив содержит слишком много файлов" }
                names = entries.filterNot { it.isDirectory }.map { it.name }
                require(names.distinct().size == names.size) { "Повторяющиеся имена файлов в архиве" }
            } catch (e: Exception) { zip.close(); throw e }
        }
        fun read(name: String): ByteArray {
            val entry = zip.getEntry(name) ?: error("В книге отсутствует файл: $name")
            require(entry.size <= MAX_ENTRY) { "Файл $name превышает 64 МБ" }
            val bytes = zip.getInputStream(entry).use { bounded(it) }
            total += bytes.size
            require(total <= MAX_ARCHIVE) { "Распакованная книга превышает 256 МБ" }
            return bytes
        }
        override fun close() = zip.close()
    }

    private fun xml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    }

    private fun Element.all(name: String): List<Element> = getElementsByTagNameNS("*", name).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Node.children(): List<Node> = (0 until childNodes.length).map { childNodes.item(it) }

    private fun resolve(base: String, reference: String): String {
        val uri = URI(reference.replace(" ", "%20"))
        require(!uri.isAbsolute && uri.rawAuthority == null) { "Внешние ресурсы книги не поддерживаются" }
        val path = URI(null, null, "/$base", null).resolve(uri).normalize().path.removePrefix("/")
        require(!path.startsWith("../") && !path.contains('\\')) { "Некорректный путь в книге" }
        return path
    }

    private fun epub(archive: Archive, emit: (BookBlock) -> Unit): ImportedBook {
        require("META-INF/encryption.xml" !in archive.names) { "EPUB с DRM или зашифрованными ресурсами не поддерживается" }
        val rootfile = xml(archive.read("META-INF/container.xml")).all("rootfile").firstOrNull()
            ?.getAttribute("full-path") ?: error("EPUB не содержит описания книги")
        val opf = xml(archive.read(rootfile))
        val metadata = opf.all("metadata").firstOrNull()
        val manifest = opf.all("manifest").firstOrNull()?.all("item")?.associateBy { it.getAttribute("id") }.orEmpty()
        val spine = opf.all("spine").firstOrNull()?.all("itemref").orEmpty()
        require(spine.isNotEmpty()) { "EPUB не содержит глав" }
        spine.forEach { item ->
            val resource = manifest[item.getAttribute("idref")] ?: error("В EPUB отсутствует глава")
            val path = resolve(rootfile, resource.getAttribute("href"))
            emit(BookBlock.Break)
            if (resource.getAttribute("media-type").startsWith("image/")) emit(BookBlock.Image(archive.read(path)))
            else {
                require(resource.getAttribute("media-type") in setOf("application/xhtml+xml", "text/html")) { "Неподдерживаемая глава EPUB: $path" }
                // XML decoding honours XHTML's declared encoding; HTML parsing supplies entities and paragraphs.
                val bytes = archive.read(path)
                val encoding = Regex("encoding\\s*=\\s*['\"]([^'\"]+)", RegexOption.IGNORE_CASE).find(String(bytes.take(200).toByteArray(), Charsets.US_ASCII))?.groupValues?.get(1)
                val content = if (encoding == null) decode(bytes) else String(bytes, Charset.forName(encoding))
                html(content, emit) { ref ->
                    if (ref.startsWith("data:") || URI(ref.replace(" ", "%20")).isAbsolute) null
                    else archive.read(resolve(path, ref))
                }
            }
        }
        return ImportedBook(metadata?.all("title")?.firstOrNull()?.textContent.orEmpty(), metadata?.all("creator")?.joinToString(", ") { it.textContent.trim() }.orEmpty())
    }

    private fun html(source: String, emit: (BookBlock) -> Unit, image: (String) -> ByteArray?) {
        val buffer = StringBuilder()
        var hidden = 0
        var heading = false
        fun flush() { if (buffer.isNotBlank()) emit(BookBlock.Text(buffer.toString(), heading)); buffer.setLength(0) }
        val blocks = setOf("p", "div", "section", "article", "li", "blockquote", "pre", "tr", "h1", "h2", "h3", "h4", "h5", "h6")
        ParserDelegator().parse(StringReader(source), object : HTMLEditorKit.ParserCallback() {
            override fun handleStartTag(tag: HTML.Tag, attrs: javax.swing.text.MutableAttributeSet, pos: Int) {
                val name = tag.toString()
                if (name in setOf("head", "script", "style")) hidden++
                if (hidden == 0 && name in blocks) { flush(); heading = name.startsWith("h") && name.length == 2 }
            }
            override fun handleEndTag(tag: HTML.Tag, pos: Int) {
                val name = tag.toString()
                if (hidden == 0 && name in blocks) { flush(); heading = false }
                if (name in setOf("head", "script", "style")) hidden = (hidden - 1).coerceAtLeast(0)
            }
            override fun handleText(data: CharArray, pos: Int) { if (hidden == 0) buffer.append(data).append(' ') }
            override fun handleSimpleTag(tag: HTML.Tag, attrs: javax.swing.text.MutableAttributeSet, pos: Int) {
                if (hidden != 0) return
                if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) flush()
                if (tag == HTML.Tag.IMG) { flush(); attrs.getAttribute(HTML.Attribute.SRC)?.toString()?.let(image)?.let { emit(BookBlock.Image(it)) } }
            }
        }, true)
        flush()
    }

    private fun fb2(bytes: ByteArray, emit: (BookBlock) -> Unit): ImportedBook {
        val root = xml(bytes)
        require(root.localName == "FictionBook") { "Файл не является книгой FB2" }
        val binaries = root.all("binary").associate { it.getAttribute("id") to it.textContent }
        fun walk(node: Node, heading: Boolean = false) {
            if (node !is Element) return
            when (node.localName) {
                "p", "v", "subtitle", "text-author" -> emit(BookBlock.Text(node.textContent, heading || node.localName == "subtitle"))
                "empty-line" -> Unit
                "image" -> {
                    val href = node.getAttributeNS("http://www.w3.org/1999/xlink", "href").ifBlank { node.getAttribute("href") }
                    val data = binaries[href.removePrefix("#")] ?: error("В FB2 отсутствует изображение $href")
                    emit(BookBlock.Image(Base64.getMimeDecoder().decode(data)))
                }
                else -> node.children().forEach { walk(it, heading || node.localName == "title") }
            }
        }
        root.all("body").forEach { emit(BookBlock.Break); walk(it) }
        val info = root.all("title-info").firstOrNull()
        return ImportedBook(info?.all("book-title")?.firstOrNull()?.textContent.orEmpty(), info?.all("author")?.joinToString(", ") { author ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { author.all(it).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() } }.joinToString(" ")
        }.orEmpty())
    }

    private fun docx(archive: Archive, emit: (BookBlock) -> Unit): ImportedBook {
        val root = xml(archive.read("word/document.xml"))
        root.all("p").forEach { p ->
            val text = buildString {
                fun walk(node: Node) {
                    if (node is Element) when (node.localName) {
                        "t" -> append(node.textContent)
                        "tab" -> append(' ')
                        "br", "cr" -> append('\n')
                        else -> node.children().forEach(::walk)
                    }
                }
                walk(p)
            }
            val style = p.all("pStyle").firstOrNull()?.let { it.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val") }.orEmpty()
            text.lineSequence().forEach { emit(BookBlock.Text(it, style.startsWith("Heading", true) || style == "Title")) }
        }
        val core = if ("docProps/core.xml" in archive.names) xml(archive.read("docProps/core.xml")) else null
        return ImportedBook(core?.all("title")?.firstOrNull()?.textContent.orEmpty(), core?.all("creator")?.firstOrNull()?.textContent.orEmpty())
    }

    private fun naturalCompare(a: String, b: String): Int {
        val pattern = Regex("[0-9]+|[^0-9]+")
        val left = pattern.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = pattern.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) java.math.BigInteger(x).compareTo(java.math.BigInteger(y)) else x.compareTo(y)
            if (result != 0) return result
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
