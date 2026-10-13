package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.BackupArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class CollectionStructureTest {
    private val legacy = LibrarySnapshot(version = 3,
        collections = listOf(Collection("root", "Topic"), Collection("child", "Chapter", "root"), Collection("empty", "Empty")),
        entries = listOf(Entry("note", "Note"), Entry("quote", "Quote", kind = EntryKind.Quote), Entry("consp", "Outline", kind = EntryKind.Consp), Entry("loose", "Loose")),
        memberships = listOf(Membership("child", "note"), Membership("root", "quote"), Membership("child", "consp")))

    @Test fun legacyFoldersBecomeIndependentTreesWithoutDuplicatingEntries() {
        val separated = legacy.separateLegacyCollections()
        assertEquals(legacy.entries, separated.entries)
        assertEquals(3, separated.collectionsFor(EntryKind.Consp).size)
        assertEquals(3, separated.collectionsFor(EntryKind.Note).size)
        assertEquals(legacyConspFolderId("root"), separated.collections.first { it.id == legacyConspFolderId("child") }.parentId)
        assertEquals(setOf(legacyConspFolderId("child")), separated.memberships.filter { it.entryId == "consp" }.map { it.collectionId }.toSet())
        assertEquals(setOf("child"), separated.memberships.filter { it.entryId == "note" }.map { it.collectionId }.toSet())
        assertEquals(separated, separated.separateLegacyCollections())
        BackupArchive.validate(separated)
    }
    @Test fun directContentsSearchAndExplicitFlatViewHaveDifferentScopes() {
        val s = legacy.separateLegacyCollections()
        fun ids(folder: String?, flat: Boolean = false, text: String = "") = catalogueEntries(s, EntryKind.Note, folder, flat, NoteQuery(text)).map { it.id }.toSet()
        assertEquals(setOf("loose"), ids(null))
        assertEquals(setOf("quote"), ids("root"))
        assertEquals(setOf("note"), ids("child"))
        assertEquals(setOf("note"), ids("root", text = "Note"))
        assertEquals(setOf("note", "quote", "loose"), ids(null, flat = true))
        assertTrue(catalogueEntries(s, EntryKind.Consp, null, false, NoteQuery()).isEmpty())
        assertEquals(listOf("consp"), catalogueEntries(s, EntryKind.Consp, legacyConspFolderId("child"), false, NoteQuery()).map { it.id })
    }
    @Test fun olderZipSeparatesFoldersAndNewZipPreservesBothTrees() {
        val temp = Files.createTempDirectory("collection-backup").toFile()
        try {
            val oldBytes = ByteArrayOutputStream().also { BackupArchive.write(it, legacy) { error("No PDFs") } }.toByteArray()
            val separated = BackupArchive.stage(oldBytes.inputStream(), File(temp, "old")).data
            assertEquals(legacy.migrateResearchFields(), separated)
            assertEquals(5, separated.version)
            val newBytes = ByteArrayOutputStream().also { BackupArchive.write(it, separated) { error("No PDFs") } }.toByteArray()
            assertEquals(separated, BackupArchive.stage(newBytes.inputStream(), File(temp, "new")).data)
        } finally { temp.deleteRecursively() }
    }
    @Test fun backupsRejectCrossCatalogueParentsAndMemberships() {
        val s = legacy.separateLegacyCollections()
        assertThrows(Exception::class.java) { BackupArchive.validate(s.copy(memberships = s.memberships + Membership("root", "consp"))) }
        assertThrows(Exception::class.java) { BackupArchive.validate(s.copy(collections = s.collections.map { if (it.id == legacyConspFolderId("child")) it.copy(parentId = "root") else it })) }
    }
    @Test fun legacyDraftsMapOnlyToAvailableOutlineFolders() {
        val s = legacy.separateLegacyCollections()
        assertEquals(setOf(legacyConspFolderId("child")), scopedCollectionIds(setOf("child", "deleted"), EntryKind.Consp, s.collections))
        assertEquals(setOf("child"), scopedCollectionIds(setOf("child", legacyConspFolderId("root")), EntryKind.Note, s.collections))
    }
}
