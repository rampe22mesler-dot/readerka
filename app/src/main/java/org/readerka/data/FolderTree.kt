package org.readerka.data

object FolderTree {
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
