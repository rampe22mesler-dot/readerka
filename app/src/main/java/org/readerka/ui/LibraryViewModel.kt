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
import java.io.File
import java.util.UUID

data class ReaderSession(val document: Document, val engine: PdfEngine, val page: Int, val offset: Int, val anchor: Anchor? = null, val token: String = UUID.randomUUID().toString())
data class Busy(val label: Int, val progress: Float? = null)
enum class ExportKind { Obsidian, Backup, Markdown, Csv }
enum class ReaderTool { Search, Quotes, Bookmarks, Page }

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
    private var opening: Job? = null
    private val workspaceStore = WorkspaceStore(application)
    private val _workspace = MutableStateFlow(WorkspaceState())
    val workspace = _workspace.asStateFlow()
    private val _draftStatus = MutableStateFlow("Загрузка черновиков…")
    val draftStatus = _draftStatus.asStateFlow()
    private val _editorSaving = MutableStateFlow(false)
    val editorSaving = _editorSaving.asStateFlow()
    private val workspaceReady = CompletableDeferred<Unit>()
    private val workspaceWrites = Channel<Unit>(Channel.CONFLATED)
    private val workspaceLock = Mutex()
    init {
        viewModelScope.launch {
            try {
                _workspace.value = workspaceStore.values.first()
                _draftStatus.value = "Черновик сохранён"
                workspaceReady.complete(Unit)
                val data = repository.snapshots.first()
                val id = _workspace.value.editor?.entry?.documentId ?: savedState.get<String>("document") ?: _workspace.value.history.current?.documentId
                data.documents.firstOrNull { it.id == id }?.let { document ->
                    val location = _workspace.value.history.current?.takeIf { it.documentId == id }
                    open(if (location != null) document.copy(lastPage = location.page, lastOffset = location.offset) else document,
                        navigate = _workspace.value.editor?.entry?.documentId != null, recordHistory = _workspace.value.history.current?.documentId != document.id)
                }
                if (_workspace.value.editor?.entry?.documentId == null && _workspace.value.editor != null) navigate("notes")
                for (ignored in workspaceWrites) {
                    try { persistWorkspace() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { _draftStatus.value = "Не удалось сохранить черновик"; message(R.string.operation_failed) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspaceReady.completeExceptionally(e); _draftStatus.value = "Не удалось загрузить черновики"; message(R.string.operation_failed) }
        }
    }
    private fun changeWorkspace(value: WorkspaceState) {
        _workspace.value = value
        _draftStatus.value = "Сохранение…"
        workspaceWrites.trySend(Unit)
    }
    private suspend fun persistWorkspace() = workspaceLock.withLock {
        val state = _workspace.value
        workspaceStore.save(state)
        if (_workspace.value == state) _draftStatus.value = "Черновик сохранён"
    }
    fun editEntry(entry: Entry) = perform {
        workspaceReady.await()
        if (_editorSaving.value) return@perform
        val data = repository.dao.snapshot()
        val draft = _workspace.value.drafts.firstOrNull { it.entry.id == entry.id } ?: EntryDraft(entry,
            data.entryTags.filter { it.entryId == entry.id }.mapNotNull { link -> data.tags.firstOrNull { it.id == link.tagId }?.name }.joinToString(", "),
            data.memberships.filter { it.entryId == entry.id }.map { it.collectionId }.toSet(),
            data.readerMemberships.filter { it.entryId == entry.id }.map { it.folderId }.toSet())
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id == entry.id } + draft, activeDraftId = entry.id))
    }
    fun newNote(documentId: String? = null) {
        if (documentId == null) navigate("notes")
        editEntry(Entry(UUID.randomUUID().toString(), "", documentId = documentId, color = preferences.value.defaultColor))
    }
    fun updateDraft(draft: EntryDraft) {
        if (_editorSaving.value || _workspace.value.activeDraftId != draft.entry.id) return
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.map { if (it.entry.id == draft.entry.id) draft.copy(modifiedAt = System.currentTimeMillis()) else it }))
    }
    fun closeEditor() { if (!_editorSaving.value) changeWorkspace(_workspace.value.copy(activeDraftId = null)) }
    fun resumeDraft(draft: EntryDraft) {
        if (_editorSaving.value) return
        changeWorkspace(_workspace.value.copy(activeDraftId = draft.entry.id))
        val document = library.value.documents.firstOrNull { it.id == draft.entry.documentId }
        if (document != null) open(document, draft.entry.anchor) else navigate("notes")
    }
    fun discardDraft(id: String) = perform {
        workspaceReady.await()
        if (_editorSaving.value && _workspace.value.activeDraftId == id) return@perform
        changeWorkspace(_workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id == id }, activeDraftId = _workspace.value.activeDraftId?.takeUnless { it == id }))
        persistWorkspace()
    }
    fun saveEditor() = perform {
        workspaceReady.await()
        if (_editorSaving.value) return@perform
        val draft = _workspace.value.editor ?: return@perform
        if (draft.entry.title.isBlank()) return@perform
        _editorSaving.value = true
        try {
            val data = repository.dao.snapshot()
            repository.saveEntry(draft.entry.copy(title = draft.entry.title.trim()), draft.tags,
                draft.collections.intersect(data.collections.map { it.id }.toSet()),
                draft.readerFolders.intersect(data.readerFolders.filter { it.documentId == draft.entry.documentId && it.kind == ReaderFolderKind.Quotes }.map { it.id }.toSet()))
            // Stable entry IDs make retries safe even if the disk write below fails after the Room transaction.
            workspaceLock.withLock {
                val state = _workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id == draft.entry.id }, activeDraftId = null)
                workspaceStore.save(state)
                // Reading may continue while the disk write is suspended. Keep its newest history.
                val latest = _workspace.value.copy(drafts = _workspace.value.drafts.filterNot { it.entry.id == draft.entry.id }, activeDraftId = null)
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
        open(document.copy(lastPage = location.page, lastOffset = location.offset), recordHistory = false,
            onOpened = { changeWorkspace(_workspace.value.copy(history = target)) })
    }
    fun trackPosition(documentId: String, page: Int, offset: Int) {
        if (_reader.value?.document?.id != documentId) return
        _reader.value?.takeIf { it.document.id == documentId }?.let { _reader.value = it.copy(page = page, offset = offset) }
        val history = _workspace.value.history.update(ReadingLocation(documentId, page, offset))
        if (history != _workspace.value.history) changeWorkspace(_workspace.value.copy(history = history))
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
    fun open(document: Document, anchor: Anchor? = null, page: Int? = null, navigate: Boolean = true, recordHistory: Boolean = true, onOpened: () -> Unit = {}) {
        val existing = _reader.value
        if (existing?.document?.id == document.id) {
            _reader.value = existing.copy(document = document, page = (page ?: anchor?.page ?: document.lastPage).coerceIn(0, existing.engine.pageCount - 1),
                offset = if (page == null && anchor == null) document.lastOffset else 0, anchor = anchor, token = UUID.randomUUID().toString())
            if (navigate) this.navigate("reader")
            rememberOpenDocument(document.id)
            if (recordHistory) recordLocation(_reader.value!!)
            onOpened()
            return
        }
        opening?.cancel()
        opening = perform(R.string.opening) {
            var newEngine: PdfEngine? = null
            try {
                withContext(Dispatchers.IO) { newEngine = PdfEngine(repository.pdfFile(document), context().cacheDir) }
                val engine = newEngine!!
                val old = _reader.value
                _reader.value = ReaderSession(document, engine, (page ?: anchor?.page ?: document.lastPage).coerceIn(0, engine.pageCount - 1), if (anchor == null && page == null) document.lastOffset else 0, anchor)
                newEngine = null
                old?.engine?.let { withContext(Dispatchers.IO) { it.close() } }
                savedState["document"] = document.id
                rememberOpenDocument(document.id)
                if (navigate) this@LibraryViewModel.navigate("reader")
                if (recordHistory) recordLocation(_reader.value!!)
                onOpened()
            } finally { newEngine?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } } }
        }
    }
    private fun rememberOpenDocument(id: String) {
        if (id !in openDocumentIds.value) savedState["open-documents"] = ArrayList(openDocumentIds.value + id)
    }
    fun closeDocument(id: String) {
        val ids = openDocumentIds.value
        val index = ids.indexOf(id)
        val remaining = ids.filterNot { it == id }
        savedState["open-documents"] = ArrayList(remaining)
        if (_reader.value?.document?.id != id) return
        val engine = _reader.value?.engine
        _reader.value = null
        savedState["document"] = null
        engine?.let { viewModelScope.launch(Dispatchers.IO) { it.close() } }
        val next = remaining.getOrNull(index.coerceAtLeast(0).coerceAtMost((remaining.size - 1).coerceAtLeast(0)))
        val document = library.value.documents.firstOrNull { it.id == next }
        if (document != null) open(document) else navigate("library")
    }
    fun source(entry: Entry) { library.value.documents.firstOrNull { it.id == entry.documentId }?.let { open(it, entry.anchor) } }
    fun position(document: Document, page: Int, offset: Int) = perform {
        repository.dao.savePosition(document.id, page, offset, System.currentTimeMillis())
    }
    fun highlight(text: String, anchor: Anchor, color: Marker): Job? {
        val doc = _reader.value?.document ?: return null
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
            open(document.copy(lastPage = value.page, lastOffset = value.offset))
        }
    }
    fun saveDocument(document: Document, tags: String) = perform { repository.saveDocument(document, tags) }
    fun folder(value: DocumentFolder) = perform { repository.saveFolder(value) }
    fun collection(value: Collection) = perform { repository.saveCollection(value) }
    fun deleteFolder(id: String, notes: Boolean) = perform { if (notes) repository.dao.deleteCollection(id) else repository.dao.deleteFolder(id) }
    fun deleteEntries(ids: Set<String>) = perform {
        workspaceReady.await()
        repository.dao.deleteEntries(ids.toList())
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
    fun restore(uri: Uri) = perform(R.string.restoring) {
        withContext(Dispatchers.IO) {
            val staged = context().contentResolver.openInputStream(uri)?.use { BackupArchive.stage(it, File(context().cacheDir, "restore-${UUID.randomUUID()}")) } ?: error("Cannot open backup")
            RestoreService(repository).restore(staged)
        }
        message(R.string.restore_done)
    }
    override fun onCleared() { opening?.cancel(); val engine = _reader.value?.engine; if (engine != null) CoroutineScope(Dispatchers.IO).launch { engine.close() }; super.onCleared() }
}
