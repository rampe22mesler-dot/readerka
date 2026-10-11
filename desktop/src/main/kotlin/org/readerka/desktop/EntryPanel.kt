package org.readerka.desktop

import java.awt.*
import javax.swing.*
import javax.swing.event.*
import kotlinx.serialization.json.Json
import org.readerka.data.*

class EntryPanel(val app: DesktopWindow, val id: String) : JPanel(BorderLayout()) {
    private var draft = app.session.draft(id)
    private var updating = false
    private var pending = false
    val title = JTextField(draft.entry.title)
    val editor =
        RichEditor(
            draft.entry.richText(),
            { content, a, b ->
                if (!updating) {
                    draft =
                        draft.copy(
                            entry = draft.entry.withContent(content),
                            cursorStart = a,
                            cursorEnd = b,
                            focusedField = "body",
                            modifiedAt = System.currentTimeMillis(),
                        )
                    pending = true
                }
            },
            app::openLink,
        )
    private val outline = DefaultListModel<TextBlock>()
    private val outlineList = JList(outline)
    private val side = JTabbedPane()
    private val editorSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, side)
    private var manualOutline = false
    private val timer = Timer(700) { if (pending && !app.busy) app.safe { flush() } }
    private val autosave =
        Timer(2500) {
            if (
                draft.entry.kind == EntryKind.Consp &&
                    !app.busy &&
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
                        pending = true
                    }
                }
            }
        )
        val toolbar =
            flow(
                button("Сохранить") { app.safe { save() } },
                button("B") { editor.style(org.readerka.data.TextStyle.Bold) },
                button("I") { editor.style(org.readerka.data.TextStyle.Italic) },
                button("U") { editor.style(org.readerka.data.TextStyle.Underline) },
                button("H1") { editor.paragraph(BlockStyle.Heading, 1) },
                button("H2") { editor.paragraph(BlockStyle.Heading, 2) },
                button("Абзац") { editor.paragraph(BlockStyle.Paragraph) },
                button("⋮ Формат") { formatMenu().show(this, 50, 70) },
                button("Ссылка") { link() },
                button("Вставить…") { insertCatalogue() },
                button("Свойства") { app.safe { app.entryProperties(id) } },
                button("Читать") { editor.togglePreview() },
                button("Версии") { versions() },
            )
        add(
            JPanel(BorderLayout()).apply {
                add(title, BorderLayout.NORTH)
                add(toolbar, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
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
        editorSplit.resizeWeight = .78
        editorSplit.dividerSize = 8
        side.minimumSize = Dimension(100, 100)
        side.preferredSize = Dimension(210, 400)
        editorSplit.addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (!manualOutline) {
                        editorSplit.rightComponent = if (editorSplit.width >= 650) side else null
                        editorSplit.dividerSize = if (editorSplit.rightComponent == null) 0 else 8
                        editorSplit.setDividerLocation(.78)
                    }
                }
            }
        )
        add(editorSplit, BorderLayout.CENTER)
        if (draft.entry.quote.isNotBlank())
            add(
                flow(
                    JLabel("❝ ${draft.entry.quote.take(130)}"),
                    button("Цитата целиком") {
                        val quote =
                            JTextArea(draft.entry.quote, 12, 55).apply {
                                isEditable = false
                                lineWrap = true
                                wrapStyleWord = true
                            }
                        JOptionPane.showMessageDialog(
                            app,
                            JScrollPane(quote),
                            "Исходная цитата",
                            JOptionPane.PLAIN_MESSAGE,
                        )
                    },
                    button("Источник · стр. ${(draft.entry.anchor?.page?:0)+1}") {
                        draft.entry.documentId?.let { app.openPdf(it, draft.entry.anchor?.page) }
                    },
                ),
                BorderLayout.SOUTH,
            )
        editor.textPane.select(
            (if (draft.focusedField.isEmpty())
                    app.session.repository.preference("cursor-start:$id")?.toIntOrNull()
                        ?: draft.cursorStart
                else draft.cursorStart)
                .coerceIn(0, editor.rich.text.length),
            (if (draft.focusedField.isEmpty())
                    app.session.repository.preference("cursor-end:$id")?.toIntOrNull()
                        ?: draft.cursorEnd
                else draft.cursorEnd)
                .coerceIn(0, editor.rich.text.length),
        )
        updateOutline()
        timer.start()
        autosave.start()
    }

    fun flush() {
        app.session.repository.persistPreference(
            "cursor-start:$id",
            editor.textPane.selectionStart.toString(),
        )
        app.session.repository.persistPreference(
            "cursor-end:$id",
            editor.textPane.selectionEnd.toString(),
        )
        if (pending) {
            draft =
                draft.copy(entry = draft.entry.copy(title = title.text).withContent(editor.rich))
            app.session.updateDraft(draft)
            pending = false
            updateOutline()
        }
    }

    fun save() {
        flush()
        app.session.save(draft)
        draft = app.session.draft(id)
        pending = false
    }

    fun disposePanel() {
        timer.stop()
        autosave.stop()
    }

    fun refreshFromSaved() {
        updating = true
        try {
            draft = app.session.draft(id)
            title.text = draft.entry.title
            editor.replace(draft.entry.richText())
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
                add(button("↗ ${e.title}") { app.openEntry(e.id) })
            }
            draft.entry.sourceBooks().forEach { book ->
                app.session.data.documents
                    .firstOrNull { it.id == book }
                    ?.let { d -> add(button("▣ ${d.title}") { app.openPdf(d.id) }) }
            }
        }

    fun find() {
        val query = JOptionPane.showInputDialog(app, "Найти в тексте") ?: return
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
            } else editor.style(org.readerka.data.TextStyle.Link, uri)
        }
    }

    private fun formatMenu() =
        JPopupMenu().apply {
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
                        manualOutline = true
                        editorSplit.rightComponent =
                            if (editorSplit.rightComponent == null) side else null
                        editorSplit.dividerSize = if (editorSplit.rightComponent == null) 0 else 8
                        editorSplit.setDividerLocation(.7)
                    }
                }
            )
            addSeparator()
            add(JMenuItem("Отменить").apply { addActionListener { editor.undo() } })
            add(JMenuItem("Повторить").apply { addActionListener { editor.redo() } })
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
                    JTextArea(old.entry.richText().text, 16, 65).apply {
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
                        old.copy(
                            collections =
                                old.collections
                                    .filter { folder ->
                                        app.session.data.collections.any {
                                            it.id == folder &&
                                                it.scope == old.entry.kind.collectionScope()
                                        }
                                    }
                                    .toSet(),
                            readerFolders =
                                old.readerFolders
                                    .filter { folder ->
                                        app.session.data.readerFolders.any { it.id == folder }
                                    }
                                    .toSet(),
                        ),
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
            flush()
        }
    }
}
