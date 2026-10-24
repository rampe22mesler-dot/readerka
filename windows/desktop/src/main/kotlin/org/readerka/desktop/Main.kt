package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.io.File
import java.awt.Desktop
import java.awt.event.WindowEvent
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
    var window: DesktopWindow? = null
    val pendingFiles = mutableListOf<File>()
    if (DesktopPlatform.isMac) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "Readerka")
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) desktop.setOpenFileHandler { event ->
            SwingUtilities.invokeLater {
                window?.let { openFiles(it, event.files) } ?: pendingFiles.addAll(event.files)
            }
        }
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response ->
            SwingUtilities.invokeLater {
                window?.let { frame ->
                    frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
                    if (frame.isDisplayable) response.cancelQuit() else response.performQuit()
                } ?: response.cancelQuit()
            }
        }
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler {
            SwingUtilities.invokeLater { window?.commands?.get("settings")?.action?.invoke() }
        }
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler {
            SwingUtilities.invokeLater { JOptionPane.showMessageDialog(window, "Readerka ${ReaderkaVersion.current}", "О Readerka", JOptionPane.INFORMATION_MESSAGE) }
        }
    }
    SwingUtilities.invokeLater {
        try {
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(mapOf("@accentColor" to "#7453b4"))
            val repo = DesktopRepository(directory)
            if (repo.preference("dark") == "true") FlatDarkLaf.setup() else FlatLightLaf.setup()
            configureChrome()
            val frame = DesktopWindow(LibrarySession(repo))
            window = frame
            frame.isVisible = true
            openFiles(frame, args.map(::File) + pendingFiles)
            pendingFiles.clear()
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

private fun openFiles(frame: DesktopWindow, files: List<File>) {
    frame.isVisible = true
    frame.toFront()
    files.filter { it.isFile }.firstOrNull { it.extension.equals("zip", true) && !BookImporter.accepts(it) }
        ?.let { frame.importLibrary(it) }
    files.filter { it.isFile && BookImporter.accepts(it) }.forEach { file ->
        frame.background("Импорт ${file.name}…", { frame.session.importBook(file, null) }) { frame.openPdf(it.id) }
    }
}
