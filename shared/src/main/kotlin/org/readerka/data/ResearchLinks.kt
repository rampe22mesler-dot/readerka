package org.readerka.data

object ResearchLinks {
    fun entry(id: String, block: String? = null) = "readerka://entry/$id" + (block?.let { "?block=$it" } ?: "")
    fun document(id: String, page: Int = 1) = "readerka://document/$id?page=$page"
    fun targets(entry: Entry): Set<String> = (entry.content?.let { c -> c.marks.filter { it.style == TextStyle.Link }.map { it.value } + c.blocks.filter { it.style == BlockStyle.LinkCard }.map { it.target } }
        ?: Regex("\\]\\((readerka://[^)]+)\\)").findAll(entry.comment).map { it.groupValues[1] }.toList()).toSet()
    fun backlinks(data: LibrarySnapshot, id: String) = data.entries.filter { e -> e.id != id && targets(e).any { it.substringBefore('?').substringBefore('#') == entry(id) || it.startsWith("readerka://document/") && it.substringAfter('?', "").split('&').any { arg -> arg == "entry=$id" } } }
}

enum class ConspTemplate(val title: String, val source: String) {
    Blank("Пустой", ""),
    Book("По книге", "# Основная идея\n\n# Ключевые понятия\n\n# Цитаты и комментарии\n\n# Вопросы\n\n# Выводы"),
    Topic("По теме", "# Постановка вопроса\n\n# Аргументы\n\n# Источники\n\n# Мои выводы"),
    Compare("Сравнение источников", "# Общий вопрос\n\n# Позиция первого источника\n\n# Позиция второго источника\n\n# Сходства и различия\n\n# Выводы")
}
