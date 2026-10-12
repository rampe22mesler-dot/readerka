package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.io.File
import javax.swing.*

object ReaderkaVersion {
    val current: String = ReaderkaVersion::class.java.`package`.implementationVersion ?: "development"
}

fun main(args: Array<String>) {
    if ("--version" in args) {
        println("Readerka ${ReaderkaVersion.current}")
        return
    }
    val path = args.indexOf("--data-dir").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val directory = path?.let(::File) ?: DesktopRepository.defaultDirectory()
    SwingUtilities.invokeLater {
        try {
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(mapOf("@accentColor" to "#7453b4"))
            val repo = DesktopRepository(directory)
            if (repo.preference("dark") == "true") FlatDarkLaf.setup() else FlatLightLaf.setup()
            configureChrome()
            val frame = DesktopWindow(LibrarySession(repo))
            frame.isVisible = true
            args
                .map(::File)
                .filter { it.isFile }
                .firstOrNull { it.extension.equals("zip", true) && !BookImporter.accepts(it) }
                ?.let { frame.importLibrary(it) }
            args
                .map(::File)
                .filter { it.isFile && BookImporter.accepts(it) }
                .forEach { file ->
                    frame.background("Импорт ${file.name}…", { frame.session.importBook(file, null) }) {
                        frame.openPdf(it.id)
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching {
                directory.mkdirs()
                java.io.PrintWriter(File(directory, "startup.log")).use { e.printStackTrace(it) }
            }
            JOptionPane.showMessageDialog(
                null,
                e.message ?: e.toString(),
                "Readerka: запуск не выполнен",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }
}
