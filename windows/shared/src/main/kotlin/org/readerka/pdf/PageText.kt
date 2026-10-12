package org.readerka.pdf

import org.readerka.data.Anchor
import org.readerka.data.PageRect
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

data class Glyph(val start: Int, val end: Int, val rect: PageRect)
data class PageText(val page: Int, val text: String, val glyphs: List<Glyph>) {
    fun wordAt(glyph: Int): IntRange {
        require(glyph in glyphs.indices)
        val offset = glyphs[glyph].start
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(this@PageText.text) }
        val start = if (words.isBoundary(offset)) offset else words.preceding(offset)
        val end = words.following(offset).takeIf { it != BreakIterator.DONE } ?: text.length
        val first = glyphs.indexOfFirst { it.end > start }.coerceAtLeast(0)
        val last = glyphs.indexOfLast { it.start < end }.coerceAtLeast(first)
        return first..last
    }
    fun selection(first: Int, last: Int): Pair<String, Anchor>? {
        if (glyphs.isEmpty()) return null
        val a = min(first, last).coerceIn(glyphs.indices)
        val b = max(first, last).coerceIn(glyphs.indices)
        val start = glyphs[a].start
        val end = glyphs[b].end
        return text.substring(start, end) to Anchor(page, start, end, glyphs.subList(a, b + 1).map { it.rect })
    }
    fun hit(x: Float, y: Float): Int? {
        val exact = glyphs.indexOfFirst { it.rect.contains(x, y) }
        if (exact >= 0) return exact
        return glyphs.indices.minByOrNull {
            val r = glyphs[it].rect
            hypot(x - x.coerceIn(r.left, r.right), y - y.coerceIn(r.top, r.bottom))
        }?.takeIf {
            val r = glyphs[it].rect
            hypot(x - x.coerceIn(r.left, r.right), y - y.coerceIn(r.top, r.bottom)) < .025f
        }
    }
    fun matches(query: String): List<Anchor> {
        if (query.isBlank()) return emptyList()
        val result = mutableListOf<Anchor>()
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            val rects = glyphs.filter { it.end > at && it.start < at + query.length }.map { it.rect }
            if (rects.isNotEmpty()) result += Anchor(page, at, at + query.length, rects)
            from = at + query.length
        }
        return result
    }
}
