package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class LibraryGraphUiTest {
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun all(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::all) else emptyList()
    private fun waitFor(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            var ready = false; edt { ready = test() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Graph did not update")
    }
    private fun screenshot(app: DesktopWindow, name: String) {
        app.validate()
        val image = BufferedImage(app.width, app.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); try { app.printAll(g) } finally { g.dispose() }
        ImageIO.write(image, "png", File("build/screenshots/$name.png").apply { parentFile.mkdirs() })
    }
    private fun close(app: DesktopWindow) {
        app.libraryGraph?.disposePanel(); app.editors.values.forEach { it.disposePanel() }
        app.pdfs.values.forEach { it.close() }; app.researchFields.values.forEach { it.disposePanel() }; app.dispose()
    }

    @Test fun graphFiltersNavigationGesturesRefreshAndWorkspaceSurviveRestart() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless())
        val root = Files.createTempDirectory("readerka-graph-ui").toFile()
        val directory = File(root,"library")
        var repo = DesktopRepository(directory)
        var app: DesktopWindow? = null
        lateinit var panel: LibraryGraphPanel
        var savedPositions: Map<String, Any> = emptyMap()
        try {
            val session = LibrarySession(repo); val quotes = researchFixture(session,root)
            session.change { data -> data.copy(tags = listOf(Tag("topic", "Основания знания")),
                entryTags = quotes.map { EntryTag(it.id, "topic") }, documentTags = data.documents.map { DocumentTag(it.id, "topic") },
                collections = listOf(org.readerka.data.Collection("folder", "Эпистемология")), memberships = quotes.map { Membership("folder", it.id) }) }
            val canonical = session.data
            edt {
                FlatLightLaf.setup(); configureChrome()
                app = DesktopWindow(session).apply { setSize(1400,900); isVisible = true }
                app!!.commands.getValue("graph").action()
                panel = app!!.libraryGraph!!
            }
            waitFor { panel.graph.nodes.isNotEmpty() }
            edt {
                assertSame(panel, app!!.openLibraryGraph())
                assertEquals(1, app!!.tabs.values.count { it.kind == WorkspaceKind.LibraryGraph })
                assertEquals(2, panel.graph.nodes.count { it.kind == GraphKind.Author })
                panel.fit(); screenshot(app!!, "library-graph-light")
                panel.select("topic:основания знания")
                all(panel).filterIsInstance<JCheckBox>().single { it.text == "Окрестность" }.doClick()
                assertTrue(panel.visibleGraph.nodes.size < panel.graph.nodes.size)
                assertEquals(5, panel.visibleGraph.nodes.size)
                all(panel).filterIsInstance<JCheckBox>().single { it.text == "Окрестность" }.doClick()
                all(panel).filterIsInstance<JCheckBox>().single { it.text == "Авторы" }.doClick()
                assertFalse(panel.visibleGraph.nodes.any { it.kind == GraphKind.Author })
                all(panel).filterIsInstance<JCheckBox>().single { it.text == "Авторы" }.doClick()
                all(panel).filterIsInstance<JTextField>().single().text = "Hume"
            }
            waitFor { panel.visibleGraph.nodes.size < panel.graph.nodes.size }
            edt { assertTrue(panel.visibleGraph.nodes.any { it.title == "Hume" }); all(panel).filterIsInstance<JTextField>().single().text = "" }
            waitFor { panel.visibleGraph.nodes.size == panel.graph.nodes.size }
            edt {
                // A visible node can be moved in world coordinates at any zoom.
                panel.fit()
                val surface = panel.surface
                val paint = BufferedImage(surface.width,surface.height,BufferedImage.TYPE_INT_RGB)
                val graphics = paint.createGraphics(); surface.printAll(graphics); graphics.dispose()
                val original = panel.positions.mapValues { it.value.clone() }
                val nodePoint = (8 until surface.width step 6).asSequence().flatMap { x ->
                    (8 until surface.height step 6).asSequence().map { y -> Point(x,y) }
                }.first { p -> surface.getToolTipText(MouseEvent(surface,MouseEvent.MOUSE_MOVED,0,0,p.x,p.y,0,false))?.startsWith("${quotes[0].title} · Цитаты") == true }
                surface.dispatchEvent(MouseEvent(surface,MouseEvent.MOUSE_PRESSED,0,0,nodePoint.x,nodePoint.y,1,false,MouseEvent.BUTTON1))
                surface.dispatchEvent(MouseEvent(surface,MouseEvent.MOUSE_DRAGGED,0,InputEvent.BUTTON1_DOWN_MASK,nodePoint.x+45,nodePoint.y+25,1,false,MouseEvent.NOBUTTON))
                surface.dispatchEvent(MouseEvent(surface,MouseEvent.MOUSE_RELEASED,0,0,nodePoint.x+45,nodePoint.y+25,1,false,MouseEvent.BUTTON1))
                assertNotEquals(original.getValue("entry:${quotes[0].id}"), panel.positions.getValue("entry:${quotes[0].id}"))
                val afterDrag = panel.positions.mapValues { it.value.clone() }
                surface.dispatchEvent(MouseEvent(surface,MouseEvent.MOUSE_PRESSED,0,0,8,8,1,false,MouseEvent.BUTTON1))
                surface.dispatchEvent(MouseEvent(surface,MouseEvent.MOUSE_DRAGGED,0,InputEvent.BUTTON1_DOWN_MASK,88,68,1,false,MouseEvent.NOBUTTON))
                surface.dispatchEvent(MouseEvent(surface,MouseEvent.MOUSE_RELEASED,0,0,88,68,1,false,MouseEvent.BUTTON1))
                assertEquals(afterDrag,panel.positions) // Panning never changes node coordinates.
                panel.select("entry:${quotes[0].id}")
                all(panel).filterIsInstance<AbstractButton>().single { it.text == "Открыть материал" }.doClick()
                assertTrue(app!!.editors.containsKey(quotes[0].id))
                assertTrue(app!!.panes[1].selectedComponent is EntryPanel)
                app!!.closeTab(app!!.editors.getValue(quotes[0].id))
                FlatDarkLaf.setup(); configureChrome(); SwingUtilities.updateComponentTreeUI(app!!); applyChromeTheme(app!!); panel.refreshTheme()
                panel.fit(); screenshot(app!!,"library-graph-dark")
                app!!.setSize(760,720); app!!.validate()
            }
            Thread.sleep(200)
            edt {
                panel.fit(); screenshot(app!!,"library-graph-narrow")
                all(panel).filterIsInstance<JCheckBox>().forEach { box ->
                    val bounds = SwingUtilities.convertRectangle(box.parent,box.bounds,panel)
                    assertTrue("Hidden graph filter: ${box.text}", bounds.y >= 0 && bounds.y + bounds.height <= SwingUtilities.convertPoint(panel.surface,0,0,panel).y)
                }
                panel.flush(); savedPositions = panel.positions.mapValues { it.value.clone() }; app!!.captureWorkspace()
            }
            assertEquals(canonical,session.data)
            edt { close(app!!); app = null }; repo.close()
            repo = DesktopRepository(directory)
            val reopened = LibrarySession(repo)
            edt { app = DesktopWindow(reopened).apply { setSize(1400,900); isVisible = true }; panel = app!!.libraryGraph!! }
            waitFor { panel.graph.nodes.isNotEmpty() }
            edt {
                assertEquals(canonical,reopened.data)
                assertEquals(savedPositions,panel.positions)
                assertTrue(reopened.workspace.panes.flatMap { it.tabs }.any { it.kind == WorkspaceKind.LibraryGraph })
                reopened.save(EntryDraft(Entry("new", "Новая заметка")))
            }
            waitFor { "entry:new" in panel.graph.byId }
            edt {
                reopened.change { it.copy(entries = it.entries.filterNot { e -> e.id == "new" }) }
            }
            waitFor { "entry:new" !in panel.graph.byId }
        } finally {
            edt { app?.let(::close) }; repo.close(); root.deleteRecursively()
        }
    }
}
