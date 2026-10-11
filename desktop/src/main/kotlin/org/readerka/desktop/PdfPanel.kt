package org.readerka.desktop

import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.net.URLEncoder
import javax.swing.*
import kotlin.math.*
import org.readerka.data.*
import org.readerka.pdf.*

class PdfPanel(val app: DesktopWindow, val id: String) : JPanel(BorderLayout()), AutoCloseable {
    private val session = app.session
    private var document = session.data.documents.first { it.id == id }
    private val engine =
        DesktopPdfEngine(session.repository.pdf(document), session.repository.directory)
    var page = document.lastPage
        private set

    private val pageField = JSpinner(SpinnerNumberModel(page + 1, 1, engine.pageCount, 1))
    private var zoom = 1.0
    private var fitting = true
    private var image: BufferedImage? = null
    private var text: PageText? = null
    private var selected: Pair<String, Anchor>? = null
    private var first: Int? = null
    private var worker: SwingWorker<Pair<BufferedImage, PageText>, Unit>? = null
    private var generation = 0
    private var closed = false
    private val selectionLabel = JLabel("Выделите текст; двойной щелчок выбирает слово")
    private val canvas =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val img = image ?: return
                g.drawImage(img, 0, 0, null)
                val g2 = g.create() as Graphics2D
                val rects =
                    session.data.entries
                        .filter { it.documentId == id && it.anchor?.page == page }
                        .flatMap { e ->
                            e.anchor!!.rects.map { it to Color(e.color.argb.toInt(), true) }
                        } + selected?.second?.rects.orEmpty().map { it to Color(100, 140, 230) }
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .28f)
                rects.forEach { (r, c) ->
                    g2.color = c
                    g2.fillRect(
                        (r.left * img.width).toInt(),
                        (r.top * img.height).toInt(),
                        max(1, ((r.right - r.left) * img.width).toInt()),
                        max(2, ((r.bottom - r.top) * img.height).toInt()),
                    )
                }
                g2.dispose()
            }
        }
    private val scroll = JScrollPane(canvas)
    private val selectionBar =
        flow(
            selectionLabel,
            button("Копировать") { copy() },
            button("Цитата / комментарий") { quote() },
            button("В конспект…") { addToConsp() },
            button("Перевести") {
                selected?.first?.let {
                    app.openLink(
                        "https://translate.google.com/?sl=auto&tl=ru&text=" +
                            URLEncoder.encode(it, "UTF-8")
                    )
                }
            },
        )

    init {
        name = "pdf:$id"
        canvas.background = Color.WHITE
        canvas.accessibleContext.accessibleName = "Страница PDF"
        add(
            flow(
                button("↩ К записи") { app.returnToNote() },
                button("‹") { go(page - 1) },
                pageField,
                JLabel("/ ${engine.pageCount}"),
                button("›") { go(page + 1) },
                button("По ширине") {
                    fitting = true
                    render()
                },
                button("−") {
                    fitting = false
                    zoom = (zoom - .15).coerceAtLeast(.3)
                    render()
                },
                button("＋") {
                    fitting = false
                    zoom = (zoom + .15).coerceAtMost(3.0)
                    render()
                },
                button("Поиск") { search() },
                button("Текст") { pageText() },
                button("＋ Закладка") { bookmark() },
                button("Материалы книги") { materials(0) },
                button("Свойства") { app.safe { app.documentProperties(id) } },
            ),
            BorderLayout.NORTH,
        )
        add(scroll, BorderLayout.CENTER)
        add(selectionBar, BorderLayout.SOUTH)
        pageField.addChangeListener {
            if (pageField.value as Int != page + 1) go((pageField.value as Int) - 1)
        }
        val resizeTimer = Timer(200) { if (fitting) render() }.apply { isRepeats = false }
        scroll.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    resizeTimer.restart()
                }
            }
        )
        canvas.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val p = point(e) ?: return
                    first = text?.hit(p.first, p.second)
                    first?.let { n ->
                        selected = text?.selection(n, n)
                        canvas.repaint()
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val p = point(e) ?: return
                        val t = text ?: return
                        val hit = t.hit(p.first, p.second) ?: return
                        val range = t.wordAt(hit)
                        selected = t.selection(range.first, range.last)
                        selectionLabel.text = selected?.first?.take(75) ?: ""
                        canvas.repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    selectionLabel.text =
                        selected?.first?.replace('\n', ' ')?.take(75) ?: "Выделите текст"
                }
            }
        )
        canvas.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val p = point(e) ?: return
                    val last = text?.hit(p.first, p.second) ?: return
                    first?.let {
                        selected = text?.selection(it, last)
                        canvas.repaint()
                    }
                }
            }
        )
        canvas.componentPopupMenu =
            JPopupMenu().apply {
                listOf(
                        "Создать цитату" to { quote() },
                        "Добавить в конспект" to { addToConsp() },
                        "Копировать" to { copy() },
                        "Добавить закладку" to { bookmark() },
                    )
                    .forEach { (label, fn) ->
                        add(JMenuItem(label).apply { addActionListener { fn() } })
                    }
            }
        scroll.verticalScrollBar.addAdjustmentListener {
            if (!it.valueIsAdjusting && !app.busy) {
                val bar = scroll.verticalScrollBar
                val offset =
                    if (bar.maximum > bar.visibleAmount)
                        (10000L * bar.value / (bar.maximum - bar.visibleAmount)).toInt()
                    else 0
                if (session.data.documents.any { it.id == id }) {
                    document = document.copy(lastPage = page, lastOffset = offset)
                    session.change { s ->
                        s.copy(
                            documents =
                                s.documents.map {
                                    if (it.id == id) it.copy(lastPage = page, lastOffset = offset)
                                    else it
                                }
                        )
                    }
                }
            }
        }
        render()
    }

    private fun point(e: MouseEvent): Pair<Float, Float>? =
        image?.let { e.x.toFloat() / it.width to e.y.toFloat() / it.height }

    fun go(next: Int, offset: Int = 0) {
        if (next !in 0 until engine.pageCount) return
        page = next
        selected = null
        first = null
        pageField.value = page + 1
        render(offset)
        session.change { s ->
            s.copy(
                documents =
                    s.documents.map {
                        if (it.id == id)
                            it.copy(lastPage = page, lastOpenedAt = System.currentTimeMillis())
                        else it
                    }
            )
        }
        app.captureWorkspace()
    }

    private fun render(offset: Int = document.lastOffset) {
        if (closed) return
        val current = ++generation
        val p = page
        val width =
            (if (fitting) (scroll.viewport.width - 20).coerceAtLeast(64) else 800 * zoom).toInt()
        worker =
            object : SwingWorker<Pair<BufferedImage, PageText>, Unit>() {
                    override fun doInBackground() = engine.render(p, width) to engine.text(p)

                    override fun done() {
                        if (closed || generation != current) return
                        app.safe {
                            val result = get()
                            image = result.first
                            text = result.second
                            canvas.preferredSize =
                                Dimension(result.first.width, result.first.height)
                            canvas.revalidate()
                            canvas.repaint()
                            SwingUtilities.invokeLater {
                                val bar = scroll.verticalScrollBar
                                bar.value =
                                    ((bar.maximum - bar.visibleAmount) * offset / 10000)
                                        .coerceAtLeast(0)
                            }
                            selectionLabel.text =
                                if (result.second.glyphs.isEmpty())
                                    "Нет текстового слоя; можно добавить закладку или заметку"
                                else "Выделите текст; двойной щелчок выбирает слово"
                        }
                    }
                }
                .apply { execute() }
    }

    private fun copy() {
        selected?.first?.let {
            Toolkit.getDefaultToolkit()
                .systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(it), null)
        }
    }

    private fun newQuote(): Entry? {
        val selection =
            selected
                ?: run {
                    JOptionPane.showMessageDialog(app, "Сначала выделите текст PDF")
                    return null
                }
        return Entry(
            newId(),
            selection.first.replace('\n', ' ').take(85),
            quote = selection.first,
            documentId = id,
            anchor = selection.second,
            type = NoteType.Quote,
            kind = EntryKind.Quote,
        )
    }

    fun quote() {
        app.safe {
            val e = newQuote() ?: return@safe
            session.save(EntryDraft(e))
            app.openEntry(e.id)
            canvas.repaint()
        }
    }

    fun addToConsp() {
        app.safe {
            val quote = newQuote() ?: return@safe
            val outlines = session.data.entries.filter { it.kind == EntryKind.Consp }
            val choices =
                outlines.map { it.title + " · " + it.id.take(8) } +
                    "Создать новый конспект по книге"
            val selected =
                JOptionPane.showInputDialog(
                    app,
                    "Выберите существующий конспект: новые цитаты дополняют его",
                    "В конспект",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    choices.toTypedArray(),
                    outlines
                        .indexOfFirst { it.id == session.workspace.lastConspByBook[id] }
                        .takeIf { it >= 0 }
                        ?.let { choices[it] } ?: choices.last(),
                ) ?: return@safe
            session.save(EntryDraft(quote))
            val consp =
                if (selected == choices.last())
                    session.create(EntryKind.Consp, book = id, template = ConspTemplate.Book)
                else outlines[choices.indexOf(selected)]
            app.editors[consp.id]?.flush()
            val d = session.draft(consp.id)
            val content =
                ResearchInsertion.insert(
                    d.entry.richText(),
                    d.entry.richText().text.length,
                    d.entry.richText().text.length,
                    quote,
                    InsertionMode.Content,
                )
            session.save(
                d.copy(
                    entry =
                        d.entry
                            .copy(sources = (d.entry.sources + id).distinct())
                            .withContent(content)
                ),
                "Цитата из книги",
            )
            session.setWorkspace(
                session.workspace.copy(
                    lastConspByBook = session.workspace.lastConspByBook + (id to consp.id)
                )
            )
            app.editors[consp.id]?.refreshFromSaved()
            app.openEntry(consp.id, right = true)
            canvas.repaint()
        }
    }

    fun bookmark() {
        app.safe {
            val title =
                JOptionPane.showInputDialog(app, "Название закладки", "Страница ${page+1}")
                    ?: return@safe
            session.change {
                it.copy(
                    bookmarks =
                        it.bookmarks +
                            Bookmark(newId(), id, page, title, offset = document.lastOffset)
                )
            }
        }
    }

    fun pageText() {
        val cached = text
        if (cached?.page == page) showPageText(cached)
        else app.background("Чтение текста страницы…", { engine.text(page) }) { showPageText(it) }
    }

    private fun showPageText(value: PageText) {
        if (value.glyphs.isEmpty()) {
            JOptionPane.showMessageDialog(app, "На странице нет текстового слоя")
            return
        }
        val area =
            JTextArea(value.text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = Font("Segoe UI", Font.PLAIN, 18)
                accessibleContext.accessibleName = "Текст страницы PDF"
            }
        val dialog =
            JDialog(app, "Текст PDF · страница ${value.page+1}", false).apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            }
        fun selection(): Boolean {
            val from = area.selectionStart
            val to = area.selectionEnd
            if (from == to) {
                JOptionPane.showMessageDialog(dialog, "Выделите фрагмент текста")
                return false
            }
            val first = value.glyphs.indexOfFirst { it.end > from }
            val last = value.glyphs.indexOfLast { it.start < to }
            if (first < 0 || last < first) return false
            go(value.page)
            selected = value.selection(first, last)
            canvas.repaint()
            return true
        }
        val quote =
            button("Создать цитату (Ctrl+Shift+Q)") {
                app.safe {
                    if (selection()) {
                        dialog.dispose()
                        quote()
                    }
                }
            }
        val consp =
            button("В конспект (Ctrl+Alt+A)") {
                app.safe {
                    if (selection()) {
                        dialog.dispose()
                        addToConsp()
                    }
                }
            }
        dialog.add(
            JPanel(BorderLayout()).apply {
                add(
                    JLabel(
                            "Выделите фрагмент клавишами Shift+стрелки или Ctrl+A; Ctrl+C копирует текст"
                        )
                        .apply { border = BorderFactory.createEmptyBorder(10, 12, 10, 12) },
                    BorderLayout.NORTH,
                )
                add(JScrollPane(area), BorderLayout.CENTER)
                add(flow(quote, consp, button("Закрыть") { dialog.dispose() }), BorderLayout.SOUTH)
            }
        )
        listOf("control shift Q" to quote, "control alt A" to consp).forEach { (key, button) ->
            dialog.rootPane
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key), key)
            dialog.rootPane.actionMap.put(
                key,
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        button.doClick()
                    }
                },
            )
        }
        dialog.rootPane.defaultButton = quote
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        dialog.setSize(minOf(820, available.width - 80), minOf(620, available.height - 100))
        dialog.setLocationRelativeTo(app)
        dialog.isVisible = true
        SwingUtilities.invokeLater { area.requestFocusInWindow() }
    }

    fun search() {
        val query = JOptionPane.showInputDialog(app, "Найти во всех страницах PDF") ?: return
        app.background(
            "Поиск в PDF…",
            { (0 until engine.pageCount).flatMap { engine.text(it).matches(query) }.take(2000) },
        ) { matches ->
            if (matches.isEmpty()) {
                JOptionPane.showMessageDialog(app, "Совпадений нет")
                return@background
            }
            val choices = matches.map { "Стр. ${it.page+1} · символ ${it.start+1}" }
            val chosen =
                JOptionPane.showInputDialog(
                    app,
                    "Найдено ${matches.size} (до 2000)",
                    "Поиск",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    choices.toTypedArray(),
                    choices[0],
                ) ?: return@background
            val a = matches[choices.indexOf(chosen)]
            go(a.page, (a.rects.first().top * 10000).toInt())
            selected = query to a
            canvas.repaint()
        }
    }

    fun materials(start: Int) {
        BookMaterialsDialog(app, this, id, start).isVisible = true
    }

    override fun close() {
        closed = true
        generation++
        engine.close()
    }
}

class BookMaterialsDialog(
    val app: DesktopWindow,
    val pdf: PdfPanel,
    val bookId: String,
    start: Int,
) : JDialog(app, "Материалы книги", false) {
    private val tabs = JTabbedPane()

    init {
        setSize(630, 660)
        setLocationRelativeTo(app)
        (0..2).forEach { index ->
            tabs.addTab(
                when (index) {
                    0 -> "Цитаты"
                    1 -> "Закладки"
                    else -> "Конспекты"
                },
                MaterialList(index),
            )
        }
        tabs.selectedIndex = start
        add(tabs)
    }

    inner class MaterialList(val kind: Int) : JPanel(BorderLayout()) {
        private var folder: String? = null
        private val model = DefaultListModel<Any>()
        private val list = JList(model)

        init {
            list.cellRenderer =
                object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        l: JList<*>?,
                        v: Any?,
                        i: Int,
                        s: Boolean,
                        f: Boolean,
                    ): Component {
                        super.getListCellRendererComponent(l, v, i, s, f)
                        text =
                            when (v) {
                                is ReaderFolder -> "▸ ${v.name}"
                                is Bookmark -> "⚑ ${v.title} · стр. ${v.page+1}"
                                is Entry -> v.title
                                else -> ""
                            }
                        return this
                    }
                }
            list.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) open()
                    }
                }
            )
            list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
            list.actionMap.put(
                "open",
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        open()
                    }
                },
            )
            add(
                flow(
                    button("↑") {
                        folder =
                            app.session.data.readerFolders.firstOrNull { it.id == folder }?.parentId
                        refresh()
                    },
                    button("Открыть") { open() },
                    button("＋") {
                        if (kind == 2) app.newEntry(EntryKind.Consp, bookId)
                        else if (kind == 1) pdf.bookmark()
                        refresh()
                    },
                    button("＋ Папка") { newFolder() },
                    button("⋮") { menu().show(list, 20, 20) },
                ),
                BorderLayout.NORTH,
            )
            add(JScrollPane(list), BorderLayout.CENTER)
            refresh()
        }

        private fun folderKind() =
            if (kind == 0) ReaderFolderKind.Quotes else ReaderFolderKind.Bookmarks

        private fun refresh() {
            val s = app.session.data
            model.clear()
            if (kind == 2) {
                s.entries
                    .filter { it.kind == EntryKind.Consp && bookId in it.sourceBooks() }
                    .forEach { model.addElement(it) }
                return
            }
            s.readerFolders
                .filter {
                    it.documentId == bookId && it.kind == folderKind() && it.parentId == folder
                }
                .forEach { model.addElement(it) }
            if (kind == 0)
                s.entries
                    .filter { e ->
                        e.documentId == bookId &&
                            e.kind == EntryKind.Quote &&
                            (if (folder == null) s.readerMemberships.none { it.entryId == e.id }
                            else
                                s.readerMemberships.any {
                                    it.entryId == e.id && it.folderId == folder
                                })
                    }
                    .forEach { model.addElement(it) }
            else
                s.bookmarks
                    .filter { it.documentId == bookId && it.folderId == folder }
                    .sortedBy { it.page }
                    .forEach { model.addElement(it) }
        }

        private fun open() {
            when (val value = list.selectedValue) {
                is ReaderFolder -> {
                    folder = value.id
                    refresh()
                }
                is Entry -> app.openEntry(value.id, right = true)
                is Bookmark -> pdf.go(value.page, value.offset)
            }
        }

        private fun newFolder() {
            if (kind == 2) {
                JOptionPane.showMessageDialog(
                    app,
                    "Папки конспектов доступны в отдельном разделе библиотеки",
                )
                return
            }
            app.safe {
                val name = JOptionPane.showInputDialog(this, "Название папки") ?: return@safe
                app.session.change {
                    it.copy(
                        readerFolders =
                            it.readerFolders +
                                ReaderFolder(newId(), bookId, folderKind(), name, folder)
                    )
                }
                refresh()
            }
        }

        private fun menu() =
            JPopupMenu().apply {
                fun item(label: String, fn: () -> Unit) {
                    add(
                        JMenuItem(label).apply {
                            addActionListener {
                                app.safe(fn)
                                refresh()
                            }
                        }
                    )
                }
                item("Открыть источник") {
                    when (val value = list.selectedValue) {
                        is Entry ->
                            value.anchor?.let {
                                pdf.go(it.page, (it.rects.first().top * 10000).toInt())
                            }
                        is Bookmark -> pdf.go(value.page, value.offset)
                    }
                }
                item("Изменить") {
                    when (val v = list.selectedValue) {
                        is Entry -> app.entryProperties(v.id)
                        is ReaderFolder -> {
                            val name =
                                JOptionPane.showInputDialog(app, "Название", v.name) ?: return@item
                            app.session.change { s ->
                                s.copy(
                                    readerFolders =
                                        s.readerFolders.map {
                                            if (it.id == v.id) it.copy(name = name) else it
                                        }
                                )
                            }
                        }
                        is Bookmark -> {
                            val title = JTextField(v.title)
                            val comment = JTextArea(v.comment, 4, 30)
                            val page =
                                JSpinner(
                                    SpinnerNumberModel(
                                        v.page + 1,
                                        1,
                                        app.session.data.documents
                                            .first { it.id == bookId }
                                            .pageCount,
                                        1,
                                    )
                                )
                            val bookmarks =
                                listOf<String?>(null) +
                                    app.session.data.bookmarks
                                        .filter { it.documentId == bookId && it.id != v.id }
                                        .map { it.id }
                            val parents =
                                JComboBox(
                                        (listOf("Без родителя") +
                                                bookmarks.drop(1).map { id ->
                                                    app.session.data.bookmarks
                                                        .first { it.id == id }
                                                        .title
                                                })
                                            .toTypedArray()
                                    )
                                    .apply {
                                        selectedIndex =
                                            bookmarks.indexOf(v.parentId).coerceAtLeast(0)
                                    }
                            if (
                                JOptionPane.showConfirmDialog(
                                    app,
                                    fieldPanel(
                                        "Название" to title,
                                        "Комментарий" to JScrollPane(comment),
                                        "Страница" to page,
                                        "Родитель" to parents,
                                    ),
                                    "Закладка",
                                    JOptionPane.OK_CANCEL_OPTION,
                                ) == JOptionPane.OK_OPTION
                            )
                                app.session.change { s ->
                                    s.copy(
                                        bookmarks =
                                            s.bookmarks.map {
                                                if (it.id == v.id)
                                                    it.copy(
                                                        title = title.text,
                                                        comment = comment.text,
                                                        page = (page.value as Int) - 1,
                                                        parentId = bookmarks[parents.selectedIndex],
                                                    )
                                                else it
                                            }
                                    )
                                }
                        }
                    }
                }
                item("Переместить / Добавить в папку") {
                    val v = list.selectedValue ?: return@item
                    if (kind == 2) {
                        (v as? Entry)?.let { app.entryProperties(it.id) }
                        return@item
                    }
                    val folders =
                        app.session.data.readerFolders.filter {
                            it.documentId == bookId && it.kind == folderKind()
                        }
                    val choices = listOf("Без папки") + folders.map { it.name }
                    val chosen =
                        JOptionPane.showInputDialog(
                            app,
                            "Папка",
                            "Переместить",
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            choices.toTypedArray(),
                            choices[0],
                        ) ?: return@item
                    val target = folders.getOrNull(choices.indexOf(chosen) - 1)?.id
                    app.session.change { s ->
                        when (v) {
                            is Entry ->
                                s.copy(
                                    readerMemberships =
                                        s.readerMemberships.filterNot { it.entryId == v.id } +
                                            listOfNotNull(
                                                target?.let { ReaderMembership(it, v.id) }
                                            )
                                )
                            is Bookmark ->
                                s.copy(
                                    bookmarks =
                                        s.bookmarks.map {
                                            if (it.id == v.id) it.copy(folderId = target) else it
                                        }
                                )
                            is ReaderFolder ->
                                s.copy(
                                    readerFolders =
                                        s.readerFolders.map {
                                            if (it.id == v.id) it.copy(parentId = target) else it
                                        }
                                )
                            else -> s
                        }
                    }
                }
                item("Удалить") {
                    val v = list.selectedValue ?: return@item
                    if (!confirm(app, "Удалить выбранное?")) return@item
                    when (v) {
                        is Entry -> app.session.deleteEntry(v.id)
                        is Bookmark ->
                            app.session.change { s ->
                                s.copy(
                                    bookmarks =
                                        s.bookmarks
                                            .filterNot { it.id == v.id }
                                            .map {
                                                if (it.parentId == v.id) it.copy(parentId = null)
                                                else it
                                            }
                                )
                            }
                        is ReaderFolder ->
                            app.session.change { s ->
                                s.copy(
                                    readerFolders =
                                        s.readerFolders
                                            .filterNot { it.id == v.id }
                                            .map {
                                                if (it.parentId == v.id) it.copy(parentId = null)
                                                else it
                                            },
                                    readerMemberships =
                                        s.readerMemberships.filterNot { it.folderId == v.id },
                                    bookmarks =
                                        s.bookmarks.map {
                                            if (it.folderId == v.id) it.copy(folderId = null)
                                            else it
                                        },
                                )
                            }
                    }
                }
            }
    }
}
