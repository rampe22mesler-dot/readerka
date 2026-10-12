package org.readerka.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter

internal data class HelpTopic(val title: String, val body: String) {
    override fun toString() = title
    val searchable: String = "$title ${body.replace(Regex("<[^>]+>"), " ")}".replace("&nbsp;", " ")
}

internal fun helpEscape(text: String): String = text.replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Local documentation only: opening and searching help never touches the library. */
class HelpPanel(commands: Map<String, DesktopCommand>, libraryPath: String) : JPanel(BorderLayout(0, 10)) {
    val search = JTextField().apply {
        name = "help-search"
        accessibleContext.accessibleName = "Поиск по справке и руководству"
        putClientProperty("JTextField.placeholderText", "Поиск по всем вкладкам справки")
    }
    val tabs = JTabbedPane().apply {
        name = "help-tabs"
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
    }
    private val browsers = listOf(
        HelpBrowser(HelpContent.reference(libraryPath)),
        HelpBrowser(HelpContent.guide()),
        HelpBrowser(HelpContent.shortcuts(commands)),
    )
    private val titles = listOf("Функции", "Практический гайд", "Горячие клавиши")

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(JLabel("Найти:"), BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(button("Сбросить") { search.text = ""; search.requestFocusInWindow() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        browsers.forEachIndexed { i, browser -> tabs.addTab(titles[i], browser) }
        add(tabs, BorderLayout.CENTER)
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
    }

    private fun filter() {
        browsers.forEachIndexed { i, browser ->
            val count = browser.filter(search.text.trim())
            tabs.setTitleAt(i, if (search.text.isBlank()) titles[i] else "${titles[i]} ($count)")
        }
    }
}

private class HelpBrowser(private val topics: List<HelpTopic>) : JPanel(BorderLayout()) {
    private val model = DefaultListModel<HelpTopic>()
    private val navigation = JList(model).apply {
        name = "help-topics"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "Разделы справки"
        fixedCellHeight = 32
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }
    private val page = JEditorPane().apply {
        name = "help-page"
        contentType = "text/html"
        isEditable = false
        accessibleContext.accessibleName = "Содержание раздела справки"
        background = documentColor()
        margin = java.awt.Insets(12, 20, 20, 20)
    }
    private var query = ""
    private var rebuilding = false

    init {
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(navigation).apply { minimumSize = Dimension(150, 100) },
            JScrollPane(page).apply { minimumSize = Dimension(200, 100) },
        ).apply {
            border = BorderFactory.createEmptyBorder()
            dividerLocation = 235
            resizeWeight = 0.0
            dividerSize = 5
        }, BorderLayout.CENTER)
        navigation.addListSelectionListener { if (!it.valueIsAdjusting && !rebuilding) showTopic() }
        filter("")
    }

    fun filter(value: String): Int {
        query = value
        val selected = navigation.selectedValue
        rebuilding = true
        model.clear()
        topics.filter { value.isEmpty() || it.searchable.contains(value, ignoreCase = true) }
            .forEach(model::addElement)
        if (model.size() > 0) {
            val index = (0 until model.size()).firstOrNull { model[it] == selected } ?: 0
            navigation.selectedIndex = index
        }
        rebuilding = false
        showTopic()
        return model.size()
    }

    private fun showTopic() {
        fun css(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)
        val foreground = UIManager.getColor("TextPane.foreground") ?: Color.DARK_GRAY
        val topic = navigation.selectedValue
        val title = topic?.title ?: "Совпадений нет"
        val body = topic?.body ?: "<p>Измените запрос или нажмите «Сбросить». Поиск проверяет названия и текст разделов во всех вкладках.</p>"
        page.text = """
            <html><head><style>
            body { font-family: Segoe UI; font-size: 14pt; color: ${css(foreground)}; }
            h1 { font-size: 20pt; color: ${css(accentColor())}; margin-top: 4px; }
            h2 { font-size: 14pt; margin-top: 20px; }
            p, li { margin-top: 6px; margin-bottom: 10px; }
            td { padding: 7px; vertical-align: top; }
            th { padding: 7px; text-align: left; background-color: ${css(selectionColor())}; }
            </style></head><body><h1>${helpEscape(title)}</h1>$body</body></html>
        """.trimIndent()
        page.caretPosition = 0
        page.highlighter.removeAllHighlights()
        if (query.isNotEmpty() && topic != null) {
            val text = page.document.getText(0, page.document.length)
            val painter = DefaultHighlighter.DefaultHighlightPainter(selectionColor())
            var from = 0
            var firstMatch: Int? = null
            while (from < text.length) {
                val at = text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                if (firstMatch == null) firstMatch = at
                page.highlighter.addHighlight(at, at + query.length, painter)
                from = at + query.length
            }
            firstMatch?.let { page.caretPosition = it }
        }
    }
}
