package org.readerka.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix

internal object DjvuConverter {
    // App-local x86 CRT files let the decoder run without a system VC++ installation.
    private val nativeFiles = listOf("ddjvu.exe", "djvused.exe", "libdjvulibre.dll", "libjpeg.dll", "libtiff.dll", "libz.dll", "msvcp140.dll", "vcruntime140.dll")

    fun convert(source: File, target: File) {
        require(System.getProperty("os.name").startsWith("Windows")) { "Декодер DJVU этой сборки предназначен для Windows" }
        require(source.length() <= 512L * 1024 * 1024) { "DJVU превышает 512 МБ" }
        val work = Files.createTempDirectory(target.parentFile.toPath(), ".djvu-").toFile()
        try {
            nativeFiles.forEach { name ->
                val resource = javaClass.getResourceAsStream("/native/djvu/$name") ?: error("В сборке отсутствует декодер DJVU: $name")
                resource.use { input -> File(work, name).outputStream().use { input.copyTo(it) } }
            }
            // Relative ASCII arguments work even with Cyrillic source names and library paths.
            source.copyTo(File(work, "source.djvu"))
            run(work, "ddjvu.exe", listOf("-format=pdf", "-size=3000x3000", "source.djvu", "pages.pdf"), "decode.log")
            run(work, "djvused.exe", listOf("-u", "-e", "select; print-txt", "source.djvu"), "text.txt")
            run(work, "djvused.exe", listOf("-u", "-e", "select; size", "source.djvu"), "sizes.txt")
            val rawText = File(work, "text.txt")
            require(rawText.length() <= 64L * 1024 * 1024) { "Текстовый слой DJVU превышает 64 МБ" }
            val pages = parseText(rawText.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            val sizes = Regex("width=(\\d+) height=(\\d+)(?: rotation=(\\d+))?").findAll(File(work, "sizes.txt").readText()).map {
                Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toIntOrNull() ?: 0)
            }.toList()
            PDDocument.load(File(work, "pages.pdf"), MemoryUsageSetting.setupTempFileOnly().setTempDir(work)).use { pdf ->
                require(pages.size == pdf.numberOfPages && sizes.size == pdf.numberOfPages) { "Не совпадают страницы изображения и текста DJVU" }
                var font: PDType0Font? = null
                // Pages without OCR are deliberately left without selectable text.
                pages.forEachIndexed { index, page ->
                    val words = page.leaves().filter { it.text.isNotBlank() }
                    if (words.isEmpty()) return@forEachIndexed
                    val f = font ?: bookFont(pdf).also { font = it }
                    val pdPage = pdf.getPage(index)
                    val (w, h, rotation) = sizes[index]
                    require(w > 0 && h > 0 && rotation in 0..3) { "Некорректные размеры страницы DJVU" }
                    val pw = pdPage.cropBox.width; val ph = pdPage.cropBox.height
                    val transform = when (rotation) {
                        1 -> Matrix(0f, ph / w, -pw / h, 0f, pw, 0f)
                        2 -> Matrix(-pw / w, 0f, 0f, -ph / h, pw, ph)
                        3 -> Matrix(0f, -ph / w, pw / h, 0f, 0f, ph)
                        else -> Matrix(pw / w, 0f, 0f, ph / h, 0f, 0f)
                    }
                    PDPageContentStream(pdf, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                        stream.transform(transform)
                        words.forEach { word ->
                            val text = f.printable(word.text).trim()
                            if (text.isEmpty() || word.x1 <= word.x0 || word.y1 <= word.y0) return@forEach
                            val size = ((word.y1 - word.y0) * .85f).coerceIn(1f, 1000f)
                            val measured = f.getStringWidth(text) * size / 1000
                            if (measured <= 0f) return@forEach
                            stream.beginText(); stream.setFont(f, size)
                            stream.setRenderingMode(RenderingMode.NEITHER)
                            stream.setHorizontalScaling(((word.x1 - word.x0) / measured * 100).coerceIn(1f, 1000f))
                            stream.newLineAtOffset(word.x0.toFloat(), word.y0.toFloat())
                            stream.showText(text); stream.endText()
                        }
                    }
                }
                pdf.documentInformation.creator = "Readerka / DjVuLibre"
                pdf.save(target)
            }
        } finally { work.deleteRecursively() }
    }

    private fun run(work: File, executable: String, args: List<String>, logName: String) {
        val log = File(work, logName)
        val process = ProcessBuilder(listOf(File(work, executable).absolutePath) + args)
            .directory(work).redirectError(File(work, "$logName.err")).redirectOutput(log).start()
        try {
            check(process.waitFor(10, TimeUnit.MINUTES)) { "Превышено время импорта DJVU (10 минут)" }
            if (process.exitValue() != 0) {
                val details = File(work, "$logName.err").inputStream().use { String(it.readNBytes(4096), Charsets.UTF_8) }
                error("Не удалось прочитать DJVU. Нужен целый файл книги со всеми страницами. ${details.trim()}")
            }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    internal data class TextZone(val kind: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val text: String, val children: List<TextZone>) {
        fun leaves(): List<TextZone> = if (text.isNotEmpty()) listOf(this) else children.flatMap { it.leaves() }
    }

    internal fun parseText(source: String): List<TextZone> {
        val tokens = Regex("\\(|\\)|\"(?:\\\\[\\s\\S]|[^\"\\\\])*\"|[^\\s()]+").findAll(source).iterator()
        fun next() = if (tokens.hasNext()) tokens.next().value else error("Повреждённый текстовый слой DJVU")
        fun unquote(token: String): String = buildString {
            var i = 1
            while (i < token.length - 1) {
                val c = token[i++]
                if (c != '\\') append(c)
                else if (i < token.length - 1) {
                    val escaped = token[i++]
                    if (escaped in '0'..'7') {
                        var number = escaped - '0'; var count = 1
                        while (count < 3 && i < token.length - 1 && token[i] in '0'..'7') { number = number * 8 + (token[i++] - '0'); count++ }
                        append(number.toChar())
                    } else append(when (escaped) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> escaped })
                }
            }
        }
        fun zone(depth: Int): TextZone {
            require(depth < 32) { "Слишком сложный текстовый слой DJVU" }
            val kind = next()
            val coordinates = (0 until 4).map { next().toInt() }
            val children = mutableListOf<TextZone>()
            var text = ""
            while (true) {
                val token = next()
                when {
                    token == ")" -> return TextZone(kind, coordinates[0], coordinates[1], coordinates[2], coordinates[3], text, children)
                    token == "(" -> children += zone(depth + 1)
                    token.startsWith('"') -> text = unquote(token)
                    else -> error("Повреждённый текстовый слой DJVU")
                }
            }
        }
        return buildList {
            while (tokens.hasNext()) { require(next() == "("); add(zone(0)) }
        }
    }
}
