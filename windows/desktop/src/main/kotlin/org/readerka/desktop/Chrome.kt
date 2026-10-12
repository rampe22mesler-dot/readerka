package org.readerka.desktop

import java.awt.*
import javax.swing.*

fun darkChrome() = UIManager.getLookAndFeel() is com.formdev.flatlaf.FlatDarkLaf
fun documentColor() = if (darkChrome()) Color(31, 34, 41) else Color.WHITE
fun sidebarColor() = if (darkChrome()) Color(37, 41, 50) else Color(245, 246, 250)
fun chromeLineColor() = if (darkChrome()) Color(54, 60, 73) else Color(226, 229, 237)
fun accentColor() = if (darkChrome()) Color(177, 154, 231) else Color(112, 82, 174)
fun selectionColor() = if (darkChrome()) Color(67, 57, 88) else Color(235, 229, 246)
fun mutedColor() = if (darkChrome()) Color(164, 173, 190) else Color(109, 117, 134)

/** Reapply after every look-and-feel change; Swing otherwise discards these defaults. */
fun configureChrome() {
    val foreground = if (darkChrome()) Color(224, 228, 237) else Color(47, 54, 68)
    val surface = if (darkChrome()) Color(43, 47, 57) else Color(249, 250, 252)
    mapOf<String, Any>(
        "defaultFont" to Font("Segoe UI", Font.PLAIN, 13),
        "control" to sidebarColor(), "text" to documentColor(),
        "Component.arc" to 10, "Button.arc" to 10, "TextComponent.arc" to 10,
        "Component.focusWidth" to 1, "Component.innerFocusWidth" to 0,
        "Component.borderColor" to chromeLineColor(), "Component.focusColor" to accentColor(),
        "Button.background" to surface, "Button.foreground" to foreground,
        "Button.hoverBackground" to selectionColor(), "Button.pressedBackground" to selectionColor(),
        "ToggleButton.background" to surface, "ToggleButton.foreground" to foreground,
        "ToggleButton.selectedBackground" to selectionColor(), "ToggleButton.selectedForeground" to accentColor(),
        "TextField.background" to documentColor(), "TextField.foreground" to foreground,
        "TextPane.background" to documentColor(), "TextPane.foreground" to foreground,
        "TextArea.background" to documentColor(), "TextArea.foreground" to foreground,
        "Label.foreground" to foreground, "Panel.background" to sidebarColor(),
        "Tree.background" to sidebarColor(), "Tree.foreground" to foreground,
        "Tree.selectionBackground" to selectionColor(), "Tree.selectionForeground" to foreground,
        "Tree.selectionInactiveBackground" to selectionColor(), "Tree.selectionInactiveForeground" to foreground,
        "Tree.selectionArc" to 8, "Tree.selectionInsets" to Insets(1, 4, 1, 4),
        "Tree.wideSelection" to true, "Tree.paintSelection" to true,
        "TabbedPane.tabHeight" to 38, "TabbedPane.tabInsets" to Insets(5, 12, 5, 12),
        "TabbedPane.selectedBackground" to documentColor(), "TabbedPane.background" to sidebarColor(),
        "TabbedPane.foreground" to mutedColor(), "TabbedPane.selectedForeground" to foreground,
        "TabbedPane.underlineColor" to accentColor(), "TabbedPane.hoverColor" to selectionColor(),
        "TabbedPane.contentAreaColor" to chromeLineColor(), "TabbedPane.tabSelectionHeight" to 2,
        "TabbedPane.showTabSeparators" to false, "TabbedPane.hasFullBorder" to false,
        "ScrollBar.width" to 10, "ScrollBar.thumbArc" to 999,
        "ScrollBar.thumbInsets" to Insets(2, 2, 2, 2), "ScrollBar.showButtons" to false,
        "ScrollBar.track" to sidebarColor(), "ScrollPane.border" to BorderFactory.createLineBorder(chromeLineColor()),
        "Separator.foreground" to chromeLineColor(), "SplitPane.background" to chromeLineColor(),
        "SplitPaneDivider.gripDotCount" to 0,
        "Table.selectionBackground" to selectionColor(), "Table.selectionForeground" to foreground,
        "List.selectionBackground" to selectionColor(), "List.selectionForeground" to foreground,
    ).forEach { (key, value) ->
        UIManager.put(key, when (value) {
            is Color -> javax.swing.plaf.ColorUIResource(value)
            is Font -> javax.swing.plaf.FontUIResource(value)
            is Insets -> javax.swing.plaf.InsetsUIResource(value.top, value.left, value.bottom, value.right)
            is javax.swing.border.Border -> javax.swing.plaf.BorderUIResource(value)
            else -> value
        })
    }
}
fun applyChromeTheme(component: Component) {
    if (component is JTree && component.getClientProperty("readerka.sidebar") == true) component.background = sidebarColor()
    if (component is MotionToggleButton) component.foreground = if (component.isSelected) accentColor() else UIManager.getColor("Button.foreground")
    when (component.name) {
        "readerka-document" -> component.background = documentColor()
        "readerka-sidebar" -> component.background = sidebarColor()
        "readerka-rail" -> { component.background = sidebarColor(); (component as JComponent).border = BorderFactory.createMatteBorder(0, 0, 0, 1, chromeLineColor()) }
        "readerka-muted" -> component.foreground = mutedColor()
        "readerka-status" -> {
            component.background = sidebarColor(); component.foreground = mutedColor()
            (component as JComponent).border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, chromeLineColor()), BorderFactory.createEmptyBorder(4, 12, 4, 12))
        }
    }
    if (component is Container) component.components.forEach(::applyChromeTheme)
}

/** Small, uniformly stroked icons, independent of fonts and platform glyph substitutions. */
class LineIcon(private val kind: String, private val size: Int = 16) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        g.translate(x, y); g.scale(size / 16.0, size / 16.0)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (!c.isEnabled) mutedColor() else if (c is AbstractButton && (c.model.isSelected || c.model.isRollover)) accentColor() else mutedColor()
        g.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        fun line(a: Int, b: Int, d: Int, e: Int) = g.drawLine(a, b, d, e)
        when (kind) {
            "graph" -> { line(3, 4, 12, 3); line(3, 4, 8, 12); line(12, 3, 8, 12); g.fillOval(1, 2, 4, 4); g.fillOval(10, 1, 4, 4); g.fillOval(6, 10, 4, 4) }
            "folder", "folder-add" -> { g.drawRoundRect(1, 5, 14, 9, 2, 2); line(1, 5, 1, 2); line(1, 2, 6, 2); line(6, 2, 8, 5); if (kind == "folder-add") { line(6, 9, 10, 9); line(8, 7, 8, 11) } }
            "back", "previous" -> { line(10, 3, 5, 8); line(5, 8, 10, 13) }
            "next" -> { line(6, 3, 11, 8); line(11, 8, 6, 13) }
            "search" -> { g.drawOval(2, 1, 9, 9); line(10, 9, 14, 14) }
            "split", "sidebar", "outline" -> { g.drawRoundRect(1, 2, 14, 12, 2, 2); line(if (kind == "outline") 11 else 5, 2, if (kind == "outline") 11 else 5, 14) }
            "close" -> { line(4, 4, 12, 12); line(12, 4, 4, 12) }
            "more" -> { for (a in listOf(3, 7, 11)) g.fillOval(7, a, 2, 2) }
            "sort" -> { line(3, 2, 3, 14); line(1, 4, 3, 2); line(5, 4, 3, 2); line(7, 4, 15, 4); line(7, 8, 13, 8); line(7, 12, 11, 12) }
            "collapse" -> { line(3, 6, 8, 2); line(8, 2, 13, 6); line(3, 10, 8, 14); line(8, 14, 13, 10) }
            "save" -> { g.drawRect(2, 1, 12, 14); g.drawRect(5, 1, 6, 5); g.drawRect(5, 10, 6, 5) }
            "link" -> { g.drawRoundRect(1, 6, 9, 5, 4, 4); g.drawRoundRect(6, 3, 9, 5, 4, 4) }
            "bookmark" -> { line(4, 1, 12, 1); line(4, 1, 4, 15); line(12, 1, 12, 15); line(4, 15, 8, 11); line(8, 11, 12, 15) }
            "undo" -> { g.drawArc(3, 4, 11, 10, -45, 210); line(2, 4, 2, 9); line(2, 4, 7, 4) }
            "redo" -> { g.drawArc(2, 4, 11, 10, 15, 210); line(14, 4, 14, 9); line(14, 4, 9, 4) }
            "read" -> { g.drawRoundRect(1, 2, 14, 12, 2, 2); line(8, 2, 8, 14) }
            "history" -> { g.drawOval(2, 2, 12, 12); line(8, 4, 8, 8); line(8, 8, 11, 10) }
            "settings" -> { g.drawOval(2, 2, 12, 12); g.drawOval(6, 6, 4, 4) }
            "help" -> { g.drawOval(1, 1, 14, 14); g.drawArc(5, 4, 6, 5, 0, 180); line(8, 8, 8, 10); line(8, 12, 8, 12) }
            "import" -> { line(8, 1, 8, 10); line(4, 6, 8, 10); line(12, 6, 8, 10); line(2, 10, 2, 14); line(2, 14, 14, 14); line(14, 14, 14, 10) }
            else -> { g.drawRoundRect(3, 1, 10, 14, 2, 2); line(6, 5, 10, 5); line(6, 8, 10, 8); if (kind == "note-add") { line(7, 10, 11, 10); line(9, 8, 9, 12) } }
        }
        g.dispose()
    }
}

fun iconButton(icon: String, label: String, action: () -> Unit) = MotionButton("", LineIcon(icon)).apply {
    toolTipText = label
    accessibleContext.accessibleName = label
    isFocusable = false
    preferredSize = Dimension(30, 30); minimumSize = preferredSize; maximumSize = preferredSize
    margin = Insets(3, 3, 3, 3)
    putClientProperty("JButton.buttonType", "toolBarButton")
    addActionListener { action() }
}

fun compactBar(vararg components: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
    isOpaque = false
    components.forEach { add(it) }
}

/** Centers a document column, allowing it to shrink in a split or narrow window. */
class DocumentColumn(private val content: Component, private val top: Int = 12) : JPanel(null) {
    init { name = "readerka-document"; background = documentColor(); add(content); minimumSize = Dimension(100, 100) }
    override fun doLayout() {
        val w = (width - 48).coerceAtLeast(80).coerceAtMost(780)
        content.setBounds(((width - w) / 2).coerceAtLeast(0), top, w.coerceAtMost(width), (height - top).coerceAtLeast(0))
    }
    override fun getPreferredSize() = Dimension(800, 600)
}
