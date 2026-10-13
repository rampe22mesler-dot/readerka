@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.readerka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.readerka.data.*
import kotlin.math.*

@Composable fun LibraryGraphScreen(data: LibrarySnapshot, vm: LibraryViewModel) {
    val view by vm.graphViewState.collectAsState()
    val graph by produceState(LibraryGraph(emptyList(), emptyList()), data) {
        value = withContext(Dispatchers.Default) { LibraryGraph.build(data) }
    }
    val visible = remember(graph, view.kinds, view.query, view.focus, view.depth) {
        graph.visible(GraphKind.entries.filter { it.name in view.kinds }.toSet(), view.query, view.focus, view.depth)
    }
    val points by produceState(emptyMap<String, GraphPoint>(), visible) {
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            GraphLayout.arrange(visible) { !context.isActive }
        }
    }
    val currentView by rememberUpdatedState(view)
    val currentPoints by rememberUpdatedState(points)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(false) }
    fun point(id: String): GraphPoint = currentView.positions[id]?.takeIf { it.size == 2 }?.let { GraphPoint(it[0], it[1]) } ?: currentPoints[id] ?: GraphPoint(0.0, 0.0)
    fun open(node: GraphNode) {
        when (node.kind) {
            GraphKind.Book -> data.documents.firstOrNull { it.id == node.sourceId }?.let { vm.open(it) }
            GraphKind.Quote, GraphKind.Note, GraphKind.Consp -> data.entries.firstOrNull { it.id == node.sourceId }?.let { vm.openNote(it) }
            GraphKind.Field -> vm.openResearchField(node.sourceId)
            GraphKind.Bookmark -> data.bookmarks.firstOrNull { it.id == node.sourceId }?.let(vm::openBookmark)
            else -> vm.graphView(view.copy(focus = node.id))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        val width = maxWidth.value; val height = (maxHeight.value - 180).coerceAtLeast(100f)
        fun fit() {
            if (visible.nodes.isEmpty()) return
            val ps = visible.nodes.map { point(it.id) }
            val x = ps.minOf { it.x }; val y = ps.minOf { it.y }
            val w = ps.maxOf { it.x } - x + 160; val h = ps.maxOf { it.y } - y + 100
            val zoom = min(width / w, height / h).coerceIn(.05, 3.0)
            vm.graphView(view.copy(zoom = zoom, viewX = x + (w - 160) / 2, viewY = y + (h - 100) / 2))
        }
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text("Граф библиотеки", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(view.query, { vm.graphView(view.copy(query = it)) }, label = { Text("Поиск узлов") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            FlowRow {
                TextButton(onClick = { filterOpen = true }) { Text("Фильтры") }
                TextButton(onClick = { vm.graphView(view.copy(focus = null)); fit() }) { Text("Всё") }
                TextButton(enabled = selected != null, onClick = { vm.graphView(view.copy(focus = selected)) }) { Text("Окрестность") }
                TextButton(onClick = { vm.graphView(view.copy(depth = if (view.depth == 1) 2 else 1)) }) { Text("Глубина: ${view.depth}") }
                TextButton(onClick = { list = !list }) { Text(if (list || narrow) "Список узлов" else "Показать список") }
                TextButton(onClick = ::fit) { Text("Вписать") }
                TextButton(onClick = { vm.graphView(view.copy(zoom = (view.zoom * 1.2).coerceAtMost(3.0))) }) { Text("+") }
                TextButton(onClick = { vm.graphView(view.copy(zoom = (view.zoom / 1.2).coerceAtLeast(.05))) }) { Text("−") }
            }
            Text("${visible.nodes.size} узлов · ${visible.edges.size} связей", style = MaterialTheme.typography.bodySmall)
            if (list || narrow) LazyColumn(Modifier.weight(1f).testTag("library-graph-list")) {
                // No take(N): every node remains reachable, also on the phone.
                items(visible.nodes, key = { it.id }) { node ->
                    ListItem(headlineContent = { Text(node.title) }, supportingContent = { Text(node.kind.label) },
                        modifier = Modifier.clickable { selected = node.id }, trailingContent = { TextButton(onClick = { open(node) }) { Text("Открыть") } })
                }
            } else {
                val density = LocalDensity.current.density
                val edgeColor = MaterialTheme.colorScheme.outline
                val nodeColor = MaterialTheme.colorScheme.primary
                val selectedColor = MaterialTheme.colorScheme.tertiary
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                fun nearest(offset: Offset): String? {
                    val v = currentView
                    fun distance(id: String): Double { val p = point(id); return hypot((p.x - v.viewX) * v.zoom * density + canvasSize.width / 2 - offset.x, (p.y - v.viewY) * v.zoom * density + canvasSize.height / 2 - offset.y) }
                    return visible.nodes.minByOrNull { distance(it.id) }?.id?.takeIf { distance(it) < 36 * density }
                }
                var moving by remember { mutableStateOf<String?>(null) }
                Canvas(Modifier.fillMaxWidth().weight(1f).onSizeChanged { canvasSize = it }.testTag("library-graph-canvas")
                    .pointerInput(visible) { detectTapGestures(onDoubleTap = { at -> nearest(at)?.let { graph.byId[it]?.let(::open) } }) }
                    .pointerInput(visible) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val target = nearest(down.position)
                            var accumulated = Offset.Zero
                            var dragging = false
                            do {
                                val event = awaitPointerEvent()
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val multi = event.changes.count { it.pressed } > 1
                                accumulated += pan
                                if (dragging || multi || accumulated.getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    val current = currentView
                                    if (multi) vm.graphView(current.copy(viewX = current.viewX - pan.x / density / current.zoom, viewY = current.viewY - pan.y / density / current.zoom, zoom = (current.zoom * zoom).coerceIn(.05, 3.0)))
                                    else if (target != null) {
                                        val p = point(target)
                                        selected = target
                                        vm.graphView(current.copy(positions = current.positions + (target to listOf(p.x + pan.x / density / current.zoom, p.y + pan.y / density / current.zoom))))
                                    } else vm.graphView(current.copy(viewX = current.viewX - pan.x / density / current.zoom, viewY = current.viewY - pan.y / density / current.zoom))
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (!dragging) selected = target
                        }
                    }) {
                    fun at(id: String): Offset { val p = point(id); return Offset(((p.x - view.viewX) * view.zoom).toFloat() * density + size.width / 2, ((p.y - view.viewY) * view.zoom).toFloat() * density + size.height / 2) }
                    visible.edges.forEach { edge -> drawLine(edgeColor, at(edge.from), at(edge.to), 1.dp.toPx()) }
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = 13.dp.toPx() }
                    visible.nodes.forEach { node ->
                        val p = at(node.id)
                        drawCircle(if (selected == node.id) selectedColor else nodeColor, 9.dp.toPx(), p)
                        drawContext.canvas.nativeCanvas.drawText(node.title.take(32), p.x + 12.dp.toPx(), p.y + 5.dp.toPx(), paint)
                    }
                }
            }
            selected?.let { id -> graph.byId[id]?.let { node ->
                Text(node.title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { open(node) }) { Text("Открыть материал") }
                LazyColumn(Modifier.heightIn(max = 160.dp)) { items(graph.incident[id].orEmpty()) { edge ->
                    val other = graph.byId[if (edge.from == id) edge.to else edge.from]
                    TextButton(onClick = { selected = other?.id }) { Text("${edge.relation}: ${other?.title.orEmpty()} ${edge.context}") }
                } }
            } }
        }
    }
    if (filterOpen) AlertDialog(onDismissRequest = { filterOpen = false }, title = { Text("Типы узлов") }, text = {
        LazyColumn { items(GraphKind.entries) { kind -> Row {
            Checkbox(kind.name in view.kinds, { enabled -> vm.graphView(view.copy(kinds = if (enabled) view.kinds + kind.name else view.kinds - kind.name)) })
            Text(kind.label, Modifier.padding(top = 12.dp))
        } } }
    }, confirmButton = { TextButton(onClick = { filterOpen = false }) { Text("Готово") } })
}
