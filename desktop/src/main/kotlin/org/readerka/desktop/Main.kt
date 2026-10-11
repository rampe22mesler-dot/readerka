package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.io.File
import javax.swing.*

fun main(args: Array<String>) {
    val path = args.indexOf("--data-dir").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val directory = path?.let(::File) ?: DesktopRepository.defaultDirectory()
    SwingUtilities.invokeLater {
        try {
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(mapOf("@accentColor" to "#7453b4"))
            val repo = DesktopRepository(directory)
            if (repo.preference("dark") == "true") FlatDarkLaf.setup() else FlatLightLaf.setup()
            UIManager.put("Component.arc", 10)
            UIManager.put("Button.arc", 10)
            UIManager.put("ScrollBar.width", 14)
            UIManager.put("defaultFont", java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 15))
            val frame = DesktopWindow(LibrarySession(repo))
            frame.isVisible = true
            args
                .map(::File)
                .filter { it.isFile }
                .firstOrNull { it.extension.equals("zip", true) }
                ?.let { frame.importLibrary(it) }
            args
                .map(::File)
                .filter { it.isFile && it.extension.equals("pdf", true) }
                .forEach { file ->
                    frame.background("Импорт PDF…", { frame.session.importPdf(file, null) }) {
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
