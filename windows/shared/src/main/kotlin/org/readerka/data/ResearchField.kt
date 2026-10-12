package org.readerka.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class ResearchCardKind { Quote, Note, Thesis }
@Serializable enum class ResearchRelation(val label: String) {
    Supports("поддерживает"), Objects("возражает"), Clarifies("уточняет"),
    Compares("сравнивается с"), Free("свободная связь");
    override fun toString() = label
}

/** Coordinates are world coordinates; the viewport never rewrites them. */
@Serializable data class ResearchPlacement(
    val id: String, val entryId: String, val x: Double, val y: Double,
    val width: Double = 300.0, val height: Double = 260.0,
    val kind: ResearchCardKind = ResearchCardKind.Quote,
    val projectComment: String = "", val groupId: String? = null,
)
@Serializable data class ResearchGroup(val id: String, val title: String)
@Serializable data class ResearchEdge(
    val id: String, val from: String, val to: String,
    val type: ResearchRelation = ResearchRelation.Free, val label: String = "",
)
@Serializable data class ResearchField(
    val id: String, val title: String, val placements: List<ResearchPlacement> = emptyList(),
    val edges: List<ResearchEdge> = emptyList(), val groups: List<ResearchGroup> = emptyList(),
    val zoom: Double = 1.0, val viewX: Double = 0.0, val viewY: Double = 0.0,
    val sequence: List<String> = emptyList(),
) {
    fun removePlacements(ids: Set<String>) = copy(
        placements = placements.filterNot { it.id in ids },
        edges = edges.filterNot { it.from in ids || it.to in ids },
        sequence = sequence.filterNot { it in ids },
    )
    fun move(ids: Set<String>, dx: Double, dy: Double) = copy(placements = placements.map {
        if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it
    })
    fun validate(entryIds: Set<String>) {
        fun unique(ids: List<String>) = require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size)
        require(title.isNotBlank())
        unique(placements.map { it.id }); unique(edges.map { it.id }); unique(groups.map { it.id })
        val cards = placements.map { it.id }.toSet()
        val groupIds = groups.map { it.id }.toSet()
        require(zoom.isFinite() && zoom in .01..3.0 && viewX.isFinite() && viewY.isFinite())
        placements.forEach {
            require(it.entryId in entryIds && it.x.isFinite() && it.y.isFinite())
            require(it.width.isFinite() && it.height.isFinite() && it.width in 180.0..2000.0 && it.height in 140.0..3000.0)
            require(it.groupId == null || it.groupId in groupIds)
        }
        edges.forEach { require(it.from in cards && it.to in cards && it.from != it.to) }
        require(groups.all { it.title.isNotBlank() })
        require(sequence.distinct().size == sequence.size && sequence.all { it in cards })
    }
}

/** Versions 1–4 have no field data. Keep the preceding collection migration explicit. */
fun LibrarySnapshot.migrateResearchFields(): LibrarySnapshot = separateLegacyCollections().let {
    if (it.version < 5) it.copy(version = 5, noteVersions = if (version < 4) noteVersions.map { version ->
        val draft = Json.decodeFromString<EntryDraft>(version.snapshot)
        version.copy(snapshot = Json.encodeToString(draft.copy(collections = scopedCollectionIds(draft.collections, draft.entry.kind, it.collections))))
    } else it.noteVersions) else it
}

object ResearchAssembly {
    fun append(destination: RichText, field: ResearchField, order: List<String>, entries: List<Entry>, mode: InsertionMode): RichText {
        require(order.isNotEmpty() && order.distinct().size == order.size)
        val placements = field.placements.associateBy { it.id }
        val sources = entries.associateBy { it.id }
        return order.fold(destination) { text, id ->
            val card = placements.getValue(id)
            val source = sources.getValue(card.entryId)
            val inserted = ResearchInsertion.insert(text, text.text.length, text.text.length, source, mode)
            if (mode == InsertionMode.Content && card.projectComment.isNotBlank())
                inserted.replace(inserted.text.length, inserted.text.length, RichText.plain("Применение в проекте: ${card.projectComment}\n"))
            else inserted
        }
    }
}
