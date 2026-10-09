package org.readerka.data

object FolderTree {
    data class Row<T>(val value: T, val depth: Int, val hasChildren: Boolean)

    /** Visible branches only; search reveals matching folders with their ancestor paths. */
    fun <T> visible(items: List<T>, id: (T) -> String, parent: (T) -> String?, name: (T) -> String,
        expanded: Set<String>, query: String = ""): List<Row<T>> {
        validate(items.map { id(it) to parent(it) })
        val byId = items.associateBy(id)
        val children = items.groupBy(parent)
        val matching: Set<String>? = if (query.isBlank()) null else buildSet<String> {
            for (item in items.filter { name(it).contains(query.trim(), ignoreCase = true) }) {
                var current: String? = id(item)
                while (current != null && add(current)) current = byId[current]?.let(parent)
            }
        }
        return buildList {
            fun visit(parentId: String?, depth: Int) {
                for (item in children[parentId].orEmpty().sortedBy { name(it).lowercase() }) {
                    val key = id(item)
                    if (matching != null && key !in matching) continue
                    add(Row(item, depth, !children[key].isNullOrEmpty()))
                    if (matching != null || key in expanded) visit(key, depth + 1)
                }
            }
            visit(null, 0)
        }
    }

    fun ancestors(ids: Set<String>, items: List<Pair<String, String?>>): Set<String> {
        val parents = items.toMap()
        return buildSet {
            for (id in ids) {
                var current = parents[id]
                while (current != null && add(current)) current = parents[current]
            }
        }
    }

    fun descendants(id: String, items: List<Pair<String, String?>>): Set<String> {
        val children = items.groupBy({ it.second }, { it.first })
        return buildSet {
            fun visit(key: String) { if (add(key)) children[key].orEmpty().forEach(::visit) }
            visit(id)
        }
    }
    /** Reject self-parenting, orphan parents and cycles before any database write. */
    fun validate(items: List<Pair<String, String?>>) {
        val parents = items.toMap()
        require(parents.size == items.size) { "Duplicate folder ID" }
        for ((id, parent) in parents) {
            require(parent == null || parent in parents) { "Missing parent" }
            val seen = mutableSetOf(id); var current = parent
            while (current != null) { require(seen.add(current)) { "Folder cycle" }; require(seen.size <= 64) { "Folder nesting exceeds 64 levels" }; current = parents[current] }
        }
    }
    fun <T> ordered(items: List<T>, id: (T) -> String, parent: (T) -> String?): List<T> {
        validate(items.map { id(it) to parent(it) })
        val result = mutableListOf<T>()
        fun append(parentId: String?) {
            for (item in items.filter { parent(it) == parentId }) { result += item; append(id(item)) }
        }
        append(null); return result
    }
    fun depth(id: String, items: List<Pair<String, String?>>): Int {
        val parents = items.toMap(); var current = parents[id]; var depth = 0; val seen = mutableSetOf(id)
        while (current != null && seen.add(current)) { depth++; current = parents[current] }
        return depth
    }
}
