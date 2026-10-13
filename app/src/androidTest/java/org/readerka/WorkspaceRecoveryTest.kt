package org.readerka

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import androidx.room.withTransaction
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.ui.LibraryViewModel
import java.util.UUID
import java.io.File
import org.readerka.export.BackupArchive
import org.readerka.pdf.PdfEngine

@RunWith(AndroidJUnit4::class)
class WorkspaceRecoveryTest {
    @Test fun pendingPdfSelectionRecoversInFreshViewModelWithoutCreatingQuote() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val dao = app.database.dao(); val disk = WorkspaceStore(app)
        val previous = disk.values.first(); val previousSelections = disk.selections.first(); val previousViews = disk.views.first()
        val id = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "selection-$id").apply { mkdirs() }
        val staged = InstrumentationRegistry.getInstrumentation().context.assets.open("interop/to-android.zip").use { BackupArchive.stage(it, File(root, "source")) }
        val document = staged.data.documents.single().copy(id = id, storedName = "$id.pdf", folderId = null)
        val managed = File(app.filesDir, "pdfs/${document.storedName}").apply { parentFile!!.mkdirs() }
        File(staged.directory, "pdfs/${staged.data.documents.single().storedName}").copyTo(managed)
        val selection = PdfEngine(managed, app.cacheDir).use { engine -> val text = engine.text(1); text.selection(0, text.glyphs.lastIndex)!! }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var models = ViewModelStore()
        fun fresh(): LibraryViewModel {
            lateinit var value: LibraryViewModel
            instrumentation.runOnMainSync { value = LibraryViewModel(app, SavedStateHandle()); models.put("selection", value) }
            return value
        }
        try {
            disk.save(WorkspaceState(), emptyMap(), emptyMap()); dao.putDocument(document)
            var vm = fresh()
            withTimeout(30_000) { vm.library.first { it.documents.any { d -> d.id == id } } }
            instrumentation.runOnMainSync { vm.open(document, page = 1, pane = 0) }
            withTimeout(30_000) { vm.readers.first { it[0]?.document?.id == id } }
            val tab = vm.workspace.value.panes[0].selected!!
            instrumentation.runOnMainSync { vm.rememberPdfSelection(tab.id, selection) }
            val viewport = PdfViewState(2f, -.4f, -.2f)
            instrumentation.runOnMainSync { vm.rememberPdfView(tab.id, 1, viewport) }
            withTimeout(30_000) { disk.selections.first { it[tab.id] == selection } }
            withTimeout(30_000) { disk.views.first { it["${tab.id}:1"] == viewport } }
            instrumentation.runOnMainSync { models.clear() }; models = ViewModelStore(); vm = fresh()
            withTimeout(30_000) { vm.pdfSelections.first { it[tab.id] == selection } }
            withTimeout(30_000) { vm.readers.first { it[0]?.document?.id == id } }
            assertEquals(1, vm.readers.value[0]!!.page)
            assertEquals(selection, vm.pdfSelections.value[tab.id])
            assertEquals(viewport, vm.pdfViews.value["${tab.id}:1"])
            assertTrue(dao.allEntries().none { it.documentId == id })
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            disk.save(previous, previousSelections, previousViews); dao.deleteDocument(id); managed.delete(); root.deleteRecursively()
        }
    }
    @Test fun typingWhileManualSaveWaitsForDatabaseRetainsTheNewerDraft() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val disk = WorkspaceStore(app); val previous = disk.values.first()
        val id = UUID.randomUUID().toString(); val models = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var vm: LibraryViewModel
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            disk.save(WorkspaceState())
            instrumentation.runOnMainSync { vm = LibraryViewModel(app, SavedStateHandle()); models.put("save-race", vm); vm.editEntry(Entry(id, "Race", comment = "Before save")) }
            val draft = withTimeout(30_000) { vm.workspace.first { it.editor?.entry?.id == id }.editor!! }
            val holding = async(Dispatchers.IO) { app.database.withTransaction { started.complete(Unit); release.await() } }
            started.await()
            lateinit var saving: kotlinx.coroutines.Job
            instrumentation.runOnMainSync { saving = vm.saveEditor(id) }
            withTimeout(30_000) { vm.editorSaving.first { it } }
            val newer = draft.copy(entry = draft.entry.withContent(RichText.plain("Typed during save")))
            instrumentation.runOnMainSync { vm.updateDraft(newer) }
            release.complete(Unit); holding.await(); saving.join()
            assertEquals("Typed during save", vm.workspace.value.drafts.single { it.entry.id == id }.entry.content!!.text)
            assertEquals("Typed during save", disk.values.first().drafts.single { it.entry.id == id }.entry.content!!.text)
            instrumentation.runOnMainSync { saving = vm.saveEditor(id) }; saving.join()
            assertEquals("Typed during save", app.database.dao().allEntries().single { it.id == id }.content!!.text)
            assertTrue(vm.workspace.value.drafts.none { it.entry.id == id })
        } finally { release.complete(Unit); instrumentation.runOnMainSync { models.clear() }; disk.save(previous); app.database.dao().deleteEntries(listOf(id)) }
    }
    @Test fun durableDraftRecoversInFreshViewModelAndSavesOneCanonicalEntryWithBothFolders() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val dao = app.database.dao()
        val disk = WorkspaceStore(app)
        val previous = disk.values.first()
        val id = UUID.randomUUID().toString()
        val folders = listOf(org.readerka.data.Collection("a-$id", "A"), org.readerka.data.Collection("b-$id", "B"))
        val original = Entry(id, "Original", comment = "Published")
        var models = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun fresh(): LibraryViewModel {
            lateinit var vm: LibraryViewModel
            main { vm = LibraryViewModel(app, SavedStateHandle()); models.put("workspace", vm) }
            return vm
        }
        try {
            disk.save(WorkspaceState())
            folders.forEach { dao.putCollection(it) }
            dao.putEntry(original)
            var vm = fresh()
            main { vm.editEntry(original) }
            val editor = withTimeout(30_000) { vm.workspace.first { it.editor?.entry?.id == id }.editor!! }
            val draft = editor.copy(entry = original.copy(title = "Recovered", comment = "Draft\n**Markdown**", color = Marker.Blue), tags = "recovery-$id, second-$id", collections = folders.map { it.id }.toSet())
            main { vm.updateDraft(draft) }
            withTimeout(30_000) { disk.values.first { it.editor?.entry?.comment == draft.entry.comment && it.editor?.collections == draft.collections } }
            assertEquals(original, dao.allEntries().single { it.id == id })
            main { models.clear() }; models = ViewModelStore()
            vm = fresh()
            val recovered = withTimeout(30_000) { vm.workspace.first { it.editor?.entry?.id == id }.editor!! }
            assertEquals(draft.entry, recovered.entry)
            assertEquals(draft.collections, recovered.collections)
            assertEquals(draft.tags, recovered.tags)
            main { vm.closeEditor() }
            withTimeout(30_000) { disk.values.first { it.activeDraftId == null && it.drafts.any { d -> d.entry.id == id } } }
            main { models.clear() }; models = ViewModelStore()
            vm = fresh()
            val closed = withTimeout(30_000) { vm.workspace.first { it.drafts.any { d -> d.entry.id == id } } }
            assertNull(closed.editor)
            main { vm.resumeDraft(closed.drafts.single { it.entry.id == id }) }
            lateinit var saving: kotlinx.coroutines.Job
            main { saving = vm.saveEditor() }; saving.join()
            assertEquals(1, dao.allEntries().count { it.id == id })
            assertEquals(draft.entry.comment, dao.allEntries().single { it.id == id }.comment)
            assertEquals(draft.collections, dao.allMemberships().filter { it.entryId == id }.map { it.collectionId }.toSet())
            assertEquals(2, dao.allEntryTags().count { it.entryId == id })
            assertTrue(disk.values.first().drafts.none { it.entry.id == id })
            val canonical = dao.allEntries().single { it.id == id }
            main { vm.editEntry(canonical) }
            withTimeout(30_000) { vm.workspace.first { it.editor?.entry?.id == id } }
            lateinit var discard: kotlinx.coroutines.Job
            main { discard = vm.discardDraft(id) }; discard.join()
            assertEquals(draft.entry.comment, dao.allEntries().single { it.id == id }.comment)
            assertTrue(disk.values.first().drafts.none { it.entry.id == id })
        } finally {
            main { models.clear() }
            disk.save(previous)
            dao.deleteEntries(listOf(id))
            folders.forEach { dao.deleteCollection(it.id) }
            dao.allTags().filter { it.name.endsWith(id) }.forEach { dao.deleteTag(it.id) }
        }
    }
}
