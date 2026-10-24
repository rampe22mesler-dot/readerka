package org.readerka.desktop

import java.io.File
import java.nio.file.Files

/** Invoked with the packaged Java and packaged JARs, so missing runtime modules/natives fail the build. */
object MacReleaseSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("readerka-package-smoke-").toFile()
        try {
            val library = File(root, "Библиотека")
            val text = File(root, "Книга.txt").apply { writeText("Readerka — проверка кириллицы в упакованной Java") }
            DesktopRepository(library).use { repo ->
                val session = LibrarySession(repo)
                val book = session.importBook(text, null)
                DesktopPdfEngine(repo.pdf(book)).use { engine ->
                    check(engine.text(0).text.contains("проверка кириллицы"))
                    check(engine.render(0, 160).width > 0)
                }
                val djvu = session.importBook(File(args.single()), null)
                DesktopPdfEngine(repo.pdf(djvu)).use { engine ->
                    check(engine.pageCount == 3)
                    check(engine.text(1).text.contains("Привет"))
                }
            }
            DesktopRepository(library).use { check(it.snapshot().documents.size == 2) }
            println("Packaged runtime: SQLite, Cyrillic import, PDF rendering and DjVu OK")
        } finally { root.deleteRecursively() }
    }
}
