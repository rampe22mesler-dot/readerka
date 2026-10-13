@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.readerka.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.readerka.data.*
import java.util.UUID
import kotlin.math.*

class FieldHistory {
    val undo = mutableListOf<ResearchField>()
    val redo = mutableListOf<ResearchField>()
}

@Composable fun ResearchFieldsCatalogue(data: LibrarySnapshot, vm: LibraryViewModel) {
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ResearchField?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Исследовательские поля", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { title = ""; creating = true }, modifier = Modifier.testTag("new-research-field")) { Text("Создать поле") }
        LazyColumn { items(data.researchFields, key = { it.id }) { field ->
            ListItem(headlineContent = { Text(field.title) }, supportingContent = { Text("Карточек: ${field.placements.size} · Связей: ${field.edges.size}") },
                modifier = Modifier.clickable { vm.openResearchField(field.id) }, trailingContent = {
                    TextButton(onClick = { deleting = field }) { Text("Удалить поле") }
                })
        } }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("Новое исследовательское поле") },
        text = { OutlinedTextField(title, { title = it }, label = { Text("Название") }) },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { vm.newResearchField(title); creating = false }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Отмена") } })
    deleting?.let { field -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить «${field.title}»?") },
        text = { Text("Удалятся размещения и связи поля. Записи библиотеки сохранятся.") },
        confirmButton = { TextButton(onClick = { vm.deleteResearchField(field.id); deleting = null }) { Text("Удалить поле") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
}

@Composable fun ResearchFieldScreen(id: String, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int = 0) {
    val edits by vm.fieldEdits.collectAsState()
    val saved = edits[id] ?: data.researchFields.firstOrNull { it.id == id }
    if (saved == null) { Text("Поле удалено или недоступно"); return }
    var field by remember(id) { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(saved, dragging) { if (!dragging) field = saved }
    val views by vm.fieldViews.collectAsState()
    val view = views["$pane:$id"] ?: FieldViewState()
    val selection = view.selection.intersect(field.placements.map { it.id }.toSet())
    val currentSelection by rememberUpdatedState(selection)
    fun selectCards(value: Set<String>) { vm.fieldView(id, pane, (vm.fieldViews.value["$pane:$id"] ?: FieldViewState()).copy(selection = value)) }
    val history = vm.fieldHistory(id)
    var historyRevision by remember { mutableIntStateOf(0) }
    var add by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf<ResearchPlacement?>(null) }
    var commentText by remember { mutableStateOf("") }
    var connect by remember { mutableStateOf(false) }
    var relation by remember { mutableStateOf(ResearchRelation.Free) }
    var relationLabel by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var geometry by remember { mutableStateOf<ResearchPlacement?>(null) }
    var geometryValues by remember { mutableStateOf(listOf<String>()) }
    var renameField by remember { mutableStateOf(false) }
    var renamedTitle by remember { mutableStateOf("") }
    var showLinks by remember { mutableStateOf(false) }
    var manageGroups by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ResearchGroup?>(null) }
    var editingEdge by remember { mutableStateOf<ResearchEdge?>(null) }
    var reverseEdge by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val displayDensity = LocalDensity.current.density
    fun commit(next: ResearchField, before: ResearchField = field) {
        if (next == before) return
        history.undo.add(before); if (history.undo.size > 50) history.undo.removeAt(0)
        history.redo.clear(); historyRevision++
        field = next; vm.saveResearchField(next)
    }
    fun choose(card: ResearchPlacement) { selectCards(if (card.id in selection) selection - card.id else selection + card.id) }
    val workspace by vm.workspace.collectAsState()
    val keyHandler by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
        if (renameField || add || comment != null || connect || groups || geometry != null || showLinks || manageGroups || editingGroup != null || editingEdge != null) false
        else {
            val undoKey = event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && event.keyCode == KeyEvent.KEYCODE_Z
            val deleteKey = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL && selection.isNotEmpty()
            val movement = if (!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && selection.isNotEmpty()) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -10.0 to 0.0
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10.0 to 0.0
                KeyEvent.KEYCODE_DPAD_UP -> 0.0 to -10.0
                KeyEvent.KEYCODE_DPAD_DOWN -> 0.0 to 10.0
                else -> null
            } else null
            if (event.action == KeyEvent.ACTION_DOWN) when {
                undoKey -> {
                    val from = if (event.isShiftPressed) history.redo else history.undo
                    val to = if (event.isShiftPressed) history.undo else history.redo
                    if (from.isNotEmpty()) { to.add(field); field = from.removeAt(from.lastIndex); historyRevision++; vm.saveResearchField(field) }
                }
                deleteKey -> { commit(field.removePlacements(selection)); selectCards(emptySet()) }
                movement != null -> commit(field.move(selection, movement.first, movement.second))
            }
            undoKey || deleteKey || movement != null
        }
    })
    DisposableEffect(id, pane, workspace.activePane) {
        val handler: (KeyEvent) -> Boolean = { keyHandler(it) }
        if (workspace.activePane == pane) vm.fieldKeyHandler = handler
        onDispose { if (vm.fieldKeyHandler === handler) vm.fieldKeyHandler = null }
    }
    val entries = remember(data.entries) { data.entries.associateBy { it.id } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        val listOnly = if (narrow) !view.forceCanvas else view.forceList
        val width = maxWidth.value; val height = maxHeight.value - 100
        fun fit(ids: Set<String>) {
            val cards = field.placements.filter { ids.isEmpty() || it.id in ids }
            if (cards.isEmpty()) return
            val x = cards.minOf { it.x }; val y = cards.minOf { it.y }
            val w = cards.maxOf { it.x + it.width } - x; val h = cards.maxOf { it.y + it.height } - y
            val canvasWidth = if (canvasSize.width > 0) canvasSize.width / displayDensity else width
            val canvasHeight = if (canvasSize.height > 0) canvasSize.height / displayDensity else height
            val zoom = min((canvasWidth - 32).coerceAtLeast(1f) / w, (canvasHeight - 32).coerceAtLeast(1f) / h).coerceIn(.01, 3.0)
            commit(field.copy(zoom = zoom, viewX = x - 16 / zoom, viewY = y - 16 / zoom))
        }
        Column(Modifier.fillMaxSize().testTag("research-field")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(field.title, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { add = true }, modifier = Modifier.testTag("field-add-material")) { Text("Добавить") }
            }
            // A wrapping toolbar keeps all actions reachable at 320 dp and with enlarged fonts.
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(enabled = historyRevision >= 0 && history.undo.isNotEmpty(), onClick = {
                    history.redo.add(field); field = history.undo.removeAt(history.undo.lastIndex); historyRevision++; vm.saveResearchField(field)
                }) { Text("Отменить") }
                TextButton(enabled = history.redo.isNotEmpty(), onClick = {
                    history.undo.add(field); field = history.redo.removeAt(history.redo.lastIndex); historyRevision++; vm.saveResearchField(field)
                }) { Text("Повторить") }
                TextButton(onClick = { vm.fieldView(id, pane, if (narrow) view.copy(forceCanvas = !view.forceCanvas) else view.copy(forceList = !view.forceList)) }) { Text(if (listOnly) "Поле" else "Список") }
                TextButton(onClick = { renamedTitle = field.title; renameField = true }) { Text("Название") }
                TextButton(onClick = { fit(emptySet()) }) { Text("Всё") }
                TextButton(enabled = selection.isNotEmpty(), onClick = { fit(selection) }) { Text("Выбранное") }
                TextButton(enabled = selection.size == 2, onClick = { editingEdge = null; reverseEdge = false; connect = true }) { Text("Связать") }
                TextButton(onClick = { showLinks = true }) { Text("Связи") }
                TextButton(enabled = selection.isNotEmpty(), onClick = { groups = true }) { Text("Группа") }
                TextButton(onClick = { manageGroups = true }) { Text("Группы") }
                TextButton(enabled = selection.isNotEmpty(), onClick = { commit(field.copy(sequence = (field.sequence + selection).distinct())) }) { Text("В порядок") }
                TextButton(enabled = selection.isNotEmpty(), onClick = { commit(field.removePlacements(selection)); selectCards(emptySet()) }) { Text("Убрать карточки") }
                TextButton(enabled = field.sequence.isNotEmpty(), onClick = { vm.assembleResearchField(field, InsertionMode.Content) }) { Text("Собрать конспект") }
            }
            if (listOnly) Text("Список сохраняет геометрию поля. Выберите запись, чтобы открыть полный редактор.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            val cardContent: @Composable (ResearchPlacement) -> Unit = { card ->
                val entry = entries[card.entryId]
                Column(Modifier.fillMaxSize().testTag("field-card-${card.id}").background(if (card.id in selection) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                    var startField by remember(card.id) { mutableStateOf(field) }
                    var dragIds by remember(card.id) { mutableStateOf(setOf<String>()) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pointerInput(card.id, field.zoom) {
                        detectDragGestures(onDragStart = { startField = field; dragging = true; dragIds = if (card.id in currentSelection) currentSelection else setOf(card.id); selectCards(dragIds) },
                            onDragEnd = { dragging = false; commit(field, startField) },
                            onDragCancel = { dragging = false; field = startField }, onDrag = { change, delta ->
                                change.consume(); if (!listOnly) field = field.move(dragIds, delta.x / density / field.zoom, delta.y / density / field.zoom)
                            })
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(card.id in selection, { choose(card) }, Modifier.testTag("field-select-${card.id}"))
                        Text(entry?.title ?: "Запись недоступна", Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = { if (entry != null) vm.openNote(entry) }) { Text("Открыть") }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().padding(8.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer { Text(entry?.let { (if (it.quote.isNotBlank()) it.quote + "\n\n" else "") + it.richText().text }.orEmpty()) }
                        if (card.projectComment.isNotBlank()) Text("Применение в этом проекте: ${card.projectComment}")
                        card.groupId?.let { group -> Text("Группа: " + field.groups.firstOrNull { it.id == group }?.title.orEmpty()) }
                    }
                    androidx.compose.foundation.layout.FlowRow {
                        TextButton(modifier = Modifier.testTag("field-comment-${card.id}"), onClick = { comment = card; commentText = card.projectComment }) { Text("Применение") }
                        TextButton(modifier = Modifier.testTag("field-geometry-${card.id}"), onClick = { geometry = card; geometryValues = listOf(card.x, card.y, card.width, card.height).map { it.toString() } }) { Text("Геометрия") }
                        var kindMenu by remember(card.id) { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { kindMenu = true }) { Text(when(card.kind) { ResearchCardKind.Quote -> "Цитата"; ResearchCardKind.Note -> "Заметка"; ResearchCardKind.Thesis -> "Тезис" }) }
                            DropdownMenu(kindMenu, { kindMenu = false }) { ResearchCardKind.entries.forEach { kind ->
                                DropdownMenuItem(text = { Text(when(kind) { ResearchCardKind.Quote -> "Цитата"; ResearchCardKind.Note -> "Заметка"; ResearchCardKind.Thesis -> "Тезис" }) }, onClick = {
                                    commit(field.copy(placements = field.placements.map { if (it.id == card.id) it.copy(kind = kind) else it })); kindMenu = false
                                })
                            } }
                        }
                        if (entry?.documentId != null) TextButton(onClick = { vm.source(entry) }) { Text("Источник") }
                        val at = field.sequence.indexOf(card.id)
                        if (at >= 0) {
                            Text("${at + 1}.", Modifier.padding(8.dp))
                            TextButton(enabled = at > 0, onClick = {
                                val order = field.sequence.toMutableList(); order.add(at - 1, order.removeAt(at)); commit(field.copy(sequence = order))
                            }) { Text("Выше") }
                            TextButton(enabled = at < field.sequence.lastIndex, onClick = {
                                val order = field.sequence.toMutableList(); order.add(at + 1, order.removeAt(at)); commit(field.copy(sequence = order))
                            }) { Text("Ниже") }
                            TextButton(onClick = { commit(field.copy(sequence = field.sequence - card.id)) }) { Text("Из порядка") }
                        }
                    }
                }
            }
            if (listOnly) LazyColumn(Modifier.weight(1f).testTag("field-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(field.placements, key = { it.id }) { card -> Box(Modifier.fillMaxWidth().height(360.dp)) { cardContent(card) } }
            } else {
                var beforeGesture by remember { mutableStateOf(field) }
                val edgeColor = MaterialTheme.colorScheme.outline
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { canvasSize = it }.testTag("research-canvas").pointerInput(id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!dragging) { beforeGesture = field; dragging = true }
                        field = field.copy(zoom = (field.zoom * zoom).coerceIn(.01, 3.0), viewX = field.viewX - pan.x / density / field.zoom, viewY = field.viewY - pan.y / density / field.zoom)
                        vm.saveResearchField(field)
                        dragging = false
                    }
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cards = field.placements.associateBy { it.id }
                        field.edges.forEach { edge ->
                            val a = cards[edge.from] ?: return@forEach; val b = cards[edge.to] ?: return@forEach
                            fun center(p: ResearchPlacement) = Offset(((p.x + p.width / 2 - field.viewX) * field.zoom).toFloat() * density, ((p.y + p.height / 2 - field.viewY) * field.zoom).toFloat() * density)
                            val from = center(a); val to = center(b)
                            drawLine(edgeColor, from, to, 2.dp.toPx())
                            val angle = atan2(to.y - from.y, to.x - from.x)
                            val tip = from + (to - from) * .65f
                            drawLine(edgeColor, tip, tip - Offset(cos(angle - .5f), sin(angle - .5f)) * 14.dp.toPx(), 2.dp.toPx())
                            drawLine(edgeColor, tip, tip - Offset(cos(angle + .5f), sin(angle + .5f)) * 14.dp.toPx(), 2.dp.toPx())
                        }
                    }
                    field.placements.forEach { card -> key(card.id) {
                        Box(Modifier.offset(((card.x - field.viewX) * field.zoom).dp, ((card.y - field.viewY) * field.zoom).dp)
                            .size((card.width * field.zoom).dp, (card.height * field.zoom).dp)) { cardContent(card) }
                    } }
                }
            }
        }
    }
    if (renameField) AlertDialog(onDismissRequest = { renameField = false }, title = { Text("Название поля") },
        text = { OutlinedTextField(renamedTitle, { renamedTitle = it }) },
        confirmButton = { TextButton(enabled = renamedTitle.isNotBlank(), onClick = { commit(field.copy(title = renamedTitle.trim())); renameField = false }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { renameField = false }) { Text("Отмена") } })
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Материал из библиотеки") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(data.entries, key = { it.id }) { entry ->
            TextButton(onClick = {
                val card = ResearchPlacement(UUID.randomUUID().toString(), entry.id, field.viewX + 24, field.viewY + 24,
                    kind = if (entry.kind == EntryKind.Quote) ResearchCardKind.Quote else ResearchCardKind.Note)
                commit(field.copy(placements = field.placements + card)); add = false
            }) { Text(entry.title) }
        } }
    }, confirmButton = { TextButton(onClick = { add = false }) { Text("Закрыть") } })
    comment?.let { card -> AlertDialog(onDismissRequest = { comment = null }, title = { Text("Применение в этом проекте") },
        text = { Column { Text("Общий комментарий записи не изменится."); OutlinedTextField(commentText, { commentText = it }, modifier = Modifier.fillMaxWidth().testTag("field-comment-input")) } },
        confirmButton = { TextButton(onClick = { commit(field.copy(placements = field.placements.map { if (it.id == card.id) it.copy(projectComment = commentText) else it })); comment = null }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { comment = null }) { Text("Отмена") } }) }
    if (connect && (selection.size == 2 || editingEdge != null)) AlertDialog(onDismissRequest = { connect = false }, title = { Text("Направленная связь") }, text = {
        Column { Text((editingEdge?.let { listOf(it.from, it.to) } ?: selection.toList()).map { selected -> entries[field.placements.first { it.id == selected }.entryId]?.title }.joinToString(" → "))
            ResearchRelation.entries.forEach { type -> TextButton(onClick = { relation = type }) { Text((if (relation == type) "✓ " else "") + type.label) } }
            OutlinedTextField(relationLabel, { relationLabel = it }, label = { Text("Подпись") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(reverseEdge, { reverseEdge = it }); Text("Обратное направление") } }
    }, confirmButton = { TextButton(onClick = {
        val current = editingEdge
        val ids = if (current == null) selection.toList() else listOf(current.from, current.to)
        val next = ResearchEdge(current?.id ?: UUID.randomUUID().toString(), ids[if (reverseEdge) 1 else 0], ids[if (reverseEdge) 0 else 1], relation, relationLabel)
        commit(field.copy(edges = if (current == null) field.edges + next else field.edges.map { if (it.id == current.id) next else it })); editingEdge = null; connect = false
    }) { Text("Связать") } }, dismissButton = { TextButton(onClick = { connect = false }) { Text("Отмена") } })
    if (showLinks) AlertDialog(onDismissRequest = { showLinks = false }, title = { Text("Связи поля") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(field.edges, key = { it.id }) { edge ->
            val cards = field.placements.associateBy { it.id }
            Column { Text("${entries[cards[edge.from]?.entryId]?.title} → ${entries[cards[edge.to]?.entryId]?.title}: ${edge.type.label} ${edge.label}")
                TextButton(onClick = { editingEdge = edge; relation = edge.type; relationLabel = edge.label; reverseEdge = false; showLinks = false; connect = true }) { Text("Изменить связь") }
                TextButton(onClick = { commit(field.copy(edges = field.edges - edge)) }) { Text("Удалить связь") } }
        } }
    }, confirmButton = { TextButton(onClick = { showLinks = false }) { Text("Закрыть") } })
    if (groups) AlertDialog(onDismissRequest = { groups = false }, title = { Text("Группа выбранных карточек") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            (listOf<ResearchGroup?>(null) + field.groups).forEach { group -> TextButton(onClick = {
                commit(field.copy(placements = field.placements.map { if (it.id in selection) it.copy(groupId = group?.id) else it })); groups = false
            }) { Text(group?.title ?: "Без группы") } }
            OutlinedTextField(groupName, { groupName = it }, label = { Text("Новая группа") })
        }
    }, confirmButton = { TextButton(enabled = groupName.isNotBlank(), onClick = {
        val group = ResearchGroup(UUID.randomUUID().toString(), groupName.trim())
        commit(field.copy(groups = field.groups + group, placements = field.placements.map { if (it.id in selection) it.copy(groupId = group.id) else it })); groupName = ""; groups = false
    }) { Text("Создать группу") } })
    if (manageGroups) AlertDialog(onDismissRequest = { manageGroups = false }, title = { Text("Группы поля") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(field.groups, key = { it.id }) { group ->
            Column { Text(group.title)
                TextButton(onClick = { editingGroup = group; groupName = group.title; manageGroups = false }) { Text("Переименовать группу") }
                TextButton(onClick = { commit(field.copy(groups = field.groups - group, placements = field.placements.map { if (it.groupId == group.id) it.copy(groupId = null) else it })) }) { Text("Удалить группу") }
            }
        } }
    }, confirmButton = { TextButton(onClick = { manageGroups = false }) { Text("Закрыть") } })
    editingGroup?.let { group -> AlertDialog(onDismissRequest = { editingGroup = null }, title = { Text("Название группы") },
        text = { OutlinedTextField(groupName, { groupName = it }) },
        confirmButton = { TextButton(enabled = groupName.isNotBlank(), onClick = { commit(field.copy(groups = field.groups.map { if (it.id == group.id) it.copy(title = groupName.trim()) else it })); editingGroup = null }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editingGroup = null }) { Text("Отмена") } }) }
    geometry?.let { card ->
        val numbers = geometryValues.map { it.toDoubleOrNull() }
        val valid = numbers.size == 4 && numbers.all { it != null && it.isFinite() } && numbers[2]!! in 180.0..2000.0 && numbers[3]!! in 140.0..3000.0
        AlertDialog(onDismissRequest = { geometry = null }, title = { Text("Координаты и размер") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { listOf("X", "Y", "Ширина", "Высота").forEachIndexed { index, label ->
                OutlinedTextField(geometryValues[index], { v -> geometryValues = geometryValues.toMutableList().also { it[index] = v } }, label = { Text(label) }, modifier = Modifier.testTag("field-geometry-$index"))
            } }
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            commit(field.copy(placements = field.placements.map { if (it.id == card.id) it.copy(x = numbers[0]!!, y = numbers[1]!!, width = numbers[2]!!, height = numbers[3]!!) else it })); geometry = null
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { geometry = null }) { Text("Отмена") } })
    }
}
