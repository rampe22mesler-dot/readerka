package org.readerka.data

/** The document is the boundary of every folder, bookmark tree and quote membership. */
object ReaderStructure {
    fun validate(folders: List<ReaderFolder>, memberships: List<ReaderMembership>, bookmarks: List<Bookmark>, entries: List<Entry>, documents: Set<String>) {
        val byFolder = folders.associateBy { it.id }
        val byBookmark = bookmarks.associateBy { it.id }
        val byEntry = entries.associateBy { it.id }
        require(byFolder.size == folders.size && byBookmark.size == bookmarks.size)
        FolderTree.validate(folders.map { it.id to it.parentId })
        FolderTree.validate(bookmarks.map { it.id to it.parentId })
        for (f in folders) {
            require(f.documentId in documents && f.name.isNotBlank())
            f.parentId?.let { p -> require(byFolder.getValue(p).let { it.documentId == f.documentId && it.kind == f.kind }) }
        }
        for (b in bookmarks) {
            require(b.documentId in documents && b.offset in 0..10_000 && b.title.isNotBlank())
            b.parentId?.let { p -> require(byBookmark.getValue(p).documentId == b.documentId) }
            b.folderId?.let { id -> require(byFolder[id]?.let { it.documentId == b.documentId && it.kind == ReaderFolderKind.Bookmarks } == true) }
        }
        require(memberships.distinct().size == memberships.size)
        for (m in memberships) {
            val f = byFolder[m.folderId] ?: error("Missing reader folder")
            val e = byEntry[m.entryId] ?: error("Missing quotation")
            require(f.kind == ReaderFolderKind.Quotes && f.documentId == e.documentId)
        }
    }
}
