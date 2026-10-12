package org.readerka.desktop

import java.awt.*
import javax.swing.*
import javax.swing.event.*
import javax.swing.undo.UndoManager
import kotlinx.serialization.json.Json
import org.readerka.data.*

class EntryPanel(val app: DesktopWindow, val id: String) : JPanel(BorderLayout()) {
    private var draft = app.session.draft(id)
    private var updating = false
    private var pending = false
    private var exportedContent = draft.entry.richText()
    private val caption = JLabel(draft.entry.title, SwingConstants.CENTER)
    val title = JTextField(draft.entry.title)
    private val hasQuote = draft.entry.kind == EntryKind.Quote || draft.entry.quote.isNotEmpty()
    val quotation = JTextArea(draft.entry.quote).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font("Segoe UI", Font.PLAIN, 17)
        margin = Insets(18, 24, 24, 24)
        name = "readerka-document"
        background = documentColor()
        accessibleContext.accessibleName = "Текст цитаты"
    }
    private val quoteUndo = UndoManager()
    val editor =
        RichEditor(
            exportedContent,
            { content, a, b ->
                if (!updating) {
                    val textChanged = content != (draft.entry.content ?: exportedContent)
                    if (!textChanged && draft.cursorStart == a && draft.cursorEnd == b && draft.focusedField == "body") return@RichEditor
                    draft =
                        draft.copy(
                            entry = if (textChanged) draft.entry.copy(content = content) else draft.entry,
                            cursorStart = a,
                            cursorEnd = b,
                            focusedField = "body",
                            modifiedAt = if (textChanged) System.currentTimeMillis() else draft.modifiedAt,
                        )
                    markPending()
                }
            },
            app::openLink,
        )
    private val outline = DefaultListModel<TextBlock>()
    private val outlineList = JList(outline)
    private val side = MotionTabbedPane()
    private val documentBody = JPanel(BorderLayout()).apply {
        name = "readerka-document"
        background = documentColor()
        add(title, BorderLayout.NORTH)
        if (!hasQuote) add(editor, BorderLayout.CENTER)
    }
    private val editorSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, DocumentColumn(documentBody, 0), side)
    private var manualOutline = false
    private val timer = Timer(700) { event ->
        if (pending) {
            if (!app.busy) app.safe { flush() }
            if (pending) (event.source as Timer).restart()
        }
    }.apply { isRepeats = false }
    private val autosave =
        Timer(2500) {
            if (
                draft.entry.kind == EntryKind.Consp &&
                    !app.busy && !app.explorer.tree.isEditing &&
                    (pending || draft.entry != app.session.data.entries.firstOrNull { it.id == id })
            )
                app.safe { save() }
        }

    init {
        name = "entry:$id"
        title.font = Font("Segoe UI", Font.BOLD, 24)
        title.accessibleContext.accessibleName = "Название записи"
        title.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = update()

                override fun removeUpdate(e: DocumentEvent) = update()

                override fun changedUpdate(e: DocumentEvent) = update()

                fun update() {
                    if (!updating) {
                        draft =
                            draft.copy(
                                entry = draft.entry.copy(title = title.text),
                                modifiedAt = System.currentTimeMillis(),
                            )
                        caption.text = title.text
                        markPending()
                    }
                }
            }
        )
        title.border = BorderFactory.createEmptyBorder(6, 24, 14, 24)
        title.name = "readerka-document"
        title.background = documentColor()
        if (hasQuote) {
            editor.textPane.accessibleContext.accessibleName = "Комментарий к цитате"
            quotation.document.addUndoableEditListener { quoteUndo.addEdit(it.edit) }
            quotation.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = update()
                override fun removeUpdate(e: DocumentEvent) = update()
                override fun changedUpdate(e: DocumentEvent) = update()
                fun update() {
                    if (!updating) {
                        draft = draft.copy(entry = draft.entry.copy(quote = quotation.text),
                            focusedField = "quote", modifiedAt = System.currentTimeMillis())
                        markPending()
                    }
                }
            })
            quotation.addCaretListener {
                if (!updating && quotation.isFocusOwner) {
                    draft = draft.copy(cursorStart = quotation.selectionStart, cursorEnd = quotation.selectionEnd,
                        focusedField = "quote")
                    markPending()
                }
            }
            fun quoteAction(key: String, action: () -> Unit) {
                quotation.inputMap.put(KeyStroke.getKeyStroke(key), key)
                quotation.actionMap.put(key, object : AbstractAction() {
                    override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
                })
            }
            quoteAction("control Z") { if (quotation.isEditable && quoteUndo.canUndo()) quoteUndo.undo() }
            quoteAction("control Y") { if (quotation.isEditable && quoteUndo.canRedo()) quoteUndo.redo() }
            quoteAction("control shift Z") { if (quotation.isEditable && quoteUndo.canRedo()) quoteUndo.redo() }
            fun label(text: String) = JLabel(text).apply {
                name = "readerka-muted"
                foreground = mutedColor()
                border = BorderFactory.createEmptyBorder(8, 24, 0, 24)
            }
            val quoteBlock = JPanel(BorderLayout()).apply {
                isOpaque = false
                minimumSize = Dimension(100, 100)
                preferredSize = Dimension(700, 320)
                add(label("Цитата"), BorderLayout.NORTH)
                add(JScrollPane(quotation).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
            }
            val commentBlock = JPanel(BorderLayout()).apply {
                isOpaque = false
                minimumSize = Dimension(100, 230)
                preferredSize = Dimension(700, 240)
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(label("Комментарий (необязательно)"), BorderLayout.NORTH)
                    add(editingToolbar(), BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                add(editor, BorderLayout.CENTER)
            }
            documentBody.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, quoteBlock, commentBlock).apply {
                border = BorderFactory.createEmptyBorder()
                resizeWeight = 0.55
                dividerSize = 5
                SwingUtilities.invokeLater { setDividerLocation(0.55) }
            }, BorderLayout.CENTER)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            name = "readerka-document"
            background = documentColor()
            border = BorderFactory.createEmptyBorder(0, 10, 0, 8)
            add(caption.apply { name = "readerka-muted"; foreground = mutedColor() }, BorderLayout.CENTER)
            add(compactBar(
                iconButton("save", "Сохранить · Ctrl+S") { app.safe { save() } },
                iconButton("read", "Режим чтения") { togglePreview() },
                iconButton("outline", "Оглавление и обратные ссылки") { toggleSide() },
                iconButton("more", "Действия записи") {
                    JPopupMenu().apply {
                        add(JMenu("Форматирование").apply { formatMenu().components.toList().forEach { add(it) } })
                        add(JMenuItem("Вставить ссылку · Ctrl+K").apply { addActionListener { link() } })
                        add(JMenuItem("Каталог вставки").apply { addActionListener { insertCatalogue() } })
                        add(JMenuItem("Добавить на исследовательское поле · Ctrl+Alt+L").apply { addActionListener { app.safe { app.addToResearchField(listOf(id)) } } })
                        addSeparator()
                        add(JMenuItem("Свойства").apply { addActionListener { app.safe { app.entryProperties(id) } } })
                        add(JMenuItem("История версий").apply { addActionListener { versions() } })
                    }.show(this@EntryPanel, (this@EntryPanel.width - 180).coerceAtLeast(0), 32)
                }
            ), BorderLayout.EAST)
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar, BorderLayout.NORTH)
            if (!hasQuote) add(editingToolbar(), BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        outlineList.cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    l: JList<*>?,
                    value: Any?,
                    index: Int,
                    selected: Boolean,
                    focus: Boolean,
                ): Component {
                    super.getListCellRendererComponent(l, value, index, selected, focus)
                    val b = value as TextBlock
                    text =
                        "  ".repeat(b.level - 1) +
                            editor.rich.text.substring(b.start, editor.rich.blockEnd(b)).take(70)
                    return this
                }
            }
        outlineList.addListSelectionListener {
            if (!it.valueIsAdjusting) outlineList.selectedValue?.let { editor.jump(it.start) }
        }
        side.addTab("Оглавление", JScrollPane(outlineList))
        side.addTab("Ссылки", backlinks())
        editorSplit.resizeWeight = 1.0
        editorSplit.border = BorderFactory.createEmptyBorder()
        side.minimumSize = Dimension(150, 100)
        side.preferredSize = Dimension(app.session.workspace.structureWidth.toInt(), 400)
        val visible = app.session.repository.preference("note-side-visible") == "true"
        editorSplit.rightComponent = if (visible) side else null
        editorSplit.dividerSize = if (visible) 1 else 0
        editorSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (editorSplit.rightComponent != null && editorSplit.isShowing && side.width >= 150)
                app.session.setWorkspace(app.session.workspace.copy(structureWidth = side.width.toFloat()))
        }
        add(editorSplit, BorderLayout.CENTER)
        if (hasQuote && draft.entry.documentId != null)
            add(
                flow(
                    button("Источник · стр. ${(draft.entry.anchor?.page?:0)+1}") {
                        draft.entry.documentId?.let { app.openPdf(it, draft.entry.anchor?.page) }
                    },
                ),
                BorderLayout.SOUTH,
            )
        val field = draft.focusedField.ifEmpty { app.session.repository.preference("cursor-field:$id") ?: "body" }
        val cursor = if (hasQuote && field == "quote") quotation else editor.textPane
        cursor.select(
            (if (draft.focusedField.isEmpty())
                    app.session.repository.preference("cursor-start:$id")?.toIntOrNull()
                        ?: draft.cursorStart
                else draft.cursorStart)
                .coerceIn(0, cursor.document.length),
            (if (draft.focusedField.isEmpty())
                    app.session.repository.preference("cursor-end:$id")?.toIntOrNull()
                        ?: draft.cursorEnd
                else draft.cursorEnd)
                .coerceIn(0, cursor.document.length),
        )
        updateOutline()
        if (draft.entry.kind == EntryKind.Consp) autosave.start()
    }

    private fun markPending() {
        pending = true
        if (!timer.isRunning) timer.start()
    }

    fun flush() {
        if (app.session.data.entries.none { it.id == id }) return
        val field = when {
            hasQuote && quotation.isFocusOwner -> "quote"
            editor.textPane.isFocusOwner -> "body"
            else -> draft.focusedField.ifEmpty { app.session.repository.preference("cursor-field:$id") ?: "body" }
        }
        val cursor = if (hasQuote && field == "quote") quotation else editor.textPane
        val cursorPreferences = mapOf(
            "cursor-field:$id" to field,
            "cursor-start:$id" to cursor.selectionStart.toString(),
            "cursor-end:$id" to cursor.selectionEnd.toString(),
        )
        if (pending) {
            val contentChanged = editor.rich != exportedContent
            val entry = draft.entry.copy(title = title.text,
                quote = if (hasQuote) quotation.text else draft.entry.quote)
            draft =
                draft.copy(cursorStart = cursor.selectionStart, cursorEnd = cursor.selectionEnd, focusedField = field,
                    entry = if (contentChanged) entry.withContent(editor.rich) else entry)
            app.session.updateDraft(draft, cursorPreferences)
            pending = false
            if (contentChanged) { exportedContent = editor.rich; updateOutline() }
        } else app.session.repository.persistPreferences(cursorPreferences)
        if (!pending) timer.stop()
    }

    fun save() {
        flush()
        app.session.save(draft)
        draft = app.session.draft(id)
        pending = false
    }

    fun togglePreview() {
        editor.togglePreview()
        quotation.isEditable = !editor.preview
    }

    fun formattingEditor() = editor.takeUnless { hasQuote && quotation.isFocusOwner }

    fun undo() {
        if (hasQuote && quotation.isFocusOwner) {
            if (quotation.isEditable && quoteUndo.canUndo()) quoteUndo.undo()
        } else editor.undo()
    }

    fun redo() {
        if (hasQuote && quotation.isFocusOwner) {
            if (quotation.isEditable && quoteUndo.canRedo()) quoteUndo.redo()
        } else editor.redo()
    }

    fun synchronizeRelationships() {
        val current = app.session.draft(id)
        draft = draft.copy(collections = current.collections, readerFolders = current.readerFolders, tags = current.tags,
            entry = if (!pending) draft.entry.copy(title = current.entry.title) else draft.entry)
        caption.text = draft.entry.title
        if (side.parent != null) side.setComponentAt(1, backlinks())
        if (!pending && title.text != draft.entry.title) {
            updating = true
            title.text = draft.entry.title
            updating = false
        }
    }

    private fun toggleSide() {
        manualOutline = true
        val visible = editorSplit.rightComponent == null
        if (visible) { side.setComponentAt(1, backlinks()); editorSplit.rightComponent = side }
        else editorSplit.rightComponent = null
        editorSplit.dividerSize = if (visible) 1 else 0
        if (visible) editorSplit.dividerLocation = (editorSplit.width - app.session.workspace.structureWidth.toInt()).coerceAtLeast(150)
        app.session.repository.persistPreference("note-side-visible", visible.toString())
    }

    fun disposePanel() {
        timer.stop()
        autosave.stop()
    }

    fun refreshFromSaved() {
        updating = true
        try {
            draft = app.session.draft(id)
            exportedContent = draft.entry.richText()
            title.text = draft.entry.title
            caption.text = draft.entry.title
            editor.replace(draft.entry.richText())
            quotation.text = draft.entry.quote
            quoteUndo.discardAllEdits()
            pending = false
            updateOutline()
        } finally {
            updating = false
        }
    }

    private fun updateOutline() {
        val selected = outlineList.selectedValue?.id
        outline.clear()
        editor.rich.blocks
            .filter { it.style == BlockStyle.Heading }
            .forEach { outline.addElement(it) }
        (0 until outline.size())
            .firstOrNull { outline[it].id == selected }
            ?.let { outlineList.selectedIndex = it }
    }

    private fun backlinks() =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            ResearchLinks.backlinks(app.session.data, id).forEach { e ->
                add(button(e.title) { app.openEntry(e.id) })
            }
            draft.entry.sourceBooks().forEach { book ->
                app.session.data.documents
                    .firstOrNull { it.id == book }
                    ?.let { d -> add(button(d.title) { app.openPdf(d.id) }) }
            }
        }

    fun find() {
        val query = JOptionPane.showInputDialog(app, "Найти в тексте") ?: return
        if (hasQuote && query.isNotEmpty()) {
            val start = quotation.text.indexOf(query, ignoreCase = true)
            if (start >= 0) {
                quotation.select(start, start + query.length)
                quotation.requestFocusInWindow()
                return
            }
        }
        if (!editor.find(query)) JOptionPane.showMessageDialog(app, "Совпадений нет")
    }

    fun link() {
        app.safe {
            val uri =
                JOptionPane.showInputDialog(app, "Ссылка (https://… или readerka://…)")?.trim()
                    ?: return@safe
            require(java.net.URI(uri).scheme in listOf("https", "http", "mailto", "readerka")) {
                "Введите полную ссылку"
            }
            val a = editor.textPane.selectionStart
            val b = editor.textPane.selectionEnd
            if (a == b) {
                val label = JOptionPane.showInputDialog(app, "Текст ссылки") ?: return@safe
                editor.replace(
                    editor.rich.replace(
                        a,
                        b,
                        RichText.plain(label)
                            .style(0, label.length, org.readerka.data.TextStyle.Link, uri),
                    )
                )
                editor.finishInsertion(a + label.length)
            } else {
                editor.style(org.readerka.data.TextStyle.Link, uri)
                editor.finishInsertion(b)
            }
        }
    }

    private fun formatMenu() =
        JPopupMenu().apply {
            listOf("Жирный · Ctrl+B" to TextStyle.Bold, "Курсив · Ctrl+I" to TextStyle.Italic, "Подчёркивание · Ctrl+U" to TextStyle.Underline).forEach { (label, style) ->
                add(JMenuItem(label).apply { addActionListener { editor.style(style) } })
            }
            addSeparator()
            BlockStyle.entries
                .filter { it != BlockStyle.LinkCard }
                .forEach { style ->
                    add(
                        JMenuItem(
                                when (style) {
                                    BlockStyle.Quote -> "Цитатный блок"
                                    BlockStyle.Bullet -> "Маркированный список"
                                    BlockStyle.Ordered -> "Нумерованный список"
                                    BlockStyle.Code -> "Код"
                                    BlockStyle.Divider -> "Разделитель"
                                    BlockStyle.Heading -> "Заголовок 3"
                                    else -> "Обычный абзац"
                                }
                            )
                            .apply { addActionListener { editor.paragraph(style, 3) } }
                    )
                }
            add(
                JMenuItem("Оглавление и обратные ссылки").apply {
                    addActionListener {
                        toggleSide()

                    }
                }
            )
            addSeparator()
            add(JMenuItem("Отменить").apply { addActionListener { editor.undo() } })
            add(JMenuItem("Повторить").apply { addActionListener { editor.redo() } })
        }

    private fun editingToolbar(): JPanel {
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            name = "readerka-document"
            background = documentColor()
            accessibleContext.accessibleName = "Редактирование текста"
            border = BorderFactory.createEmptyBorder(2, 10, 7, 8)
        }
        val format = flow().apply { (layout as WrapFlowLayout).apply { hgap = 3; vgap = 3 } }
        val insert = flow().apply { (layout as WrapFlowLayout).apply { hgap = 3; vgap = 3 } }
        listOf(format, insert).forEach { row ->
            row.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) { row.revalidate() }
            })
        }
        val states = mutableListOf<Pair<JToggleButton, () -> Boolean>>()
        val editing = mutableListOf<AbstractButton>()
        fun tool(row: JPanel, label: String, description: String = label, icon: String? = null, action: () -> Unit): JButton {
            return MotionButton(label, icon?.let(::LineIcon)).apply {
                isFocusable = false
                toolTipText = description
                accessibleContext.accessibleName = description
                margin = Insets(4, 8, 4, 8)
                addActionListener { app.safe(action) }
                row.add(this)
                editing += this
            }
        }
        fun toggle(row: JPanel, label: String, description: String = label, active: () -> Boolean, action: () -> Unit): JToggleButton {
            return MotionToggleButton(label).apply {
                isFocusable = false
                toolTipText = description
                accessibleContext.accessibleName = description
                margin = Insets(4, 8, 4, 8)
                addActionListener { app.safe(action) }
                states += this to active
                editing += this
                row.add(this)
            }
        }
        fun separator(row: JPanel) { row.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 24) }) }
        val undo = tool(format, "", "Отменить", "undo") { editor.undo() }
        val redo = tool(format, "", "Повторить", "redo") { editor.redo() }
        separator(format)
        toggle(format, "Ж", "Жирный · Ctrl+B", { editor.isStyleActive(TextStyle.Bold) }) { editor.style(TextStyle.Bold) }
            .apply { font = font.deriveFont(Font.BOLD) }
        toggle(format, "К", "Курсив · Ctrl+I", { editor.isStyleActive(TextStyle.Italic) }) { editor.style(TextStyle.Italic) }
            .apply { font = font.deriveFont(Font.ITALIC) }
        toggle(format, "Ч", "Подчёркивание · Ctrl+U", { editor.isStyleActive(TextStyle.Underline) }) { editor.style(TextStyle.Underline) }
            .apply { font = font.deriveFont(mapOf(java.awt.font.TextAttribute.UNDERLINE to java.awt.font.TextAttribute.UNDERLINE_ON)) }
        separator(format)
        toggle(format, "Абзац", active = { editor.isParagraphActive(BlockStyle.Paragraph) }) { editor.paragraph(BlockStyle.Paragraph) }
        (1..3).forEach { level ->
            toggle(format, "H$level", "Заголовок $level", { editor.isParagraphActive(BlockStyle.Heading, level) }) { editor.paragraph(BlockStyle.Heading, level) }
        }
        toggle(insert, "• Список", "Маркированный список", { editor.isParagraphActive(BlockStyle.Bullet) }) { editor.paragraph(BlockStyle.Bullet) }
        toggle(insert, "1. Список", "Нумерованный список", { editor.isParagraphActive(BlockStyle.Ordered) }) { editor.paragraph(BlockStyle.Ordered) }
        toggle(insert, "Цитата", "Цитатный блок", { editor.isParagraphActive(BlockStyle.Quote) }) { editor.paragraph(BlockStyle.Quote) }
        toggle(insert, "Код", active = { editor.isParagraphActive(BlockStyle.Code) }) { editor.paragraph(BlockStyle.Code) }
        tool(insert, "Разделитель") { editor.paragraph(BlockStyle.Divider) }
        separator(insert)
        tool(insert, "Ссылка", "Вставить ссылку · Ctrl+K", "link") { link() }
        tool(insert, "Вставить материал", "Каталог вставки", "note-add") { insertCatalogue() }
        val properties = tool(insert, "", "Свойства", "settings") { app.entryProperties(id) }
        toolbar.add(format)
        toolbar.add(insert)
        fun update() {
            states.forEach { (button, active) ->
                button.isSelected = active()
                button.foreground = if (button.isSelected) accentColor() else UIManager.getColor("Button.foreground")
            }
            editing.forEach { it.isEnabled = !editor.preview }
            properties.isEnabled = true
            undo.isEnabled = !editor.preview && editor.canUndo
            redo.isEnabled = !editor.preview && editor.canRedo
        }
        editor.formattingChanged = { update() }
        update()
        return toolbar
    }
    fun versions() {
        app.safe {
            flush()
            val versions =
                app.session.data.noteVersions
                    .filter { it.entryId == id }
                    .sortedByDescending { it.createdAt }
            val labels = versions.map { "${java.util.Date(it.createdAt)} — ${it.label}" }
            val chosen =
                JOptionPane.showInputDialog(
                    app,
                    "Восстановление создаёт резервную версию текущей записи",
                    "История версий",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    (labels + "Создать контрольную точку").toTypedArray(),
                    null,
                ) ?: return@safe
            if (chosen == "Создать контрольную точку") {
                val label = JOptionPane.showInputDialog(app, "Название версии") ?: return@safe
                app.session.checkpoint(id, label)
            } else {
                val version = versions[labels.indexOf(chosen)]
                val old = Json.decodeFromString<EntryDraft>(version.snapshot)
                val text =
                    JTextArea(old.entry.libraryContent(includeQuoteComment = true).text, 16, 65).apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                    }
                if (
                    JOptionPane.showConfirmDialog(
                        app,
                        JScrollPane(text),
                        "Восстановить эту версию?",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    app.session.checkpoint(id, "Перед восстановлением")
                    app.session.save(
                        app.session.restorableDraft(old),
                        "Восстановление",
                    )
                    refreshFromSaved()
                }
            }
        }
    }

    fun insertCatalogue() {
        app.safe {
            flush()
            val data = app.session.data
            val query = JTextField(30)
            val kinds =
                JComboBox(arrayOf("Все записи", "Цитаты", "Заметки", "Конспекты", "PDF / страница"))
            val folders =
                JComboBox(
                    arrayOf("Все папки") +
                        data.collections.map { it.name + " · " + displayLabel(it.scope) }
                )
            val tags = JTextField(10)
            val listModel = DefaultListModel<Entry>()
            val list =
                JList(listModel).apply {
                    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                    cellRenderer =
                        object : DefaultListCellRenderer() {
                            override fun getListCellRendererComponent(
                                l: JList<*>?,
                                v: Any?,
                                i: Int,
                                s: Boolean,
                                f: Boolean,
                            ): Component {
                                super.getListCellRendererComponent(l, v, i, s, f)
                                text = (v as Entry).title
                                return this
                            }
                        }
                }
            val preview =
                JTextArea(8, 50).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                }
            val fragment = JCheckBox("Вставить только выделенный фрагмент предпросмотра")
            list.addListSelectionListener {
                preview.text = list.selectedValue?.libraryContent(true)?.text ?: ""
                preview.caretPosition = 0
            }
            fun update() {
                val tagId = data.tags.firstOrNull { it.name.equals(tags.text.trim(), true) }?.id
                listModel.clear()
                if (tags.text.isNotBlank() && tagId == null) return
                NoteQuery(
                        text = query.text,
                        tagId = tagId,
                        collectionId = data.collections.getOrNull(folders.selectedIndex - 1)?.id,
                    )
                    .apply(data)
                    .filter {
                        it.id != id &&
                            (kinds.selectedIndex == 0 ||
                                it.kind ==
                                    when (kinds.selectedIndex) {
                                        1 -> EntryKind.Quote
                                        2 -> EntryKind.Note
                                        else -> EntryKind.Consp
                                    })
                    }
                    .forEach { listModel.addElement(it) }
            }
            val listener =
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = update()

                    override fun removeUpdate(e: DocumentEvent) = update()

                    override fun changedUpdate(e: DocumentEvent) = update()
                }
            query.document.addDocumentListener(listener)
            tags.document.addDocumentListener(listener)
            kinds.addActionListener { update() }
            folders.addActionListener { update() }
            update()
            val mode =
                JComboBox(
                    arrayOf(
                        "Ссылка на выделенном слове / текстовая ссылка",
                        "Отдельная карточка-ссылка",
                        "Содержимое и ссылка на источник",
                    )
                )
            val books = JComboBox(data.documents.map { it.title }.toTypedArray())
            val page = JSpinner(SpinnerNumberModel(1, 1, 100000, 1))
            val panel =
                JPanel(BorderLayout()).apply {
                    add(
                        fieldPanel(
                            "Поиск" to query,
                            "Категория" to kinds,
                            "Папка" to folders,
                            "Тег" to tags,
                        ),
                        BorderLayout.NORTH,
                    )
                    add(
                        JSplitPane(
                                JSplitPane.VERTICAL_SPLIT,
                                JScrollPane(list),
                                JScrollPane(preview),
                            )
                            .apply { resizeWeight = .5 },
                        BorderLayout.CENTER,
                    )
                    add(
                        fieldPanel(
                            "Способ вставки" to mode,
                            "Фрагмент" to fragment,
                            "PDF (выберите категорию PDF / страница)" to books,
                            "Страница PDF" to page,
                        ),
                        BorderLayout.SOUTH,
                    )
                    preferredSize =
                        Dimension(
                            minOf(710, Toolkit.getDefaultToolkit().screenSize.width - 100),
                            minOf(720, Toolkit.getDefaultToolkit().screenSize.height - 180),
                        )
                }
            if (
                JOptionPane.showConfirmDialog(
                    app,
                    panel,
                    "Вставить из библиотеки",
                    JOptionPane.OK_CANCEL_OPTION,
                ) != JOptionPane.OK_OPTION
            )
                return@safe
            var a = editor.textPane.selectionStart
            var b = editor.textPane.selectionEnd
            var result = editor.rich
            if (kinds.selectedIndex == 4) {
                val d = data.documents.getOrNull(books.selectedIndex) ?: return@safe
                val p = (page.value as Int)
                require(p <= d.pageCount) { "В книге ${d.pageCount} страниц" }
                val url = ResearchLinks.document(d.id, p)
                val label = "${d.title} · стр. $p"
                val previousLength = result.text.length
                result =
                    if (mode.selectedIndex == 0 && a < b)
                        result.style(a, b, org.readerka.data.TextStyle.Link, url)
                    else
                        result.replace(
                            a,
                            b,
                            RichText.plain("\n$label\n")
                                .paragraph(1, 1, BlockStyle.LinkCard, target = url),
                        )
                a = (b + result.text.length - previousLength).coerceIn(0, result.text.length)
            } else {
                val selected = list.selectedValuesList
                require(selected.isNotEmpty()) { "Выберите запись" }
                require(mode.selectedIndex != 0 || selected.size == 1) {
                    "Текстовая ссылка указывает на одну запись"
                }
                selected.forEach { e ->
                    val previousLength = result.text.length
                    val previousEnd = b
                    val content = e.libraryContent(true)
                    val value =
                        if (
                            fragment.isSelected &&
                                selected.size == 1 &&
                                preview.selectionStart < preview.selectionEnd
                        )
                            content.fragment(preview.selectionStart, preview.selectionEnd)
                        else content
                    result =
                        ResearchInsertion.insert(
                            result,
                            a,
                            b,
                            e,
                            InsertionMode.entries[mode.selectedIndex],
                            value,
                        )
                    a =
                        (previousEnd + result.text.length - previousLength).coerceIn(
                            0,
                            result.text.length,
                        )
                    b = a
                }
            }
            editor.replace(result)
            editor.finishInsertion(a)
            flush()
        }
    }
}
