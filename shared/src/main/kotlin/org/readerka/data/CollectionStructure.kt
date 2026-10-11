package org.readerka.data

import java.util.UUID

fun EntryKind.collectionScope() = if (this == EntryKind.Consp) CollectionScope.Consp else CollectionScope.Notes
fun LibrarySnapshot.collectionsFor(kind: EntryKind) = collections.filter { it.scope == kind.collectionScope() }

/** Stable mapping shared by Room, old ZIP imports, drafts and historical versions. */
fun legacyConspFolderId(id: String) = "consp-legacy-" + UUID.nameUUIDFromBytes("readerka/consp/$id".toByteArray(Charsets.UTF_8))

fun scopedCollectionIds(ids: Set<String>, kind: EntryKind, folders: List<Collection>): Set<String> {
    val byId = folders.associateBy { it.id }
    return ids.mapNotNull { id ->
        if (byId[id]?.scope == kind.collectionScope()) id
        else if (kind == EntryKind.Consp) legacyConspFolderId(id).takeIf { byId[it]?.scope == CollectionScope.Consp }
        else null
    }.toSet()
}

/** Keep the old tree for notes/quotes and give outlines an independent copy, including empty folders. */
fun LibrarySnapshot.separateLegacyCollections(): LibrarySnapshot {
    if (version >= 4) return this
    val outlines = entries.filter { it.kind == EntryKind.Consp }.map { it.id }.toSet()
    val clones = collections.map { it.copy(id = legacyConspFolderId(it.id), parentId = it.parentId?.let(::legacyConspFolderId), scope = CollectionScope.Consp) }
    val folders = collections.map { it.copy(scope = CollectionScope.Notes) } + clones
    require(folders.map { it.id }.distinct().size == folders.size)
    return copy(version = 4, collections = folders,
        memberships = memberships.map { if (it.entryId in outlines) it.copy(collectionId = legacyConspFolderId(it.collectionId)) else it })
}

/** Normal browsing shows direct contents; search includes descendants, while flat view is explicit. */
fun catalogueEntries(data: LibrarySnapshot, kind: EntryKind, folder: String?, flat: Boolean, query: NoteQuery): List<Entry> {
    val folders = data.collectionsFor(kind)
    val scopeIds = folders.map { it.id }.toSet()
    val filtered = query.copy(collectionId = if (flat) null else folder).apply(data).filter { it.kind.collectionScope() == kind.collectionScope() }
    if (flat || query.text.isNotBlank()) return filtered
    val membership = data.memberships.filter { it.collectionId in scopeIds }.groupBy { it.entryId }
    return filtered.filter { entry -> if (folder == null) membership[entry.id].isNullOrEmpty() else membership[entry.id].orEmpty().any { it.collectionId == folder } }
}
