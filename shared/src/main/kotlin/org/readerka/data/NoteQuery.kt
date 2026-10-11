package org.readerka.data

enum class NoteSort { Newest, Oldest, Source, Author, Page, Type }
data class NoteQuery(val text: String = "", val collectionId: String? = null, val documentId: String? = null,
    val type: NoteType? = null, val color: Marker? = null, val tagId: String? = null, val createdAfter: Long? = null,
    val sort: NoteSort = NoteSort.Newest) {
    fun apply(data: LibrarySnapshot): List<Entry> {
        val docs = data.documents.associateBy { it.id }
        val tags = data.tags.associateBy { it.id }
        val entryTags = data.entryTags.groupBy { it.entryId }
        val members = collectionId?.let { id ->
            val folders = FolderTree.descendants(id, data.collections.map { it.id to it.parentId })
            data.memberships.filter { it.collectionId in folders }.map { it.entryId }.toSet()
        }
        val filtered = data.entries.filter { e ->
            val d = docs[e.documentId]
            val assigned = entryTags[e.id].orEmpty()
            (members == null || e.id in members) && (documentId == null || documentId in e.sourceBooks()) &&
                (type == null || e.type == type) && (color == null || e.color == color) &&
                (tagId == null || assigned.any { it.tagId == tagId }) && (createdAfter == null || e.createdAt >= createdAfter) &&
                (text.isBlank() || listOf(e.title, e.quote, e.content?.text ?: e.comment, d?.title.orEmpty(), d?.authors.orEmpty(), data.documents.filter { it.id in e.sourceBooks() }.joinToString(" ") { it.title + " " + it.authors }, assigned.mapNotNull { tags[it.tagId]?.name }.joinToString(" ")).any { it.contains(text.trim(), ignoreCase = true) })
        }
        return when (sort) {
            NoteSort.Newest -> filtered.sortedByDescending { it.createdAt }
            NoteSort.Oldest -> filtered.sortedBy { it.createdAt }
            NoteSort.Source -> filtered.sortedBy { docs[it.documentId]?.title ?: it.title }
            NoteSort.Author -> filtered.sortedBy { docs[it.documentId]?.authors.orEmpty() }
            NoteSort.Page -> filtered.sortedWith(compareBy({ docs[it.documentId]?.title.orEmpty() }, { it.anchor?.page ?: -1 }))
            NoteSort.Type -> filtered.sortedBy { it.type.ordinal }
        }
    }
}
