package org.readerka.data

/** An occurrence carries its source folder; identities always refer to the canonical object. */
data class ExplorerItem(val category: Int, val kind: String, val id: String, val parent: String? = null)

object ExplorerOperations {
    fun move(data: LibrarySnapshot, items: List<ExplorerItem>, category: Int, destination: String?, add: Boolean = false): LibrarySnapshot {
        require(items.isNotEmpty() && items.all { it.category == category }) { "Выберите объекты одного раздела" }
        val folders = if (category == 0) data.documentFolders.map { it.id to it.parentId }
            else data.collections.filter { it.scope == if (category == 2) CollectionScope.Consp else CollectionScope.Notes }.map { it.id to it.parentId }
        require(destination == null || folders.any { it.first == destination }) { "Папка назначения не существует" }
        require(!add || destination != null && items.all { it.kind == "entry" }) { "Дополнительное назначение доступно для заметок и конспектов" }
        val selectedFolders = items.filter { it.kind == "folder" }.map { it.id }.toSet()
        for (id in selectedFolders) require(destination !in FolderTree.descendants(id, folders)) { "Нельзя переносить папку в себя или её подпапку" }
        // Moving an ancestor already carries its descendants. Do not flatten the selected subtree.
        val effective = items.distinct().filter { item ->
            FolderTree.ancestors(setOf(if (item.kind == "folder") item.id else item.parent ?: ""), folders)
                .none { it in selectedFolders } && (item.kind == "folder" || item.parent !in selectedFolders)
        }
        var next = data
        effective.forEach { item ->
            next = when (item.kind) {
                "folder" -> if (category == 0) next.copy(documentFolders = next.documentFolders.map { if (it.id == item.id) it.copy(parentId = destination) else it })
                    else next.copy(collections = next.collections.map { if (it.id == item.id) it.copy(parentId = destination) else it })
                "pdf" -> next.copy(documents = next.documents.map { if (it.id == item.id) it.copy(folderId = destination) else it })
                "entry" -> next.copy(memberships = (next.memberships.filterNot { !add && it.entryId == item.id && it.collectionId == item.parent } +
                    listOfNotNull(destination?.let { Membership(it, item.id) })).distinct())
                else -> error("Неизвестный объект")
            }
        }
        FolderTree.validate(next.documentFolders.map { it.id to it.parentId })
        FolderTree.validate(next.collections.map { it.id to it.parentId })
        return next
    }

    /** Removing a container lifts direct children to its parent, preserving other memberships. */
    fun deleteFolder(data: LibrarySnapshot, category: Int, id: String): LibrarySnapshot {
        if (category == 0) {
            val folder = data.documentFolders.firstOrNull { it.id == id } ?: return data
            return data.copy(documentFolders = data.documentFolders.filterNot { it.id == id }.map { if (it.parentId == id) it.copy(parentId = folder.parentId) else it },
                documents = data.documents.map { if (it.folderId == id) it.copy(folderId = folder.parentId) else it })
        }
        val folder = data.collections.firstOrNull { it.id == id } ?: return data
        val lifted = data.memberships.filter { it.collectionId == id }.mapNotNull { m -> folder.parentId?.let { Membership(it, m.entryId) } }
        return data.copy(collections = data.collections.filterNot { it.id == id }.map { if (it.parentId == id) it.copy(parentId = folder.parentId) else it },
            memberships = (data.memberships.filterNot { it.collectionId == id } + lifted).distinct())
    }
}
