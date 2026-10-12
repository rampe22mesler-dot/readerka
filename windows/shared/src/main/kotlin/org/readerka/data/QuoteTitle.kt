package org.readerka.data

/** A title is generated once; subsequent quotation edits leave a user's title intact. */
fun quoteTitle(text: String): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return "Цитата"
    if (normalized.length <= 60) return normalized
    val prefix = normalized.take(59)
    val boundary = prefix.lastIndexOf(' ')
    val shortened = if (normalized[59] == ' ') prefix else if (boundary > 0) prefix.take(boundary) else prefix.dropLastWhile { it.isHighSurrogate() }
    return shortened.trimEnd() + "…"
}
