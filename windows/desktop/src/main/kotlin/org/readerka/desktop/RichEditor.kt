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

    private val scroll = JScrollPane(textPane).apply { border = BorderFactory.createEmptyBorder(); viewport.name = "readerka-document"; viewport.background = documentColor() }
    private val typingOverrides = mutableMapOf<TextStyle, String?>()
    private val previewSections = mutableListOf<Triple<Int, JPanel, RichEditor>>()
    private var applying = false
    private var decorationQueued = false
    private var decorationStart = Int.MAX_VALUE
    private var decorationEnd = 0
    private val undo = ArrayDeque<RichText>()
    private val redo = ArrayDeque<RichText>()
    var dirty = false
        private set

    var preview = false
        private set
    var formattingChanged: () -> Unit = {}
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

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
                        rich = if (inserting)
                            RichTextEditing.type(rich, event.offset, event.offset,
                                textPane.document.getText(event.offset, event.length), typingOverrides)
                        else rich.replace(event.offset, event.offset + event.length, RichText())
                        dirty = true
                        notifyChange()
                        scheduleDecoration(event.offset, if (inserting) event.length else -event.length)
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
                    ) {
                        typingOverrides.clear()
                        formattingChanged()
                    }
                }
            }
        )
        textPane.addCaretListener { if (!applying) notifyChange() }
        val nativeBackspace = textPane.actionMap.get(DefaultEditorKit.deletePrevCharAction)
        textPane.actionMap.put(DefaultEditorKit.insertBreakAction, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (preview) return
                val from = textPane.selectionStart
                val to = textPane.selectionEnd
                val next = RichTextEditing.enter(rich, from, to, typingOverrides)
                val caret = from + if (next.text != rich.text) 1 else 0
                replace(next)
                textPane.caretPosition = caret.coerceAtMost(rich.text.length)
                typingOverrides.remove(TextStyle.Link)
                notifyChange()
            }
        })
        textPane.actionMap.put(DefaultEditorKit.deletePrevCharAction, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (preview) return
                val from = textPane.selectionStart
                if (RichTextEditing.removesListMarker(rich, from, textPane.selectionEnd))
                    replace(rich.paragraph(from, from, BlockStyle.Paragraph))
                else nativeBackspace.actionPerformed(e)
            }
        })
        textPane.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    typingOverrides.clear()
                    formattingChanged()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (preview || DesktopPlatform.isShortcutDown(e)) {
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
        formattingChanged()
    }

    fun isStyleActive(style: TextStyle): Boolean {
        val from = textPane.selectionStart
        val to = textPane.selectionEnd
        return if (from < to) rich.hasStyle(from, to, style)
        else if (typingOverrides.containsKey(style)) typingOverrides[style] != null
        else style in RichTextEditing.typingStyles(rich, from)
    }

    fun isParagraphActive(style: BlockStyle, level: Int? = null): Boolean {
        val block = rich.blockAt(textPane.selectionStart)
        if (textPane.selectionStart == textPane.selectionEnd)
            return block.style == style && (level == null || block.level == level)
        val from = block.start
        val to = if (textPane.selectionEnd > textPane.selectionStart) textPane.selectionEnd - 1 else textPane.selectionStart
        return rich.blocks.asSequence().filter { it.start in from..to }.all { it.style == style && (level == null || it.level == level) }
    }

    fun finishInsertion(position: Int) {
        typingOverrides[TextStyle.Link] = null
        textPane.caretPosition = position.coerceIn(0, rich.text.length)
        textPane.requestFocusInWindow()
        notifyChange()
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
                else RichTextEditing.typingStyles(rich, at)[style]
            typingOverrides[style] = if (existing == null) value else null
        } else replace(rich.style(textPane.selectionStart, textPane.selectionEnd, style, value))
        formattingChanged()
        textPane.requestFocusInWindow()
    }

    fun paragraph(style: BlockStyle, level: Int = 1) {
        if (preview) return
        if (style == BlockStyle.Divider) {
            val at = textPane.selectionEnd
            replace(
                rich.replace(at, at, RichText.plain("\n—\n").paragraph(1, 1, BlockStyle.Divider))
            )
        } else {
            val selectedStyle = if (style in listOf(BlockStyle.Bullet, BlockStyle.Ordered, BlockStyle.Quote, BlockStyle.Code) && isParagraphActive(style))
                BlockStyle.Paragraph else style
            replace(rich.paragraph(textPane.selectionStart, textPane.selectionEnd, selectedStyle, level))
        }
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
        formattingChanged()
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
        if (preview && allowFolding) {
            jump(at)
            previewSections.lastOrNull { it.first <= at }?.let { (start, _, section) ->
                section.textPane.select(at - start, (at + query.length - start).coerceAtMost(section.rich.text.length))
                section.textPane.requestFocusInWindow()
            }
        } else {
            jump(at)
            textPane.select(at, at + query.length)
        }
        return true
    }

    private fun render() {
        decorationStart = Int.MAX_VALUE
        decorationEnd = 0
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
        if (preview && allowFolding) {
            removeAll()
            add(buildPreview(), BorderLayout.CENTER)
            revalidate()
            repaint()
        }
    }

    // Document listeners run under the document's write lock. Coalesce attribute
    // updates after that lock is released, including every paragraph touched.
    private fun scheduleDecoration(offset: Int, delta: Int) {
        decorationStart = minOf(decorationStart, offset)
        decorationEnd = maxOf(offset + delta.coerceAtLeast(0), decorationEnd + delta)
        if (decorationQueued) return
        decorationQueued = true
        SwingUtilities.invokeLater {
            decorationQueued = false
            if (decorationStart != Int.MAX_VALUE) {
                val start = decorationStart.coerceAtMost(textPane.document.length)
                val end = decorationEnd.coerceIn(start, textPane.document.length)
                decorationStart = Int.MAX_VALUE
                decorationEnd = 0
                decorate(start, end)
            }
        }
    }

    private fun decorate(from: Int = 0, to: Int = textPane.document.length) {
        val before = applying
        applying = true
        try {
            val doc = textPane.styledDocument
            val root = doc.defaultRootElement
            val start = root.getElement(root.getElementIndex(from)).startOffset
            val end = root.getElement(root.getElementIndex(to)).endOffset.coerceAtMost(doc.length)
            val base =
                SimpleAttributeSet().apply {
                    StyleConstants.setFontFamily(this, "Segoe UI")
                    StyleConstants.setFontSize(this, 17)
                    StyleConstants.setForeground(this, UIManager.getColor("TextPane.foreground"))
                }
            doc.setCharacterAttributes(start, end - start, base, true)
            val para =
                SimpleAttributeSet().apply {
                    StyleConstants.setSpaceAbove(this, 4f)
                    StyleConstants.setSpaceBelow(this, 8f)
                    StyleConstants.setLeftIndent(this, 0f)
                    StyleConstants.setFirstLineIndent(this, 0f)
                }
            doc.setParagraphAttributes(start, end - start, para, true)
            rich.blocks.forEachIndexed { index, b ->
                if (b.start < start || b.start > end || (b.start == end && end != doc.length)) return@forEachIndexed
                val blockEnd = rich.blocks.getOrNull(index + 1)?.start?.minus(1) ?: rich.text.length
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
                doc.setCharacterAttributes(b.start, (blockEnd - b.start).coerceAtLeast(0), attrs, false)
                if (b.style in listOf(BlockStyle.Quote, BlockStyle.Bullet, BlockStyle.Ordered)) {
                    val a = SimpleAttributeSet()
                    StyleConstants.setLeftIndent(a, 24f)
                    StyleConstants.setFirstLineIndent(a, 0f)
                    doc.setParagraphAttributes(b.start, (blockEnd - b.start).coerceAtLeast(0), a, false)
                }
            }
            rich.marks.forEach { m ->
                val markStart = maxOf(start, m.start)
                val markEnd = minOf(end, m.end)
                if (markStart >= markEnd) return@forEach
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
                doc.setCharacterAttributes(markStart, markEnd - markStart, a, false)
            }
        } finally {
            applying = before
        }
    }
}

private val RICH_FLAVOR =
    DataFlavor("application/x-readerka-richtext;class=java.lang.String", "Readerka rich text")
