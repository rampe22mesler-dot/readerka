package org.readerka.desktop

import java.awt.*
import java.awt.event.*
import java.awt.geom.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readerka.data.*
import kotlin.math.*

@Serializable private data class GraphPoint(val x: Double, val y: Double)
@Serializable private data class GraphView(val zoom: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0,
    val positions: Map<String, GraphPoint> = emptyMap())
private data class GraphNeighbor(val id: String, val label: String) { override fun toString() = label }

class LibraryGraphPanel(private val app: DesktopWindow) : JPanel(BorderLayout()) {
    var graph = LibraryGraph(emptyList(), emptyList())
        private set
    var visibleGraph = graph
        private set
    var selectedId: String? = null
        private set
    private var snapshot: LibrarySnapshot? = null
    private var disposed = false
    private var worker: SwingWorker<Pair<LibraryGraph, Map<String, Point2D.Double>>, Unit>? = null
    internal val positions = linkedMapOf<String, Point2D.Double>()
    private val saved = runCatching { app.session.repository.preference("library-graph-view")?.let { Json.decodeFromString<GraphView>(it) } }.getOrNull()
    private var zoom = saved?.zoom?.takeIf { it.isFinite() }?.coerceIn(.005, 4.0) ?: 1.0
    private var viewX = saved?.x?.takeIf { it.isFinite() } ?: 0.0
    private var viewY = saved?.y?.takeIf { it.isFinite() } ?: 0.0
    private var initialFit = saved == null
    private val query = JTextField(18).apply {
        putClientProperty("JTextField.placeholderText", "Поиск по графу")
        toolTipText = "Поиск по названию и тексту; результаты показываются с прямыми соседями"
        accessibleContext.accessibleName = "Поиск по графу"
    }
    private val kinds = GraphKind.entries.associateWith { JCheckBox(it.label, true) }
    private val local = JCheckBox("Окрестность")
    private val depth = JComboBox(arrayOf("1 шаг", "2 шага", "3 шага"))
    private val labels = JCheckBox("Подписи связей")
    private val counts = JLabel("Построение графа…")
    private val heading = JLabel("Связи библиотеки")
    private val description = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 6; border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
    private val neighbors = DefaultListModel<GraphNeighbor>()
    private val neighborList = JList(neighbors).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION; visibleRowCount = 8; accessibleContext.accessibleName = "Связи выбранного узла" }
    private val open = button("Открыть материал") { openSelected() }
    private val searchTimer = Timer(180) { applyFilters() }.apply { isRepeats = false }
    private val refreshTimer = Timer(250) { if (snapshot != app.session.data) rebuild() }.apply { isRepeats = false }
    private var origin: Point? = null
    private var dragging: String? = null
    private var moved = false
    private var lastCanvasSize = Dimension()
    internal val surface = object : JPanel() {
        override fun paintComponent(g: Graphics) { super.paintComponent(g); paintGraph(g as Graphics2D) }
        override fun getToolTipText(e: MouseEvent): String? = hit(e.point)?.let { id ->
            graph.byId[id]?.let { "${it.title} · ${it.kind.label} · ${graph.incident[id].orEmpty().size} связей" }
        }
    }

    init {
        minimumSize = Dimension(200, 200)
        surface.name = "library-graph-surface"; surface.background = documentColor(); surface.isFocusable = true
        surface.accessibleContext.accessibleName = "Граф библиотеки"
        ToolTipManager.sharedInstance().registerComponent(surface)
        saved?.positions?.filterValues { it.x.isFinite() && it.y.isFinite() }?.forEach { (id, p) -> positions[id] = Point2D.Double(p.x, p.y) }
        val inspect = JToggleButton("Связи", true)
        val tools = flow(query,
            button("Вписать") { fit() }, button("+") { changeZoom(1.25, Point(surface.width/2, surface.height/2)) },
            button("−") { changeZoom(.8, Point(surface.width/2, surface.height/2)) },
            button("Разложить") { rebuild(rearrange = true) }, button("Обновить") { app.flushEditors(); rebuild() }, local, depth, labels, inspect)
        val filters = flow(*kinds.values.toTypedArray())
        val header = object : JPanel(BorderLayout()) {
            override fun getPreferredSize(): Dimension {
                val w = parent?.width?.takeIf { it > 0 } ?: 1000
                // Measure both wrapping rows at the new width before BorderLayout allocates their height.
                tools.setSize(w, tools.height); filters.setSize(w, filters.height)
                return Dimension(w, tools.preferredSize.height + filters.preferredSize.height)
            }
        }.apply { add(tools, BorderLayout.NORTH); add(filters, BorderLayout.CENTER) }
        add(header, BorderLayout.NORTH)
        val inspector = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            minimumSize = Dimension(180, 120); preferredSize = Dimension(285, 500)
            add(JPanel(BorderLayout()).apply { add(heading, BorderLayout.NORTH); add(JScrollPane(description), BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(JScrollPane(neighborList), BorderLayout.CENTER)
            add(flow(open, button("К выбранному") { neighbors.getElementAtOrNull(neighborList.selectedIndex)?.let { select(it.id); center(it.id) } }), BorderLayout.SOUTH)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, surface, inspector).apply { resizeWeight = 1.0; dividerSize = 5; border = null }
        fun showInspector(show: Boolean) {
            inspect.isSelected = show; inspector.isVisible = show; split.dividerSize = if (show) 5 else 0
            if (show) split.dividerLocation = (width - if (width < 780) 200 else 285).coerceAtLeast(width / 2)
            split.revalidate()
        }
        inspect.addActionListener { showInspector(inspect.isSelected) }
        add(split, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
            add(counts, BorderLayout.NORTH)
            add(JLabel("Колесо — масштаб · фон — панорама · узел — перемещение · двойной щелчок — открыть").apply { toolTipText = text }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        var narrow = false
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if ((width < 640) != narrow) { narrow = width < 640; showInspector(!narrow) }
                else if (width > 0 && inspector.isVisible) split.dividerLocation = (width - if (width < 780) 200 else 285).coerceAtLeast(width / 2)
                header.revalidate()
            }
        })
        surface.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (initialFit && visibleGraph.nodes.isNotEmpty()) fit()
                else if (lastCanvasSize.width > 0) { viewX += (surface.width-lastCanvasSize.width)/2/zoom; viewY += (surface.height-lastCanvasSize.height)/2/zoom }
                lastCanvasSize = surface.size
            }
        })
        query.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = searchTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = searchTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = searchTimer.restart()
        })
        kinds.forEach { (kind, box) -> box.foreground = color(kind); box.addActionListener { applyFilters() } }
        local.addActionListener { if (local.isSelected && selectedId == null) local.isSelected = false; applyFilters(); fit() }
        depth.addActionListener { applyFilters(); if (local.isSelected) fit() }
        labels.addActionListener { surface.repaint() }
        neighborList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) neighbors.getElementAtOrNull(neighborList.selectedIndex)?.let { select(it.id); center(it.id) } }
        })
        installGestures()
        updateInspector()
        rebuild()
    }

    private fun <T> DefaultListModel<T>.getElementAtOrNull(index: Int): T? = if (index in 0 until size()) getElementAt(index) else null

    fun refreshFromSaved() { if (snapshot != app.session.data && !disposed) refreshTimer.restart() }
    fun refreshTheme() { surface.background = documentColor(); kinds.forEach { (kind, box) -> box.foreground = color(kind) }; surface.repaint() }
    fun flush() {
        if (snapshot == null) return
        app.session.repository.persistPreference("library-graph-view", Json.encodeToString(GraphView(zoom, viewX, viewY,
            positions.filterKeys { it in graph.byId }.mapValues { GraphPoint(it.value.x, it.value.y) })))
    }
    fun disposePanel() { disposed = true; worker?.cancel(true); searchTimer.stop(); refreshTimer.stop(); ToolTipManager.sharedInstance().unregisterComponent(surface) }

    private fun rebuild(rearrange: Boolean = false) {
        if (disposed) return
        refreshTimer.stop(); worker?.cancel(true)
        val data = app.session.data
        val positioned = positions.keys.toSet()
        counts.text = "Построение графа…"
        worker = object : SwingWorker<Pair<LibraryGraph, Map<String, Point2D.Double>>, Unit>() {
            override fun doInBackground(): Pair<LibraryGraph, Map<String, Point2D.Double>> {
                val built = LibraryGraph.build(data)
                val layout = if (rearrange || built.nodes.any { it.id !in positioned })
                    LibraryGraphLayout.arrange(built) { isCancelled } else emptyMap()
                return built to layout
            }
            override fun done() {
                if (isCancelled || disposed) return
                app.safe {
                    val (built, layout) = get()
                    snapshot = data; graph = built
                    positions.keys.retainAll(built.byId.keys)
                    layout.forEach { (id, point) -> if (rearrange || id !in positions) positions[id] = point }
                    if (selectedId !in graph.byId) { selectedId = null; local.isSelected = false }
                    applyFilters()
                    if (initialFit || rearrange) fit()
                }
            }
        }.also { it.execute() }
    }

    private fun applyFilters() {
        visibleGraph = graph.visible(kinds.filterValues { it.isSelected }.keys, query.text, if (local.isSelected) selectedId else null, depth.selectedIndex+1)
        counts.text = "Узлы: ${visibleGraph.nodes.size} / ${graph.nodes.size} · связи: ${visibleGraph.edges.size} / ${graph.edges.size}" +
            if (query.text.isNotBlank()) " · поиск и прямые соседи" else ""
        updateInspector(); surface.repaint()
    }

    fun select(id: String?) { selectedId = id?.takeIf { it in graph.byId }; if (selectedId == null) local.isSelected = false; applyFilters() }

    private fun updateInspector() {
        val node = graph.byId[selectedId]
        heading.text = node?.kind?.label ?: "Связи библиотеки"
        description.text = node?.let { "${it.title}\n\n${it.detail.take(6000)}\n\nСвязей: ${graph.incident[it.id].orEmpty().size}" }
            ?: "Выберите узел, чтобы увидеть все его связи. Темы берутся из тегов, папки показаны отдельно. Авторы нескольких книг объединяются по написанию имени; соавторов разделяет точка с запятой."
        description.caretPosition = 0
        neighbors.clear()
        graph.incident[selectedId].orEmpty().sortedBy { graph.byId[if (it.from == selectedId) it.to else it.from]?.title }.forEach { e ->
            val target = if (e.from == selectedId) e.to else e.from
            val arrow = if (e.from == selectedId) "→" else "←"
            neighbors.addElement(GraphNeighbor(target, "$arrow ${e.relation} · ${graph.byId[target]?.title}" + if (e.context.isNotBlank()) " · ${e.context}" else ""))
        }
        open.isEnabled = node?.kind in setOf(GraphKind.Book, GraphKind.Quote, GraphKind.Note, GraphKind.Consp, GraphKind.Bookmark, GraphKind.Field)
    }

    private fun openSelected() {
        val node = graph.byId[selectedId] ?: return
        app.safe {
            when (node.kind) {
                GraphKind.Book -> app.openPdf(node.sourceId, right = true)
                GraphKind.Quote, GraphKind.Note, GraphKind.Consp -> app.openEntry(node.sourceId, right = true)
                GraphKind.Bookmark -> app.session.data.bookmarks.firstOrNull { it.id == node.sourceId }?.let { app.openPdf(it.documentId, it.page, right = true, offset = it.offset) }
                GraphKind.Field -> app.openResearchField(node.sourceId, right = true)
                else -> { local.isSelected = true; applyFilters(); fit() }
            }
        }
    }

    private fun color(kind: GraphKind): Color = when (kind) {
        GraphKind.Book -> Color(86, 146, 215); GraphKind.Author -> Color(212, 154, 75)
        GraphKind.Topic -> Color(157, 117, 214); GraphKind.Quote -> Color(73, 166, 144)
        GraphKind.Note -> Color(207, 126, 151); GraphKind.Consp -> Color(90, 170, 198)
        GraphKind.Folder -> Color(148, 155, 172); GraphKind.Bookmark -> Color(201, 167, 73)
        GraphKind.Field -> Color(193, 117, 79)
    }
    private fun screen(p: Point2D.Double) = Point2D.Double((p.x+viewX)*zoom, (p.y+viewY)*zoom)
    private fun world(p: Point) = Point2D.Double(p.x/zoom-viewX, p.y/zoom-viewY)
    private fun radius(id: String) = (7.0 + sqrt(graph.incident[id].orEmpty().size.toDouble()) * 1.5).coerceAtMost(19.0)
    private fun hit(p: Point): String? = visibleGraph.nodes.asReversed().firstOrNull { n -> positions[n.id]?.let { screen(it).distance(p.x.toDouble(), p.y.toDouble()) <= max(8.0, radius(n.id)*sqrt(zoom)) } == true }?.id

    private fun paintGraph(graphics: Graphics2D) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = chromeLineColor()
            val spacing = (40*zoom).coerceAtLeast(16.0)
            var x = viewX*zoom % spacing
            while (x < surface.width) { var y = viewY*zoom % spacing; while (y < surface.height) { g.fillOval(x.toInt(), y.toInt(), 1, 1); y += spacing }; x += spacing }
            if (visibleGraph.nodes.isEmpty()) {
                g.color = mutedColor(); g.drawString(if (graph.nodes.isEmpty()) "В библиотеке пока нет материалов" else "Нет узлов по выбранным фильтрам", 24, 40); return
            }
            val near = graph.incident[selectedId].orEmpty().flatMap { listOf(it.from, it.to) }.toSet()
            val selectedEdges = visibleGraph.edges.filter { it.from == selectedId || it.to == selectedId }.toSet()
            fun drawEdge(e: GraphEdge, active: Boolean) {
                val a = positions[e.from]?.let(::screen) ?: return; val b = positions[e.to]?.let(::screen) ?: return
                if (!Line2D.Double(a,b).intersects(-30.0,-30.0,surface.width+60.0,surface.height+60.0)) return
                g.color = if (active) accentColor() else if (darkChrome()) Color(78,85,101, if (selectedId == null) 125 else 45) else Color(157,166,185, if (selectedId == null) 115 else 35)
                g.stroke = BasicStroke(if (active) 1.6f else .8f)
                g.draw(Line2D.Double(a,b))
                if (active || labels.isSelected && zoom >= .55) {
                    val angle = atan2(b.y-a.y, b.x-a.x)
                    val r = max(8.0, radius(e.to)*sqrt(zoom)) + 3
                    val tipX = b.x - cos(angle)*r; val tipY = b.y - sin(angle)*r
                    g.draw(Line2D.Double(tipX,tipY,tipX-cos(angle-.45)*8,tipY-sin(angle-.45)*8))
                    g.draw(Line2D.Double(tipX,tipY,tipX-cos(angle+.45)*8,tipY-sin(angle+.45)*8))
                    if (zoom >= .45) {
                        g.font = font.deriveFont(11f)
                        val text = e.relation; val w = g.fontMetrics.stringWidth(text)
                        val midX = ((a.x+b.x)/2).toInt(); val midY = ((a.y+b.y)/2).toInt()
                        g.color = documentColor(); g.fillRoundRect(midX-w/2-4, midY-12, w+8, 16, 6, 6)
                        g.color = mutedColor(); g.drawString(text,midX-w/2,midY)
                    }
                }
            }
            visibleGraph.edges.forEach { if (it !in selectedEdges) drawEdge(it, false) }
            selectedEdges.forEach { drawEdge(it, true) }
            visibleGraph.nodes.forEach { n ->
                val p = positions[n.id]?.let(::screen) ?: return@forEach
                if (p.x < -150 || p.x > surface.width+150 || p.y < -70 || p.y > surface.height+70) return@forEach
                val r = max(3.0, radius(n.id)*sqrt(zoom))
                val active = selectedId == null || n.id == selectedId || n.id in near
                val c = color(n.kind)
                g.color = if (active) c else Color(c.red,c.green,c.blue,70)
                g.fill(Ellipse2D.Double(p.x-r,p.y-r,r*2,r*2))
                if (n.id == selectedId) { g.color = accentColor(); g.stroke = BasicStroke(2f); g.draw(Ellipse2D.Double(p.x-r-4,p.y-r-4,r*2+8,r*2+8)) }
                if (zoom >= .48 || n.id == selectedId || selectedId != null && n.id in near) {
                    g.font = font.deriveFont(if (n.id == selectedId) Font.BOLD else Font.PLAIN, 12f)
                    val text = if (n.title.length > 28) n.title.take(27)+"…" else n.title
                    val w = g.fontMetrics.stringWidth(text); val tx = (p.x-w/2).toInt(); val ty = (p.y+r+17).toInt()
                    g.color = documentColor(); g.fillRoundRect(tx-3,ty-13,w+6,18,5,5)
                    g.color = if (active) UIManager.getColor("Label.foreground") else mutedColor(); g.drawString(text,tx,ty)
                }
            }
        } finally { g.dispose() }
    }

    fun fit() {
        val points = visibleGraph.nodes.mapNotNull { positions[it.id] }
        if (points.isEmpty() || surface.width <= 0 || surface.height <= 0) return
        val minX = points.minOf { it.x }-100; val maxX = points.maxOf { it.x }+100
        val minY = points.minOf { it.y }-70; val maxY = points.maxOf { it.y }+70
        zoom = min(surface.width/(maxX-minX),surface.height/(maxY-minY)).coerceIn(.005, 1.5)
        viewX = surface.width/2/zoom-(minX+maxX)/2; viewY = surface.height/2/zoom-(minY+maxY)/2
        initialFit = false; surface.repaint()
    }
    private fun center(id: String) { positions[id]?.let { viewX = surface.width/2/zoom-it.x; viewY = surface.height/2/zoom-it.y; surface.repaint() } }
    private fun changeZoom(factor: Double, at: Point) {
        val p = world(at); zoom = (zoom*factor).coerceIn(.005,4.0)
        viewX = at.x/zoom-p.x; viewY = at.y/zoom-p.y; surface.repaint()
    }
    private fun installGestures() {
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                surface.requestFocusInWindow(); moved = false; origin = e.point
                dragging = if (SwingUtilities.isLeftMouseButton(e) && !e.isAltDown) hit(e.point) else null
                if (SwingUtilities.isLeftMouseButton(e) && !e.isAltDown) select(dragging)
            }
            override fun mouseDragged(e: MouseEvent) {
                val before = origin ?: return
                val dx = (e.x-before.x)/zoom; val dy = (e.y-before.y)/zoom
                if (before.distance(e.point) > 1) moved = true
                dragging?.let { positions[it]?.apply { x += dx; y += dy } } ?: run { viewX += dx; viewY += dy }
                origin = e.point; surface.repaint()
            }
            override fun mouseReleased(e: MouseEvent) { origin = null; dragging = null }
            override fun mouseClicked(e: MouseEvent) { if (!moved && e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) hit(e.point)?.let { select(it); openSelected() } }
            override fun mouseWheelMoved(e: MouseWheelEvent) { changeZoom(.88.pow(e.preciseWheelRotation), e.point) }
        }
        surface.addMouseListener(mouse); surface.addMouseMotionListener(mouse); surface.addMouseWheelListener(mouse)
        listOf("ESCAPE" to { select(null) }, "ENTER" to { openSelected() }, "HOME" to { fit() }).forEachIndexed { i, (key, action) ->
            surface.getInputMap(WHEN_FOCUSED).put(DesktopPlatform.keyStroke(key), "graph-$i")
            surface.actionMap.put("graph-$i", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
        }
    }
}
