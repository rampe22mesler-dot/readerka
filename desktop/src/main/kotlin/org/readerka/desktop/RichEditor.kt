package org.readerka.desktop

import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.*
import javax.swing.text.*
import kotlin.math.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readerka.data.*
import org.readerka.export.BackupArchive

/** StyledDocument is an adapter; the portable RichText remains the source of truth. */
class RichEditor(
    initial: RichText,
    val changed: (RichText, Int, Int) -> Unit,
    val openLink: (String) -> Unit,
    val allowFolding: Boolean = true,
) : JPanel(BorderLayout()) {
    val textPane =
        object : JTextPane() {
            override fun copy() {
                if (selectionStart == selectionEnd) return
                val value = rich.fragment(selectionStart, selectionEnd)
                Toolkit.getDefaultToolkit()
                    .systemClipboard
                    .setContents(
                        object : Transferable {
                            override fun getTransferDataFlavors() =
                                arrayOf(RICH_FLAVOR, DataFlavor.stringFlavor)

                            override fun isDataFlavorSupported(flavor: DataFlavor) =
                                flavor in transferDataFlavors

                            override fun getTransferData(flavor: DataFlavor): Any =
                                when (flavor) {
                                    RICH_FLAVOR -> Json.encodeToString(value)
                                    DataFlavor.stringFlavor -> value.text
                                    else -> throw UnsupportedFlavorException(flavor)
                                }
                        },
                        null,
                    )
            }

            override fun cut() {
                if (!isEditable) return
                val start = selectionStart
                val end = selectionEnd
                if (start == end) return
                copy()
                this@RichEditor.replace(rich.replace(start, end, RichText()))
                caretPosition = start
            }

            override fun paste() {
                if (!isEditable) return
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val value =
                    if (clipboard.isDataFlavorAvailable(RICH_FLAVOR))
                        runCatching {
                                val source = clipboard.getData(RICH_FLAVOR) as String
                                require(source.length <= 16 * 1024 * 1024)
                                val parsed = Json.decodeFromString<RichText>(source)
                                BackupArchive.validate(
                                    LibrarySnapshot(
                                        entries =
                                            listOf(
                                                Entry("clipboard", "Clipboard").withContent(parsed)
                                            )
                                    )
                                )
                                parsed
                            }
                            .getOrNull()
                    else null
                if (value == null) {
                    super.paste()
                    return
                }
                val start = selectionStart
                val end = selectionEnd
                this@RichEditor.replace(rich.replace(start, end, value))
                caretPosition = start + value.text.length
            }

            override fun paintComponent(graphics: Graphics) {
                super.paintComponent(graphics)
                val g = graphics.create() as Graphics2D
                g.color = UIManager.getColor("TextPane.foreground")
                var ordered = 0
                rich.blocks.forEach { block ->
                    if (block.style == BlockStyle.Ordered) ordered++ else ordered = 0
                    if (
                        block.style in
                            listOf(BlockStyle.Bullet, BlockStyle.Ordered, BlockStyle.Quote)
                    )
                        modelToView2D(block.start)?.let { rect ->
                            when (block.style) {
                                BlockStyle.Bullet ->
                                    g.drawString(
                                        "•",
                                        margin.left + 6,
                                        rect.y.toInt() + rect.height.toInt() - 4,
                                    )
                                BlockStyle.Ordered ->
                                    g.drawString(
                                        "$ordered.",
                                        margin.left,
                                        rect.y.toInt() + rect.height.toInt() - 4,
                                    )
                                BlockStyle.Quote -> {
                                    g.color = Color(116, 83, 180)
                                    g.fillRect(
                                        margin.left + 8,
                                        rect.y.toInt(),
                                        3,
                                        rect.height.toInt(),
                                    )
                                    g.color = UIManager.getColor("TextPane.foreground")
                                }
                                else -> {}
                            }
                        }
                }
                g.dispose()
            }
        }
    var rich = initial
        private set

    private val scroll = JScrollPane(textPane)
    private val typingOverrides = mutableMapOf<TextStyle, String?>()
    private val previewSections = mutableListOf<Triple<Int, JPanel, RichEditor>>()
    private var applying = false
    private val undo = ArrayDeque<RichText>()
    private val redo = ArrayDeque<RichText>()
    var dirty = false
        private set

    var preview = false
        private set

    init {
        textPane.margin = Insets(18, 24, 24, 24)
        textPane.font = Font("Segoe UI", Font.PLAIN, 17)
        textPane.accessibleContext.accessibleName = "Текст записи"
        add(scroll, BorderLayout.CENTER)
        textPane.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = edit(e, true)

                override fun removeUpdate(e: DocumentEvent) = edit(e, false)

                override fun changedUpdate(e: DocumentEvent) {}

                fun edit(event: DocumentEvent, inserting: Boolean) {
                    if (!applying) {
                        remember()
                        val before = rich
                        rich = rich.changeText(textPane.text)
                        if (inserting && event.length > 0) {
                            val position = event.offset
                            val inherited =
                                before.marks
                                    .filter { position > it.start && position <= it.end }
                                    .associate { it.style to it.value }
                            val desired = inherited + typingOverrides
                            desired.forEach { (style, value) ->
                                if (value != null) {
                                    if (
                                        rich.marks.none {
                                            it.style == style &&
                                                it.value == value &&
                                                it.start <= position &&
                                                it.end >= position + event.length
                                        }
                                    )
                                        rich =
                                            rich.style(
                                                position,
                                                position + event.length,
                                                style,
                                                value,
                                            )
                                } else {
                                    rich =
                                        rich.copy(
                                            marks =
                                                rich.marks.flatMap { mark ->
                                                    if (
                                                        mark.style != style ||
                                                            mark.end <= position ||
                                                            mark.start >= position + event.length
                                                    )
                                                        listOf(mark)
                                                    else
                                                        listOfNotNull(
                                                            mark.copy(end = position).takeIf {
                                                                it.start < position
                                                            },
                                                            mark
                                                                .copy(
                                                                    start = position + event.length
                                                                )
                                                                .takeIf {
                                                                    it.end > position + event.length
                                                                },
                                                        )
                                                }
                                        )
                                }
                            }
                        }
                        dirty = true
                        notifyChange()
                        SwingUtilities.invokeLater { decorate() }
                    }
                }
            }
        )
        textPane.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (
                        e.keyCode in
                            listOf(
                                KeyEvent.VK_LEFT,
                                KeyEvent.VK_RIGHT,
                                KeyEvent.VK_UP,
                                KeyEvent.VK_DOWN,
                                KeyEvent.VK_HOME,
                                KeyEvent.VK_END,
                            )
                    )
                        typingOverrides.clear()
                }
            }
        )
        textPane.addCaretListener { if (!applying) notifyChange() }
        textPane.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    typingOverrides.clear()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (preview || e.isControlDown) {
                        val at = textPane.viewToModel2D(e.point)
                        val link =
                            rich.marks
                                .firstOrNull {
                                    it.style == TextStyle.Link && at in it.start until it.end
                                }
                                ?.value
                                ?: rich
                                    .blockAt(at)
                                    .takeIf { it.style == BlockStyle.LinkCard }
                                    ?.target
                        link?.let(openLink)
                    }
                }
            }
        )
        textPane.componentPopupMenu =
            JPopupMenu().apply {
                add(
                    JMenuItem("Открыть ссылку").apply {
                        addActionListener {
                            val at = textPane.caretPosition
                            val target =
                                rich.marks
                                    .firstOrNull {
                                        it.style == TextStyle.Link && at in it.start until it.end
                                    }
                                    ?.value ?: rich.blockAt(at).target
                            if (target.isNotBlank()) openLink(target)
                        }
                    }
                )
                add(JMenuItem(DefaultEditorKit.CopyAction()).apply { text = "Копировать" })
                add(JMenuItem(DefaultEditorKit.CutAction()).apply { text = "Вырезать" })
                add(JMenuItem(DefaultEditorKit.PasteAction()).apply { text = "Вставить" })
            }
        render()
    }

    private fun remember() {
        undo.addLast(rich)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
    }

    private fun notifyChange() {
        changed(rich, textPane.selectionStart, textPane.selectionEnd)
    }

    fun replace(value: RichText) {
        if (value == rich) return
        remember()
        rich = value
        dirty = true
        render()
        notifyChange()
    }

    fun style(style: org.readerka.data.TextStyle, value: String = "") {
        if (preview) return
        if (textPane.selectionStart == textPane.selectionEnd) {
            val at = textPane.caretPosition
            val existing =
                if (typingOverrides.containsKey(style)) typingOverrides[style]
                else
                    rich.marks
                        .firstOrNull { it.style == style && at > it.start && at <= it.end }
                        ?.value
            typingOverrides[style] = if (existing == null) value else null
        } else replace(rich.style(textPane.selectionStart, textPane.selectionEnd, style, value))
        textPane.requestFocusInWindow()
    }

    fun paragraph(style: BlockStyle, level: Int = 1) {
        if (preview) return
        if (style == BlockStyle.Divider) {
            val at = textPane.selectionEnd
            replace(
                rich.replace(at, at, RichText.plain("\n—\n").paragraph(1, 1, BlockStyle.Divider))
            )
        } else replace(rich.paragraph(textPane.selectionStart, textPane.selectionEnd, style, level))
        textPane.requestFocusInWindow()
    }

    fun undo() {
        if (undo.isNotEmpty()) {
            redo.addLast(rich)
            rich = undo.removeLast()
            dirty = true
            render()
            notifyChange()
        }
    }

    fun redo() {
        if (redo.isNotEmpty()) {
            undo.addLast(rich)
            rich = redo.removeLast()
            dirty = true
            render()
            notifyChange()
        }
    }

    fun togglePreview() {
        preview = !preview
        textPane.isEditable = !preview
        if (allowFolding) {
            removeAll()
            if (preview) add(buildPreview(), BorderLayout.CENTER)
            else add(scroll, BorderLayout.CENTER)
            revalidate()
            repaint()
        }
    }

    private fun buildPreview(): JScrollPane {
        previewSections.clear()
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val starts =
            (listOf(0) + rich.blocks.filter { it.style == BlockStyle.Heading }.map { it.start })
                .distinct()
                .sorted()
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.minus(1) ?: rich.text.length
            val block = rich.blockAt(start)
            val section =
                RichEditor(rich.fragment(start, end), { _, _, _ -> }, openLink, false).apply {
                    togglePreview()
                    preferredSize =
                        Dimension(
                            600,
                            ((rich.text.length / 60 + rich.blocks.size) * 28 + 45).coerceIn(
                                100,
                                800,
                            ),
                        )
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                }
            val container =
                JPanel(BorderLayout()).apply {
                    alignmentX = LEFT_ALIGNMENT
                    add(section, BorderLayout.CENTER)
                }
            if (block.style == BlockStyle.Heading) {
                val title = rich.text.substring(block.start, rich.blockEnd(block))
                val toggle = JButton("▾ $title")
                toggle.addActionListener {
                    section.isVisible = !section.isVisible
                    toggle.text = (if (section.isVisible) "▾ " else "▸ ") + title
                    container.revalidate()
                }
                container.add(toggle, BorderLayout.NORTH)
            }
            container.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            panel.add(container)
            previewSections += Triple(start, container, section)
        }
        return JScrollPane(panel)
    }

    fun jump(position: Int) {
        if (preview && allowFolding) {
            previewSections
                .lastOrNull { it.first <= position }
                ?.let { (start, container, section) ->
                    section.isVisible = true
                    container.revalidate()
                    section.jump(position - start)
                    container.scrollRectToVisible(container.bounds)
                }
            return
        }
        textPane.requestFocusInWindow()
        textPane.caretPosition = position.coerceIn(0, rich.text.length)
        textPane.modelToView2D(textPane.caretPosition)?.let {
            textPane.scrollRectToVisible(it.bounds)
        }
    }

    fun find(query: String): Boolean {
        if (query.isBlank()) return false
        val from = textPane.selectionEnd
        val at =
            rich.text.indexOf(query, from, true).let {
                if (it < 0) rich.text.indexOf(query, ignoreCase = true) else it
            }
        if (at < 0) return false
        textPane.select(at, at + query.length)
        textPane.requestFocusInWindow()
        return true
    }

    private fun render() {
        val a = textPane.selectionStart
        val b = textPane.selectionEnd
        applying = true
        try {
            textPane.text = rich.text
            decorate()
            textPane.select(a.coerceAtMost(rich.text.length), b.coerceAtMost(rich.text.length))
        } finally {
            applying = false
        }
    }

    private fun decorate() {
        val before = applying
        applying = true
        try {
            val doc = textPane.styledDocument
            val base =
                SimpleAttributeSet().apply {
                    StyleConstants.setFontFamily(this, "Segoe UI")
                    StyleConstants.setFontSize(this, 17)
                    StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground"))
                }
            doc.setCharacterAttributes(0, doc.length, base, true)
            val para =
                SimpleAttributeSet().apply {
                    StyleConstants.setSpaceAbove(this, 4f)
                    StyleConstants.setSpaceBelow(this, 8f)
                    StyleConstants.setLeftIndent(this, 0f)
                    StyleConstants.setFirstLineIndent(this, 0f)
                }
            doc.setParagraphAttributes(0, doc.length, para, true)
            rich.blocks.forEach { b ->
                val end = rich.blockEnd(b)
                val attrs = SimpleAttributeSet()
                when (b.style) {
                    BlockStyle.Heading -> {
                        StyleConstants.setBold(attrs, true)
                        StyleConstants.setFontSize(
                            attrs,
                            when (b.level) {
                                1 -> 28
                                2 -> 24
                                else -> 20
                            },
                        )
                    }
                    BlockStyle.Code -> StyleConstants.setFontFamily(attrs, Font.MONOSPACED)
                    BlockStyle.LinkCard -> {
                        StyleConstants.setForeground(attrs, Color(112, 74, 173))
                        StyleConstants.setUnderline(attrs, true)
                        StyleConstants.setBackground(attrs, Color(232, 226, 246))
                    }
                    else -> {}
                }
                doc.setCharacterAttributes(b.start, (end - b.start).coerceAtLeast(0), attrs, false)
                if (b.style in listOf(BlockStyle.Quote, BlockStyle.Bullet, BlockStyle.Ordered)) {
                    val a = SimpleAttributeSet()
                    StyleConstants.setLeftIndent(a, 24f)
                    StyleConstants.setFirstLineIndent(a, 0f)
                    doc.setParagraphAttributes(b.start, (end - b.start).coerceAtLeast(0), a, false)
                }
            }
            rich.marks.forEach { m ->
                val a = SimpleAttributeSet()
                when (m.style) {
                    org.readerka.data.TextStyle.Bold -> StyleConstants.setBold(a, true)
                    org.readerka.data.TextStyle.Italic -> StyleConstants.setItalic(a, true)
                    org.readerka.data.TextStyle.Underline -> StyleConstants.setUnderline(a, true)
                    org.readerka.data.TextStyle.Link -> {
                        StyleConstants.setUnderline(a, true)
                        StyleConstants.setForeground(a, Color(120, 83, 186))
                    }
                    org.readerka.data.TextStyle.Code ->
                        StyleConstants.setFontFamily(a, Font.MONOSPACED)
                    else -> {}
                }
                doc.setCharacterAttributes(m.start, m.end - m.start, a, false)
            }
        } finally {
            applying = before
        }
    }
}

private val RICH_FLAVOR =
    DataFlavor("application/x-readerka-richtext;class=java.lang.String", "Readerka rich text")
