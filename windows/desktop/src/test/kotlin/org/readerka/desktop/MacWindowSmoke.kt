package org.readerka.desktop

import java.awt.Window
import java.awt.event.WindowEvent
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import org.readerka.data.EntryKind

/** Exercises the actual macOS entry point using only an isolated, temporary library. */
object MacWindowSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("readerka-window-smoke-").toFile()
        var result = 0
        try {
            org.readerka.desktop.main(arrayOf("--data-dir", root.path))
            SwingUtilities.invokeAndWait {
                val window = Window.getWindows().filterIsInstance<DesktopWindow>().single { it.isVisible }
                check(window.jMenuBar != null && window.jMenuBar.menuCount > 0)
                check(window.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)[KeyStroke.getKeyStroke("meta S")] == "save")
                val note = window.session.create(EntryKind.Note)
                window.openEntry(note.id)
                window.editors.getValue(note.id).editor.textPane.replaceSelection("Проверка окна macOS")
                window.commands.getValue("save").action()
                check(window.session.repository.snapshot().entries.single().content?.text?.contains("Проверка окна macOS") == true)
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                check(!window.isDisplayable)
            }
            DesktopRepository(root).use { check(it.snapshot().entries.single().content?.text?.contains("Проверка окна macOS") == true) }
            println("macOS window: native menu, Command shortcuts, editing and safe close OK")
        } catch (error: Throwable) {
            error.printStackTrace()
            result = 1
        } finally {
            root.deleteRecursively()
            exitProcess(result)
        }
    }
}
