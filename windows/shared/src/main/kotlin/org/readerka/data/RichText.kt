package org.readerka.data

import kotlinx.serialization.Serializable
import org.commonmark.node.*
import org.commonmark.parser.Parser
import java.util.UUID

@Serializable enum class EntryKind { Quote, Note, Consp }
@Serializable enum class TextStyle { Bold, Italic, Underline, Link, Code, Literal }
@Serializable enum class BlockStyle { Paragraph, Heading, Quote, Bullet, Ordered, Code, LinkCard, Divider }
@Serializable data class TextMark(val start: Int, val end: Int, val style: TextStyle, val value: String = "")
@Serializable data class TextBlock(val start: Int, val id: String = UUID.randomUUID().toString(), val style: BlockStyle = BlockStyle.Paragraph, val level: Int = 1, val target: String = "")

/** Plain editable text plus persistent formatting and paragraph identities. Markdown is an export. */
@Serializable data class RichText(val text: String = "", val marks: List<TextMark> = emptyList(), val blocks: List<TextBlock> = listOf(TextBlock(0))) {
    fun blockAt(position: Int) = blocks.lastOrNull { it.start <= position.coerceIn(0, text.length) } ?: TextBlock(0)
    fun blockEnd(block: TextBlock) = blocks.firstOrNull { it.start > block.start }?.start?.minus(1) ?: text.length
    fun changeText(next: String): RichText {
        var prefix = 0
        while (prefix < minOf(text.length, next.length) && text[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(text.length - prefix, next.length - prefix) && text[text.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        return replace(prefix, text.length - suffix, plain(next.substring(prefix, next.length - suffix)))
    }
    fun replace(from: Int, to: Int, value: RichText): RichText {
        require(from in 0..text.length && to in from..text.length)
        val delta = value.text.length - (to - from)
        val nextText = text.replaceRange(from, to, value.text)
        val nextMarks = marks.flatMap { m -> buildList {
            if (m.start < from) add(m.copy(end = minOf(m.end, from)))
            if (m.end > to) add(m.copy(start = maxOf(m.start, to) + delta, end = m.end + delta))
            if (from == to && m.start < from && m.end > from) add(m.copy(start = from, end = from + value.text.length))
        } }.filter { it.start < it.end } + value.marks.map { it.copy(start = it.start + from, end = it.end + from) }
        val inheritedBlocks = blocks.filter { it.start <= from || it.start >= to }.map { b ->
            b.copy(start = if (b.start == from && from == to && value.text.endsWith('\n')) b.start + value.text.length else if (b.start <= from) b.start else b.start + delta)
        }
        val insertedBlocks = value.blocks.filter { b -> !(b.start == value.text.length && value.text.endsWith('\n')) && (b.start > 0 || (from == 0 && to == text.length && text.isEmpty()) || (b.style != BlockStyle.Paragraph && from == blockAt(from).start)) }
            .map { b -> b.copy(start = b.start + from, id = if (b.start == 0 && from == blockAt(from).start && to > from) blockAt(from).id else UUID.randomUUID().toString()) }
        return normalized(nextText, nextMarks, insertedBlocks + inheritedBlocks)
    }
    fun style(from: Int, to: Int, style: TextStyle, value: String = ""): RichText {
        if (from == to) return this
        val remove = style != TextStyle.Link && hasStyle(from, to, style)
        val retained = marks.flatMap { m ->
            if (m.style != style || m.end <= from || m.start >= to) listOf(m)
            else listOfNotNull(m.copy(end = from).takeIf { m.start < from }, m.copy(start = to).takeIf { m.end > to })
        }
        return copy(marks = retained + if (remove) emptyList() else listOf(TextMark(from, to, style, value)))
    }
    fun hasStyle(from: Int, to: Int, style: TextStyle): Boolean {
        if (from == to) return false
        var covered = from
        for (mark in marks.filter { it.style == style && it.end > from && it.start < to }.sortedBy { it.start }) {
            if (mark.start > covered) return false
            covered = maxOf(covered, mark.end)
            if (covered >= to) return true
        }
        return false
    }
    fun clearStyle(from: Int, to: Int, style: TextStyle) = copy(marks = marks.flatMap { m ->
        if (m.style != style || m.end <= from || m.start >= to) listOf(m)
        else listOfNotNull(m.copy(end = from).takeIf { it.start < from }, m.copy(start = to).takeIf { it.end > to })
    })
    fun paragraph(from: Int, to: Int, style: BlockStyle, level: Int = 1, target: String = ""): RichText {
        val first = blockAt(from).start
        val last = if (to > from) to - 1 else from
        return copy(blocks = blocks.map { if (it.start in first..last) it.copy(style = style, level = level, target = target) else it })
    }
    fun fragment(from: Int, to: Int): RichText = normalized(text.substring(from, to), marks.mapNotNull {
        val start = maxOf(from, it.start); val end = minOf(to, it.end)
        it.copy(start = start - from, end = end - from).takeIf { start < end }
    }, blocks.filter { it.start in from..to }.map { it.copy(start = it.start - from) } + blockAt(from).copy(start = 0))
    fun markdown(): String {
        if (text.isEmpty()) return ""
        var index = 0
        var ordered = 0
        return blocks.joinToString("\n\n") { b ->
            val end = blocks.getOrNull(++index)?.start?.minus(1) ?: text.length
            ordered = if (b.style == BlockStyle.Ordered) ordered + 1 else 0
            val content = if (b.style == BlockStyle.Code) text.substring(b.start, end) else inlineMarkdown(b.start, end)
            val anchor = "<a id=\"${b.id}\"></a>"
            when (b.style) {
                BlockStyle.Heading -> "$anchor\n\n${"#".repeat(b.level.coerceIn(1, 6))} $content"
                BlockStyle.Quote -> "$anchor\n\n> $content"
                BlockStyle.Bullet -> "$anchor\n\n- $content"
                BlockStyle.Ordered -> "$anchor\n\n$ordered. $content"
                BlockStyle.Code -> "$anchor\n\n    $content"
                BlockStyle.Divider -> "$anchor\n\n---"
                BlockStyle.LinkCard -> "$anchor\n\n[${escapeLabel(text.substring(b.start, end))}](${b.target})"
                BlockStyle.Paragraph -> "$anchor\n\n$content" // identities survive paragraph edits and export
            }
        }
    }
    private fun inlineMarkdown(start: Int, end: Int): String = buildString {
        var active = emptyList<TextMark>()
        fun open(m: TextMark) = when (m.style) { TextStyle.Bold -> "**"; TextStyle.Italic -> "*"; TextStyle.Underline -> "<u>"; TextStyle.Code -> "`"; TextStyle.Link -> "["; TextStyle.Literal -> "" }
        fun close(m: TextMark) = when (m.style) { TextStyle.Bold -> "**"; TextStyle.Italic -> "*"; TextStyle.Underline -> "</u>"; TextStyle.Code -> "`"; TextStyle.Link -> "](${m.value})"; TextStyle.Literal -> "" }
        val relevant = marks.filter { it.start < end && it.end > start }
        if (relevant.isEmpty()) {
            for (at in start until end) {
                val c = text[at]; if (c in "\\*_[]`") append('\\'); append(c)
            }
            return@buildString
        }
        val boundaries = (listOf(start, end) + relevant.flatMap { listOf(it.start.coerceAtLeast(start), it.end.coerceAtMost(end)) }).distinct().sorted()
        boundaries.forEachIndexed { index, i ->
            val wanted = if (i == end) emptyList() else relevant.filter { i in it.start until it.end }.sortedWith(compareBy<TextMark> { it.style.ordinal }.thenBy { it.value })
            if (wanted != active) { active.asReversed().forEach { append(close(it)) }; wanted.forEach { append(open(it)) }; active = wanted }
            val escape = active.none { it.style == TextStyle.Code || it.style == TextStyle.Literal }
            for (at in i until (boundaries.getOrNull(index + 1) ?: end)) {
                val c = text[at]; if (c in "\\*_[]`" && escape) append('\\'); append(c)
            }
        }
    }
    companion object {
        fun escapeLabel(text: String) = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        fun plain(text: String) = normalized(text, emptyList(), emptyList())
        private fun normalized(text: String, marks: List<TextMark>, candidates: List<TextBlock>): RichText {
            val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
            val byStart = mutableMapOf<Int, TextBlock>()
            candidates.forEach { byStart.putIfAbsent(it.start, it) }
            val used = mutableSetOf<String>()
            return RichText(text, marks.filter { it.start >= 0 && it.end <= text.length && it.start < it.end }, starts.map { start ->
                val b = byStart[start] ?: TextBlock(start)
                if (used.add(b.id)) b else b.copy(id = UUID.randomUUID().toString()).also { used.add(it.id) }
            })
        }
        fun fromMarkdown(source: String): RichText {
            val prepared = Regex("(?m)^<a id=\"([A-Za-z0-9_-]+)\"></a>[ \t]*\r?\n(?:\r?\n)?").replace(source) { "<!--readerka:" + it.groupValues[1] + "-->\n" }
            val root = Parser.builder().build().parse(prepared)
            val text = StringBuilder(); val marks = mutableListOf<TextMark>(); val blocks = mutableListOf<TextBlock>()
            var pendingId: String? = null
            var underlineStart: Int? = null
            fun inline(node: Node) {
                val begin = text.length
                when (node) {
                    is org.commonmark.node.Text -> text.append(node.literal)
                    is SoftLineBreak, is HardLineBreak -> text.append('\n')
                    is Code -> text.append(node.literal)
                    is HtmlInline -> when (node.literal.lowercase()) {
                        "<u>" -> underlineStart = text.length
                        "</u>" -> { underlineStart?.let { if (it < text.length) marks += TextMark(it, text.length, TextStyle.Underline) }; underlineStart = null }
                        else -> text.append(node.literal)
                    }
                    is Image -> {
                        fun alt(n: Node): String = if (n is org.commonmark.node.Text) n.literal else buildString { var child = n.firstChild; while (child != null) { append(alt(child)); child = child.next } }
                        text.append("![").append(escapeLabel(alt(node))).append("](<").append(node.destination).append('>')
                        if (!node.title.isNullOrEmpty()) text.append(" \"").append(node.title.replace("\"", "\\\"")).append("\"")
                        text.append(')')
                    }
                    else -> { var n = node.firstChild; while (n != null) { inline(n); n = n.next } }
                }
                val style = when (node) { is StrongEmphasis -> TextStyle.Bold; is Emphasis -> TextStyle.Italic; is Link -> TextStyle.Link; is Code -> TextStyle.Code; is Image -> TextStyle.Literal; else -> null }
                if (style != null && text.length > begin) marks += TextMark(begin, text.length, style, (node as? Link)?.destination.orEmpty())
            }
            fun visit(node: Node, inherited: BlockStyle = BlockStyle.Paragraph) {
                when (node) {
                    is ThematicBreak -> {
                        if (text.isNotEmpty()) text.append('\n')
                        blocks += TextBlock(text.length, pendingId ?: UUID.randomUUID().toString(), BlockStyle.Divider)
                        pendingId = null; text.append("—")
                    }
                    is HtmlBlock -> {
                        val match = Regex("<!--readerka:([A-Za-z0-9_-]+)-->\\s*").matchEntire(node.literal)
                        if (match != null) pendingId = match.groupValues[1]
                        else { if (text.isNotEmpty()) text.append('\n'); blocks += TextBlock(text.length); text.append(node.literal.trimEnd()) }
                    }
                    is Paragraph, is Heading, is FencedCodeBlock, is IndentedCodeBlock -> {
                        if (text.isNotEmpty()) text.append('\n')
                        val b = TextBlock(text.length, pendingId ?: UUID.randomUUID().toString(), if (node is Heading) BlockStyle.Heading else if (node is FencedCodeBlock || node is IndentedCodeBlock) BlockStyle.Code else inherited, (node as? Heading)?.level ?: 1)
                        pendingId = null; blocks += b
                        when (node) { is FencedCodeBlock -> text.append(node.literal.trimEnd()); is IndentedCodeBlock -> text.append(node.literal.trimEnd()); else -> inline(node) }
                    }
                    else -> { var n = node.firstChild; val style = when (node) { is BlockQuote -> BlockStyle.Quote; is BulletList -> BlockStyle.Bullet; is OrderedList -> BlockStyle.Ordered; else -> inherited }; while (n != null) { visit(n, style); n = n.next } }
                }
            }
            visit(root)
            val result = text.toString()
            val continuation = result.indices.filter { result[it] == '\n' }.map { it + 1 }.filter { start -> blocks.none { it.start == start } }.mapNotNull { start ->
                blocks.lastOrNull { it.start < start }?.takeIf { it.style == BlockStyle.Quote || it.style == BlockStyle.Code }?.copy(start = start, id = UUID.randomUUID().toString())
            }
            return normalized(result, marks, blocks + continuation)
        }
    }
}

fun Entry.sourceBooks(): Set<String> = if (kind == EntryKind.Consp) sources.toSet() else setOfNotNull(documentId)
fun Entry.richText(): RichText = content ?: RichText.fromMarkdown(comment).let { parsed ->
    if (comment.contains("<a id=\"")) parsed else parsed.copy(blocks = parsed.blocks.map { block ->
        block.copy(id = UUID.nameUUIDFromBytes((id + ":block:" + block.start).toByteArray(Charsets.UTF_8)).toString())
    })
}
fun Entry.withContent(value: RichText) = copy(content = value, comment = value.markdown())

fun Entry.libraryContent(includeQuoteComment: Boolean = false): RichText {
    if (quote.isBlank()) return richText()
    val quotation = RichText.plain(quote)
    return if (includeQuoteComment && comment.isNotBlank()) quotation.replace(quotation.text.length, quotation.text.length, RichText.plain("\n\n")).let { it.replace(it.text.length, it.text.length, richText()) } else quotation
}
