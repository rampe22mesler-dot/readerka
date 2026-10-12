package org.readerka.desktop

import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.geom.*
import javax.swing.*
import org.readerka.data.*
import kotlin.math.*

/** One panel per document. Only headings and resize handles initiate card gestures. */
class ResearchFieldPanel(val app: DesktopWindow, val id: String) : JPanel(BorderLayout()) {
    var field = app.session.data.researchFields.first { it.id == id }
        private set
    val selected = linkedSetOf<String>()
    private val undo = ArrayDeque<ResearchField>()
    private val redo = ArrayDeque<ResearchField>()
    private var saving = false
    private var gesture: ResearchField? = null
    private var origin = Point()
    private var marquee: Rectangle? = null
    private val cards = linkedMapOf<String, Card>()
    private val zoomLabel = JLabel()
    private val hint = JLabel("Заголовок — перемещение · Ctrl — выбор · пустое место — рамка · Alt/средняя кнопка — панорама · Ctrl+колесо — масштаб")
    private val viewTimer = Timer(300) { persistView() }.apply { isRepeats = false }
    private val surface = object : JPanel(null) {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = chromeLineColor()
            val spacing = (40 * field.zoom).coerceAtLeast(8.0)
            var x = (-field.viewX * field.zoom) % spacing
            while (x < width) { var y = (-field.viewY * field.zoom) % spacing
                while (y < height) { g.fillRect(x.toInt(), y.toInt(), 1, 1); y += spacing }; x += spacing
            }
            field.groups.forEach { group -> groupBounds(group.id)?.let { r ->
                g.color = Color(100, 140, 200, 25); g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16)
                g.color = Color(110, 140, 180); g.drawRoundRect(r.x, r.y, r.width, r.height, 16, 16)
                g.font = font.deriveFont(Font.BOLD, 13f)
                g.drawString(group.title, r.x + 10, r.y + 20)
            } }
            field.edges.forEach { edge -> edgePoints(edge)?.let { (a, b) ->
                g.color = when (edge.type) { ResearchRelation.Objects -> Color(180, 80, 70); ResearchRelation.Supports -> Color(60, 140, 100); else -> Color(85, 120, 175) }
                g.stroke = BasicStroke(2f)
                val route = edgeRoute(edge)!!
                g.draw(Path2D.Double().apply { moveTo(a.x,a.y); route.drop(1).forEach { lineTo(it.x,it.y) } })
                val previous = route[route.lastIndex-1]
                val angle = atan2(b.y - previous.y, b.x - previous.x)
                val arrow = Path2D.Double().apply { moveTo(b.x, b.y)
                    lineTo(b.x - 12*cos(angle-.45), b.y - 12*sin(angle-.45))
                    lineTo(b.x - 12*cos(angle+.45), b.y - 12*sin(angle+.45)); closePath() }
                g.fill(arrow)
                g.font = font.deriveFont(12f)
                val label = edgeText(edge)
                val bounds = edgeLabelBounds(edge, g.fontMetrics)!!
                g.color = background; g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6)
                g.color = foreground; g.font = font.deriveFont(12f)
                g.drawString(label, bounds.x + 5, bounds.y + bounds.height - 5)
            } }
            marquee?.let { g.color = Color(65, 120, 200, 60); g.fill(it); g.color = Color(65, 120, 200); g.draw(it) }
            if (field.placements.isEmpty()) {
                g.color = foreground; g.drawString("Добавьте цитату из PDF или запись из библиотеки.", 30, 50)
            }
            g.dispose()
        }
    }

    init {
        name = "research:$id"
        surface.name = "research-surface"
        surface.isFocusable = true
        surface.background = UIManager.getColor("Panel.background")
        add(flow(
            button("+ Материал") { app.safe { chooseEntry() } },
            button("+ Заметка") { app.safe { createEntry(false) } },
            button("+ Тезис") { app.safe { createEntry(true) } },
            button("Связь") { app.safe { linkSelected() } },
            button("Группа") { app.safe { createGroup() } },
            button("−") { zoom(field.zoom / 1.2, Point(surface.width/2, surface.height/2)); persistView() },
            zoomLabel,
            button("+") { zoom(field.zoom * 1.2, Point(surface.width/2, surface.height/2)); persistView() },
            button("Всё") { fit(false) }, button("Выбранное") { fit(true) },
            button("Отмена") { app.safe { undo() } }, button("Повтор") { app.safe { redo() } },
            button("В конспект…") { app.safe { assemble() } },
            button("Поле…") { app.safe { fieldProperties() } },
        ), BorderLayout.NORTH)
        add(surface, BorderLayout.CENTER)
        add(hint.apply { border = BorderFactory.createEmptyBorder(4, 8, 4, 8); toolTipText = text }, BorderLayout.SOUTH)
        installSurfaceGestures()
        surface.transferHandler = object : TransferHandler() {
            override fun canImport(s: TransferSupport) = s.isDataFlavorSupported(researchEntryFlavor)
            override fun importData(s: TransferSupport): Boolean {
                if (!canImport(s)) return false
                val ids = s.transferable.getTransferData(researchEntryFlavor) as? List<*> ?: return false
                val point = if (s.isDrop) s.dropLocation.dropPoint else Point(40, 40)
                var ok = false
                app.safe { addEntries(ids.filterIsInstance<String>(), point); ok = true }
                return ok
            }
        }
        listOf("control Z" to { undo() }, "control Y" to { redo() }, "DELETE" to { removeSelected() },
            "control A" to { selected.addAll(field.placements.map { it.id }); refreshSelection() }).forEachIndexed { i, (key, action) ->
            // Focused text components keep their own selection/copy shortcuts.
            surface.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "research-$i")
            surface.actionMap.put("research-$i", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { app.safe(action) } })
        }
        refreshCards()
    }

    fun refreshFromSaved() {
        if (saving || gesture != null) return
        val saved = app.session.data.researchFields.firstOrNull { it.id == id } ?: return
        val updated = saved.copy(zoom = field.zoom, viewX = field.viewX, viewY = field.viewY)
        if (updated != field) { undo.clear(); redo.clear(); field = updated }
        refreshCards()
    }

    private fun persist(next: ResearchField) {
        saving = true
        try { app.session.change { data -> data.copy(researchFields = data.researchFields.map { if (it.id == id) next else it }) } }
        finally { saving = false }
    }

    private fun commit(next: ResearchField, before: ResearchField = field) {
        if (next == before) return
        try { persist(next) } catch (e: Exception) { field = before; refreshCards(); throw e }
        undo.addLast(before); while (undo.size > 100) undo.removeFirst()
        redo.clear(); field = next; refreshCards()
    }

    private fun persistView() {
        viewTimer.stop()
        app.safe {
            val saved = app.session.data.researchFields.firstOrNull { it.id == id } ?: return@safe
            if (gesture == null && saved != field) {
                try { persist(field) } catch (e: Exception) { field = saved; refreshCards(); throw e }
            }
        }
    }

    fun flush() { if (gesture == null) persistView() }
    fun disposePanel() { viewTimer.stop() }
    fun refreshTheme() { surface.background = UIManager.getColor("Panel.background"); layoutCards() }

    fun undo() {
        if (undo.isEmpty()) return
        val next = undo.last().copy(zoom = field.zoom, viewX = field.viewX, viewY = field.viewY)
        persist(next); undo.removeLast(); redo.addLast(field); field = next; refreshCards()
    }
    fun redo() {
        if (redo.isEmpty()) return
        val next = redo.last().copy(zoom = field.zoom, viewX = field.viewX, viewY = field.viewY)
        persist(next); redo.removeLast(); undo.addLast(field); field = next; refreshCards()
    }

    fun addEntries(ids: List<String>, at: Point = Point(40, 45)) {
        var next = field
        val added = mutableListOf<String>()
        ids.distinct().forEachIndexed { index, entryId ->
            val entry = app.session.data.entries.firstOrNull { it.id == entryId } ?: return@forEachIndexed
            val p = world(Point(at.x + index*30, at.y + index*30))
            val card = ResearchPlacement(newId(), entry.id, p.x, p.y,
                kind = if (entry.kind == EntryKind.Quote) ResearchCardKind.Quote else if (entry.type == NoteType.Idea) ResearchCardKind.Thesis else ResearchCardKind.Note)
            next = next.copy(placements = next.placements + card); added += card.id
        }
        commit(next); selected.clear(); selected.addAll(added); refreshSelection()
    }

    private fun chooseEntry() {
        val entries = app.session.data.entries.filter { it.kind != EntryKind.Consp }
        if (entries.isEmpty()) { JOptionPane.showMessageDialog(this, "В библиотеке нет цитат и заметок"); return }
        val list = JList(entries.map { "${it.title} · ${it.id.take(8)}" }.toTypedArray())
        if (JOptionPane.showConfirmDialog(this, JScrollPane(list).apply { preferredSize = Dimension(500, 300) },
                "Добавить на исследовательское поле", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            addEntries(list.selectedIndices.map { entries[it].id })
    }

    private fun createEntry(thesis: Boolean) {
        val title = JTextField(if (thesis) "Новый тезис" else "Новая заметка", 28)
        val text = JTextArea(8, 36).apply { lineWrap = true; wrapStyleWord = true }
        if (JOptionPane.showConfirmDialog(this, fieldPanel("Название" to title, "Текст" to JScrollPane(text)),
                if (thesis) "Тезис" else "Самостоятельная заметка", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        require(title.text.isNotBlank()) { "Введите название" }
        val entry = Entry(newId(), title.text.trim(), kind = EntryKind.Note,
            type = if (thesis) NoteType.Idea else NoteType.GeneralNote).withContent(RichText.plain(text.text))
        app.session.save(EntryDraft(entry)); addEntries(listOf(entry.id))
    }

    fun removeSelected() { commit(field.removePlacements(selected.toSet())); selected.clear(); refreshSelection() }

    private fun projectComment(cardId: String) {
        val card = field.placements.first { it.id == cardId }
        val area = JTextArea(card.projectComment, 8, 40).apply { lineWrap = true; wrapStyleWord = true }
        if (JOptionPane.showConfirmDialog(this, JScrollPane(area), "Применение в этом проекте · исходная запись сохраняется",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            commit(field.copy(placements = field.placements.map { if (it.id == cardId) it.copy(projectComment = area.text) else it }))
    }

    private fun linkSelected(from: String? = null) {
        require(field.placements.size >= 2) { "Добавьте хотя бы две карточки" }
        val chosen = field.placements.filter { it.id in selected }
        val source = from ?: chosen.firstOrNull()?.id ?: error("Выберите исходную карточку")
        val targets = field.placements.filter { it.id != source }
        val target = JComboBox(targets.map { cardTitle(it) }.toTypedArray()).apply {
            selectedIndex = targets.indexOfFirst { it.id == chosen.getOrNull(1)?.id }.coerceAtLeast(0)
        }
        val type = JComboBox(ResearchRelation.entries.toTypedArray())
        val label = JTextField(28)
        if (JOptionPane.showConfirmDialog(this, fieldPanel("Из: ${cardTitle(field.placements.first { it.id == source })}" to JLabel("Направленная связь"),
                "В карточку" to target, "Тип" to type, "Подпись (необязательно)" to label), "Добавить связь", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            commit(field.copy(edges = field.edges + ResearchEdge(newId(), source, targets[target.selectedIndex].id,
                type.selectedItem as ResearchRelation, label.text)))
    }

    private fun editEdge(edge: ResearchEdge) {
        val type = JComboBox(ResearchRelation.entries.toTypedArray()).apply { selectedItem = edge.type }
        val label = JTextField(edge.label, 28)
        val result = JOptionPane.showOptionDialog(this, fieldPanel("Тип" to type, "Подпись" to label), "Связь",
            JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, arrayOf("Сохранить", "Удалить связь", "Отмена"), "Сохранить")
        if (result == 0) commit(field.copy(edges = field.edges.map { if (it.id == edge.id) it.copy(type = type.selectedItem as ResearchRelation, label = label.text) else it }))
        if (result == 1) commit(field.copy(edges = field.edges.filterNot { it.id == edge.id }))
    }

    private fun createGroup() {
        require(selected.isNotEmpty()) { "Выберите карточки для группы" }
        val title = JOptionPane.showInputDialog(this, "Название группы")?.trim()?.takeIf { it.isNotBlank() } ?: return
        val group = ResearchGroup(newId(), title)
        commit(field.copy(groups = field.groups + group, placements = field.placements.map { if (it.id in selected) it.copy(groupId = group.id) else it }))
    }

    private fun groupMenu(group: ResearchGroup, e: MouseEvent) = JPopupMenu().apply {
        fun item(text: String, fn: () -> Unit) { add(JMenuItem(text).apply { addActionListener { app.safe(fn) } }) }
        item("Выбрать группу") { selected.clear(); selected.addAll(field.placements.filter { it.groupId == group.id }.map { it.id }); refreshSelection() }
        item("Переименовать") {
            val title = JOptionPane.showInputDialog(this@ResearchFieldPanel, "Название группы", group.title)?.trim()?.takeIf { it.isNotBlank() } ?: return@item
            commit(field.copy(groups = field.groups.map { if (it.id == group.id) it.copy(title = title) else it }))
        }
        item("Расформировать группу · сохранить карточки") { commit(field.copy(groups = field.groups.filterNot { it.id == group.id },
            placements = field.placements.map { if (it.groupId == group.id) it.copy(groupId = null) else it })) }
    }.show(surface, e.x, e.y)

    private fun fieldProperties() {
        val title = JTextField(field.title, 30)
        if (JOptionPane.showConfirmDialog(this, title, "Название исследовательского поля", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            require(title.text.isNotBlank()) { "Введите название" }; commit(field.copy(title = title.text.trim()))
        }
    }

    private fun cardTitle(card: ResearchPlacement) = (app.session.data.entries.firstOrNull { it.id == card.entryId }?.title ?: "Запись") + " · " + card.id.take(6)

    private fun assemble() {
        require(selected.isNotEmpty()) { "Выберите материалы для сборки" }
        val order = (field.sequence.filter { it in selected } + field.placements.filter { it.id in selected && it.id !in field.sequence }.map { it.id }).toMutableList()
        val model = DefaultListModel<String>()
        fun fill() { model.clear(); order.forEachIndexed { i, id -> model.addElement("${i+1}. ${cardTitle(field.placements.first { it.id == id })}") } }
        fill()
        val list = JList(model).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION; selectedIndex = 0 }
        fun move(delta: Int) { val i = list.selectedIndex; val j = i + delta
            if (i >= 0 && j in order.indices) { val value = order.removeAt(i); order.add(j, value); fill(); list.selectedIndex = j } }
        val consp = app.session.data.entries.filter { it.kind == EntryKind.Consp }
        require(consp.isNotEmpty()) { "Сначала создайте конспект" }
        val destination = JComboBox(consp.map { "${it.title} · ${it.id.take(8)}" }.toTypedArray())
        val mode = JComboBox(arrayOf("Ссылками", "Содержимым со ссылками на источники"))
        val panel = JPanel(BorderLayout(4, 8)).apply {
            add(JLabel("Подтвердите порядок материалов. Расположение на поле его не задаёт."), BorderLayout.NORTH)
            add(JScrollPane(list).apply { preferredSize = Dimension(540, 260) }, BorderLayout.CENTER)
            add(fieldPanel("Порядок" to flow(button("Выше") { move(-1) }, button("Ниже") { move(1) }),
                "Добавить в конец конспекта" to destination, "Вставка" to mode), BorderLayout.SOUTH)
        }
        if (JOptionPane.showConfirmDialog(this, panel, "Сборка конспекта", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        app.flushEditors()
        val target = consp[destination.selectedIndex]
        val draft = app.session.draft(target.id)
        val content = ResearchAssembly.append(draft.entry.richText(), field, order, app.session.data.entries,
            if (mode.selectedIndex == 0) InsertionMode.LinkCard else InsertionMode.Content)
        val sourceIds = field.placements.filter { it.id in order }.flatMap { card -> app.session.data.entries.first { it.id == card.entryId }.sourceBooks() }
        app.session.save(draft.copy(entry = draft.entry.copy(sources = (draft.entry.sources + sourceIds).distinct()).withContent(content)), "Сборка исследовательского поля")
        commit(field.copy(sequence = order + field.sequence.filterNot { it in order }))
        app.editors[target.id]?.refreshFromSaved(); app.openEntryBesideField(target.id, this)
    }

    private fun world(p: Point) = Point2D.Double(field.viewX + p.x / field.zoom, field.viewY + p.y / field.zoom)
    private fun screen(x: Double, y: Double) = Point2D.Double((x - field.viewX)*field.zoom, (y - field.viewY)*field.zoom)
    private fun bounds(card: ResearchPlacement): Rectangle {
        val p = screen(card.x, card.y)
        return Rectangle(p.x.roundToInt(), p.y.roundToInt(), (card.width*field.zoom).roundToInt(), (card.height*field.zoom).roundToInt())
    }
    private fun groupBounds(id: String): Rectangle? = field.placements.filter { it.groupId == id }.map(::bounds).reduceOrNull { a, b -> a.union(b) }?.apply { grow(14, 14); y -= 26; height += 26 }
    private fun edgePoints(edge: ResearchEdge): Pair<Point2D.Double, Point2D.Double>? {
        val a = field.placements.firstOrNull { it.id == edge.from } ?: return null
        val b = field.placements.firstOrNull { it.id == edge.to } ?: return null
        return screen(a.x+a.width/2, a.y+a.height) to screen(b.x+b.width/2, b.y+b.height)
    }
    // Separate channels keep relation labels away from selectable card text.
    private fun edgeRoute(edge: ResearchEdge): List<Point2D.Double>? = edgePoints(edge)?.let { (a,b) ->
        val bottom = field.placements.maxOfOrNull { it.y+it.height } ?: 0.0
        val y = screen(0.0,bottom).y + 32 + field.edges.indexOf(edge)*28
        listOf(a, Point2D.Double(a.x,y), Point2D.Double(b.x,y), b)
    }
    private fun edgeText(edge: ResearchEdge) = edge.type.label + edge.label.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    private fun edgeLabelBounds(edge: ResearchEdge, metrics: FontMetrics): Rectangle? = edgePoints(edge)?.let { (a,b) ->
        val width = metrics.stringWidth(edgeText(edge)) + 10
        val lane = edgeRoute(edge)!![1].y
        Rectangle(((a.x+b.x-width)/2).toInt(), (lane-11).toInt(), width, 22)
    }

    private fun zoom(value: Double, pivot: Point) {
        val w = world(pivot); val z = value.coerceIn(.01, 3.0)
        field = field.copy(zoom = z, viewX = w.x-pivot.x/z, viewY = w.y-pivot.y/z)
        layoutCards()
    }
    fun fit(selectionOnly: Boolean) {
        val items = field.placements.filter { !selectionOnly || it.id in selected }
        if (items.isEmpty()) return
        val left = items.minOf { it.x }-30; val top = items.minOf { it.y }-60
        val right = items.maxOf { it.x+it.width }+30; val bottom = items.maxOf { it.y+it.height }+30
        val availableHeight = (surface.height - if (selectionOnly) 0 else field.edges.size*28+45).coerceAtLeast(1)
        val z = min(surface.width.coerceAtLeast(1)/(right-left), availableHeight/(bottom-top)).coerceIn(.01, 3.0)
        field = field.copy(zoom = z, viewX = (left+right)/2-surface.width/2/z, viewY = (top+bottom)/2-availableHeight/2/z)
        layoutCards(); persistView()
    }

    private fun refreshCards() {
        selected.retainAll(field.placements.map { it.id }.toSet())
        val ids = field.placements.map { it.id }.toSet()
        cards.keys.filterNot { it in ids }.toList().forEach { surface.remove(cards.remove(it)) }
        field.placements.forEach { card -> cards.getOrPut(card.id) { Card(card.id).also { surface.add(it) } }.refresh(card) }
        layoutCards()
    }
    private fun layoutCards() {
        field.placements.forEach { card -> cards[card.id]?.let { it.bounds = bounds(card); it.scale() } }
        zoomLabel.text = "${(field.zoom*100).roundToInt()}%"
        surface.revalidate(); refreshSelection()
    }
    private fun refreshSelection() {
        cards.forEach { (id, card) -> card.border = BorderFactory.createLineBorder(if (id in selected) Color(65, 125, 210) else chromeLineColor(), if (id in selected) 2 else 1) }
        surface.repaint()
    }

    private fun installSurfaceGestures() {
        val mouse = object : MouseAdapter() {
            private var panning = false
            private var movingGroup = false
            private var prior = emptySet<String>()
            override fun mousePressed(e: MouseEvent) {
                surface.requestFocusInWindow(); origin = e.point
                val group = field.groups.lastOrNull { g -> groupBounds(g.id)?.let { it.contains(e.point) && e.y < it.y+28 } == true }
                if (e.isPopupTrigger && group != null) { groupMenu(group, e); return }
                if (SwingUtilities.isRightMouseButton(e)) return
                gesture = field; panning = SwingUtilities.isMiddleMouseButton(e) || e.isAltDown
                movingGroup = !panning && group != null
                prior = if (e.isControlDown || e.isShiftDown) selected.toSet() else emptySet()
                if (movingGroup) { selected.clear(); selected.addAll(field.placements.filter { it.groupId == group!!.id }.map { it.id }) }
                else if (!panning && prior.isEmpty()) selected.clear()
                refreshSelection()
            }
            override fun mouseDragged(e: MouseEvent) {
                val before = gesture ?: return
                if (panning) { field = before.copy(viewX = before.viewX-(e.x-origin.x)/before.zoom, viewY = before.viewY-(e.y-origin.y)/before.zoom); layoutCards() }
                else if (movingGroup) { field = before.move(selected, (e.x-origin.x)/before.zoom, (e.y-origin.y)/before.zoom); layoutCards() }
                else { marquee = Rectangle(min(origin.x,e.x), min(origin.y,e.y), abs(e.x-origin.x), abs(e.y-origin.y))
                    selected.clear(); selected.addAll(prior); selected.addAll(field.placements.filter { bounds(it).intersects(marquee) }.map { it.id }); refreshSelection() }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) field.groups.lastOrNull { groupBounds(it.id)?.contains(e.point) == true }?.let { groupMenu(it, e) }
                val before = gesture; gesture = null; marquee = null
                if (before != null) app.safe { if (panning) persistView() else if (movingGroup) commit(field, before) }
                surface.repaint()
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val metrics = surface.getFontMetrics(surface.font.deriveFont(12f))
                    field.edges.lastOrNull { edgeLabelBounds(it, metrics)?.contains(e.point) == true || edgeRoute(it)?.zipWithNext()?.any { (a,b) -> Line2D.ptSegDist(a.x,a.y,b.x,b.y,e.x.toDouble(),e.y.toDouble()) < 7 } == true }?.let { app.safe { editEdge(it) } }
                }
            }
        }
        surface.addMouseListener(mouse); surface.addMouseMotionListener(mouse)
        surface.addMouseWheelListener { e ->
            if (gesture != null) return@addMouseWheelListener
            if (e.isControlDown) zoom(field.zoom * 1.12.pow(-e.preciseWheelRotation), e.point)
            else { field = if (e.isShiftDown) field.copy(viewX = field.viewX + e.preciseWheelRotation*50/field.zoom)
                else field.copy(viewY = field.viewY + e.preciseWheelRotation*50/field.zoom); layoutCards() }
            e.consume(); viewTimer.restart()
        }
    }

    private inner class Card(val cardId: String) : JPanel(BorderLayout()) {
        private val header = JLabel()
        private val body = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; margin = Insets(6,8,6,8); name = "research-card-text" }
        private val grip = object : JLabel() {
            init { preferredSize = Dimension(16,16); cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR); toolTipText = "Изменить размер" }
            override fun paintComponent(g: Graphics) { super.paintComponent(g); g.color = mutedColor()
                for (d in listOf(4,8,12)) g.drawLine(width-d,height-2,width-2,height-d)
            }
        }
        private val menu = iconButton("more", "Действия карточки") { popup().show(this, 10, 28) }
        init {
            name = "research-card:$cardId"
            header.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            header.border = BorderFactory.createEmptyBorder(5, 8, 5, 4)
            add(JPanel(BorderLayout()).apply { add(header, BorderLayout.CENTER); add(menu, BorderLayout.EAST) }, BorderLayout.NORTH)
            add(JScrollPane(body).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
            add(grip, BorderLayout.SOUTH)
            body.addMouseListener(object : MouseAdapter() { override fun mousePressed(e: MouseEvent) {
                if (!e.isControlDown && cardId !in selected) selected.clear()
                selected.add(cardId); refreshSelection()
            } })
            fun gesture(resize: Boolean) = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) { popup().show(e.component, e.x, e.y); return }
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    surface.requestFocusInWindow()
                    if (e.isControlDown || e.isShiftDown) { if (!selected.add(cardId)) selected.remove(cardId) }
                    else if (cardId !in selected) { selected.clear(); selected.add(cardId) }
                    if (resize) { selected.clear(); selected.add(cardId) }
                    refreshSelection(); origin = SwingUtilities.convertPoint(e.component, e.point, surface); gesture = field
                }
                override fun mouseDragged(e: MouseEvent) {
                    val before = gesture ?: return
                    if (cardId !in selected) return
                    val point = SwingUtilities.convertPoint(e.component, e.point, surface)
                    val dx = (point.x-origin.x)/before.zoom; val dy = (point.y-origin.y)/before.zoom
                    field = if (resize) before.copy(placements = before.placements.map {
                        if (it.id == cardId) it.copy(width = (it.width+dx).coerceIn(180.0,2000.0), height = (it.height+dy).coerceIn(140.0,3000.0)) else it
                    }) else before.move(selected, dx, dy)
                    layoutCards()
                }
                override fun mouseReleased(e: MouseEvent) {
                    val before = gesture; gesture = null
                    if (before != null) app.safe { commit(field, before) }
                    if (e.isPopupTrigger) popup().show(e.component, e.x, e.y)
                }
            }
            val drag = gesture(false); header.addMouseListener(drag); header.addMouseMotionListener(drag)
            val resize = gesture(true); grip.addMouseListener(resize); grip.addMouseMotionListener(resize)
        }
        fun scale() {
            val size = (13*field.zoom).coerceIn(11.0, 30.0).toFloat()
            body.font = UIManager.getFont("TextArea.font").deriveFont(size)
            header.font = UIManager.getFont("Label.font").deriveFont(Font.BOLD, size)
        }
        fun refresh(card: ResearchPlacement) {
            val entry = app.session.data.entries.firstOrNull { it.id == card.entryId } ?: return
            header.text = when (card.kind) { ResearchCardKind.Quote -> "Цитата"; ResearchCardKind.Note -> "Заметка"; ResearchCardKind.Thesis -> "Тезис" } + " · " + entry.title
            header.toolTipText = header.text
            val source = app.session.data.documents.firstOrNull { it.id == entry.documentId }
            val text = buildString {
                if (card.kind == ResearchCardKind.Quote) appendLine(entry.quote) else appendLine(entry.richText().text)
                source?.let { appendLine("\n${it.authors.ifBlank { "Автор не указан" }} · ${it.title}" + (entry.anchor?.let { a -> " · стр. ${a.page+1}" } ?: "")) }
                if (card.kind == ResearchCardKind.Quote && entry.richText().text.isNotBlank()) appendLine("\nКомментарий записи:\n${entry.richText().text}")
                if (card.projectComment.isNotBlank()) appendLine("\nПрименение в проекте:\n${card.projectComment}")
            }.trimEnd()
            if (body.text != text) { body.text = text; body.caretPosition = 0 }
        }
        private fun popup() = JPopupMenu().apply {
            val card = field.placements.first { it.id == cardId }
            val entry = app.session.data.entries.firstOrNull { it.id == card.entryId }
            fun item(text: String, enabled: Boolean = true, fn: () -> Unit) { add(JMenuItem(text).apply { isEnabled = enabled; addActionListener { app.safe(fn) } }) }
            item("Открыть источник", entry?.documentId != null) { entry?.let { app.openResearchSource(it, this@ResearchFieldPanel) } }
            item("Открыть запись") { app.openEntryBesideField(card.entryId, this@ResearchFieldPanel) }
            item("Применение в этом проекте…") { projectComment(cardId) }
            item("Добавить связь") { linkSelected(cardId) }
            item("Добавить выбранные в эту группу", card.groupId != null && selected.isNotEmpty()) {
                commit(field.copy(placements = field.placements.map { if (it.id in selected) it.copy(groupId = card.groupId) else it })) }
            item("Убрать из группы", card.groupId != null) { commit(field.copy(placements = field.placements.map { if (it.id == cardId) it.copy(groupId = null) else it })) }
            addSeparator()
            item("Убрать карточку с поля · сохранить запись") { commit(field.removePlacements(setOf(cardId))) }
            item("Убрать выбранные карточки · сохранить записи", selected.isNotEmpty()) { removeSelected() }
        }
    }
}

internal val researchEntryFlavor = DataFlavor("application/x-readerka-research-entries;class=java.util.List", "Записи для поля")
