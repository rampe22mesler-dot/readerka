package org.readerka.desktop

import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class TransferUiTest {
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun waitFor(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            var ready = false
            edt { ready = test() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Transfer UI timed out")
    }
    private fun screenshot(c: Container, name: String) {
        c.validate()
        val image = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { c.printAll(g) } finally { g.dispose() }
        ImageIO.write(image, "png", File("build/screenshots/$name.png").apply { parentFile.mkdirs() })
    }

    @Test fun transferMenuAndImportPreviewCancelThenApplyWithoutRobot() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-transfer-ui").toFile()
        var app: DesktopWindow? = null
        try {
            DesktopRepository(File(root, "local")).use { repo ->
                DesktopRepository(File(root, "other")).use { other ->
                    val original = LibrarySnapshot(entries = listOf(Entry("same", "Местная версия"), Entry("local", "Только здесь")))
                    repo.persist(original)
                    other.persist(original.copy(entries = listOf(original.entries.first().copy(title = "Версия на другом устройстве"), Entry("new", "Новая запись"))))
                    val zip = File(root, "incoming.zip")
                    other.export(zip)
                    edt {
                        FlatLightLaf.setup(); configureChrome()
                        app = DesktopWindow(LibrarySession(repo)).apply { isVisible = true }
                        SwingUtilities.invokeLater { app!!.commands.getValue("transfer").action() }
                    }
                    waitFor { app!!.ownedWindows.filterIsInstance<JDialog>().any { it.isVisible && it.title == "Синхронизация" } }
                    edt {
                        val dialog = app!!.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible && it.title == "Синхронизация" }
                        assertEquals(2, all(dialog).filterIsInstance<AbstractButton>().count { it.name in listOf("readerka-transfer-export", "readerka-transfer-import") })
                        screenshot(dialog, "transfer-center")
                        dialog.dispose()
                        app!!.importLibrary(zip)
                    }
                    waitFor { app!!.ownedWindows.filterIsInstance<JDialog>().any { it.isVisible && it.title == "Сравнение перед импортом" } }
                    edt {
                        val dialog = app!!.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible && it.title == "Сравнение перед импортом" }
                        val summary = all(dialog).filterIsInstance<JTable>().single { it.name == "readerka-transfer-summary" }
                        assertEquals(1, summary.getValueAt(1, 2))
                        assertEquals(1, summary.getValueAt(1, 3))
                        assertEquals(1, summary.getValueAt(1, 4))
                        screenshot(dialog, "transfer-preview")
                        all(dialog).filterIsInstance<AbstractButton>().single { it.text == "Отмена" }.doClick()
                    }
                    waitFor { !app!!.busy }
                    assertEquals(original, repo.snapshot())
                    assertTrue(repo.directory.listFiles()!!.none { it.name.startsWith("import-") })
                    edt { app!!.importLibrary(zip) }
                    waitFor { app!!.ownedWindows.filterIsInstance<JDialog>().any { it.isVisible && it.title == "Сравнение перед импортом" } }
                    edt {
                        val dialog = app!!.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible && it.title == "Сравнение перед импортом" }
                        all(dialog).filterIsInstance<AbstractButton>().single { it.text == "Обновить из архива" }.doClick()
                    }
                    waitFor { repo.snapshot().entries.any { it.id == "new" } && !app!!.busy }
                    edt {
                        app!!.ownedWindows.filterIsInstance<JDialog>().forEach { it.dispose() }
                        assertEquals(3, app!!.session.data.entries.size)
                        assertEquals("Версия на другом устройстве", app!!.session.data.entries.first { it.id == "same" }.title)
                        assertEquals(1, repo.backups.listFiles()!!.count { it.name.startsWith("before-import-") })
                    }
                }
            }
        } finally {
            edt {
                app?.let { window ->
                    window.ownedWindows.forEach { it.dispose() }
                    window.editors.values.forEach { it.disposePanel() }
                    window.dispose()
                }
            }
            root.deleteRecursively()
        }
    }
}
