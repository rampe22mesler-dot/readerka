package org.readerka.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readerka.R
import org.readerka.ReaderkaApp
import org.readerka.data.*
import org.readerka.data.Collection
import org.readerka.export.*
import org.readerka.pdf.PdfEngine
import org.readerka.dropbox.*
import java.io.File
import java.util.UUID

data class ReaderSession(val document: Document, val engine: PdfEngine, val page: Int, val offset: Int, val anchor: Anchor? = null, val token: String = UUID.randomUUID().toString(), val pane: Int = 0, val tabId: String = document.id)
class QuoteHistory {
    val undo = mutableListOf<Pair<String, androidx.compose.ui.text.TextRange>>()
    val redo = mutableListOf<Pair<String, androidx.compose.ui.text.TextRange>>()
}
class EditorHistory {
    val undo = mutableListOf<Pair<RichText, androidx.compose.ui.text.TextRange>>()
    val redo = mutableListOf<Pair<RichText, androidx.compose.ui.text.TextRange>>()
}
data class Busy(val label: Int, val progress: Float? = null)
enum class ExportKind { Obsidian, Backup, Markdown, Csv }
enum class ReaderTool { Search, Quotes, Bookmarks, Consp, Page, NewBookmark, Quote, AppendConsp, ToField }

class LibraryViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val repository = LibraryRepository(application, (application as ReaderkaApp).database)
    private val preferencesStore = Preferences(application)
    val preferences = preferencesStore.values.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPreferences())
    val library = repository.snapshots.stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySnapshot())
    private val _reader = MutableStateFlow<ReaderSession?>(null)
    val reader = _reader.asStateFlow()
    private val _readerTool = MutableStateFlow<ReaderTool?>(null)
    val readerTool = _readerTool.asStateFlow()
    fun requestReaderTool(tool: ReaderTool) { if (_reader.value != null) { navigate("reader"); _readerTool.value = tool } }
    fun consumeReaderTool() { _readerTool.value = null }
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy = _busy.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val screen = savedState.getStateFlow("screen", "library")
    val openDocumentIds = savedState.getStateFlow<ArrayList<String>>("open-documents", arrayListOf())
    var pendingExport: ExportKind
        get() = savedState.get<String>("export-kind")?.let(ExportKind::valueOf) ?: ExportKind.Obsidian
        set(value) { savedState["export-kind"] = value.name }
    var includePdf: Boolean
        get() = savedState["include-pdf"] ?: true
        set(value) { savedState["include-pdf"] = value }
    var exportIds: Set<String>?
        get() = savedState.get<List<String>>("export-ids")?.toSet()
        set(value) { savedState["export-ids"] = value?.toList() }
    private val opening = mutableMapOf<Int, Job>()
    private val _readers = MutableStateFlow<Map<Int, ReaderSession>>(emptyMap())
    val readers = _readers.asStateFlow()
    val pdfSelections = MutableStateFlow<Map<String, Pair<String, Anchor>>>(emptyMap())
    val pdfViews = MutableStateFlow<Map<String, PdfViewState>>(emptyMap())
    private var selectionChanged = false
    fun rememberPdfSelection(tabId: String, value: Pair<String, Anchor>?) {
        val next = if (value == null) pdfSelections.value - tabId else pdfSelections.value + (tabId to value)
        if (next != pdfSelections.value) {
            selectionChanged = true; pdfSelections.value = next; workspaceWrites.trySend(Unit)
        }
    }
    fun rememberPdfView(tabId: String, page: Int, value: PdfViewState) {
        val key = "$tabId:$page"
        if (pdfViews.value[key] != value) {
            pdfViews.value = pdfViews.value + (key to value); workspaceWrites.trySend(Unit)
        }
    }
    var editorKeyHandler: ((android.view.KeyEvent) -> Boolean)? = null
    var fieldKeyHandler: ((android.view.KeyEvent) -> Boolean)? = null
    var readerKeyHandler: ((android.view.KeyEvent) -> Boolean)? = null
    private val workspaceStore = WorkspaceStore(application)
    private val _workspace = MutableStateFlow(WorkspaceState())
    val workspace = _workspace.asStateFlow()
    private val _draftStatus = MutableStateFlow("Загрузка черновиков…")
    val draftStatus = _draftStatus.asStateFlow()
    private val _editorSaving = MutableStateFlow(false)
    val editorSaving = _editorSaving.asStateFlow()
    private val workspaceReady = CompletableDeferred<Unit>()
    val workspaceLoaded = MutableStateFlow(false)
    val workspaceLoadError = MutableStateFlow<String?>(null)
    private val workspaceWrites = Channel<Unit>(Channel.CONFLATED)
    private val workspaceLock = Mutex()
    init {
        viewModelScope.launch {
            try {
                _workspace.value = workspaceStore.values.first()
                val selections = workspaceStore.selections.first()
                if (!selectionChanged) pdfSelections.value = selections.filterKeys { id -> _workspace.value.panes.any { pane -> pane.tabs.any { it.id == id && it.kind == WorkspaceKind.Pdf } } }
                pdfViews.value = workspaceStore.views.first().filterKeys { key -> _workspace.value.panes.any { pane -> pane.tabs.any { key.startsWith(it.id + ":") && it.kind == WorkspaceKind.Pdf } } }
                _draftStatus.value = "Черновик сохранён"
                val data = repository.snapshots.first()
                // Map old outline draft memberships to their independent folders before autosaving.
                _workspace.value = _workspace.value.copy(drafts = _workspace.value.drafts.map { draft ->
                    draft.copy(collections = scopedCollectionIds(draft.collections, draft.entry.kind, data.collections))
                })
                if (_workspace.value.panes.all { it.tabs.isEmpty() }) {
                    val state = _workspace.value
                    val doc = data.documents.firstOrNull { it.id == (savedState.get<String>("document") ?: state.history.current?.documentId) }
                    val pdf = doc?.let { WorkspaceTab(UUID.randomUUID().toString(), it.id, WorkspaceKind.Pdf, page = state.history.current?.page ?: it.lastPage, offset = state.history.current?.offset ?: it.lastOffset) }
                    val note = state.editor?.let { WorkspaceTab(UUID.randomUUID().toString(), it.entry.id, WorkspaceKind.Note) }
                    _workspace.value = state.copy(panes = listOf(WorkspacePane().let { if (pdf != null) it.open(pdf) else if (note != null) it.open(note) else it }, WorkspacePane().let { if (pdf != null && note != null) it.open(note) else it }), split = pdf != null && note != null, activePane = if (pdf != null && note != null) 1 else 0)
                }
                for ((pane, state) in _workspace.value.panes.withIndex()) {
                    val tab = state.selected ?: continue
                    if (tab.kind == WorkspaceKind.Pdf) data.documents.firstOrNull { it.id == tab.documentId }?.let { open(it, page = tab.page, navigate = false, recordHistory = false, pane = pane, tabId = tab.id, offset = tab.offset) }
                }
                if (_workspace.value.panes.any { it.tabs.isNotEmpty() }) navigate("reader")
                _workspace.value.drafts.filter { it.entry.kind == EntryKind.Consp && it.entry.title.isNotBlank() }.forEach { updateDraft(it) }
                workspaceReady.complete(Unit)
                workspaceLoaded.value = true
                for (ignored in workspaceWrites) {
                    try { persistWorkspace() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { _draftStatus.value = "Не удалось сохранить черновик"; message(R.string.operation_failed) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspaceReady.completeExceptionally(e); workspaceLoadError.value = "Не удалось восстановить рабочее пространство"; _draftStatus.value = "Не удалось загрузить черновики"; message(R.string.operation_failed) }
        }
    }
    private fun changeWorkspace(value: WorkspaceState) {
        _workspace.value = value
        _draftStatus.value = "Сохранение…"
        workspaceWrites.trySend(Unit)
    }
    private suspend fun persistWorkspace() = workspaceLock.withLock {
        val state = _workspace.value
        workspaceStore.save(state, pdfSelections.value, pdfViews.value)
        if (_workspace.value == state) _draftStatus.value = "Черновик сохранён"
    }
    fun editEntry(entry: Entry, pane: Int = _workspace.value.activePane, collection: String? = null) = perform {
        workspaceReady.await()
        if (_editorSaving.value) return@perform
        val data = repository.dao.snapshot()
        val draft = _workspace.value.drafts.firstOrNull { it.entry.id == entry.id } ?: EntryDraft(if (entry.content == null) entry.withContent(entry.richText()) else entry,
            data.entryTags.filter { it.entryId == entry.id }.mapNotNull { link -> data.tags.firstOrNull { it.id == link.tagId }?.name }.joinToString(", "),
            data.memberships.filter { it.entryId == entry.id }.map { it.collectionId }.toSet() + setOfNotNull(collection),
            data.readerMemberships.filter { it.entryId == entry.id }.map { it.folderId }.toSet())
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id == entry.id } + draft, activeDraftId = entry.id))
        openNoteTab(entry.id, pane)
        if (draft.entry.kind == EntryKind.Consp) updateDraft(draft)
    }
    fun editBeside(entry: Entry, pane: Int) { layout(split = true); editEntry(entry, 1 - pane) }
    fun newConsp(documentId: String? = null, collection: String? = null, title: String = "", content: RichText = RichText()) {
        val active = _workspace.value.activePane
        val pane = if (_workspace.value.panes[active].selected?.kind == WorkspaceKind.Pdf) 1 - active else active
        if (pane != active) layout(split = true)
        editEntry(Entry(UUID.randomUUID().toString(), title, kind = EntryKind.Consp, sources = listOfNotNull(documentId)).withContent(content), pane, collection)
    }
    fun convertToConsp(entry: Entry) = perform {
        val data = repository.dao.snapshot()
        val next = entry.copy(kind = EntryKind.Consp, sources = (entry.sources + listOfNotNull(entry.documentId)).distinct(), documentId = if (entry.anchor == null) null else entry.documentId)
        val collections = if (entry.kind == EntryKind.Consp) data.memberships.filter { it.entryId == entry.id }.map { it.collectionId }.toSet() else repository.foldersForConversion(data.memberships.filter { it.entryId == entry.id }.map { it.collectionId }.toSet())
        repository.saveEntry(next, data.entryTags.filter { it.entryId == entry.id }.mapNotNull { link -> data.tags.firstOrNull { it.id == link.tagId }?.name }.joinToString(", "), collections, if (next.documentId == null) emptySet() else null)
        _workspace.value.drafts.firstOrNull { it.entry.id == entry.id }?.let { updateDraft(it.copy(entry = next, collections = collections, readerFolders = if (next.documentId == null) emptySet() else it.readerFolders)) }
        openNote(next)
    }
    private val quoteHistories = mutableMapOf<String, QuoteHistory>()
    fun quoteHistory(id: String) = quoteHistories.getOrPut(id) { QuoteHistory() }
    private val editorHistories = mutableMapOf<String, EditorHistory>()
    private val fieldHistories = mutableMapOf<String, FieldHistory>()
    fun fieldHistory(id: String) = fieldHistories.getOrPut(id) { FieldHistory() }
    private val graphStore = GraphViewStore(application)
    private val _graphView = MutableStateFlow(GraphViewState())
    val graphViewState = _graphView.asStateFlow()
    private val graphWrites = Channel<Unit>(Channel.CONFLATED)
    private var graphChanged = false
    val fieldViews = MutableStateFlow<Map<String, FieldViewState>>(emptyMap())
    private var fieldViewChanged = false
    init {
        viewModelScope.launch {
            try {
                val stored = graphStore.values.first(); if (!graphChanged) _graphView.value = stored
                val fields = graphStore.fields.first(); if (!fieldViewChanged) fieldViews.value = fields
            }
            catch (_: Exception) { messages.emit("Не удалось прочитать сохранённый вид графа; библиотека не изменена.") }
            for (ignored in graphWrites) {
                try { graphStore.save(_graphView.value); graphStore.saveFields(fieldViews.value) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { messages.emit("Не удалось сохранить вид графа.") }
            }
        }
    }
    fun graphView(value: GraphViewState) { graphChanged = true; _graphView.value = value; graphWrites.trySend(Unit) }
    fun fieldView(id: String, pane: Int, value: FieldViewState) {
        fieldViewChanged = true; fieldViews.value = fieldViews.value + ("$pane:$id" to value); graphWrites.trySend(Unit)
    }
    fun historyForEntry(id: String) = editorHistories.getOrPut(id) { EditorHistory() }
    private val autoSaves = mutableMapOf<String, Job>()
    private val entryWriteLock = Mutex()
    fun newNote(documentId: String? = null, collection: String? = null) {
        val active = _workspace.value.activePane
        val pane = if (_workspace.value.panes[active].selected?.kind == WorkspaceKind.Pdf) 1 - active else active
        if (pane != active) changeWorkspace(_workspace.value.copy(split = true))
        editEntry(Entry(UUID.randomUUID().toString(), "", documentId = documentId, color = preferences.value.defaultColor), pane, collection)
    }
    fun focusEditorField(id: String, field: String) {
        _workspace.value.drafts.firstOrNull { it.entry.id == id && it.focusedField != field }?.let { updateDraft(it.copy(focusedField = field)) }
    }
    fun updateDraft(draft: EntryDraft) {
        if (_workspace.value.drafts.none { it.entry.id == draft.entry.id }) return
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.map { if (it.entry.id == draft.entry.id) draft.copy(modifiedAt = System.currentTimeMillis()) else it }))
        if (draft.entry.kind == EntryKind.Consp && draft.entry.title.isNotBlank()) {
            autoSaves.remove(draft.entry.id)?.cancel()
            autoSaves[draft.entry.id] = perform {
                delay(900)
                entryWriteLock.withLock {
                    val latest = _workspace.value.drafts.firstOrNull { it.entry.id == draft.entry.id } ?: return@withLock
                    val data = repository.dao.snapshot()
                    repository.saveEntry(latest.entry, latest.tags, latest.collections.intersect(data.collections.map { it.id }.toSet()), latest.readerFolders.intersect(data.readerFolders.map { it.id }.toSet()))
                    if (_workspace.value.drafts.firstOrNull { it.entry.id == draft.entry.id } == latest) _draftStatus.value = "Конспект сохранён"
                }
            }
        }
    }
    fun closeEditor() {
        if (_editorSaving.value) return
        val state = _workspace.value
        val pane = state.activePane
        val tab = state.panes[pane].selected
        if (tab?.kind == WorkspaceKind.Note && state.drafts.any { it.entry.id == tab.documentId }) closeTab(pane, tab.id)
        focusPane(pane)
    }
    fun resumeDraft(draft: EntryDraft) {
        if (_editorSaving.value) return
        changeWorkspace(_workspace.value.copy(activeDraftId = draft.entry.id))
        openNoteTab(draft.entry.id, _workspace.value.activePane)
    }
    private fun openNoteTab(id: String, pane: Int) {
        val state = _workspace.value
        val old = state.panes[pane]
        val tab = old.tabs.firstOrNull { it.documentId == id && it.kind == WorkspaceKind.Note } ?: WorkspaceTab(UUID.randomUUID().toString(), id, WorkspaceKind.Note)
        setPane(pane, old.open(tab))
        focusPane(pane); navigate("reader")
    }
    fun openNote(entry: Entry, pane: Int = _workspace.value.activePane) { openNoteTab(entry.id, pane) }
    fun focusPane(pane: Int) {
        val state = _workspace.value
        val tab = state.panes[pane].selected
        val draftId = tab?.documentId?.takeIf { tab.kind == WorkspaceKind.Note && state.drafts.any { d -> d.entry.id == it } }
        if (state.activePane != pane || state.activeDraftId != draftId || (!state.split && state.visiblePane != pane))
            changeWorkspace(state.copy(activePane = pane, activeDraftId = draftId, visiblePane = if (state.split) state.visiblePane else pane))
        _reader.value = _readers.value[pane]
    }
    private fun setPane(pane: Int, value: WorkspacePane) { changeWorkspace(_workspace.value.copy(panes = _workspace.value.panes.toMutableList().also { it[pane] = value })) }
    fun layout(split: Boolean = _workspace.value.split, fraction: Float = _workspace.value.splitFraction,
        sidebarWidth: Float = _workspace.value.sidebarWidth, structureWidth: Float = _workspace.value.structureWidth, sidebarVisible: Boolean = _workspace.value.sidebarVisible) {
        changeWorkspace(_workspace.value.copy(split = split, splitFraction = fraction.coerceIn(.3f, .7f), sidebarWidth = sidebarWidth.coerceIn(200f, 400f), structureWidth = structureWidth.coerceIn(220f, 400f), sidebarVisible = sidebarVisible, visiblePane = if (_workspace.value.split && !split) _workspace.value.activePane else _workspace.value.visiblePane))
        if (!split) focusPane(_workspace.value.visiblePane)
    }
    fun collapsePane(pane: Int) {
        val keep = 1 - pane
        changeWorkspace(_workspace.value.copy(split = false, visiblePane = keep, activePane = keep, splitFraction = .5f))
        focusPane(keep)
    }
    fun maximizePane(pane: Int) { changeWorkspace(_workspace.value.copy(split = false, visiblePane = pane)); focusPane(pane) }
    fun selectTab(pane: Int, id: String) {
        val state = _workspace.value.panes[pane]
        val tab = state.tabs.firstOrNull { it.id == id } ?: return
        setPane(pane, state.copy(selectedId = id)); focusPane(pane); navigate("reader")
        if (tab.kind == WorkspaceKind.Pdf) library.value.documents.firstOrNull { it.id == tab.documentId }?.let { open(it, page = tab.page, recordHistory = false, pane = pane, tabId = id, offset = tab.offset) }
        else changeWorkspace(_workspace.value.copy(activeDraftId = tab.documentId.takeIf { note -> _workspace.value.drafts.any { it.entry.id == note } }))
    }
    fun closeTab(pane: Int, id: String) {
        if (_editorSaving.value) return
        val old = _workspace.value.panes[pane]
        if (old.selectedId == id) opening[pane]?.cancel()
        setPane(pane, old.close(id))
        rememberPdfSelection(id, null)
        pdfViews.value = pdfViews.value.filterKeys { !it.startsWith("$id:") }
        if (old.selectedId == id) {
            _readers.value[pane]?.engine?.let { viewModelScope.launch(Dispatchers.IO) { it.close() } }
            _readers.value = _readers.value - pane
            if (_workspace.value.activePane == pane) _reader.value = null
            _workspace.value.panes[pane].selectedId?.let { selectTab(pane, it) }
        }
        if (_workspace.value.split && _workspace.value.panes[pane].tabs.isEmpty()) collapsePane(pane)
        if (!_workspace.value.split && _workspace.value.panes[_workspace.value.visiblePane].tabs.isEmpty() && _workspace.value.panes[1 - _workspace.value.visiblePane].tabs.isNotEmpty()) maximizePane(1 - _workspace.value.visiblePane)
        if (_workspace.value.panes.none { it.selected?.kind == WorkspaceKind.Note }) changeWorkspace(_workspace.value.copy(activeDraftId = null))
    }
    fun pinTab(pane: Int, id: String) { val old = _workspace.value.panes[pane]; setPane(pane, old.copy(tabs = old.tabs.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }.sortedByDescending { it.pinned })) }
    fun moveTab(pane: Int, id: String, delta: Int) { setPane(pane, _workspace.value.panes[pane].move(id, delta)) }
    fun openRight(tab: WorkspaceTab, from: Int = _workspace.value.activePane) {
        val pane = 1 - from
        layout(split = true)
        if (tab.kind == WorkspaceKind.Pdf) library.value.documents.firstOrNull { it.id == tab.documentId }?.let { open(it, page = tab.page, pane = pane, offset = tab.offset) }
        else when (tab.kind) {
            WorkspaceKind.Note -> openNoteTab(tab.documentId, pane)
            WorkspaceKind.ResearchField -> openResearchField(tab.documentId, pane)
            WorkspaceKind.LibraryGraph -> openLibraryGraph(pane)
            else -> Unit
        }
    }
    private fun openTool(id: String, kind: WorkspaceKind, pane: Int) {
        val old = _workspace.value.panes[pane]
        val tab = old.tabs.firstOrNull { it.documentId == id && it.kind == kind }
            ?: WorkspaceTab(UUID.randomUUID().toString(), id, kind)
        setPane(pane, old.open(tab)); focusPane(pane); navigate("reader")
    }
    fun openResearchField(id: String, pane: Int = _workspace.value.activePane) = openTool(id, WorkspaceKind.ResearchField, pane)
    fun openLibraryGraph(pane: Int = _workspace.value.activePane) = openTool("library-graph", WorkspaceKind.LibraryGraph, pane)
    fun newResearchField(title: String) = perform {
        val field = ResearchField(UUID.randomUUID().toString(), title.trim())
        repository.saveResearchField(field); openResearchField(field.id)
    }
    val fieldEdits = MutableStateFlow<Map<String, ResearchField>>(emptyMap())
    private val fieldWrites = Channel<Unit>(Channel.CONFLATED)
    private val fieldLock = Mutex()
    private val writtenFields = mutableMapOf<String, ResearchField>()
    init {
        viewModelScope.launch {
            library.collect { snapshot ->
                fieldLock.withLock {
                    val observed = snapshot.researchFields.associateBy { it.id }
                    val complete = fieldEdits.value.filter { (id, value) -> observed[id] == value && writtenFields[id] == value }.keys
                    if (complete.isNotEmpty()) {
                        fieldEdits.value = fieldEdits.value - complete
                        complete.forEach { writtenFields.remove(it) }
                    }
                }
            }
        }
        viewModelScope.launch {
            for (ignored in fieldWrites) {
                delay(100)
                try { flushResearchFields() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { messages.emit("Не удалось сохранить исследовательское поле. Изменения остаются в этой сессии.") }
            }
        }
    }
    fun saveResearchField(field: ResearchField) {
        fieldEdits.value = fieldEdits.value + (field.id to field)
        fieldWrites.trySend(Unit)
    }
    private suspend fun flushResearchFields() = fieldLock.withLock {
        val known = repository.dao.allResearchFields().map { it.id }.toSet()
        for ((id, field) in fieldEdits.value) {
            if (id in known && writtenFields[id] != field) { repository.saveResearchField(field); writtenFields[id] = field }
        }
    }
    val fieldInsertion = MutableStateFlow<String?>(null)
    fun requestFieldInsertion(entry: Entry) = perform {
        saveEditor(entry.id).join()
        if (repository.dao.allEntries().any { it.id == entry.id }) fieldInsertion.value = entry.id
    }
    fun quoteToField(text: String, anchor: Anchor, document: Document) = perform {
        val entry = Entry(UUID.randomUUID().toString(), quoteTitle(text), quote = text, documentId = document.id,
            anchor = anchor, kind = EntryKind.Quote, type = NoteType.Quote)
        repository.saveEntry(entry, "", emptySet()); fieldInsertion.value = entry.id
    }
    fun addEntryToField(entryId: String, fieldId: String) = perform {
        val data = repository.dao.snapshot()
        val field = data.researchFields.first { it.id == fieldId }
        val entry = data.entries.first { it.id == entryId }
        val placement = ResearchPlacement(UUID.randomUUID().toString(), entryId, field.viewX + 20, field.viewY + 20,
            kind = if (entry.kind == EntryKind.Quote) ResearchCardKind.Quote else ResearchCardKind.Note)
        repository.saveResearchField(field.copy(placements = field.placements + placement))
        fieldInsertion.value = null
        val pane = if (_workspace.value.panes[_workspace.value.activePane].selected?.kind == WorkspaceKind.Pdf) 1 - _workspace.value.activePane else _workspace.value.activePane
        if (pane != _workspace.value.activePane) layout(split = true)
        openResearchField(fieldId, pane)
    }
    fun saveAllEditors() = perform {
        _workspace.value.drafts.filter { it.entry.title.isNotBlank() }.forEach { saveEditor(it.entry.id).join() }
        persistWorkspace()
    }
    fun collapseSecondary() {
        val state = _workspace.value
        val tabs = (state.panes[0].tabs + state.panes[1].tabs).distinctBy { it.id }
        changeWorkspace(state.copy(panes = listOf(state.panes[0].copy(tabs = tabs), WorkspacePane()), split = false, visiblePane = 0, activePane = 0))
        focusPane(0)
    }
    fun deleteResearchField(id: String) = perform {
        fieldLock.withLock { fieldEdits.value = fieldEdits.value - id; writtenFields.remove(id); repository.dao.deleteResearchField(id) }
        _workspace.value.panes.forEachIndexed { pane, state -> state.tabs.filter { it.kind == WorkspaceKind.ResearchField && it.documentId == id }.forEach { closeTab(pane, it.id) } }
    }
    fun assembleResearchField(field: ResearchField, mode: InsertionMode) = perform {
        require(field.sequence.isNotEmpty()) { "Задайте порядок карточек для сборки" }
        val data = repository.dao.snapshot()
        val content = ResearchAssembly.append(RichText(), field, field.sequence, data.entries, mode)
        val entry = Entry(UUID.randomUUID().toString(), "Конспект · " + field.title, kind = EntryKind.Consp,
            sources = field.placements.flatMap { p -> data.entries.first { it.id == p.entryId }.sourceBooks() }.distinct()).withContent(content)
        repository.saveEntry(entry, "", emptySet()); openNote(entry)
    }
    fun moveTabToOtherPane(pane: Int, id: String) {
        val old = _workspace.value.panes[pane]
        val tab = old.tabs.firstOrNull { it.id == id } ?: return
        val other = 1 - pane
        setPane(pane, old.close(id)); layout(split = true)
        setPane(other, _workspace.value.panes[other].open(tab)); selectTab(other, tab.id)
    }
    fun nextTab(delta: Int) { val pane = _workspace.value.activePane; val state = _workspace.value.panes[pane]; if (state.tabs.isNotEmpty()) selectTab(pane, state.tabs[Math.floorMod(state.tabs.indexOfFirst { it.id == state.selectedId } + delta, state.tabs.size)].id) }
    fun insertQuote(text: String, document: Document, page: Int) {
        val data = library.value
        val id = _workspace.value.lastConspByBook[document.id]
        val target = data.entries.firstOrNull { it.id == id && it.kind == EntryKind.Consp } ?: data.entries.firstOrNull { it.kind == EntryKind.Consp && document.id in it.sourceBooks() }
        if (target != null) appendToConsp(target.id, text, document, page)
        else createFromQuote(text, document, page)
    }
    private fun quoteFragment(text: String, document: Document, page: Int): RichText = RichText.fromMarkdown("> " + text.replace("\n", "\n> ") + "\n\n[" + RichText.escapeLabel(document.title + " · " + (page + 1)) + "](readerka://document/" + document.id + "?page=" + (page + 1) + ")")
    fun createFromQuote(text: String, document: Document, page: Int) = perform {
        val entry = Entry(UUID.randomUUID().toString(), "Конспект · " + document.title, kind = EntryKind.Consp, sources = listOf(document.id)).withContent(quoteFragment(text, document, page))
        repository.saveEntry(entry, "", emptySet())
        changeWorkspace(_workspace.value.copy(lastConspByBook = _workspace.value.lastConspByBook + (document.id to entry.id)))
        layout(split = true); editEntry(entry, 1 - _workspace.value.activePane).join()
    }
    fun appendToConsp(id: String, text: String, document: Document, page: Int) = perform {
        val data = repository.dao.snapshot()
        val existing = _workspace.value.drafts.firstOrNull { it.entry.id == id }
        val entry = existing?.entry ?: data.entries.firstOrNull { it.id == id && it.kind == EntryKind.Consp } ?: return@perform

        changeWorkspace(_workspace.value.copy(lastConspByBook = _workspace.value.lastConspByBook + (document.id to id)))
        layout(split = true)
        editEntry(entry, 1 - _workspace.value.activePane).join()
        _workspace.value.drafts.firstOrNull { it.entry.id == id }?.let { latest ->
            val rich = latest.entry.richText()
            val value = rich.replace(rich.text.length, rich.text.length, RichText.plain("\n\n")).let { it.replace(it.text.length, it.text.length, quoteFragment(text, document, page)) }
            updateDraft(latest.copy(entry = latest.entry.withContent(value), cursorStart = value.text.length, cursorEnd = value.text.length))
        }
    }
    fun checkpointDraft(id: String, label: String) = perform {
        val draft = _workspace.value.drafts.firstOrNull { it.entry.id == id } ?: return@perform
        entryWriteLock.withLock {
            val data = repository.dao.snapshot()
            if (data.entries.none { it.id == id }) repository.saveEntry(draft.entry, draft.tags, draft.collections)
            repository.checkpoint(draft.entry, label)
        }
    }
    fun restoreVersion(version: NoteVersion) = perform {
        val draft = kotlinx.serialization.json.Json.decodeFromString<EntryDraft>(version.snapshot)
        val current = _workspace.value.drafts.firstOrNull { it.entry.id == version.entryId }
        if (current != null) checkpointDraft(current.entry.id, "До восстановления версии").join()
        val data = repository.dao.snapshot()
        val restored = draft.copy(entry = draft.entry.copy(sources = draft.entry.sources.filter { id -> data.documents.any { it.id == id } }), collections = draft.collections.intersect(data.collections.map { it.id }.toSet()), readerFolders = draft.readerFolders.intersect(data.readerFolders.map { it.id }.toSet()))
        repository.saveEntry(restored.entry, restored.tags, restored.collections, restored.readerFolders)
        if (current != null) updateDraft(restored) else editEntry(restored.entry).join()
    }
    data class NoteReturn(val pane: Int, val tabId: String)
    private val noteReturns = ArrayDeque<NoteReturn>()
    val canReturnToNote = MutableStateFlow(false)
    val requestedBlock = MutableStateFlow<Pair<String, String>?>(null)
    fun openLink(link: String, pane: Int, rememberReturn: Boolean = true, data: LibrarySnapshot = library.value) {
        val uri = Uri.parse(link)
        if (uri.scheme == "readerka") {
            val id = uri.pathSegments.firstOrNull() ?: return
            val note = data.entries.firstOrNull { it.id == id }
            val doc = data.documents.firstOrNull { it.id == id }
            if (uri.host == "entry" && note == null || uri.host == "document" && doc == null) { messages.tryEmit("Источник недоступен или удалён"); return }
            if (rememberReturn) _workspace.value.panes[pane].selected?.takeIf { it.kind == WorkspaceKind.Note }?.let { noteReturns.addLast(NoteReturn(pane, it.id)); canReturnToNote.value = true }
            if (uri.host == "entry" && note != null) {
                openNote(note, pane); requestedBlock.value = null
                uri.getQueryParameter("block")?.let { block ->
                    val target = _workspace.value.drafts.firstOrNull { it.entry.id == id }?.entry ?: note
                    if (target.richText().blocks.any { it.id == block }) requestedBlock.value = id to block else messages.tryEmit("Фрагмент был удалён; открыта запись")
                }
            }
            else if (uri.host == "document" && doc != null) {
                val original = data.entries.firstOrNull { it.id == uri.getQueryParameter("entry") && it.documentId == doc.id }
                open(doc, anchor = original?.anchor, page = original?.anchor?.page ?: ((uri.getQueryParameter("page")?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0), pane = pane)
            }
        } else if (uri.scheme in listOf("http", "https")) runCatching { context().startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun incomingLink(link: String) = perform {
        workspaceReady.await()
        openLink(link, _workspace.value.activePane, false, repository.dao.snapshot())
    }
    fun returnToNote() {
        while (noteReturns.isNotEmpty()) {
            val target = noteReturns.removeLast()
            if (_workspace.value.panes[target.pane].tabs.any { it.id == target.tabId }) { if (_workspace.value.split || target.pane == _workspace.value.visiblePane) selectTab(target.pane, target.tabId) else { maximizePane(target.pane); selectTab(target.pane, target.tabId) }; break }
        }
        canReturnToNote.value = noteReturns.isNotEmpty()
    }
    fun discardDraft(id: String) = perform {
        workspaceReady.await()
        if (_editorSaving.value && _workspace.value.activeDraftId == id) return@perform
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id == id }, activeDraftId = _workspace.value.activeDraftId?.takeUnless { it == id }))
        autoSaves.remove(id)?.cancel()
        editorHistories.remove(id)
        if (library.value.entries.none { it.id == id }) _workspace.value.panes.forEachIndexed { pane, state -> state.tabs.filter { it.kind == WorkspaceKind.Note && it.documentId == id }.forEach { closeTab(pane, it.id) } }
        persistWorkspace()
    }
    fun saveEditor(id: String? = _workspace.value.activeDraftId) = perform {
        workspaceReady.await()
        if (_editorSaving.value) return@perform
        autoSaves.remove(id)?.cancel()
        val draft = _workspace.value.drafts.firstOrNull { it.entry.id == id } ?: return@perform
        if (draft.entry.title.isBlank()) return@perform
        _editorSaving.value = true
        try {
            val data = repository.dao.snapshot()
            entryWriteLock.withLock { repository.saveEntry(draft.entry.copy(title = draft.entry.title.trim()), draft.tags,
                draft.collections.intersect(data.collections.map { it.id }.toSet()),
                draft.readerFolders.intersect(data.readerFolders.filter { it.documentId == draft.entry.documentId && it.kind == ReaderFolderKind.Quotes }.map { it.id }.toSet())) }
            if (draft.entry.kind == EntryKind.Consp) {
                persistWorkspace()
                if (_workspace.value.drafts.firstOrNull { it.entry.id == draft.entry.id }?.entry == draft.entry)
                    _draftStatus.value = "Конспект сохранён"
                return@perform
            }
            // Stable entry IDs make retries safe even if the disk write below fails after the Room transaction.
            workspaceLock.withLock {
                fun committed(state: WorkspaceState): WorkspaceState {
                    val remaining = state.drafts.filterNot { it.entry.id == draft.entry.id && it.entry == draft.entry && it.tags == draft.tags && it.collections == draft.collections && it.readerFolders == draft.readerFolders }
                    return state.copy(drafts = remaining, activeDraftId = state.activeDraftId?.takeIf { id -> remaining.any { it.entry.id == id } })
                }
                val state = committed(_workspace.value)
                workspaceStore.save(state)
                // Reading may continue while the disk write is suspended. Keep its newest history.
                val latest = committed(_workspace.value)
                _workspace.value = latest
                if (latest == state) _draftStatus.value = "Черновик сохранён" else workspaceWrites.trySend(Unit)
            }
        } finally { _editorSaving.value = false }
    }
    fun historyBack() = historyMove(false)
    fun historyForward() = historyMove(true)
    private fun historyMove(forward: Boolean) {
        if (_busy.value != null) return
        val history = _workspace.value.history
        val target = if (forward) history.forward() else history.back()
        if (target == history) return
        val location = target.current ?: return
        val document = library.value.documents.firstOrNull { it.id == location.documentId } ?: return
        open(document, page = location.page, offset = location.offset, recordHistory = false,
            onOpened = { changeWorkspace(_workspace.value.copy(history = target)) })
    }
    fun trackNotePosition(pane: Int, id: String, offset: Int) {
        val old = _workspace.value.panes[pane]
        if (old.tabs.none { it.kind == WorkspaceKind.Note && it.documentId == id && it.offset != offset }) return
        setPane(pane, old.copy(tabs = old.tabs.map { if (it.kind == WorkspaceKind.Note && it.documentId == id) it.copy(offset = offset) else it }))
    }
    fun trackPosition(documentId: String, page: Int, offset: Int, pane: Int = _workspace.value.activePane, tabId: String? = null) {
        val session = _readers.value[pane]?.takeIf { it.document.id == documentId } ?: return
        val current = session.copy(page = page, offset = offset)
        _readers.value = _readers.value + (pane to current)
        if (pane == _workspace.value.activePane) _reader.value = current
        val old = _workspace.value.panes[pane]
        val tabs = old.tabs.map { if (it.id == (tabId ?: session.tabId)) it.copy(page = page, offset = offset) else it }
        val history = if (pane == _workspace.value.activePane) _workspace.value.history.update(ReadingLocation(documentId, page, offset)) else _workspace.value.history
        val state = _workspace.value.copy(panes = _workspace.value.panes.toMutableList().also { it[pane] = old.copy(tabs = tabs) }, history = history)
        if (state != _workspace.value) changeWorkspace(state)
    }
    private fun recordLocation(session: ReaderSession) {
        changeWorkspace(_workspace.value.copy(history = _workspace.value.history.visit(ReadingLocation(session.document.id, session.page, session.offset))))
    }
    fun navigate(value: String) { savedState["screen"] = value }
    private fun context() = getApplication<Application>()
    private fun message(id: Int) { messages.tryEmit(context().getString(id)) }
    fun perform(label: Int? = null, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (label != null && _busy.value != null) return@launch
        if (label != null) _busy.value = Busy(label)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message(when (label) { R.string.importing -> R.string.import_failed; R.string.restoring -> R.string.restore_failed; R.string.exporting -> R.string.export_failed; R.string.opening -> R.string.open_failed; else -> R.string.operation_failed }) }
        finally { if (label != null) _busy.value = null }
    }
    fun import(uris: List<Uri>, folder: String?) = perform(R.string.importing) {
        var failed = 0
        for ((i, uri) in uris.withIndex()) {
            try { repository.importPdf(uri, folder) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed++ }
            _busy.value = Busy(R.string.importing, (i + 1f) / uris.size)
        }
        messages.emit(context().getString(R.string.import_result, uris.size - failed, failed))
    }
    fun open(document: Document, anchor: Anchor? = null, page: Int? = null, navigate: Boolean = true, recordHistory: Boolean = true, onOpened: () -> Unit = {}, pane: Int = _workspace.value.activePane, tabId: String? = null, offset: Int? = null) {
        val paneState = _workspace.value.panes[pane]
        val previousTab = paneState.tabs.firstOrNull { it.id == tabId } ?: paneState.tabs.firstOrNull { it.kind == WorkspaceKind.Pdf && it.documentId == document.id }
            ?: WorkspaceTab(UUID.randomUUID().toString(), document.id, WorkspaceKind.Pdf, page = document.lastPage, offset = document.lastOffset)
        val targetPage = page ?: anchor?.page ?: previousTab.page
        val targetOffset = (offset ?: if (page == null && anchor == null) previousTab.offset else 0).coerceIn(0, 10_000)
        val tab = previousTab.copy(page = targetPage.coerceAtLeast(0), offset = targetOffset)
        setPane(pane, paneState.copy(tabs = paneState.tabs.map { if (it.id == tab.id) tab else it }).open(tab))
        if (navigate) { focusPane(pane); this.navigate("reader") }
        val existing = _readers.value[pane]
        fun install(engine: PdfEngine) {
            val session = ReaderSession(document, engine, targetPage.coerceIn(0, engine.pageCount - 1), targetOffset, anchor, pane = pane, tabId = tab.id)
            if (session.page != tab.page) {
                val currentPane = _workspace.value.panes[pane]
                setPane(pane, currentPane.copy(tabs = currentPane.tabs.map { if (it.id == tab.id) it.copy(page = session.page) else it }))
            }
            _readers.value = _readers.value + (pane to session)
            if (pane == _workspace.value.activePane) _reader.value = session
            if (document.id !in openDocumentIds.value) savedState["open-documents"] = ArrayList(openDocumentIds.value + document.id)
            if (recordHistory) recordLocation(session)
            onOpened()
        }
        opening[pane]?.cancel()
        if (existing?.document?.id == document.id) { install(existing.engine); return }
        opening[pane] = perform {
            var engine: PdfEngine? = null
            try {
                engine = withContext(Dispatchers.IO) { PdfEngine(repository.pdfFile(document), context().cacheDir) }
                install(engine!!); engine = null
                existing?.engine?.let { withContext(Dispatchers.IO) { it.close() } }
            } finally { engine?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } } }
        }
    }
    fun closeDocument(id: String) {
        for ((pane, state) in _workspace.value.panes.withIndex()) state.tabs.filter { it.documentId == id && it.kind == WorkspaceKind.Pdf }.forEach { closeTab(pane, it.id) }
        savedState["open-documents"] = ArrayList(openDocumentIds.value.filterNot { it == id })
    }
    fun source(entry: Entry) { library.value.documents.firstOrNull { it.id == entry.documentId }?.let { open(it, entry.anchor) } }
    fun position(document: Document, page: Int, offset: Int) = perform {
        repository.dao.savePosition(document.id, page, offset, System.currentTimeMillis())
    }
    fun highlight(text: String, anchor: Anchor, color: Marker, document: Document? = _reader.value?.document): Job? {
        val doc = document ?: return null
        return perform {
        repository.dao.putEntry(Entry(UUID.randomUUID().toString(), text.lineSequence().first().take(80), text,
            documentId = doc.id, anchor = anchor, type = NoteType.Quote, color = color))
    } }
    fun saveEntry(entry: Entry, tags: String, collections: Set<String>, readerFolders: Set<String>? = null) = perform { repository.saveEntry(entry, tags, collections, readerFolders) }
    fun readerFolder(value: ReaderFolder) = perform { repository.saveReaderFolder(value) }
    fun deleteReaderFolder(id: String) = perform { repository.dao.deleteReaderFolder(id) }
    fun saveBookmark(value: Bookmark) = perform { repository.saveBookmark(value) }
    fun openBookmark(value: Bookmark) {
        library.value.documents.firstOrNull { it.id == value.documentId }?.let { document ->
            open(document, page = value.page, offset = value.offset)
        }
    }
    fun saveDocument(document: Document, tags: String) = perform { repository.saveDocument(document, tags) }
    fun folder(value: DocumentFolder) = perform { repository.saveFolder(value) }
    fun collection(value: Collection) = perform { repository.saveCollection(value) }
    fun deleteFolder(id: String, notes: Boolean) = perform { if (notes) repository.dao.deleteCollection(id) else repository.dao.deleteFolder(id) }
    fun deleteEntries(ids: Set<String>) = perform {
        workspaceReady.await()
        ids.forEach { autoSaves.remove(it)?.cancel(); editorHistories.remove(it) }
        entryWriteLock.withLock {
            fieldLock.withLock {
                repository.deleteEntries(ids)
                fieldEdits.value = fieldEdits.value.mapValues { (_, field) ->
                    field.removePlacements(field.placements.filter { it.entryId in ids }.map { it.id }.toSet())
                }
                writtenFields.clear()
                fieldHistories.values.forEach { history ->
                    for (states in listOf(history.undo, history.redo)) states.indices.forEach { index ->
                        val state = states[index]
                        states[index] = state.removePlacements(state.placements.filter { it.entryId in ids }.map { it.id }.toSet())
                    }
                }
            }
        }
        fieldWrites.trySend(Unit)
        _workspace.value.panes.forEachIndexed { pane, state -> state.tabs.filter { it.kind == WorkspaceKind.Note && it.documentId in ids }.forEach { closeTab(pane, it.id) } }
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id in ids }, activeDraftId = _workspace.value.activeDraftId?.takeUnless { it in ids }))
        persistWorkspace()
    }
    fun removeFromCollection(id: String, entries: Set<String>) = perform { repository.dao.removeMemberships(id, entries.toList()) }
    fun batch(ids: Set<String>, color: Marker? = null, type: NoteType? = null, tags: String? = null, collection: String? = null) = perform { repository.batch(ids, color, type, tags, collection) }
    fun bookmark(page: Int, title: String, comment: String) = perform {
        val doc = _reader.value?.document ?: return@perform
        repository.saveBookmark(Bookmark(UUID.randomUUID().toString(), doc.id, page, title, comment))
    }
    fun deleteBookmark(id: String) = perform { repository.dao.deleteBookmark(id) }
    fun renameTag(id: String, name: String) = perform { repository.renameTag(id, name) }
    fun deleteTag(id: String) = perform { repository.dao.deleteTag(id) }
    fun settings(value: ReaderPreferences) = perform { preferencesStore.save(value) }
    fun export(uri: Uri): Job {
        val kind = pendingExport; val include = includePdf; val ids = exportIds
        return perform(R.string.exporting) {
        flushConspDrafts()
        val data = repository.dao.snapshot()
        val subset = ids?.let { ids -> data.copy(entries = data.entries.filter { it.id in ids }, memberships = data.memberships.filter { it.entryId in ids }, entryTags = data.entryTags.filter { it.entryId in ids }) } ?: data
        withContext(Dispatchers.IO) {
            context().contentResolver.openOutputStream(uri)?.use { out ->
                when (kind) {
                    ExportKind.Obsidian -> {
                        val result = VaultExporter().write(out, data, include, { d -> runCatching { repository.pdfFile(d).takeIf { it.isFile }?.inputStream() }.getOrNull() }) { n, total -> _busy.value = Busy(R.string.exporting, n.toFloat() / total) }
                        messages.emit(context().getString(R.string.export_result, result.entries, result.missingPdf.size))
                    }
                    ExportKind.Backup -> { BackupArchive.write(out, data, repository::pdfFile); message(R.string.backup_done) }
                    ExportKind.Markdown -> { out.write(NotesExporter.markdown(subset).toByteArray(Charsets.UTF_8)); message(R.string.export_done) }
                    ExportKind.Csv -> { out.write(NotesExporter.csv(subset).toByteArray(Charsets.UTF_8)); message(R.string.export_done) }
                }
            } ?: error("Cannot open destination")
        }
    } }
    private suspend fun flushConspDrafts() {
        flushResearchFields()
        val drafts = _workspace.value.drafts.filter { it.entry.kind == EntryKind.Consp && it.entry.title.isNotBlank() }
        drafts.forEach { autoSaves.remove(it.entry.id)?.cancel() }
        entryWriteLock.withLock { val data = repository.dao.snapshot(); drafts.forEach { repository.saveEntry(it.entry, it.tags, it.collections.intersect(data.collections.map { it.id }.toSet()), it.readerFolders.intersect(data.readerFolders.map { it.id }.toSet())) } }
    }
    private val cloudClient = DropboxClient()
    private val cloudStore = DropboxAccountStore(application)
    val dropboxAccount = MutableStateFlow<String?>(null)
    val dropboxLogin = MutableStateFlow<DropboxClient.Login?>(null)
    data class DropboxUpload(val archive: File, val previous: DropboxFile?)
    val dropboxUpload = MutableStateFlow<DropboxUpload?>(null)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { dropboxAccount.value = cloudStore.load()?.account }
            catch (_: Exception) { messages.emit("Не удалось открыть учётные данные Dropbox; повторите вход. Локальная библиотека доступна.") }
        }
    }
    fun beginDropboxLogin(appKey: String): String? = try {
        DropboxClient.Login(appKey.trim()).also { dropboxLogin.value = it }.authorization.toString()
    } catch (_: IllegalArgumentException) { messages.tryEmit("Укажите App key из Dropbox App Console."); null }
    fun finishDropboxLogin(code: String) = perform(R.string.opening) {
        val login = dropboxLogin.value ?: return@perform
        try {
            val account = withContext(Dispatchers.IO) { cloudClient.login(login, code).also(cloudStore::save) }
            dropboxAccount.value = account.account; dropboxLogin.value = null
        } catch (e: DropboxException) { messages.emit(e.message.orEmpty()) }
    }
    fun disconnectDropbox() = perform {
        withContext(Dispatchers.IO) { cloudStore.clear() }
        dropboxAccount.value = null; dropboxLogin.value = null
        cancelDropboxUpload()
    }
    fun prepareDropboxUpload() = perform(R.string.exporting) {
        flushConspDrafts()
        val data = repository.dao.snapshot()
        try {
            withContext(Dispatchers.IO) {
                val credentials = cloudStore.load() ?: error("Требуется вход в Dropbox")
                val access = cloudClient.access(credentials)
                val previous = cloudClient.metadata(access)
                val archive = File(context().cacheDir, "dropbox-upload-${UUID.randomUUID()}.zip")
                try {
                    archive.outputStream().use { BackupArchive.write(it, data, repository::pdfFile) }
                    dropboxUpload.value = DropboxUpload(archive, previous)
                } catch (e: Exception) { archive.delete(); throw e }
            }
        } catch (e: DropboxException) { messages.emit(e.message.orEmpty()) }
    }
    fun cancelDropboxUpload() { dropboxUpload.value?.archive?.delete(); dropboxUpload.value = null }
    fun uploadDropbox() = perform(R.string.exporting) {
        val pending = dropboxUpload.value ?: return@perform
        try {
            withContext(Dispatchers.IO) {
                val credentials = cloudStore.load() ?: error("Требуется вход в Dropbox")
                val access = cloudClient.access(credentials)
                val result = cloudClient.upload(access, pending.archive, pending.previous)
                require(result.size == pending.archive.length() && result.contentHash == cloudClient.dropboxHash(pending.archive)) { "Проверка выгрузки Dropbox не прошла" }
                messages.emit("Копия библиотеки отправлена в Dropbox. Локальные изменения не выгружаются автоматически.")
            }
        } catch (e: DropboxException) { messages.emit(e.message.orEmpty()) }
        finally { cancelDropboxUpload() }
    }
    fun downloadDropbox() = perform(R.string.restoring) {
        flushConspDrafts(); cancelTransfer()
        val before = repository.dao.snapshot()
        try {
            withContext(Dispatchers.IO) {
                val credentials = cloudStore.load() ?: error("Требуется вход в Dropbox")
                val access = cloudClient.access(credentials)
                val remote = cloudClient.metadata(access) ?: error("В Dropbox ещё нет копии библиотеки")
                val archive = File(context().cacheDir, "dropbox-download-${UUID.randomUUID()}.zip")
                require(archive.parentFile!!.usableSpace > remote.size * 2 + 16L * 1024 * 1024) { "Недостаточно места для загрузки и проверки" }
                try {
                    cloudClient.download(access, remote, archive)
                    val staged = archive.inputStream().use { BackupArchive.stage(it, File(context().cacheDir, "dropbox-stage-${UUID.randomUUID()}")) }
                    try {
                        val service = RestoreService(repository)
                        val checked = service.checkPdfs(staged)
                        pendingTransfer.value = PendingTransfer(checked, before, LibraryMerge.differences(before, checked.data), TransferSummary.compare(before, checked.data), service.pdfConflicts(checked))
                    } catch (e: Exception) { staged.directory.deleteRecursively(); throw e }
                } finally { archive.delete() }
            }
        } catch (e: DropboxException) { messages.emit(e.message.orEmpty()) }
    }
    data class PendingTransfer(val staged: StagedBackup, val before: LibrarySnapshot,
        val differences: List<ImportDifference>, val counts: List<TransferCount>, val pdfConflicts: List<String>)
    val pendingTransfer = MutableStateFlow<PendingTransfer?>(null)
    fun cancelTransfer() { pendingTransfer.value?.staged?.directory?.deleteRecursively(); pendingTransfer.value = null }
    fun applyTransfer(policy: ImportPolicy) = perform(R.string.restoring) {
        val pending = pendingTransfer.value ?: return@perform
        val backup = try {
            withContext(Dispatchers.IO) { RestoreService(repository).restore(pending.staged, policy, pending.before) }
        } finally { pendingTransfer.value = null }
        fieldLock.withLock { fieldEdits.value = emptyMap(); writtenFields.clear(); fieldHistories.clear() }
        // Close stale readers and tabs only after the import has committed; retained drafts are local.
        _workspace.value.panes.forEachIndexed { pane, state -> state.tabs.forEach { closeTab(pane, it.id) } }
        messages.emit("Импорт завершён. Резервная копия: " + backup.name)
    }
    fun restore(uri: Uri) = perform(R.string.restoring) {
        flushConspDrafts()
        val before = repository.dao.snapshot()
        cancelTransfer()
        withContext(Dispatchers.IO) {
            val staged = context().contentResolver.openInputStream(uri)?.use { BackupArchive.stage(it, File(context().cacheDir, "restore-${UUID.randomUUID()}")) } ?: error("Cannot open backup")
            try {
                val service = RestoreService(repository)
                val checked = service.checkPdfs(staged)
                pendingTransfer.value = PendingTransfer(checked, before, LibraryMerge.differences(before, checked.data),
                    TransferSummary.compare(before, checked.data), service.pdfConflicts(checked))
            } catch (e: Exception) { staged.directory.deleteRecursively(); throw e }
        }
    }
    override fun onCleared() { cancelTransfer(); cancelDropboxUpload(); opening.values.forEach { it.cancel() }; _readers.value.values.forEach { session -> CoroutineScope(Dispatchers.IO).launch { session.engine.close() } }; super.onCleared() }
}
