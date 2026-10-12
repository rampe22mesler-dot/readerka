package org.readerka.desktop

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.readerka.data.*
import org.readerka.data.Collection
import org.readerka.export.*

/** Synthetic fixture only. No user library is ever packaged. */
object InteropFixture {
    private fun stableBlocks(id: String, value: RichText) =
        value.copy(
            blocks =
                value.blocks.mapIndexed { index, block ->
                    block.copy(
                        id = java.util.UUID.nameUUIDFromBytes("$id:$index".toByteArray()).toString()
                    )
                }
        )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "desktop/build/interop").apply { mkdirs() }
        if (args.getOrNull(1) == "verify") {
            val staged =
                File(args[0]).inputStream().use {
                    BackupArchive.stage(
                        it,
                        File(System.getProperty("java.io.tmpdir"), "interop-verify-${newId()}"),
                    )
                }
            try {
                val e = staged.data.entries.single { it.id == "interop-consp" }
                require(e.title == "Изменено на Android")
                require(e.richText().marks.any { it.style == org.readerka.data.TextStyle.Bold })
                require(ResearchLinks.targets(e).any { it.contains("interop-note") })
                val expected =
                    Json.decodeFromString<LibrarySnapshot>(
                        File("desktop/build/interop/expected.json").takeIf { it.isFile }?.readText()
                            ?: File("build/interop/expected.json").readText()
                    )
                require(staged.data.documents.toSet() == expected.documents.toSet())
                require(staged.data.collections.toSet() == expected.collections.toSet())
                require(staged.data.documentFolders.toSet() == expected.documentFolders.toSet())
                require(staged.data.memberships.toSet() == expected.memberships.toSet())
                require(staged.data.bookmarks.toSet() == expected.bookmarks.toSet())
                require(staged.data.readerFolders.toSet() == expected.readerFolders.toSet())
                require(staged.data.readerMemberships.toSet() == expected.readerMemberships.toSet())
                require(expected.noteVersions.all { it in staged.data.noteVersions })
                require(e.richText() == expected.entries.single { it.id == e.id }.richText())
                require(
                    staged.data.entries.single { it.kind == EntryKind.Quote } ==
                        expected.entries.single { it.kind == EntryKind.Quote }
                )
                println(
                    "Android -> desktop: identities, rich text, links, versions, folder scopes and PDF checksums verified"
                )
            } finally {
                staged.directory.deleteRecursively()
            }
            return
        }
        val pdf = File(root, "interop-book.pdf")
        PDDocument().use { d ->
            (0..3).forEach { rotation ->
                val page = PDPage(PDRectangle.A4)
                page.rotation = rotation * 90
                d.addPage(page)
                PDPageContentStream(d, page).use { s ->
                    s.beginText()
                    s.setFont(PDType1Font.TIMES_ROMAN, 14f)
                    s.newLineAtOffset(40f, 700f)
                    s.showText("Existence is not a real predicate.")
                    s.newLineAtOffset(0f, -30f)
                    s.showText("Second line with text for research.")
                    s.endText()
                }
            }
            d.save(pdf)
        }
        val texts = DesktopPdfEngine(pdf, root).use { engine -> (0..3).map { engine.text(it) } }
        val source = texts[0].selection(0, texts[0].glyphs.size - 1)!!
        val book =
            Document(
                "interop-book",
                "Тестовая книга",
                "book.pdf",
                pdf.name,
                folderId = "interop-pdf-folder",
                authors = "Автор",
                year = "2026",
                pageCount = 4,
                createdAt = 1000,
            )
        val quote =
            Entry(
                "interop-quote",
                "Цитата из PDF",
                quote = source.first,
                documentId = book.id,
                anchor = source.second,
                kind = EntryKind.Quote,
                type = NoteType.Quote,
                createdAt = 1000,
            )
        val note =
            Entry("interop-note", "Заметка по теме", createdAt = 1000)
                .withContent(stableBlocks("interop-note", RichText.plain("Текст заметки")))
        val body =
            RichText.fromMarkdown("# Основная идея\n\nЖирный курсив подчёркнутый\n\nИсточник")
        var rich =
            body
                .style(
                    body.text.indexOf("Жирный"),
                    body.text.indexOf("Жирный") + 6,
                    org.readerka.data.TextStyle.Bold,
                )
                .style(
                    body.text.indexOf("курсив"),
                    body.text.indexOf("курсив") + 6,
                    org.readerka.data.TextStyle.Italic,
                )
                .style(
                    body.text.indexOf("подчёркнутый"),
                    body.text.indexOf("подчёркнутый") + 11,
                    org.readerka.data.TextStyle.Underline,
                )
        rich =
            ResearchInsertion.insert(
                rich,
                rich.text.length,
                rich.text.length,
                note,
                InsertionMode.LinkCard,
            )
        rich =
            ResearchInsertion.insert(
                rich,
                rich.text.length,
                rich.text.length,
                quote,
                InsertionMode.Content,
            )
        val consp =
            Entry(
                    "interop-consp",
                    "Конспект по книге",
                    kind = EntryKind.Consp,
                    sources = listOf(book.id),
                    createdAt = 1000,
                )
                .withContent(stableBlocks("interop-consp", rich))
        val draft =
            EntryDraft(
                consp,
                "философия",
                setOf("interop-consp-child"),
                modifiedAt = 1000,
                cursorStart = 3,
                cursorEnd = 5,
                focusedField = "body",
            )
        val snapshot =
            LibrarySnapshot(
                documents = listOf(book),
                documentFolders = listOf(DocumentFolder("interop-pdf-folder", "Книги")),
                entries = listOf(quote, note, consp),
                collections =
                    listOf(
                        Collection("interop-notes", "Материалы"),
                        Collection(
                            "interop-consp-folder",
                            "Конспекты",
                            scope = CollectionScope.Consp,
                        ),
                        Collection(
                            "interop-consp-child",
                            "Тема",
                            "interop-consp-folder",
                            CollectionScope.Consp,
                        ),
                    ),
                memberships =
                    listOf(
                        Membership("interop-consp-child", consp.id),
                        Membership("interop-notes", note.id),
                    ),
                tags = listOf(Tag("interop-tag", "философия")),
                entryTags = listOf(EntryTag(consp.id, "interop-tag")),
                documentTags = listOf(DocumentTag(book.id, "interop-tag")),
                readerFolders =
                    listOf(
                        ReaderFolder("interop-quotes", book.id, ReaderFolderKind.Quotes, "Цитаты"),
                        ReaderFolder(
                            "interop-bookmarks",
                            book.id,
                            ReaderFolderKind.Bookmarks,
                            "Главы",
                        ),
                    ),
                readerMemberships = listOf(ReaderMembership("interop-quotes", quote.id)),
                bookmarks =
                    listOf(
                        Bookmark(
                            "interop-bookmark",
                            book.id,
                            0,
                            "Глава",
                            folderId = "interop-bookmarks",
                            offset = 2300,
                        ),
                        Bookmark(
                            "interop-bookmark-child",
                            book.id,
                            1,
                            "Раздел",
                            parentId = "interop-bookmark",
                            folderId = "interop-bookmarks",
                            offset = 1000,
                        ),
                    ),
                noteVersions =
                    listOf(
                        NoteVersion(
                            "interop-version",
                            consp.id,
                            Json.encodeToString(draft),
                            1000,
                            "Версия на ПК",
                        )
                    ),
            )
        File(root, "to-android.zip").outputStream().use {
            BackupArchive.write(it, snapshot) { pdf }
        }
        File(root, "expected.json").writeText(Json.encodeToString(snapshot))
        File(root, "page-texts.json").writeText(Json.encodeToString(texts.map { it.text }))
        println("Created desktop ZIP4 transfer fixture: ${root.path}")
    }
}
