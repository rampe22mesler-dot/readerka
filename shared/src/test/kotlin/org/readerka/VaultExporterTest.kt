package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.VaultExporter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class VaultExporterTest {
    private val sample by lazy { fixture() }
    private fun fixture() = LibrarySnapshot(
        documents = listOf(Document("d1", "Кант", "Кант [1781].pdf", "d1.pdf", "f2"), Document("d2", "Кант", "Кант [1781].pdf", "d2.pdf", "f2")),
        documentFolders = listOf(DocumentFolder("f1", "Моя библиотека"), DocumentFolder("f2", "Книги", "f1"), DocumentFolder("empty", "Пустая")),
        entries = listOf(Entry("e1", "Existence", "One\nTwo", "Мой комментарий", "d1", Anchor(213, 0, 7, listOf(PageRect(.1f, .2f, .3f, .4f))), NoteType.Objection, Marker.Green), Entry("e2", "Идея", comment = "Своя мысль")),
        collections = listOf(Collection("c1", "Онтология"), Collection("c2", "Существование", "c1"), Collection("c3", "Другая папка")),
        memberships = listOf(Membership("c2", "e1"), Membership("c3", "e1")),
        tags = listOf(Tag("t1", "Кант: \"существование\"")), entryTags = listOf(EntryTag("e1", "t1")),
        bookmarks = listOf(Bookmark("b1", "d1", 213, "Аргумент", "Важно"))
    )
    private fun export(include: Boolean, missing: Boolean = false): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        VaultExporter().write(out, sample, include, { if (missing) null else "%PDF-test".byteInputStream() })
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; assertFalse(files.containsKey(entry.name)); files[entry.name] = zip.readBytes() }
        }
        return files
    }
    @Test fun vaultHasCanonicalNotesCollectionEmbedsHierarchyAndActualPdfPages() {
        val data = export(true)
        assertEquals(2, data.keys.count { it.endsWith(".pdf") })
        assertTrue(data.keys.any { it.endsWith("Библиотека/Пустая — empty/") })
        val quote = data.entries.single { it.key.contains("Заметки/Цитаты/") && it.key.endsWith(".md") }.value.toString(Charsets.UTF_8)
        assertTrue(quote.contains("source_page: 214")); assertTrue(quote.contains("#page=214"))
        assertTrue(quote.contains("source_pdf: \"Кант [1781].pdf\""))
        assertTrue(quote.contains("## Цитата\n> One\n> Two\n\n## Мой комментарий\nМой комментарий"))
        assertTrue(quote.contains("color: \"green\"")); assertTrue(quote.contains("type: \"Objection\""))
        val shortcuts = data.filterKeys { it.endsWith("ссылка.md") }
        assertEquals(2, shortcuts.size)
        assertEquals(1, shortcuts.values.map { it.toString(Charsets.UTF_8) }.distinct().size)
        shortcuts.values.forEach { assertTrue(it.toString(Charsets.UTF_8).contains("![[Заметки/Цитаты/Existence — e1]]")) }
        val note = data.entries.single { it.key.contains("Самостоятельные/") && it.key.endsWith(".md") }.value.toString(Charsets.UTF_8)
        assertFalse(note.contains("source_page")); assertFalse(note.contains("source_pdf"))
    }
    @Test fun excludedOrMissingPdfNeverProducesDeadPdfLinks() {
        for ((include, missing) in listOf(false to false, true to true)) {
            val data = export(include, missing)
            assertFalse(data.keys.any { it.endsWith(".pdf") })
            data.values.forEach { assertFalse(it.toString(Charsets.UTF_8).contains(".pdf#page=")) }
            val quote = data.entries.single { it.key.contains("Заметки/Цитаты/") && it.key.endsWith(".md") }.value.toString(Charsets.UTF_8)
            assertTrue(quote.contains("source_page: 214")); assertTrue(quote.contains("source_pdf:"))
        }
    }
    @Test fun exportIsStableAndLeavesSnapshotUntouched() {
        val a = export(true); val b = export(true)
        assertEquals(a.keys, b.keys)
        for (key in a.keys) assertArrayEquals(a[key], b[key])
        assertFalse(VaultExporter.safeName("../a[#]|b\\c\n").contains('/'))
    }
}
