package org.readerka

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.ui.LibraryViewModel
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceRecoveryTest {
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
