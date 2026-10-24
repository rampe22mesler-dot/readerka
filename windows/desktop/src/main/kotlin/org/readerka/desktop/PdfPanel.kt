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
    private val engineDelegate = lazy {
        DesktopPdfEngine(session.repository.pdf(document), session.repository.directory)
    }
    private val engine by engineDelegate
    var page = document.lastPage.coerceIn(0, document.pageCount.coerceAtLeast(1) - 1)
        private set
    val pageCount: Int get() = document.pageCount.coerceAtLeast(1)
    val currentOffset: Int get() = document.lastOffset

    private val pageField = JSpinner(SpinnerNumberModel(page + 1, 1, pageCount, 1))
    private var zoom = 1.0
    private enum class Fit { Page, Width, Manual }
    private var fitting = Fit.Page
    private val zoomLabel = JLabel("Страница целиком")
    private var image: BufferedImage? = null
    private var text: PageText? = null
    private var selected: Pair<String, Anchor>? = null
    private var first: Int? = null
    private data class ZoomAnchor(val x: Double, val y: Double, val point: Point)
    private data class RenderRequest(val generation: Int, val page: Int, val width: Int,
        val height: Int?, val offset: Int, val anchor: ZoomAnchor?)
    private var worker: SwingWorker<PageText?, BufferedImage>? = null
    private var pendingRender: RenderRequest? = null
    private var generation = 0
    @Volatile private var closed = false
    private var restoringPosition = false
    private var status = "Загрузка книги…"
    private var pendingHistory = true
    private val positionTimer = Timer(500) { flushPosition() }.apply { isRepeats = false }
    private val resizeTimer = Timer(180) { if (fitting != Fit.Manual) render() }.apply { isRepeats = false }
    private val selectionLabel = JLabel("Загрузка книги…")
    private val canvas =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val img = image ?: run {
                    g.color = Color.DARK_GRAY
                    g.drawString(status, 16, 30)
                    return
                }
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
    private val viewerSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, null).apply {
        name = "pdf-viewer-split"
        border = BorderFactory.createEmptyBorder()
        resizeWeight = 1.0
        dividerSize = 0
        scroll.minimumSize = Dimension(100, 100)
    }
    private var materialsPanel: BookMaterialsPanel? = null
    private var materialsWidth = session.repository.preference("pdf-side-width")?.toIntOrNull()?.coerceIn(220, 640) ?: 320
    private val selectionBar = compactBar(
        selectionLabel.apply { preferredSize = Dimension(230, 26) },
        iconButton("note", "Копировать выделение") { copy() },
        iconButton("note-add", "Цитата / комментарий") { quote() },
        iconButton("link", "Добавить в конспект") { addToConsp() },
        iconButton("split", "Добавить на исследовательское поле · Ctrl+Alt+L") { addToResearchField() },
        iconButton("more", "Перевести выделение") {
            selected?.first?.let { app.openLink("https://translate.google.com/?sl=auto&tl=ru&text=" + URLEncoder.encode(it, "UTF-8")) }
        }
    )

    init {
        name = "pdf:$id"
        canvas.background = Color.WHITE
        canvas.isFocusable = true
        canvas.accessibleContext.accessibleName = "Страница книги"
        scroll.verticalScrollBar.unitIncrement = 40
        scroll.horizontalScrollBar.unitIncrement = 40
        val controls = flow(
                iconButton("back", "К записи · Alt+Up") { app.returnToNote() },
                iconButton("previous", "Предыдущая страница · ← / ↑") { go(page - 1) },
                pageField,
                JLabel("/ $pageCount"),
                iconButton("next", "Следующая страница · → / ↓") { go(page + 1) },
                iconButton("previous", "Уменьшить · Ctrl+колесо") { changeZoom(-1.0) },
                zoomLabel,
                iconButton("next", "Увеличить · Ctrl+колесо") { changeZoom(1.0) },
                iconButton("search", "Поиск в книге") { search() },
                iconButton("bookmark", "Добавить закладку · Ctrl+D") { bookmark() },
                iconButton("more", "Действия книги") {
                    JPopupMenu().apply {
                        fun action(title: String, fn: () -> Unit) { add(JMenuItem(title).apply { addActionListener { app.safe(fn) } }) }
                        action("Страница целиком") { fitting = Fit.Page; render() }
                        action("По ширине") { fitting = Fit.Width; render() }
                        action("Уменьшить · Ctrl+колесо") { changeZoom(-1.0) }
                        action("Увеличить · Ctrl+колесо") { changeZoom(1.0) }
                        action("Текст страницы") { pageText() }
                        action("Добавить закладку") { bookmark() }
                        action("Материалы книги") { materials(0) }
                        action("Свойства") { app.documentProperties(id) }
                    }.show(this@PdfPanel, 160, 32)
                }
            ).apply { layout = WrapFlowLayout().apply { hgap = 2; vgap = 2 } }
        add(JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.CENTER)
            add(compactBar(iconButton("outline", "Показать / скрыть материалы книги") { toggleMaterials() }), BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(viewerSplit, BorderLayout.CENTER)
        add(selectionBar, BorderLayout.SOUTH)
        pageField.addChangeListener {
            if (pageField.value as Int != page + 1) go((pageField.value as Int) - 1)
        }
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(DesktopPlatform.keyStroke("control D"), "bookmark")
        actionMap.put("bookmark", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { bookmark() }
        })
        scroll.viewport.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    resizeTimer.restart()
                }
            }
        )
        // The scroll pane UI installs its own wheel listener. Replace it so Ctrl+wheel
        // cannot also scroll, and page transitions happen only at a page boundary.
        scroll.isWheelScrollingEnabled = false
        scroll.addMouseWheelListener { e ->
            e.consume()
            canvas.requestFocusInWindow()
            if (DesktopPlatform.isShortcutDown(e)) {
                changeZoom(-e.preciseWheelRotation, SwingUtilities.convertPoint(e.component, e.point, scroll.viewport))
            } else if (e.preciseWheelRotation != 0.0 && image != null && !restoringPosition) {
                val bar = if (e.isShiftDown) scroll.horizontalScrollBar else scroll.verticalScrollBar
                val delta = (e.preciseWheelRotation * e.scrollAmount.coerceAtLeast(1) * bar.unitIncrement).roundToInt()
                val atEnd = bar.value >= bar.maximum - bar.visibleAmount
                if (!e.isShiftDown && delta > 0 && atEnd && page + 1 < pageCount && worker == null) go(page + 1)
                else if (!e.isShiftDown && delta < 0 && bar.value == 0 && page > 0 && worker == null) go(page - 1, 10_000)
                else bar.value += delta
            }
        }
        listOf("LEFT" to -1, "UP" to -1, "PAGE_UP" to -1,
            "RIGHT" to 1, "DOWN" to 1, "PAGE_DOWN" to 1).forEach { (key, delta) ->
            val action = "pdf-page-$key"
            val stroke = DesktopPlatform.keyStroke(key)
            canvas.getInputMap(WHEN_FOCUSED).put(stroke, action)
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, action)
            actionMap.put(action, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) { go(page + delta) }
            })
            canvas.actionMap.put(action, actionMap.get(action))
        }
        canvas.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    canvas.requestFocusInWindow()
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
                        "Добавить на исследовательское поле" to { addToResearchField() },
                        "Копировать" to { copy() },
                        "Добавить закладку" to { bookmark() },
                    )
                    .forEach { (label, fn) ->
                        add(JMenuItem(label).apply { addActionListener { fn() } })
                    }
            }
        scroll.verticalScrollBar.addAdjustmentListener {
            if (!closed && !restoringPosition) {
                val bar = scroll.verticalScrollBar
                val offset =
                    if (bar.maximum > bar.visibleAmount)
                        (10000L * bar.value / (bar.maximum - bar.visibleAmount)).toInt()
                    else 0
                if (session.data.documents.any { it.id == id }) {
                    document = document.copy(lastPage = page, lastOffset = offset)
                    pendingHistory = true
                    positionTimer.restart()
                }
            }
        }
        render()
        if (session.repository.preference("pdf-side-visible") == "true") {
            val tab = session.repository.preference("pdf-side-tab")?.toIntOrNull() ?: 0
            SwingUtilities.invokeLater { if (!closed) materials(tab) }
        }
    }

    private fun point(e: MouseEvent): Pair<Float, Float>? =
        image?.takeIf { e.x in 0 until it.width && e.y in 0 until it.height }
            ?.let { e.x.toFloat() / it.width to e.y.toFloat() / it.height }

    private fun flushPosition() {
        positionTimer.stop()
        session.saveReadingPosition(id, page, document.lastOffset, pendingHistory)
    }

    fun focusPage() { if (!closed) canvas.requestFocusInWindow() }

    fun go(next: Int, offset: Int = 0, updateHistory: Boolean = true) {
        if (closed || next !in 0 until pageCount) return
        if (next != page) {
            image = null
            text = null
            canvas.repaint()
        }
        page = next
        document = document.copy(lastPage = page, lastOffset = offset.coerceIn(0, 10_000))
        selected = null
        first = null
        restoringPosition = true
        pageField.value = page + 1
        render(document.lastOffset)
        pendingHistory = updateHistory
        positionTimer.restart()
    }

    private fun changeZoom(steps: Double, cursor: Point? = null) {
        val img = image ?: return
        val view = scroll.viewport
        val point = cursor ?: Point(view.width / 2, view.height / 2)
        val anchor = ZoomAnchor(
            (view.viewPosition.x + point.x).toDouble() / img.width,
            (view.viewPosition.y + point.y).toDouble() / img.height, point)
        // Use the pending scale during a burst of wheel events.
        val current = if (fitting == Fit.Manual) zoom else img.width / 800.0
        zoom = (current * 1.15.pow(steps.coerceIn(-5.0, 5.0))).coerceIn(.1, 4.0)
        fitting = Fit.Manual
        zoomLabel.text = "${(zoom * 100).roundToInt()}%"
        render(anchor = anchor)
    }

    private fun render(offset: Int = document.lastOffset, anchor: ZoomAnchor? = null) {
        if (closed) return
        restoringPosition = true
        status = "Загрузка страницы ${page + 1}…"
        selectionLabel.text = status
        canvas.repaint()
        val width = if (fitting == Fit.Manual) (800 * zoom).roundToInt()
            else (scroll.viewport.width - 2).coerceAtLeast(64)
        val height = if (fitting == Fit.Page) (scroll.viewport.height - 2).coerceAtLeast(64) else null
        zoomLabel.text = when (fitting) {
            Fit.Page -> "Страница целиком"
            Fit.Width -> "По ширине"
            Fit.Manual -> "${(zoom * 100).roundToInt()}%"
        }
        pendingRender = RenderRequest(++generation, page, width, height, offset, anchor)
        if (worker == null) startRender()
    }

    private fun showImage(img: BufferedImage, request: RenderRequest) {
        if (closed || generation != request.generation) return
        image = img
        canvas.accessibleContext.accessibleDescription = "Страница ${request.page + 1} из $pageCount"
        canvas.preferredSize = Dimension(img.width, img.height)
        canvas.revalidate()
        canvas.repaint()
        SwingUtilities.invokeLater {
            if (closed || generation != request.generation) return@invokeLater
            val view = scroll.viewport
            val anchor = request.anchor
            if (anchor != null) {
                view.viewPosition = Point(
                    (anchor.x * img.width - anchor.point.x).roundToInt()
                        .coerceIn(0, (img.width - view.width).coerceAtLeast(0)),
                    (anchor.y * img.height - anchor.point.y).roundToInt()
                        .coerceIn(0, (img.height - view.height).coerceAtLeast(0)))
            } else {
                val bar = scroll.verticalScrollBar
                bar.value = ((bar.maximum - bar.visibleAmount).toLong() * request.offset / 10000)
                    .toInt().coerceAtLeast(0)
            }
            restoringPosition = false
            val bar = scroll.verticalScrollBar
            val extent = bar.maximum - bar.visibleAmount
            document = document.copy(lastOffset = if (extent > 0) (10000L * bar.value / extent).toInt() else 0)
            positionTimer.restart()
        }
    }

    private fun startRender() {
        val request = pendingRender ?: return
        pendingRender = null
        worker = object : SwingWorker<PageText?, BufferedImage>() {
            private var rendered: BufferedImage? = null
            private var textError: Exception? = null
            override fun doInBackground(): PageText? {
                rendered = engine.render(request.page, request.width, request.height)
                publish(rendered!!)
                return try { engine.text(request.page) } catch (e: Exception) {
                    textError = e
                    null
                }
            }

            override fun process(chunks: MutableList<BufferedImage>) {
                showImage(chunks.last(), request)
            }

            override fun done() {
                worker = null
                if (!closed && generation == request.generation) {
                    try {
                        text = get()
                        // process() normally paints first; this also covers delayed delivery.
                        if (image !== rendered) rendered?.let { showImage(it, request) }
                        selectionLabel.text = when {
                            textError != null -> "Страница показана; ошибка текстового слоя: ${textError?.message}"
                            text?.glyphs.isNullOrEmpty() -> "Нет текстового слоя; можно добавить закладку или заметку"
                            else -> "Выделите текст; двойной щелчок выбирает слово"
                        }
                    } catch (e: Exception) {
                        restoringPosition = false
                        status = "Не удалось показать страницу: ${(e.cause ?: e).message}"
                        selectionLabel.text = status
                        canvas.repaint()
                    }
                }
                if (!closed) startRender()
            }
        }.apply { execute() }
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
            quoteTitle(selection.first),
            quote = selection.first,
            documentId = id,
            anchor = selection.second,
            type = NoteType.Quote,
            kind = EntryKind.Quote,
        )
    }

    fun quote(folderId: String? = null) {
        app.safe {
            val e = newQuote() ?: return@safe
            session.save(EntryDraft(e, readerFolders = setOfNotNull(folderId)))
            app.openEntry(e.id)
            SwingUtilities.invokeLater { app.editors[e.id]?.quotation?.requestFocusInWindow() }
            canvas.repaint()
        }
    }

    fun showAnchor(quote: String, anchor: Anchor) {
        go(anchor.page, (anchor.rects.firstOrNull()?.top?.times(10000) ?: 0f).toInt())
        selected = quote to anchor
        selectionLabel.text = quote.take(75)
        canvas.repaint()
    }

    fun addToResearchField() {
        app.safe {
            val candidate = newQuote() ?: return@safe
            val anchor = candidate.anchor ?: return@safe
            val existing = session.data.entries.filter { it.documentId == id && it.kind == EntryKind.Quote &&
                it.anchor?.let { a -> a.page == anchor.page && a.start == anchor.start && a.end == anchor.end } == true }
            var entry = candidate
            if (existing.isNotEmpty()) {
                val labels = existing.map { "Использовать: ${it.title} · ${it.id.take(8)}" } + "Создать отдельную запись"
                val chosen = JOptionPane.showInputDialog(app, "Это выделение уже сохранено", "Существующая цитата", JOptionPane.PLAIN_MESSAGE,
                    null, labels.toTypedArray(), labels.first()) ?: return@safe
                val index = labels.indexOf(chosen)
                if (index < existing.size) entry = existing[index]
            }
            val field = app.chooseResearchField() ?: return@safe
            if (entry === candidate) session.save(EntryDraft(entry))
            field.addEntries(listOf(entry.id)); canvas.repaint()
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

    fun bookmark(folderId: String? = null) {
        app.safe {
            val existing = session.data.bookmarks.firstOrNull {
                it.documentId == id && it.page == page && it.offset == document.lastOffset && it.folderId == folderId
            }
            if (existing != null) {
                materials(1)
                materialsPanel?.selectBookmark(existing.id)
                return@safe
            }
            val bookmark = Bookmark(newId(), id, page, "Страница ${page+1}", folderId = folderId, offset = document.lastOffset)
            session.change {
                it.copy(
                    bookmarks = it.bookmarks + bookmark
                )
            }
            materials(1)
            materialsPanel?.selectBookmark(bookmark.id)
        }
    }

    fun pageText() {
        if (closed) return
        val requestedPage = page
        val cached = text
        if (cached?.page == page) showPageText(cached)
        else app.background("Чтение текста страницы…", { engine.text(requestedPage) }) { if (!closed) showPageText(it) }
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
        val research = button("На исследовательское поле (Ctrl+Alt+L)") {
            app.safe { if (selection()) { dialog.dispose(); addToResearchField() } }
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
                add(flow(quote, consp, research, button("Закрыть") { dialog.dispose() }), BorderLayout.SOUTH)
            }
        )
        listOf("control shift Q" to quote, "control alt A" to consp, "control alt L" to research).forEach { (key, button) ->
            dialog.rootPane
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(DesktopPlatform.keyStroke(key), key)
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
        if (query.isBlank() || closed) return
        app.background(
            "Поиск в PDF…",
            {
                buildList {
                    for (p in 0 until pageCount) {
                        if (closed || size >= 2000) break
                        addAll(engine.text(p).matches(query).take(2000 - size))
                    }
                }
            },
        ) { matches ->
            if (closed) return@background
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
        if (closed) return
        val panel = materialsPanel ?: BookMaterialsPanel(app, this, id) { hideMaterials() }.also { materialsPanel = it }
        panel.selectTab(start)
        panel.refresh()
        if (viewerSplit.rightComponent == null) {
            viewerSplit.rightComponent = panel
            viewerSplit.dividerSize = 5
            val available = (viewerSplit.width - 100 - viewerSplit.dividerSize).coerceAtLeast(0)
            viewerSplit.dividerLocation = (viewerSplit.width - materialsWidth.coerceAtMost(available) - viewerSplit.dividerSize).coerceAtLeast(0)
            revalidate()
        }
        session.repository.persistPreference("pdf-side-visible", "true")
    }

    private fun toggleMaterials() {
        if (viewerSplit.rightComponent == null) materials(materialsPanel?.selectedTab ?: 0)
        else hideMaterials()
    }

    private fun saveMaterialsSize() {
        val panel = materialsPanel ?: return
        if (viewerSplit.rightComponent == null) return
        materialsWidth = panel.width.coerceIn(220, 640)
        session.repository.persistPreference("pdf-side-width", materialsWidth.toString())
        session.repository.persistPreference("pdf-side-tab", panel.selectedTab.toString())
    }

    private fun hideMaterials() {
        saveMaterialsSize()
        viewerSplit.rightComponent = null
        viewerSplit.dividerSize = 0
        session.repository.persistPreference("pdf-side-visible", "false")
        revalidate()
        focusPage()
    }

    fun refreshMaterials() {
        materialsPanel?.refresh()
        canvas.repaint()
    }

    override fun close() {
        if (closed) return
        saveMaterialsSize()
        flushPosition()
        closed = true
        generation++
        resizeTimer.stop()
        positionTimer.stop()
        pendingRender = null
        // PDFBox serializes render/text/close. Never wait for that lock on the EDT.
        Thread({
            if (engineDelegate.isInitialized() || worker != null) {
                runCatching { engine.close() }
            }
        }, "readerka-pdf-close").apply { isDaemon = true; start() }
    }
}
