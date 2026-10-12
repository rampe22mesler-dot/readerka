package org.readerka.data

/** Native text input has different boundary rules from inserting a formatted fragment. */
object RichTextEditing {
    fun typingStyles(value: RichText, at: Int): Map<TextStyle, String> {
        val right = if (at == 0 || value.text.getOrNull(at - 1) == '\n') value.marks.filter {
            it.start == at && it.style != TextStyle.Link
        }.associate { it.style to it.value } else emptyMap()
        return right + value.marks.filter {
            at > it.start && (if (it.style == TextStyle.Link) at < it.end else at <= it.end)
        }.associate { it.style to it.value }
    }

    fun type(value: RichText, from: Int, to: Int, text: String,
        overrides: Map<TextStyle, String?> = emptyMap()): RichText {
        var next = value.replace(from, to, RichText.plain(text))
        val end = from + text.length
        val desired = typingStyles(value, from) + overrides
        // Clear inherited ranges first: a replacement inside a link can otherwise
        // retain a link even when the user has explicitly finished that insertion.
        TextStyle.entries.forEach { next = next.clearStyle(from, end, it) }
        desired.forEach { (style, target) ->
            if (target != null && text.isNotEmpty()) {
                if (style == TextStyle.Link) {
                    // Links never turn a new paragraph into a continuation of their target.
                    val length = text.indexOf('\n').let { if (it < 0) text.length else it }
                    if (length > 0) next = next.style(from, from + length, style, target)
                } else next = next.style(from, end, style, target)
            }
        }
        val block = value.blockAt(from)
        if (block.style in listOf(BlockStyle.Bullet, BlockStyle.Ordered, BlockStyle.Code, BlockStyle.Quote)) {
            if (from == block.start && '\n' in text) next = next.paragraph(from, from, block.style, block.level)
            text.forEachIndexed { index, char -> if (char == '\n') {
                val start = from + index + 1
                next = next.paragraph(start, start, block.style, block.level)
            } }
        }
        return next
    }

    fun enter(value: RichText, from: Int, to: Int, overrides: Map<TextStyle, String?> = emptyMap()): RichText {
        val block = value.blockAt(from)
        if (from == to && block.style in listOf(BlockStyle.Bullet, BlockStyle.Ordered) &&
            value.text.substring(block.start, value.blockEnd(block)).isBlank())
            return value.paragraph(from, from, BlockStyle.Paragraph)
        val next = type(value, from, to, "\n", overrides)
        return if (from == value.blockEnd(block) && block.style in listOf(BlockStyle.Heading, BlockStyle.LinkCard, BlockStyle.Divider))
            next.paragraph(from + 1, from + 1, BlockStyle.Paragraph)
        else next
    }

    fun removesListMarker(value: RichText, from: Int, to: Int) = from == to &&
        value.blockAt(from).let { it.start == from && it.style in listOf(BlockStyle.Bullet, BlockStyle.Ordered) }
}
