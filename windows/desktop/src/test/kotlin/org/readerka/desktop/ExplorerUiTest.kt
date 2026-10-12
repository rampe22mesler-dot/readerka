package org.readerka.desktop

import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.*
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.tree.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.data.Collection

/** Runs only with Local.ps1 -Action Test -Ui, against a disposable library. */
class ExplorerUiTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun paths(tree: JTree) = (tree.model.root as DefaultMutableTreeNode).preorderEnumeration().toList().map { TreePath((it as DefaultMutableTreeNode).path) }
    private fun path(tree: JTree, id: String, parent: String? = null) = paths(tree).first { p ->
        val n = (p.lastPathComponent as DefaultMutableTreeNode).userObject as? ExplorerNode
        n?.item?.id == id && (parent == null || n.item.parent == parent)
    }
    private fun allComponents(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::allComponents) else emptyList()
    private fun answerDialog(action: () -> Unit, answer: (JDialog) -> Unit) {
        val timer = Timer(120, null)
        var failure: Throwable? = null
        timer.addActionListener {
            val dialog = Window.getWindows().filterIsInstance<JDialog>().lastOrNull { it.isShowing }
            if (dialog != null) {
                timer.stop()
                try { answer(dialog) } catch (e: Throwable) { failure = e; dialog.dispose() }
            }
        }
        timer.start()
        action()
        timer.stop()
        failure?.let { throw it }
    }
    private fun ok(dialog: JDialog) { allComponents(dialog).filterIsInstance<JButton>().first { it.text in listOf("OK", "ОК", "Да", "Yes") }.doClick() }
    private fun capture(app: DesktopWindow, name: String) {
        val dir = java.io.File("build/screenshots").apply { mkdirs() }
        ImageIO.write(Robot().createScreenCapture(app.bounds), "png", java.io.File(dir, name))
    }

    @Test fun explorerActionsKeepDraftsMembershipsAndRestoreTheWorkspace() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("readerka-explorer-ui").toFile()
        var repo = DesktopRepository(directory)
        val session = LibrarySession(repo)
        session.change { it.copy(collections = listOf(Collection("a", "Исследования"), Collection("child", "Источники", "a"), Collection("b", "Архив"), Collection("target", "Назначение")),
            entries = listOf(Entry("note", "Тестовая заметка").withContent(RichText.plain("Сохранённый текст")), Entry("other", "Вторая заметка")),
            memberships = listOf(Membership("child", "note"), Membership("b", "note"))) }
        lateinit var app: DesktopWindow
        val robot = Robot().apply { autoDelay = 65 }
        fun key(code: Int) { robot.keyPress(code); robot.keyRelease(code) }
        try {
            edt {
                FlatLightLaf.setup()
                app = DesktopWindow(session)
                app.setSize(1920, 1040)
                app.setLocation(0, 0)
                app.isVisible = true
                val tree = app.explorer.tree
                assertEquals(0, app.panes[0].tabCount)
                tree.selectionPath = path(tree, "child")
                answerDialog({ app.explorer.createFolder() }) { dialog ->
                    allComponents(dialog).filterIsInstance<JTextField>().single().text = "Вложенная папка"
                    ok(dialog)
                }
                assertEquals("child", session.data.collections.first { it.name == "Вложенная папка" }.parentId)
                tree.selectionPath = path(tree, "child")
                app.explorer.createEntry(EntryKind.Note)
                assertEquals(1, app.panes[0].tabCount)
                assertTrue(session.draft(app.editors.keys.single()).collections.contains("child"))
                app.openEntry("note")
                app.editors.getValue("note").editor.replace(RichText.plain("Несохранённый текст со ссылкой readerka://entry/other").style(0, 12, TextStyle.Bold))
                tree.selectionPath = path(tree, "note", "child")
                tree.scrollPathToVisible(tree.selectionPath)
                tree.requestFocusInWindow()
            }
            robot.waitForIdle()
            key(KeyEvent.VK_F2)
            robot.waitForIdle()
            edt {
                assertTrue(app.explorer.tree.isEditing)
                allComponents(app.explorer.tree).filterIsInstance<JTextField>().single().text = "Renamed"
            }
            key(KeyEvent.VK_ENTER)
            robot.waitForIdle()
            edt {
                assertEquals("renamed", session.data.entries.first { it.id == "note" }.title.lowercase())
                assertTrue(app.editors.getValue("note").editor.rich.text.startsWith("Несохранённый"))
                val tree = app.explorer.tree
                tree.selectionPath = path(tree, "note", "child")
                answerDialog({ app.explorer.chooseDestination(true) }) { dialog ->
                    val picker = allComponents(dialog).filterIsInstance<JTree>().single()
                    val target = paths(picker).first { ((it.lastPathComponent as DefaultMutableTreeNode).userObject as? Place)?.id == "a" }
                    picker.selectionPath = target
                    ok(dialog)
                }
                assertEquals(setOf("a", "b", "child"), session.draft("note").collections)
                tree.selectionPath = path(tree, "note", "child")
                paths(tree).forEach { tree.expandPath(it) }
            }
            robot.waitForIdle()
            lateinit var from: Point
            lateinit var to: Point
            edt {
                val tree = app.explorer.tree
                val a = tree.getPathBounds(path(tree, "note", "child"))!!
                val b = tree.getPathBounds(path(tree, "target"))!!
                from = Point(tree.locationOnScreen.x + a.x + 60, tree.locationOnScreen.y + a.y + a.height / 2)
                to = Point(tree.locationOnScreen.x + b.x + 60, tree.locationOnScreen.y + b.y + b.height / 2)
            }
            robot.mouseMove(from.x, from.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            for (step in 1..25) { robot.mouseMove(from.x + (to.x - from.x) * step / 25, from.y + (to.y - from.y) * step / 25); robot.delay(25) }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            edt {
                assertEquals(setOf("a", "b", "target"), session.draft("note").collections)
                app.editors.getValue("note").save()
                assertEquals(setOf("a", "b", "target"), session.draft("note").collections)
                assertTrue(session.data.entries.first { it.id == "note" }.richText().marks.any { it.style == TextStyle.Bold })
                app.explorer.search.text = "renamed"
                assertTrue(paths(app.explorer.tree).any { ((it.lastPathComponent as DefaultMutableTreeNode).userObject as? ExplorerNode)?.item?.id == "target" })
                assertFalse(paths(app.explorer.tree).any { ((it.lastPathComponent as DefaultMutableTreeNode).userObject as? ExplorerNode)?.item?.id == "other" })
                app.explorer.search.text = ""
                app.explorer.tree.selectionPaths = arrayOf(path(app.explorer.tree, "a"), path(app.explorer.tree, "b"))
                assertEquals(2, app.explorer.selectedItems().size)
                answerDialog({ app.explorer.deleteSelected() }) { ok(it) }
                assertFalse(session.data.collections.any { it.id in setOf("a", "b") })
                assertNull(session.data.collections.first { it.id == "child" }.parentId)
                assertTrue(session.data.entries.any { it.id == "note" })
                app.explorer.tree.selectionPath = path(app.explorer.tree, "note", "target")
                app.captureWorkspace()
            }
            robot.waitForIdle()
            capture(app, "readerka-1920.png")
            edt { app.setSize(760, 640) }
            robot.waitForIdle()
            capture(app, "readerka-narrow.png")
            edt { app.setSize(1320, 880); app.validate(); app.openEntry("other", true); app.validate(); app.captureWorkspace() }
            robot.waitForIdle()
            edt { assertTrue("Split stays balanced", app.panes[0].width.toDouble() / app.panes[1].width in .8..1.2) }
            capture(app, "readerka-split.png")
            edt {
                val left = app.panes[0]
                val header = left.getTabComponentAt(left.indexOfComponent(app.editors.getValue("note")))
                from = Point(header.locationOnScreen.x + 15, header.locationOnScreen.y + 12)
                to = Point(app.panes[1].locationOnScreen.x + 45, app.panes[1].locationOnScreen.y + 16)
            }
            robot.mouseMove(from.x, from.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            for (step in 1..25) { robot.mouseMove(from.x + (to.x - from.x) * step / 25, from.y + (to.y - from.y) * step / 25); robot.delay(25) }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            edt {
                assertEquals(-1, app.panes[0].indexOfComponent(app.editors.getValue("note")))
                assertTrue(app.panes[1].indexOfComponent(app.editors.getValue("note")) >= 0)
                app.captureWorkspace()
            }
            edt { app.editors.values.forEach { it.flush(); it.disposePanel() }; app.dispose() }
            repo.close()
            repo = DesktopRepository(directory)
            edt {
                app = DesktopWindow(LibrarySession(repo))
                app.isVisible = true
                assertTrue(app.session.workspace.split)
                assertTrue(app.panes[1].tabCount > 0)
                assertTrue(app.explorer.selectedItems().any { it.id == "note" && it.parent == "target" })
                assertEquals("renamed", app.editors.getValue("note").title.text.lowercase())
            }
        } finally {
            edt { app.editors.values.forEach { it.disposePanel() }; app.pdfs.values.forEach { it.close() }; app.dispose() }
            repo.close()
            directory.deleteRecursively()
        }
    }
}
