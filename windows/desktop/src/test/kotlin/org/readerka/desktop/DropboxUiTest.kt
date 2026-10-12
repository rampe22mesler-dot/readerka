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

class DropboxUiTest {
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun waitFor(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            var ready = false
            edt { ready = test() }
            if (ready) return
            Thread.sleep(40)
        }
        fail("Dropbox UI timed out")
    }

    @Test fun cloudButtonsRequireConfirmationAndCancelDoesNotReadCredentialsOrChangeLibrary() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-dropbox-ui").toFile()
        var app: DesktopWindow? = null
        try {
            DesktopRepository(root).use { repo ->
                val original = LibrarySnapshot(entries = listOf(Entry("local", "Местная запись")))
                repo.persist(original)
                repo.persistPreference("dropbox-account", "test@example.org")
                // Deliberately unreadable: a cancelled operation must not load or use these credentials.
                File(root, "dropbox-account.dpapi").writeText("invalid-encrypted-credentials")
                edt {
                    FlatLightLaf.setup(); configureChrome()
                    app = DesktopWindow(LibrarySession(repo)).apply { isVisible = true }
                }
                for (action in listOf("readerka-dropbox-upload", "readerka-dropbox-download")) {
                    edt { SwingUtilities.invokeLater { app!!.transfer() } }
                    waitFor { app!!.ownedWindows.filterIsInstance<JDialog>().any { it.isVisible && it.title == "Синхронизация" } }
                    edt {
                        val dialog = app!!.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible && it.title == "Синхронизация" }
                        all(dialog).filterIsInstance<JTabbedPane>().first().selectedIndex = 1
                        val panel = all(dialog).filterIsInstance<DropboxPanel>().single()
                        assertFalse(all(panel).filterIsInstance<JTextField>().single { it.name == "readerka-dropbox-app-key" }.isEnabled)
                        assertTrue(all(panel).filterIsInstance<AbstractButton>().single { it.name == action }.isEnabled)
                        if (action.endsWith("upload")) {
                            dialog.validate()
                            val image = BufferedImage(dialog.width, dialog.height, BufferedImage.TYPE_INT_RGB)
                            val g = image.createGraphics()
                            try { dialog.printAll(g) } finally { g.dispose() }
                            ImageIO.write(image, "png", File("build/screenshots/dropbox-transfer.png").apply { parentFile.mkdirs() })
                        }
                        SwingUtilities.invokeLater { all(panel).filterIsInstance<AbstractButton>().single { it.name == action }.doClick() }
                    }
                    waitFor { app!!.ownedWindows.filterIsInstance<JDialog>().any { it.isVisible && it.title == "Подтверждение Dropbox" } }
                    edt {
                        val dialog = app!!.ownedWindows.filterIsInstance<JDialog>().single { it.isVisible && it.title == "Подтверждение Dropbox" }
                        assertEquals("Отмена", dialog.rootPane.defaultButton.text)
                        all(dialog).filterIsInstance<AbstractButton>().single { it.text == "Отмена" }.doClick()
                    }
                    waitFor { !app!!.busy && app!!.ownedWindows.none { it.isVisible } }
                    assertEquals(original, repo.snapshot())
                    assertEquals("invalid-encrypted-credentials", File(root, "dropbox-account.dpapi").readText())
                    assertTrue(repo.backups.listFiles()!!.isEmpty())
                }
            }
        } finally {
            edt { app?.let { it.ownedWindows.forEach { dialog -> dialog.dispose() }; it.dispose() } }
            root.deleteRecursively()
        }
    }
}
