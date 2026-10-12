package org.readerka.data

import java.util.Locale

enum class GraphKind(val label: String) {
    Book("Книги"), Author("Авторы"), Topic("Темы / теги"), Quote("Цитаты"),
    Note("Заметки / тезисы"), Consp("Конспекты"), Folder("Папки"),
    Bookmark("Закладки"), Field("Поля / группы")
}

data class GraphNode(val id: String, val title: String, val kind: GraphKind, val sourceId: String = "", val detail: String = "")
data class GraphEdge(val from: String, val to: String, val relation: String, val context: String = "")

/** A projection of saved facts. Shared metadata is represented by shared nodes, never by text similarity. */
data class LibraryGraph(val nodes: List<GraphNode>, val edges: List<GraphEdge>) {
    val byId = nodes.associateBy { it.id }
    val incident: Map<String, List<GraphEdge>> = mutableMapOf<String, MutableList<GraphEdge>>().apply {
        edges.forEach { edge ->
            getOrPut(edge.from) { mutableListOf() }.add(edge)
            getOrPut(edge.to) { mutableListOf() }.add(edge)
        }
    }

    fun visible(kinds: Set<GraphKind>, query: String = "", focus: String? = null, depth: Int = 1): LibraryGraph {
        val allowed = nodes.filter { it.kind in kinds }.map { it.id }.toSet()
        fun expand(seeds: Set<String>, levels: Int): Set<String> {
            val found = seeds.toMutableSet()
            var frontier = seeds
            repeat(levels) {
                frontier = frontier.flatMap { id -> incident[id].orEmpty().flatMap { listOf(it.from, it.to) } }
                    .filter { it in allowed && it !in found }.toSet()
                found.addAll(frontier)
                if (frontier.isEmpty()) return found
            }
            return found
        }
        var ids = allowed
        if (query.isNotBlank()) {
            val words = query.trim().split(Regex("\\s+"))
            val hits = nodes.filter { n -> n.id in allowed && words.all { word -> (n.title + " " + n.detail).contains(word, true) } }.map { it.id }.toSet()
            ids = expand(hits, 1)
        }
        if (focus != null) ids = ids intersect expand(setOf(focus).intersect(allowed), depth)
        return LibraryGraph(nodes.filter { it.id in ids }, edges.filter { it.from in ids && it.to in ids })
    }

    companion object {
        private fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

        fun build(data: LibrarySnapshot): LibraryGraph {
            val nodes = linkedMapOf<String, GraphNode>()
            val edges = linkedSetOf<GraphEdge>()
            fun node(id: String, title: String, kind: GraphKind, source: String = "", detail: String = "") {
                nodes.putIfAbsent(id, GraphNode(id, title, kind, source, detail))
            }
            fun edge(from: String, to: String?, relation: String, context: String = "") {
                if (to != null && from != to && from in nodes && to in nodes) edges += GraphEdge(from, to, relation, context)
            }
            data.documents.forEach { d ->
                node("book:${d.id}", d.title, GraphKind.Book, d.id, listOf(d.authors, d.year, d.description).filter { it.isNotBlank() }.joinToString(" · "))
                // Commas may be part of an inverted name (Kant, Immanuel). Only explicit separators split authors.
                d.authors.split(Regex("[;\\n|]+")).map { it.trim() }.filter { it.isNotBlank() }.forEach { author ->
                    val id = "author:${normalized(author)}"
                    node(id, author, GraphKind.Author)
                    edge(id, "book:${d.id}", "автор")
                }
            }
            data.entries.forEach { e ->
                val kind = when (e.kind) { EntryKind.Quote -> GraphKind.Quote; EntryKind.Consp -> GraphKind.Consp; else -> GraphKind.Note }
                node("entry:${e.id}", e.title, kind, e.id, listOf(e.quote, e.content?.text ?: e.comment).filter { it.isNotBlank() }.joinToString("\n"))
                e.sourceBooks().forEach { edge("entry:${e.id}", "book:$it", "источник") }
            }
            data.tags.filter { it.name.isNotBlank() }.forEach { node("topic:${normalized(it.name)}", it.name.trim(), GraphKind.Topic) }
            val tags = data.tags.associate { it.id to "topic:${normalized(it.name)}" }
            data.entryTags.forEach { edge("entry:${it.entryId}", tags[it.tagId], "тема") }
            data.documentTags.forEach { edge("book:${it.documentId}", tags[it.tagId], "тема") }
            data.collections.forEach { node("collection:${it.id}", it.name, GraphKind.Folder, it.id, if (it.scope == CollectionScope.Consp) "Папка конспектов" else "Папка записей") }
            data.documentFolders.forEach { node("folder:${it.id}", it.name, GraphKind.Folder, it.id, "Папка книг") }
            data.readerFolders.forEach { node("reader:${it.id}", it.name, GraphKind.Folder, it.id, "Папка материалов PDF") }
            data.collections.forEach { edge("collection:${it.id}", it.parentId?.let { p -> "collection:$p" }, "вложена в") }
            data.documentFolders.forEach { edge("folder:${it.id}", it.parentId?.let { p -> "folder:$p" }, "вложена в") }
            data.documents.forEach { edge("book:${it.id}", it.folderId?.let { f -> "folder:$f" }, "в папке") }
            data.memberships.forEach { edge("entry:${it.entryId}", "collection:${it.collectionId}", "в папке") }
            data.readerFolders.forEach { f ->
                edge("reader:${f.id}", "book:${f.documentId}", "материалы книги")
                edge("reader:${f.id}", f.parentId?.let { "reader:$it" }, "вложена в")
            }
            data.readerMemberships.forEach { edge("entry:${it.entryId}", "reader:${it.folderId}", "в папке PDF") }
            data.bookmarks.forEach { node("bookmark:${it.id}", it.title, GraphKind.Bookmark, it.id, "Страница ${it.page + 1}\n${it.comment}") }
            data.bookmarks.forEach { b ->
                edge("bookmark:${b.id}", "book:${b.documentId}", "закладка книги")
                edge("bookmark:${b.id}", b.folderId?.let { "reader:$it" }, "в папке PDF")
                edge("bookmark:${b.id}", b.parentId?.let { "bookmark:$it" }, "вложена в")
            }
            data.researchFields.forEach { f ->
                node("field:${f.id}", f.title, GraphKind.Field, f.id, "Исследовательское поле")
                f.groups.forEach { group ->
                    node("group:${f.id}:${group.id}", group.title, GraphKind.Field, f.id, "Группа поля «${f.title}»")
                    edge("group:${f.id}:${group.id}", "field:${f.id}", "группа поля")
                }
                val cards = f.placements.associateBy { it.id }
                f.placements.forEach { p ->
                    edge("entry:${p.entryId}", "field:${f.id}", "на поле")
                    edge("entry:${p.entryId}", p.groupId?.let { "group:${f.id}:$it" }, "в группе")
                }
                f.edges.forEach { e ->
                    edge("entry:${cards[e.from]?.entryId}", "entry:${cards[e.to]?.entryId}", e.type.label,
                        f.title + if (e.label.isNotBlank()) " · ${e.label}" else "")
                }
                f.sequence.zipWithNext().forEach { (a, b) -> edge("entry:${cards[a]?.entryId}", "entry:${cards[b]?.entryId}", "следует перед", f.title) }
            }
            data.entries.forEach { e ->
                ResearchLinks.targets(e).forEach { url ->
                    val path = url.substringBefore('?').substringBefore('#')
                    val target = when {
                        path.startsWith("readerka://entry/") -> "entry:" + path.removePrefix("readerka://entry/")
                        path.startsWith("readerka://document/") -> "book:" + path.removePrefix("readerka://document/")
                        else -> null
                    }
                    edge("entry:${e.id}", target, "ссылка")
                    if (path.startsWith("readerka://document/")) {
                        url.substringAfter('?', "").substringBefore('#').split('&').firstOrNull { it.startsWith("entry=") }
                            ?.removePrefix("entry=")?.let { edge("entry:${e.id}", "entry:$it", "ссылка на цитату") }
                    }
                }
            }
            return LibraryGraph(nodes.values.toList(), edges.toList())
        }
    }
}
