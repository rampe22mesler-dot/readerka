package org.readerka.data

enum class InsertionMode {
    InlineLink,
    LinkCard,
    Content,
}

object ResearchInsertion {
    fun insert(
        destination: RichText,
        from: Int,
        to: Int,
        source: Entry,
        mode: InsertionMode,
        fragment: RichText = source.libraryContent(true),
    ): RichText {
        val url =
            source.anchor?.let {
                ResearchLinks.document(source.documentId!!, it.page + 1) + "&entry=${source.id}"
            } ?: ResearchLinks.entry(source.id)
        if (mode == InsertionMode.InlineLink && from < to)
            return destination.style(from, to, TextStyle.Link, url)
        val label = source.title.ifBlank { source.quote.take(80).ifBlank { "Запись" } }
        if (mode == InsertionMode.InlineLink)
            return destination.replace(
                from,
                to,
                RichText.plain(label).style(0, label.length, TextStyle.Link, url),
            )
        val separator = if (from > 0 && destination.text[from - 1] != '\n') "\n" else ""
        val inserted =
            if (mode == InsertionMode.LinkCard)
                RichText.plain(separator + label + "\n")
                    .paragraph(
                        separator.length,
                        separator.length,
                        BlockStyle.LinkCard,
                        target = url,
                    )
            else
                RichText.plain(separator)
                    .let { it.replace(it.text.length, it.text.length, fragment) }
                    .let { r ->
                        val suffix = "\nИсточник: $label\n"
                        val start = r.text.length
                        r.replace(
                            start,
                            start,
                            RichText.plain(suffix).style(11, suffix.length - 1, TextStyle.Link, url),
                        )
                    }
        return destination.replace(from, to, inserted)
    }
}
